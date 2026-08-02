from __future__ import annotations

from app.proactive_inbox import ProactiveMessageInbox
from app.schemas import ProactiveMessageRequest


def test_external_message_targets_active_session_and_deduplicates(tmp_path) -> None:
    inbox = ProactiveMessageInbox(tmp_path / "proactive.jsonl")
    inbox.set_active_session("session-1")
    request = ProactiveMessageRequest(
        event_id="event-1",
        text="물을 한 잔 드셔 보는 건 어떠세요?",
        source="activity_algorithm",
        topic="HYDRATION",
    )

    event, created = inbox.submit(request)
    duplicate, duplicate_created = inbox.submit(request)

    assert created is True
    assert duplicate_created is False
    assert event == duplicate
    assert event["session_id"] == "session-1"
    assert inbox.next_event("session-1")["event_id"] == "event-1"
    assert inbox.next_event("session-1", after_event_id="event-1") is None


def test_acknowledgement_is_idempotent_and_logged(tmp_path) -> None:
    log_path = tmp_path / "proactive.jsonl"
    inbox = ProactiveMessageInbox(log_path)
    inbox.set_active_session("session-1")
    inbox.submit(
        ProactiveMessageRequest(
            event_id="event-2",
            text="잠깐 몸을 움직여 볼까요?",
        )
    )

    event, first_ack = inbox.acknowledge("session-1", "event-2")
    _, second_ack = inbox.acknowledge("session-1", "event-2")

    assert event is not None
    assert first_ack is True
    assert second_ack is False
    assert log_path.read_text(encoding="utf-8").count("acknowledged") == 2
