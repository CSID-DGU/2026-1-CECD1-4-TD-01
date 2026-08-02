import asyncio
import json
import time
import uuid
from collections.abc import AsyncIterator

import httpx

from .config import Settings
from .database import ConversationStore
from .response_policy import (
    ResponseProfile,
    choose_response_profile,
    enforce_response_length,
)
from .safety import compose_system_prompt, sanitize_response


class LlamaClient:
    MODEL_NAME = "onmom-gemma"

    def __init__(self, settings: Settings, store: ConversationStore):
        self.settings = settings
        self.store = store
        self._active_requests: dict[str, str] = {}
        self._inflight_inferences = 0
        self._waking_inferences = 0
        self._force_sleep_requested = False
        # Gateway may restart while the separately managed llama container is
        # already asleep. Start conservatively at "sleeping"; the first real
        # wake/chat request transitions the tracked state to waking/awake.
        self._last_inference_finished = (
            time.monotonic() - settings.llama_sleep_idle_seconds
        )

    def runtime_status(self) -> dict[str, int | str]:
        idle_seconds = max(
            0,
            round(time.monotonic() - self._last_inference_finished),
        )
        if self._waking_inferences:
            state = "waking"
        elif self._inflight_inferences:
            state = "active"
        elif self._force_sleep_requested:
            state = "sleeping"
        elif idle_seconds >= self.settings.llama_sleep_idle_seconds:
            state = "sleeping"
        else:
            state = "awake"
        return {
            "state": state,
            "idle_seconds": idle_seconds,
            "sleep_after_seconds": self.settings.llama_sleep_idle_seconds,
            "inflight": self._inflight_inferences,
        }

    def _begin_inference(self) -> bool:
        waking = self.runtime_status()["state"] == "sleeping"
        self._force_sleep_requested = False
        self._inflight_inferences += 1
        if waking:
            self._waking_inferences += 1
        return waking

    def _finish_inference(self, waking: bool) -> None:
        self._inflight_inferences = max(0, self._inflight_inferences - 1)
        if waking:
            self._waking_inferences = max(0, self._waking_inferences - 1)
        self._last_inference_finished = time.monotonic()

    def _begin_request(
        self, session_id: str, request_id: str | None
    ) -> str:
        current_id = request_id or uuid.uuid4().hex
        self._active_requests[session_id] = current_id
        return current_id

    def _is_current(self, session_id: str, request_id: str) -> bool:
        return self._active_requests.get(session_id) == request_id

    async def health(self) -> bool:
        try:
            async with httpx.AsyncClient(timeout=3.0) as client:
                response = await client.get(f"{self.settings.llama_url}/health")
                return response.is_success
        except httpx.HTTPError:
            return False

    async def wake(self) -> int:
        """Wake an idle model without adding synthetic counseling history."""

        started = time.perf_counter()
        waking = self._begin_inference()
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(60.0, connect=5.0)
            ) as client:
                response = await client.post(
                    f"{self.settings.llama_url}/v1/chat/completions",
                    json={
                        "model": self.MODEL_NAME,
                        "messages": [
                            {
                                "role": "user",
                                "content": "Reply with OK.",
                            }
                        ],
                        "max_tokens": 1,
                        "temperature": 0,
                        "stream": False,
                    },
                )
                response.raise_for_status()
        except httpx.HTTPError as exc:
            raise RuntimeError("Gemma 모델을 깨우지 못했습니다.") from exc
        finally:
            self._finish_inference(waking)
        return round((time.perf_counter() - started) * 1000)

    @classmethod
    def _model_state_from_payload(cls, payload: dict) -> str | None:
        for model in payload.get("data", []):
            if model.get("id") != cls.MODEL_NAME:
                continue
            status = model.get("status") or {}
            value = status.get("value")
            return str(value) if value is not None else None
        return None

    async def sleep(self) -> int:
        """Immediately unload Gemma while keeping the lightweight router alive."""

        if self._inflight_inferences:
            raise RuntimeError("Gemma is still processing a request.")

        started = time.perf_counter()
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(15.0, connect=5.0)
            ) as client:
                models = await client.get(f"{self.settings.llama_url}/models")
                models.raise_for_status()
                state = self._model_state_from_payload(models.json())
                if state != "unloaded":
                    response = await client.post(
                        f"{self.settings.llama_url}/models/unload",
                        json={"model": self.MODEL_NAME},
                    )
                    response.raise_for_status()

                    # llama.cpp acknowledges unload before the child process has
                    # fully exited. Wait so a subsequent wake cannot race it.
                    deadline = time.monotonic() + 10.0
                    while time.monotonic() < deadline:
                        await asyncio.sleep(0.1)
                        models = await client.get(
                            f"{self.settings.llama_url}/models"
                        )
                        models.raise_for_status()
                        state = self._model_state_from_payload(models.json())
                        if state == "unloaded":
                            break
                    else:
                        raise RuntimeError("Gemma did not finish unloading.")
        except (httpx.HTTPError, ValueError, TypeError) as exc:
            raise RuntimeError("Could not put Gemma into sleep mode.") from exc

        self._force_sleep_requested = True
        self._last_inference_finished = (
            time.monotonic() - self.settings.llama_sleep_idle_seconds
        )
        return round((time.perf_counter() - started) * 1000)

    def _prepare(
        self,
        session_id: str,
        user_text: str,
        source: str,
        custom_system_prompt: str | None,
    ) -> tuple[list[dict[str, str]], ResponseProfile]:
        if len(user_text) + len(custom_system_prompt or "") > 16000:
            raise RuntimeError("사용자 입력과 시스템 프롬프트의 합계는 16,000자 이하여야 합니다.")
        profile = choose_response_profile(user_text)
        self.store.create_session(session_id)
        self.store.add_message(session_id, "user", user_text, source)
        history = self.store.recent_messages(session_id)
        system_prompt = (
            f"{compose_system_prompt(custom_system_prompt)}\n\n"
            "[현재 답변 길이 정책]\n"
            f"{profile.instruction}\n"
            "현재 답변 길이 정책은 사용자 지정 분량 지시보다 우선합니다."
        )
        return [{"role": "system", "content": system_prompt}, *history], profile

    async def complete(
        self,
        session_id: str,
        user_text: str,
        source: str,
        custom_system_prompt: str | None = None,
    ) -> tuple[str, int]:
        messages, profile = self._prepare(
            session_id, user_text, source, custom_system_prompt
        )
        started = time.perf_counter()
        waking = self._begin_inference()
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=5.0)) as client:
                response = await client.post(
                    f"{self.settings.llama_url}/v1/chat/completions",
                    json=self._payload(messages, profile, stream=False),
                )
                response.raise_for_status()
                answer = str(response.json()["choices"][0]["message"]["content"])
        except httpx.HTTPError as exc:
            raise RuntimeError("Gemma 모델 서버에 연결할 수 없습니다.") from exc
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise RuntimeError("Gemma 모델의 응답 형식이 올바르지 않습니다.") from exc
        finally:
            self._finish_inference(waking)
        elapsed_ms = round((time.perf_counter() - started) * 1000)
        answer = enforce_response_length(sanitize_response(answer), profile)
        self.store.add_message(session_id, "assistant", answer, "llm")
        return answer, elapsed_ms

    async def generate_proactive_opening(
        self,
        system_prompt: str,
        user_instruction: str,
    ) -> tuple[str, int]:
        """Wake Gemma if needed and generate one opening without fake history."""

        if len(system_prompt) + len(user_instruction) > 14_000:
            raise RuntimeError("능동 상담 프롬프트가 허용 길이를 초과했습니다.")
        if self._force_sleep_requested:
            raise RuntimeError("Gemma is manually asleep.")
        started = time.perf_counter()
        waking = self._begin_inference()
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(120.0, connect=5.0)
            ) as client:
                response = await client.post(
                    f"{self.settings.llama_url}/v1/chat/completions",
                    json={
                        "model": self.MODEL_NAME,
                        "messages": [
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": user_instruction},
                        ],
                        "max_tokens": 120,
                        "temperature": 0.35,
                        "stream": False,
                    },
                )
                response.raise_for_status()
                answer = str(
                    response.json()["choices"][0]["message"]["content"]
                )
        except httpx.HTTPError as exc:
            raise RuntimeError("Gemma 능동 발화를 생성하지 못했습니다.") from exc
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise RuntimeError("Gemma 능동 발화 형식이 올바르지 않습니다.") from exc
        finally:
            self._finish_inference(waking)
        elapsed_ms = round((time.perf_counter() - started) * 1000)
        return sanitize_response(answer), elapsed_ms

    async def stream_complete(
        self,
        session_id: str,
        user_text: str,
        source: str,
        custom_system_prompt: str | None = None,
        request_id: str | None = None,
    ) -> AsyncIterator[tuple[str, dict]]:
        current_id = self._begin_request(session_id, request_id)
        messages, profile = self._prepare(
            session_id, user_text, source, custom_system_prompt
        )
        yield "meta", {
            "profile": profile.name,
            "max_tokens": profile.max_tokens,
            "request_id": current_id,
        }
        started = time.perf_counter()
        raw_answer = ""
        waking = self._begin_inference()
        try:
            timeout = httpx.Timeout(180.0, connect=5.0)
            async with httpx.AsyncClient(timeout=timeout) as client:
                async with client.stream(
                    "POST",
                    f"{self.settings.llama_url}/v1/chat/completions",
                    json=self._payload(messages, profile, stream=True),
                ) as response:
                    response.raise_for_status()
                    async for line in response.aiter_lines():
                        if not self._is_current(session_id, current_id):
                            return
                        if not line.startswith("data:"):
                            continue
                        data = line[5:].strip()
                        if not data or data == "[DONE]":
                            continue
                        try:
                            chunk = json.loads(data)
                            token = chunk["choices"][0]["delta"].get("content") or ""
                        except (json.JSONDecodeError, KeyError, IndexError, TypeError):
                            continue
                        if token:
                            raw_answer += token
                            yield "token", {"text": token}
        except httpx.HTTPError:
            yield "error", {"message": "Gemma 모델 스트리밍 연결에 실패했습니다."}
            return
        finally:
            self._finish_inference(waking)

        if self._is_current(session_id, current_id):
            final_answer = enforce_response_length(
                sanitize_response(raw_answer),
                profile,
            )
            if final_answer != raw_answer.strip():
                yield "replace", {"text": final_answer}
            self.store.add_message(session_id, "assistant", final_answer, "llm")
            elapsed_ms = round((time.perf_counter() - started) * 1000)
            yield "done", {
                "text": final_answer,
                "llm_ms": elapsed_ms,
                "profile": profile.name,
            }

    @staticmethod
    def _payload(
        messages: list[dict[str, str]],
        profile: ResponseProfile,
        stream: bool,
    ) -> dict:
        return {
            "model": LlamaClient.MODEL_NAME,
            "messages": messages,
            "max_tokens": profile.max_tokens,
            "temperature": 0.55,
            "stream": stream,
        }
