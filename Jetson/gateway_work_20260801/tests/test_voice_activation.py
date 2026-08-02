from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.schemas import ChatRequest


def test_voice_chat_requires_activation_reason() -> None:
    with pytest.raises(ValidationError, match="voice LLM requests require"):
        ChatRequest(
            session_id="session-1",
            text="주변에서 들린 말",
            source="voice",
        )


@pytest.mark.parametrize(
    "activation",
    [
        "wake_word",
        "wake_followup",
        "proactive",
        "active_session",
        "ui_action",
    ],
)
def test_explicit_activation_allows_voice_chat(activation: str) -> None:
    request = ChatRequest(
        session_id="session-1",
        text="사용자 발화",
        source="voice",
        activation=activation,
    )

    assert request.activation == activation


def test_text_chat_does_not_require_voice_activation() -> None:
    request = ChatRequest(
        session_id="session-1",
        text="키보드 입력",
        source="text",
    )

    assert request.activation is None
