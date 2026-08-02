from app.database import ConversationStore


def test_message_events_are_recent_then_incremental(tmp_path):
    store = ConversationStore(tmp_path / "conversation.sqlite3")
    store.initialize()
    store.create_session("session-1")
    store.add_message("session-1", "user", "첫 번째", "voice")
    store.add_message("session-1", "assistant", "두 번째", "llm")
    store.add_message("session-1", "user", "세 번째", "voice")

    recent = store.message_events("session-1", limit=2)
    assert [item["content"] for item in recent] == ["두 번째", "세 번째"]

    store.add_message("session-1", "assistant", "네 번째", "llm")
    incremental = store.message_events("session-1", after_id=recent[-1]["id"])
    assert [item["content"] for item in incremental] == ["네 번째"]
