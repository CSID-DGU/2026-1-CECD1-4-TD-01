from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from types import SimpleNamespace

from app.health_proactive.models import AnalysisResult, DataBundle, ExternalComparison, TopicDecision, TriggerKind
from app.health_proactive.service import HealthProactiveService


UTC = timezone.utc


class FakeReader:
    def __init__(self):
        self.history_windows = []

    def collect(self, now, history_days=60):
        self.history_windows.append(history_days)
        return DataBundle(
            comparisons=[
                ExternalComparison(
                    "phone_screen_daily_minutes", 600, 300, 0.95,
                    "android_phenotype_summary", now,
                )
            ],
            context={"now": now.isoformat()},
            source_status={
                "android_summaries": {"available": True},
                "room": {"available": True, "rows": 1},
                "home_assistant": {
                    "available": True,
                    "light_events": 1,
                    "access_events_after_debounce": 0,
                },
            },
        )


class FakeLlm:
    def __init__(self):
        self.calls = 0

    def runtime_status(self):
        return {"state": "sleeping"}

    async def generate_proactive_opening(self, system_prompt, user_instruction):
        self.calls += 1
        assert "phone_screen_daily_minutes" in system_prompt
        return (
            "\ub208\uacfc \ubaa9\uc774 \uad1c\ucc2e\uc73c\uc2dc\uba74 "
            "\ud654\uba74\uc5d0\uc11c 1\ubd84 \uc26c\uc5b4 \ubcf4\ub294 \uac74 \uc5b4\ub5a0\uc138\uc694?"
        ), 100
        return "최근 휴대폰을 보는 시간이 늘었는데, 주로 어떤 일을 하셨나요?", 100


class FakeConversationStore:
    def __init__(self):
        self.context = None

    def set_session_context(self, session_id, content, ttl_minutes):
        self.context = (session_id, content, ttl_minutes)


class FakeInbox:
    active_session_id = "session-1"

    def __init__(self):
        self.request = None

    def submit(self, request):
        self.request = request
        return {"event_id": request.event_id}, True


def test_service_wakes_sleeping_llm_then_queues_spoken_opening(tmp_path) -> None:
    settings = SimpleNamespace(
        health_monitor_database_path=tmp_path / "health.sqlite3",
        room_database_path=tmp_path / "room.sqlite3",
        home_assistant_database_path=tmp_path / "ha.sqlite3",
        derived_insights_path=tmp_path / "derived.json",
        calendar_events_path=tmp_path / "calendar.json",
        health_monitor_timezone="Asia/Seoul",
        proactive_max_nonurgent_per_day=4,
        health_monitor_enabled=True,
        health_monitor_startup_delay_seconds=1,
        health_monitor_interval_seconds=60,
    )
    llm = FakeLlm()
    conversations = FakeConversationStore()
    inbox = FakeInbox()
    service = HealthProactiveService(settings, llm, conversations, inbox)
    service.initialize()
    reader = FakeReader()
    service.reader = reader
    service.now_provider = lambda: datetime(2026, 7, 31, 10, 0, tzinfo=UTC)

    async def run_scenario():
        first = await service.run_once(deliver=True)
        await service.run_once(deliver=False)
        return first

    payload = asyncio.run(run_scenario())

    assert payload["delivered"] is True
    assert payload["delivery"]["llm_was_sleeping"] is True
    assert payload["delivery"]["llm_used"] is True
    assert llm.calls == 1
    assert inbox.request.source == "health_monitor"
    assert inbox.request.text.endswith("?")
    assert conversations.context[0] == "session-1"
    assert reader.history_windows[0] == {"room": 21, "home_assistant": 21}
    assert reader.history_windows[1] == {"room": 2, "home_assistant": 2}


def test_morning_first_seen_turns_on_light_once_per_day(tmp_path) -> None:
    settings = SimpleNamespace(
        health_monitor_database_path=tmp_path / "health.sqlite3",
        room_database_path=tmp_path / "room.sqlite3",
        home_assistant_database_path=tmp_path / "ha.sqlite3",
        derived_insights_path=tmp_path / "derived.json",
        calendar_events_path=tmp_path / "calendar.json",
        health_monitor_timezone="Asia/Seoul",
        proactive_max_nonurgent_per_day=4,
        health_monitor_enabled=True,
        health_monitor_startup_delay_seconds=1,
        health_monitor_interval_seconds=60,
        automatic_morning_light_enabled=True,
    )

    class FakeActionExecutor:
        def __init__(self):
            self.actions = []

        async def execute_automatic(self, action):
            self.actions.append(action)
            return "ok"

    action_executor = FakeActionExecutor()
    service = HealthProactiveService(
        settings,
        FakeLlm(),
        FakeConversationStore(),
        FakeInbox(),
        action_executor,
    )
    generated_at = datetime(2026, 8, 1, 0, 0, tzinfo=UTC)
    result = AnalysisResult(
        generated_at=generated_at,
        assessments=(),
        clusters=(),
        decision=TopicDecision(
            should_speak=False,
            trigger=TriggerKind.MORNING_FIRST_SEEN,
            category=None,
            metric=None,
            priority=70,
            fallback_opening=None,
            reason="test",
        ),
        context={},
        source_status={},
    )
    service._analyze_sync = lambda: result
    service._ensure_daily_policy = lambda _result: None

    async def run_twice():
        first = await service.run_once(deliver=True)
        second = await service.run_once(deliver=True)
        return first, second

    first, second = asyncio.run(run_twice())

    assert first["automatic_action"]["action"] == "TURN_ON_BEDROOM_LIGHT"
    assert "automatic_action" not in second
    assert action_executor.actions == ["TURN_ON_BEDROOM_LIGHT"]
