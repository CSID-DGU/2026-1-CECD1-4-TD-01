"""On-device voice-emotion inference and multimodal counseling telemetry.

The runtime contract follows the Voice module pinned by the project:
16 kHz mono waveform input, at most 12 seconds, and per-utterance emotion
probabilities. Results are model observations, not statements about the
speaker's true emotion or a medical diagnosis.
"""

from __future__ import annotations

import asyncio
from collections import deque
import gc
import json
import logging
import math
import os
from pathlib import Path
from threading import Lock
import tempfile
import time
from typing import Any, Callable, Protocol
import uuid
import wave

import numpy as np

from .config import Settings


logger = logging.getLogger("onmom.voice_emotion")

VOICE_SCHEMA = "onmom.voice_emotion.v1"
SIGNALS_SCHEMA = "onmom.counseling_signals.v1"
MODEL_SOURCE_REF = (
    "CSID-DGU/2026-1-CECD1-4-TD-01@"
    "673c8cbbc32b892b04537100c4dfb17744ac33e0:Analysis/Voice"
)

MODEL_LABELS = ("Happy", "Sad", "Angry", "Fearful", "Neutral")
DISPLAY_LABELS = {
    "Sad": "슬픔",
    "Angry": "분노",
    "Fearful": "불안",
    "Happy": "기쁨",
    "Neutral": "중립",
}


class Transcriber(Protocol):
    async def transcribe(
        self,
        audio_path: Path,
        *,
        cleanup: bool = True,
    ) -> tuple[str, int]: ...


def _now_ms() -> int:
    return time.time_ns() // 1_000_000


def _softmax(logits: np.ndarray) -> np.ndarray:
    values = np.asarray(logits, dtype=np.float64).reshape(-1)
    values -= np.max(values)
    exponents = np.exp(values)
    return (exponents / max(float(exponents.sum()), 1e-12)).astype(np.float32)


def _safe_error_code(error: BaseException) -> str:
    return type(error).__name__.lower()


class VoiceEmotionAnalyzer:
    """Run a quantized ONNX speech-emotion model without storing raw audio."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self._session: Any | None = None
        self._session_lock = Lock()

    @property
    def available(self) -> bool:
        return (
            self.settings.voice_emotion_enabled
            and self.settings.voice_emotion_model.is_file()
        )

    @property
    def loaded(self) -> bool:
        return self._session is not None

    def health(self) -> dict[str, Any]:
        return {
            "enabled": self.settings.voice_emotion_enabled,
            "model_available": self.settings.voice_emotion_model.is_file(),
            "loaded": self.loaded,
            "model_file": self.settings.voice_emotion_model.name,
            "runtime": "onnxruntime-cpu",
            "source_module": MODEL_SOURCE_REF,
            "raw_audio_stored": False,
        }

    def unavailable_result(
        self,
        session_id: str,
        utterance_id: str,
        reason: str,
    ) -> dict[str, Any]:
        return {
            "schema": VOICE_SCHEMA,
            "session_id": session_id,
            "utterance_id": utterance_id,
            "observed_at_ms": _now_ms(),
            "status": "unavailable",
            "reason": reason,
            "label": None,
            "display_label": None,
            "confidence": None,
            "probabilities": {},
            "model": self._model_metadata(),
            "raw_audio_stored": False,
            "interpretation": "model_output_not_true_emotion_or_diagnosis",
        }

    async def analyze(
        self,
        audio_path: Path,
        session_id: str,
        utterance_id: str,
    ) -> dict[str, Any]:
        if not self.settings.voice_emotion_enabled:
            return self.unavailable_result(session_id, utterance_id, "disabled")
        if not self.settings.voice_emotion_model.is_file():
            return self.unavailable_result(session_id, utterance_id, "model_missing")
        try:
            return await asyncio.to_thread(
                self._analyze_sync,
                audio_path,
                session_id,
                utterance_id,
            )
        except Exception as error:  # noqa: BLE001 - analysis must fail soft
            logger.exception(
                "voice_emotion_failed session=%s utterance=%s error=%s",
                session_id,
                utterance_id,
                _safe_error_code(error),
            )
            result = self.unavailable_result(
                session_id,
                utterance_id,
                "inference_error",
            )
            result["error_code"] = _safe_error_code(error)
            return result

    def close(self) -> None:
        with self._session_lock:
            self._session = None
        gc.collect()

    def _analyze_sync(
        self,
        audio_path: Path,
        session_id: str,
        utterance_id: str,
    ) -> dict[str, Any]:
        waveform, duration_seconds, rms = self._read_waveform(audio_path)
        if waveform.size < 1600 or rms < self.settings.min_audio_rms:
            result = self.unavailable_result(
                session_id,
                utterance_id,
                "insufficient_audio",
            )
            result.update(
                {
                    "duration_seconds": round(duration_seconds, 3),
                    "audio_rms": rms,
                }
            )
            return result

        if self.settings.voice_emotion_normalize_input:
            waveform = waveform - float(waveform.mean())
            variance = float(np.mean(np.square(waveform)))
            waveform = waveform / math.sqrt(variance + 1e-7)

        started = time.perf_counter()
        with self._session_lock:
            session = self._session or self._create_session()
            feed: dict[str, np.ndarray] = {}
            for model_input in session.get_inputs():
                if model_input.name == "input_values":
                    feed[model_input.name] = waveform.reshape(1, -1).astype(
                        np.float32
                    )
                elif model_input.name == "attention_mask":
                    feed[model_input.name] = np.ones(
                        (1, waveform.size),
                        dtype=np.int64,
                    )
                else:
                    raise RuntimeError(
                        f"unsupported_model_input:{model_input.name}"
                    )
            raw_output = session.run(None, feed)[0]
            if self.settings.voice_emotion_keep_loaded:
                self._session = session
            else:
                session = None
        if not self.settings.voice_emotion_keep_loaded:
            gc.collect()

        probabilities = _softmax(np.asarray(raw_output)[0])
        if probabilities.size != len(MODEL_LABELS):
            raise RuntimeError(f"unexpected_label_count:{probabilities.size}")
        best_index = int(np.argmax(probabilities))
        label = MODEL_LABELS[best_index]
        confidence = float(probabilities[best_index])
        inference_ms = round((time.perf_counter() - started) * 1000)
        status = (
            "valid"
            if confidence >= self.settings.voice_emotion_min_confidence
            else "low_confidence"
        )
        result = {
            "schema": VOICE_SCHEMA,
            "session_id": session_id,
            "utterance_id": utterance_id,
            "observed_at_ms": _now_ms(),
            "status": status,
            "reason": None if status == "valid" else "below_confidence_threshold",
            "label": label,
            "display_label": DISPLAY_LABELS[label],
            "confidence": round(confidence, 6),
            "probabilities": {
                name: round(float(probabilities[index]), 6)
                for index, name in enumerate(MODEL_LABELS)
            },
            "duration_seconds": round(duration_seconds, 3),
            "analyzed_seconds": round(waveform.size / 16_000, 3),
            "audio_rms": rms,
            "inference_ms": inference_ms,
            "model": self._model_metadata(),
            "raw_audio_stored": False,
            "interpretation": "model_output_not_true_emotion_or_diagnosis",
        }
        logger.info(
            "voice_emotion_done session=%s utterance=%s label=%s "
            "confidence=%.3f inference_ms=%d",
            session_id,
            utterance_id,
            label,
            confidence,
            inference_ms,
        )
        return result

    def _create_session(self) -> Any:
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = max(
            1,
            self.settings.voice_emotion_threads,
        )
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        options.graph_optimization_level = (
            ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        )
        return ort.InferenceSession(
            str(self.settings.voice_emotion_model),
            sess_options=options,
            providers=["CPUExecutionProvider"],
        )

    def _read_waveform(self, audio_path: Path) -> tuple[np.ndarray, float, int]:
        max_samples = 16_000 * max(1, self.settings.voice_emotion_max_seconds)
        with wave.open(str(audio_path), "rb") as audio:
            channels = audio.getnchannels()
            sample_width = audio.getsampwidth()
            sample_rate = audio.getframerate()
            total_frames = audio.getnframes()
            if sample_width != 2:
                raise RuntimeError("unsupported_sample_width")
            if sample_rate != 16_000:
                raise RuntimeError("unsupported_sample_rate")
            frames_to_read = min(total_frames, max_samples)
            pcm = np.frombuffer(
                audio.readframes(frames_to_read),
                dtype="<i2",
            )
        if channels > 1:
            usable = pcm.size - (pcm.size % channels)
            pcm = (
                pcm[:usable]
                .reshape(-1, channels)
                .astype(np.float32)
                .mean(axis=1)
            )
        else:
            pcm = pcm.astype(np.float32)
        if pcm.size == 0:
            return np.asarray([], dtype=np.float32), 0.0, 0
        rms = round(
            float(np.sqrt(np.mean(np.square(pcm, dtype=np.float64))))
        )
        duration = total_frames / 16_000
        return (pcm / 32768.0).astype(np.float32), duration, rms

    def _model_metadata(self) -> dict[str, Any]:
        return {
            "name": self.settings.voice_emotion_model_name,
            "file": self.settings.voice_emotion_model.name,
            "runtime": "onnxruntime-cpu",
            "language_note": "acoustic_model_trained_on_english_corpus",
            "source_module": MODEL_SOURCE_REF,
            "runtime_model_source": self.settings.voice_emotion_model_source,
            "runtime_model_revision": self.settings.voice_emotion_model_revision,
            "license": "Apache-2.0",
            "original_project_weights_present_in_git": False,
        }



def assess_arousal(
    event: dict[str, Any],
    settings: Any | None = None,
) -> dict[str, Any]:
    """Combine independent observations; never treat one model as diagnosis."""
    signals: list[str] = []
    extreme_rppg = False
    threshold = lambda name, default: float(getattr(settings, name, default))

    face = event.get("face_emotion")
    if isinstance(face, dict) and face.get("status") == "valid":
        try:
            if (
                float(face.get("confidence", 0))
                >= threshold("arousal_face_min_confidence", 0.60)
                and float(face.get("arousal", 0))
                >= threshold("arousal_face_threshold", 0.72)
            ):
                signals.append("facial_arousal")
        except (TypeError, ValueError):
            pass

    rppg = event.get("rppg")
    if isinstance(rppg, dict) and rppg.get("status") == "valid":
        try:
            bpm = float(rppg.get("heart_rate_bpm", 0))
            if (
                float(rppg.get("quality", 0))
                >= threshold("arousal_rppg_min_quality", 0.60)
                and bpm >= threshold("arousal_rppg_bpm", 100.0)
            ):
                signals.append("rppg_high_rate")
                extreme_rppg = bpm >= threshold(
                    "arousal_rppg_critical_bpm",
                    125.0,
                )
        except (TypeError, ValueError):
            pass

    voice = event.get("voice_emotion")
    if isinstance(voice, dict) and voice.get("status") == "valid":
        try:
            if (
                str(voice.get("label")) in {"Angry", "Fearful"}
                and float(voice.get("confidence", 0))
                >= threshold("arousal_voice_min_confidence", 0.60)
            ):
                signals.append("voice_high_arousal")
        except (TypeError, ValueError):
            pass

    high = len(signals) >= 2
    critical = len(signals) >= 3 or (extreme_rppg and high)
    return {
        "level": "critical" if critical else "high" if high else "normal",
        "signal_count": len(signals),
        "signals": signals,
        "recommendation": (
            "\ucc9c\ucc9c\ud788 \uc228\uc744 \uace0\ub974\uac70\ub098 "
            "1~3\ubd84 \uc9e7\uc740 \uba85\uc0c1"
            if high
            else None
        ),
        "automatic_guardian_alert": critical,
        "interpretation": "multi_signal_observation_not_diagnosis",
    }


class CounselingSignalsStore:
    """Persist one correlated visual/rPPG/voice event per spoken turn."""

    def __init__(
        self,
        log_path: Path,
        latest_path: Path,
        *,
        vision_reader: Callable[[], dict[str, Any]],
        prompt_min_confidence: float,
        arousal_settings: Any | None = None,
    ):
        self.log_path = log_path
        self.latest_path = latest_path
        self.vision_reader = vision_reader
        self.prompt_min_confidence = prompt_min_confidence
        self._lock = Lock()
        self.arousal_settings = arousal_settings
        self._latest_by_session: dict[str, dict[str, Any]] = {}

    def record_voice_turn(
        self,
        voice_emotion: dict[str, Any],
        *,
        transcription_status: str,
        transcript_characters: int,
    ) -> dict[str, Any]:
        visual_status = self.vision_reader()
        telemetry = visual_status.get("telemetry")
        visual = telemetry if isinstance(telemetry, dict) else {}
        session_id = str(voice_emotion.get("session_id") or "")
        visual_session_id = str(visual.get("session_id") or "")
        visual_session_matches = (
            bool(session_id)
            and bool(visual_session_id)
            and visual_session_id == session_id
        )
        event = {
            "schema": SIGNALS_SCHEMA,
            "event_type": "voice_turn",
            "session_id": session_id,
            "utterance_id": voice_emotion.get("utterance_id"),
            "observed_at_ms": voice_emotion.get("observed_at_ms", _now_ms()),
            "transcription": {
                "status": transcription_status,
                "characters": max(0, int(transcript_characters)),
                "content_stored_in_this_log": False,
            },
            "face": visual.get("face") if visual_session_matches else None,
            "face_emotion": (
                visual.get("emotion") if visual_session_matches else None
            ),
            "rppg": visual.get("rppg") if visual_session_matches else None,
            "visual_session_id": visual_session_id or None,
            "visual_session_matches": visual_session_matches,
            "visual_observation_at_ms": visual.get("observed_at_ms"),
            "visual_available": (
                bool(visual_status.get("available")) and visual_session_matches
            ),
            "visual_stale": bool(visual_status.get("stale")),
            "voice_emotion": voice_emotion,
            "raw_media_stored": False,
            "interpretation": (
                "all_values_are_sensor_or_model_observations_not_diagnosis"
            ),
        }
        event["arousal_assessment"] = assess_arousal(
            event, self.arousal_settings
        )
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self.latest_path.parent.mkdir(parents=True, exist_ok=True)
        with self._lock:
            with self.log_path.open("a", encoding="utf-8", newline="\n") as stream:
                json.dump(event, stream, ensure_ascii=False, separators=(",", ":"))
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            self._atomic_write(self.latest_path, event)
            if session_id:
                self._latest_by_session[session_id] = event
        return event

    def latest(self, session_id: str | None = None) -> dict[str, Any] | None:
        with self._lock:
            if session_id and session_id in self._latest_by_session:
                return self._latest_by_session[session_id]
            try:
                event = json.loads(self.latest_path.read_text(encoding="utf-8"))
            except (
                FileNotFoundError,
                OSError,
                UnicodeDecodeError,
                json.JSONDecodeError,
            ):
                return None
            if (
                not isinstance(event, dict)
                or event.get("schema") != SIGNALS_SCHEMA
            ):
                return None
            if session_id and event.get("session_id") != session_id:
                return self._latest_from_log(session_id)
            if session_id:
                self._latest_by_session[session_id] = event
            return event

    def _latest_from_log(self, session_id: str) -> dict[str, Any] | None:
        latest_event: dict[str, Any] | None = None
        try:
            with self.log_path.open("r", encoding="utf-8") as stream:
                for line in stream:
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if (
                        isinstance(event, dict)
                        and event.get("schema") == SIGNALS_SCHEMA
                        and event.get("session_id") == session_id
                    ):
                        latest_event = event
        except (FileNotFoundError, OSError, UnicodeDecodeError):
            return None
        if latest_event is not None:
            self._latest_by_session[session_id] = latest_event
        return latest_event

    def timeline(
        self,
        session_id: str,
        limit: int = 200,
    ) -> list[dict[str, Any]]:
        if not session_id.strip():
            return []
        bounded_limit = min(max(1, int(limit)), 2_000)
        events: deque[dict[str, Any]] = deque(maxlen=bounded_limit)
        with self._lock:
            try:
                with self.log_path.open("r", encoding="utf-8") as stream:
                    for line in stream:
                        try:
                            event = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        if (
                            isinstance(event, dict)
                            and event.get("schema") == SIGNALS_SCHEMA
                            and event.get("session_id") == session_id
                        ):
                            events.append(event)
            except (FileNotFoundError, OSError, UnicodeDecodeError):
                return []
        return list(events)

    def voice_prompt_context(
        self,
        session_id: str,
        utterance_id: str | None,
    ) -> str:
        if not utterance_id:
            return ""
        event = self.latest(session_id)
        if not event or event.get("utterance_id") != utterance_id:
            return ""
        voice = event.get("voice_emotion")
        assessment = event.get("arousal_assessment")
        support = ""
        if isinstance(assessment, dict) and assessment.get("level") in {"high", "critical"}:
            support = (
                "[\ubcf5\uc218 \uc2e0\ud638\uc5d0\uc11c \ub192\uc740 \ud765\ubd84\ub3c4 \uad00\ucc30]\n"
                "- \uc774\ub294 \uc9c4\ub2e8\uc774 \uc544\ub2cc \uc13c\uc11c\uc640 \ubaa8\ub378\uc758 "
                "\uad00\ucc30\uc784\uc744 \uc9e7\uac8c \uc54c\ub9b0\ub2e4.\n"
                "- \uba3c\uc800 \ubd88\ud3b8\ud568\uacfc \ud604\uc7ac \uc548\uc804\uc744 \ud655\uc778\ud55c\ub2e4.\n"
                "- \ucc9c\ucc9c\ud55c \uc2ec\ud638\ud761 \ub610\ub294 1~3\ubd84 \uc9e7\uc740 "
                "\uba85\uc0c1 \uc911 \ud558\ub098\ub97c \uad8c\ud55c\ub2e4.\n"
                "- \ud604\uae30\uc99d, \ud749\ud1b5, \ud638\ud761\uace4\ub780, \uc989\uac01\uc801 \uc704\ud5d8\uc740 "
                "\ubcf4\ud638\uc790\ub098 \uc751\uae09 \ub3c4\uc6c0\uc744 \uc694\uccad\ud55c\ub2e4."
            )
        if not isinstance(voice, dict) or voice.get("status") != "valid":
            return support
        try:
            confidence = float(voice.get("confidence", 0.0))
        except (TypeError, ValueError):
            return support
        if confidence < self.prompt_min_confidence:
            return support
        label = str(voice.get("label") or "Neutral")
        display = str(
            voice.get("display_label") or DISPLAY_LABELS.get(label, label)
        )
        guidance = {
            "Sad": "조언보다 감정 반영을 먼저 하고 낮고 안정적인 어조를 유지한다.",
            "Angry": "방어하거나 반박하지 말고 짧고 차분한 문장으로 불편함을 확인한다.",
            "Fearful": "근거 없는 안심을 피하고 현재 안전과 당장 가능한 행동을 확인한다.",
            "Happy": "과하게 들뜨지 않으면서 긍정적인 내용을 자연스럽게 함께한다.",
            "Neutral": "감정을 과잉 해석하지 말고 발화 내용과 확인 질문에 집중한다.",
        }.get(label, "발화 내용과 사용자의 직접 표현을 우선한다.")
        return (
            (support + "\n\n" if support else "")
            +
            "[현재 발화의 음성 감정 모델 참고치]\n"
            f"- 모델 출력: {display} ({confidence:.2f})\n"
            f"- 응답 참고: {guidance}\n"
            "- 이 값은 음향 모델의 일시적 출력일 뿐 사용자의 실제 감정이나 "
            "의학적 상태를 뜻하지 않는다. 발화 내용과 사용자의 직접 표현을 우선하고, "
            "감정을 단정하지 말며 필요하면 부드럽게 확인한다."
        )

    @staticmethod
    def _atomic_write(path: Path, payload: dict[str, Any]) -> None:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{path.name}.",
            suffix=".tmp",
            dir=path.parent,
        )
        try:
            with os.fdopen(
                descriptor,
                "w",
                encoding="utf-8",
                newline="\n",
            ) as stream:
                json.dump(payload, stream, ensure_ascii=False, separators=(",", ":"))
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            if os.name == "posix":
                os.chmod(temporary_name, 0o640)
            os.replace(temporary_name, path)
        except BaseException:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass
            raise


class VoiceTurnProcessor:
    """Share one temporary WAV between STT and emotion inference, then delete it."""

    def __init__(
        self,
        settings: Settings,
        transcriber: Transcriber,
        analyzer: VoiceEmotionAnalyzer,
        signals: CounselingSignalsStore,
        signal_handler: Callable[[dict[str, Any]], Any] | None = None,
    ):
        self.settings = settings
        self.transcriber = transcriber
        self.analyzer = analyzer
        self.signals = signals
        self.signal_handler = signal_handler
        self._speech_priority_lock = asyncio.Lock()

    def _enable_speech_priority(self, session_id: str) -> Path | None:
        marker = getattr(self.settings, "speech_priority_marker", None)
        if marker is None:
            return None
        path = Path(marker)
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                f"session_id={session_id}\npid={os.getpid()}\n",
                encoding="utf-8",
            )
            logger.info("speech_priority_enabled marker=%s", path)
            return path
        except OSError:
            logger.exception("speech_priority_marker_write_failed path=%s", path)
            return None

    @staticmethod
    def _disable_speech_priority(marker: Path | None) -> None:
        if marker is None:
            return
        try:
            marker.unlink(missing_ok=True)
            logger.info("speech_priority_disabled marker=%s", marker)
        except OSError:
            logger.exception("speech_priority_marker_remove_failed path=%s", marker)

    async def process(
        self,
        session_id: str,
        audio_path: Path,
        *,
        utterance_id: str | None = None,
    ) -> tuple[str, int, dict[str, Any], str]:
        async with self._speech_priority_lock:
            marker = self._enable_speech_priority(session_id)
            settle_ms = max(
                0,
                int(getattr(self.settings, "speech_priority_settle_ms", 0)),
            )
            try:
                if marker is not None and settle_ms:
                    await asyncio.sleep(settle_ms / 1000)
                return await self._process_prioritized(
                    session_id,
                    audio_path,
                    utterance_id=utterance_id,
                )
            finally:
                self._disable_speech_priority(marker)

    async def _process_prioritized(
        self,
        session_id: str,
        audio_path: Path,
        *,
        utterance_id: str | None = None,
    ) -> tuple[str, int, dict[str, Any], str]:
        resolved_utterance_id = utterance_id or uuid.uuid4().hex
        transcription_task = asyncio.create_task(
            self.transcriber.transcribe(audio_path, cleanup=False)
        )
        emotion_task = asyncio.create_task(
            self.analyzer.analyze(
                audio_path,
                session_id,
                resolved_utterance_id,
            )
        )
        transcript = ""
        stt_ms = 0
        transcription_error: BaseException | None = None
        try:
            transcription_outcome, emotion_outcome = await asyncio.gather(
                transcription_task,
                emotion_task,
                return_exceptions=True,
            )
            if isinstance(transcription_outcome, BaseException):
                transcription_error = transcription_outcome
            else:
                transcript, stt_ms = transcription_outcome
            if isinstance(emotion_outcome, BaseException):
                emotion = self.analyzer.unavailable_result(
                    session_id,
                    resolved_utterance_id,
                    "inference_error",
                )
                emotion["error_code"] = _safe_error_code(emotion_outcome)
            else:
                emotion = emotion_outcome
            event = self.signals.record_voice_turn(
                emotion,
                transcription_status=(
                    "error" if transcription_error else "completed"
                ),
                transcript_characters=len(transcript),
            )
            if self.signal_handler is not None:
                try:
                    await self.signal_handler(event)
                except Exception:
                    logger.exception(
                        "counseling_signal_handler_failed session=%s",
                        session_id,
                    )
            if transcription_error is not None:
                raise transcription_error
            return transcript, stt_ms, emotion, resolved_utterance_id
        finally:
            for task in (transcription_task, emotion_task):
                if not task.done():
                    task.cancel()
            await asyncio.gather(
                transcription_task,
                emotion_task,
                return_exceptions=True,
            )
            if not self.settings.keep_recordings:
                audio_path.unlink(missing_ok=True)
