from __future__ import annotations

import asyncio
import json
import re
import time
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen

ACTION_PATTERN = re.compile(r"<onmom_action>(\{.*?\})</onmom_action>", re.DOTALL)
ALLOWED_ACTIONS = {
    "TURN_OFF_BEDROOM_LIGHT",
    "TURN_ON_BEDROOM_LIGHT",
    "START_WHITE_NOISE",
    "STOP_WHITE_NOISE",
    "SEND_HELP_ALERT",
}

NEGATIONS = (
    "\ud558\uc9c0\ub9c8",
    "\ud558\uc9c0\ub9d0",
    "\uc9c0\ub9c8",
    "\uc9c0\ub9d0",
    "\ub9d0\uc544",
    "\ub410\uc5b4",
    "\ud544\uc694\uc5c6",
    "\uad1c\ucc2e\uc544",
    "\ucde8\uc18c",
    "\ub9d0\uace0",
)


def _normalized(text: str) -> str:
    return re.sub(r"[^0-9a-z\uac00-\ud7a3]+", "", text.lower())


def detect_user_action(text: str) -> str | None:
    """Map common Korean/STT variants to the existing action allow-list."""
    value = _normalized(text)
    if not value or any(token in value for token in NEGATIONS):
        return None
    if any(
        token in value
        for token in (
            "\ub3c4\uc640\uc918",
            "\uc0b4\ub824\uc918",
            "\ub3c4\uc6c0\ud544\uc694",
            "\uc704\ud5d8\ud574",
            "119",
        )
    ):
        return "SEND_HELP_ALERT"
    if "\ubcf4\ud638\uc790" in value and any(
        token in value
        for token in (
            "\uc54c\ub824",
            "\uc5f0\ub77d",
            "\ubd88\ub7ec",
            "\uc54c\ub9bc",
            "\uc804\ud654",
        )
    ):
        return "SEND_HELP_ALERT"
    sound = any(
        token in value
        for token in (
            "\ubc31\uc0c9\uc18c\uc74c",
            "\ud654\uc774\ud2b8\ub178\uc774\uc988",
            "\ube57\uc18c\ub9ac",
            "\uc218\uba74\uc18c\ub9ac",
        )
    )
    if sound and any(
        token in value
        for token in ("\uaebc", "\uba48\ucdb0", "\uadf8\ub9cc", "\uc911\uc9c0")
    ):
        return "STOP_WHITE_NOISE"
    if sound and any(
        token in value
        for token in ("\ud2c0\uc5b4", "\ub4e4\ub824", "\ucf1c", "\uc2dc\uc791")
    ):
        return "START_WHITE_NOISE"
    light = "\ubd88" in value or "\uc870\uba85" in value
    if light and any(
        token in value
        for token in ("\uaebc", "\uc5b4\ub461\uac8c", "\ub208\ubd80\uc154")
    ):
        return "TURN_OFF_BEDROOM_LIGHT"
    if (light and any(token in value for token in ("\ucf1c", "\ubc1d\uac8c"))) or any(
        token in value
        for token in ("\uc5b4\ub450\uc6cc", "\uc548\ubcf4\uc5ec", "\uc548\ubcf4\uc778\ub2e4")
    ):
        return "TURN_ON_BEDROOM_LIGHT"
    return None


def extract_action(text: str) -> tuple[str, str | None]:
    match = ACTION_PATTERN.search(text)
    visible = ACTION_PATTERN.sub("", text).strip()
    if not match:
        return visible, None
    try:
        payload = json.loads(match.group(1))
    except json.JSONDecodeError:
        return visible, None
    action = payload.get("action") if isinstance(payload, dict) else None
    return visible, action if action in ALLOWED_ACTIONS else None


def has_explicit_consent(text: str) -> bool:
    value = _normalized(text)
    if not value or any(token in value for token in NEGATIONS):
        return False
    return detect_user_action(text) is not None or any(
        token in value
        for token in (
            "\ub124",
            "\uc608",
            "\uc751",
            "\uadf8\ub798",
            "\uc88b\uc544",
            "\ud574\uc918",
            "\ud574\uc8fc\uc138\uc694",
            "\ubd80\ud0c1",
            "\uc9c4\ud589\ud574",
        )
    )

class ActionExecutor:
    """Execute only allow-listed actions after explicit user consent."""

    def __init__(self, settings: Any):
        self.settings = settings
        self._last_automatic_alert_at = 0.0

    async def execute(self, action: str, user_text: str) -> str:
        if not has_explicit_consent(user_text):
            return "실행하려면 먼저 분명하게 말씀해 주세요."
        if action in {"TURN_OFF_BEDROOM_LIGHT", "TURN_ON_BEDROOM_LIGHT"}:
            return await self._control_bedroom_light(action)
        if action == "START_WHITE_NOISE":
            return await self._start_white_noise()
        if action == "STOP_WHITE_NOISE":
            return await self._stop_white_noise()
        if action == "SEND_HELP_ALERT":
            return await self._send_help_alert()
        return "요청한 기능은 실행할 수 없습니다."

    async def execute_automatic(self, action: str) -> str:
        if action == "TURN_ON_BEDROOM_LIGHT":
            return await self._control_bedroom_light(action)
        if action == "SEND_HELP_ALERT":
            now = time.monotonic()
            cooldown = max(
                0.0,
                float(
                    getattr(
                        self.settings,
                        "automatic_guardian_alert_cooldown_seconds",
                        600,
                    )
                ),
            )
            if (
                self._last_automatic_alert_at
                and now - self._last_automatic_alert_at < cooldown
            ):
                return "\ubcf4\ud638\uc790 \uc54c\ub9bc \uc7ac\uc804\uc1a1 \ub300\uae30 \uc911\uc785\ub2c8\ub2e4."
            self._last_automatic_alert_at = now
            return await self.send_guardian_alert(
                "\uc774\uc0c1 \uc2e0\ud638 \uc790\ub3d9 \uac10\uc9c0",
                "\uc0c1\ub2f4 \uc911 \ubcf5\uc218\uc758 \uc2e0\ud638\uc5d0\uc11c \ub192\uc740 \ud765\ubd84\ub3c4\uac00 \uad00\ucc30\ub418\uc5c8\uc2b5\ub2c8\ub2e4.",
                "HIGH",
            )
        return "\uc790\ub3d9 \uc2e4\ud589\uc744 \ud5c8\uc6a9\ud558\uc9c0 \uc54a\uc740 \ub3d9\uc791\uc785\ub2c8\ub2e4."

    async def _control_bedroom_light(self, action: str) -> str:
        device_id = self.settings.bedroom_light_device_id.strip()
        if not device_id:
            return "침실 조명 설정이 아직 완료되지 않았습니다."
        service_action = "TURN_OFF" if action == "TURN_OFF_BEDROOM_LIGHT" else "TURN_ON"
        try:
            await self._post(
                "/v1/iot/commands",
                {"device_id": device_id, "action": service_action},
                "iot-command-v1",
            )
        except (OSError, ValueError):
            return "조명 명령을 지금 보낼 수 없습니다."
        return "조명을 껐습니다." if service_action == "TURN_OFF" else "조명을 켰습니다."

    async def _start_white_noise(self) -> str:
        try:
            await self._bridge_post("/noise", {"seconds": self.settings.sleep_audio_seconds})
        except (OSError, ValueError):
            return "스피커 재생을 시작하지 못했습니다."
        return "작은 백색소음을 틀었습니다. 필요하시면 언제든 멈춰 드릴게요."

    async def _stop_white_noise(self) -> str:
        try:
            await self._bridge_post("/stop", {})
        except (OSError, ValueError):
            return "백색소음을 지금 멈출 수 없습니다."
        return "백색소음을 멈췄습니다."

    async def _send_help_alert(self) -> str:
        try:
            await self._post(
                "/v1/trigger-alert",
                {
                    "category": "SAFETY",
                    "title": "사용자 도움 요청",
                    "message": "사용자가 직접 도움 알림을 요청했습니다.",
                    "severity": "HIGH",
                },
                "trigger-alert-v1",
            )
        except (OSError, ValueError):
            return "도움 알림을 지금 보낼 수 없습니다. 주변에 직접 도움을 요청해 주세요."
        return "도움 알림을 보냈습니다."

    async def send_guardian_alert(
        self,
        title: str,
        message: str,
        severity: str = "HIGH",
    ) -> str:
        try:
            await self._post(
                "/v1/trigger-alert",
                {
                    "category": "SAFETY",
                    "title": title,
                    "message": message,
                    "severity": severity,
                },
                "trigger-alert-v1",
            )
        except (OSError, ValueError):
            return (
                "\ubcf4\ud638\uc790 \uc54c\ub9bc\uc744 \ubcf4\ub0bc \uc218 \uc5c6\uc2b5\ub2c8\ub2e4. "
                "\uc8fc\ubcc0\uc5d0 \uc9c1\uc811 \ub3c4\uc6c0\uc744 \uc694\uccad\ud574 \uc8fc\uc138\uc694."
            )
        return "\ubcf4\ud638\uc790 \uc54c\ub9bc\uc744 \ubcf4\ub0c8\uc2b5\ub2c8\ub2e4."

    async def _bridge_post(self, path: str, payload: dict[str, int]) -> None:
        await asyncio.to_thread(self._bridge_post_sync, path, payload)

    def _bridge_post_sync(self, path: str, payload: dict[str, int]) -> None:
        request = Request(
            f"{self.settings.speaker_bridge_url.rstrip('/')}{path}",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(request, timeout=5) as response:
            if not 200 <= response.status < 300:
                raise OSError("speaker bridge rejected command")

    async def _post(self, path: str, payload: dict[str, str], schema: str) -> None:
        await asyncio.to_thread(self._post_sync, path, payload, schema)

    def _post_sync(self, path: str, payload: dict[str, str], schema: str) -> None:
        token_path = Path(self.settings.action_token_file).expanduser()
        token = token_path.read_text(encoding="utf-8").strip()
        if not token:
            raise ValueError("action token is missing")
        request = Request(
            f"{self.settings.action_gateway_url.rstrip('/')}{path}",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
                "X-OnMom-Schema": schema,
            },
            method="POST",
        )
        with urlopen(request, timeout=5) as response:
            if not 200 <= response.status < 300:
                raise OSError("action gateway rejected command")