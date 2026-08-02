import asyncio
from types import SimpleNamespace

from app.action_tools import (
    ActionExecutor,
    detect_user_action,
    extract_action,
    has_explicit_consent,
)


def test_extracts_only_allowlisted_action_tag() -> None:
    text, action = extract_action(
        '조명을 꺼드릴게요. <onmom_action>{"action":"TURN_OFF_BEDROOM_LIGHT"}</onmom_action>'
    )

    assert text == '조명을 꺼드릴게요.'
    assert action == 'TURN_OFF_BEDROOM_LIGHT'
    assert has_explicit_consent('네, 해주세요') is True
    assert has_explicit_consent('그건 하지 마세요') is False


def test_rejects_unknown_action_tag() -> None:
    text, action = extract_action('<onmom_action>{"action":"DELETE_ALL"}</onmom_action>')

    assert text == ''
    assert action is None

def test_maps_natural_korean_actions_and_rejects_negation() -> None:
    assert detect_user_action("\ubd88 \ucf1c\uc918") == "TURN_ON_BEDROOM_LIGHT"
    assert (
        detect_user_action("\uc870\uba85 \uc880 \uaebc \uc8fc\uc138\uc694")
        == "TURN_OFF_BEDROOM_LIGHT"
    )
    assert (
        detect_user_action("\ubcf4\ud638\uc790\uc5d0\uac8c \uc5f0\ub77d\ud574\uc918")
        == "SEND_HELP_ALERT"
    )
    assert (
        detect_user_action("\ubc31\uc0c9\uc18c\uc74c \ud2c0\uc5b4\uc918")
        == "START_WHITE_NOISE"
    )
    assert detect_user_action("\ubd88 \ucf1c\uc9c0 \ub9c8") is None
    assert has_explicit_consent("\ubd88 \ucf1c\uc9c0 \ub9c8") is False


def test_automatic_guardian_alert_has_cooldown() -> None:
    class FakeExecutor(ActionExecutor):
        def __init__(self):
            super().__init__(
                SimpleNamespace(
                    automatic_guardian_alert_cooldown_seconds=600,
                )
            )
            self.sent = 0

        async def send_guardian_alert(self, title, message, severity="HIGH"):
            self.sent += 1
            return "ok"

    executor = FakeExecutor()

    async def run_twice():
        return await executor.execute_automatic(
            "SEND_HELP_ALERT"
        ), await executor.execute_automatic("SEND_HELP_ALERT")

    first, second = asyncio.run(run_twice())
    assert first == "ok"
    assert executor.sent == 1
    assert "\uc7ac\uc804\uc1a1 \ub300\uae30" in second
