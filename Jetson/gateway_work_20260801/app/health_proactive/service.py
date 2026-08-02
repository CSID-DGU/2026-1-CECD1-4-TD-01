from __future__ import annotations

import asyncio
import logging
import re
import uuid
from dataclasses import replace
from datetime import date, datetime, timedelta, timezone
from typing import Any

from ..proactive_questions import FALLBACK_QUESTION, OpeningQuestion
from ..schemas import ProactiveMessageRequest
from .analysis import HealthRiskAnalyzer
from .daily_policy import DailyPolicyBuilder
from .models import (
    AlertLevel,
    AnalysisResult,
    RiskCategory,
    TopicDecision,
    TriggerKind,
)
from .prompting import Gemma4HealthPromptBuilder
from .scenario_datasets import DEFAULT_DATASET_ID, ScenarioDatasetReader
from .sources import UnifiedJetsonDataReader
from .storage import HealthMonitorStore


logger = logging.getLogger("onmom.health_proactive")
UTC = timezone.utc


TEST_SCENARIOS = (
    {
        "id": "morning_checkin",
        "label": "아침 안부 인사",
        "description": "아침 첫 재실 상황에서 물 한 잔과 가벼운 몸 깨우기를 제안합니다.",
    },
    {
        "id": "sleep_shortage",
        "label": "수면 부족",
        "description": "수면이 부족한 날 무리하지 않고 일을 나누어 쉬어가도록 제안합니다.",
    },
    {
        "id": "prolonged_sitting",
        "label": "장시간 같은 자세",
        "description": "심한 통증·저림이 없다면 천천히 일어나 자세를 바꾸도록 제안합니다.",
    },
    {
        "id": "inactivity",
        "label": "장시간 무활동",
        "description": "몸이 괜찮다면 어깨를 펴고 1~2분 천천히 움직이도록 제안합니다.",
    },
    {
        "id": "screen_overuse",
        "label": "화면 사용 증가",
        "description": "화면에서 1~2분 시선을 떼고 눈과 목을 쉬도록 제안합니다.",
    },
    {
        "id": "bedtime_light",
        "label": "취침 시간 조명",
        "description": "이동이 끝났다면 조명을 낮추고 잠들 준비를 하도록 제안합니다.",
    },
)


class HealthProactiveService:
    """Analyze trusted Jetson stores and wake Gemma only for eligible speech."""

    def __init__(
        self,
        settings: Any,
        llm: Any,
        conversation_store: Any,
        proactive_inbox: Any,
        action_executor: Any | None = None,
    ):
        self.settings = settings
        self.llm = llm
        self.conversation_store = conversation_store
        self.proactive_inbox = proactive_inbox
        self.action_executor = action_executor
        self.store = HealthMonitorStore(settings.health_monitor_database_path)
        self.reader = UnifiedJetsonDataReader(
            settings.room_database_path,
            settings.home_assistant_database_path,
            settings.derived_insights_path,
            settings.calendar_events_path,
            settings.health_monitor_timezone,
            getattr(settings, "insights_database_path", None),
        )
        self.scenarios = ScenarioDatasetReader(
            getattr(settings, "health_scenario_dataset_root", None)
            or settings.health_monitor_database_path.parent / "scenarios",
            settings.health_monitor_timezone,
        )
        self._active_dataset = DEFAULT_DATASET_ID
        self._scenario_store: HealthMonitorStore | None = None
        self.analyzer = HealthRiskAnalyzer(
            self.store,
            settings.health_monitor_timezone,
            settings.proactive_max_nonurgent_per_day,
        )
        self.prompts = Gemma4HealthPromptBuilder()
        self.daily_policies = DailyPolicyBuilder(
            getattr(settings, "health_analysis_window_days", 21)
        )
        self.now_provider = lambda: datetime.now(UTC)
        self._latest: AnalysisResult | None = None
        self._task: asyncio.Task | None = None
        self._stop = asyncio.Event()
        self._run_lock = asyncio.Lock()
        self._last_error: str | None = None
        self._last_delivery: dict[str, Any] | None = None
        self._last_morning_light_day: date | None = None

    def initialize(self) -> None:
        self.store.initialize()

    async def start(self) -> None:
        if not self.settings.health_monitor_enabled or self._task is not None:
            return
        self._stop.clear()
        self._task = asyncio.create_task(
            self._loop(),
            name="onmom-health-proactive-monitor",
        )

    async def stop(self) -> None:
        self._stop.set()
        task = self._task
        self._task = None
        if task is None:
            return
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    async def _loop(self) -> None:
        try:
            await asyncio.wait_for(
                self._stop.wait(),
                timeout=max(1, self.settings.health_monitor_startup_delay_seconds),
            )
            return
        except asyncio.TimeoutError:
            pass
        while not self._stop.is_set():
            try:
                await self.run_once(deliver=True)
                self._last_error = None
            except Exception as exc:
                self._last_error = type(exc).__name__
                logger.exception("health_proactive_cycle_failed")
            try:
                await asyncio.wait_for(
                    self._stop.wait(),
                    timeout=max(30, self.settings.health_monitor_interval_seconds),
                )
            except asyncio.TimeoutError:
                continue

    async def run_once(
        self,
        *,
        deliver: bool = False,
        session_id: str | None = None,
    ) -> dict[str, Any]:
        async with self._run_lock:
            result = await asyncio.to_thread(self._analyze_sync)
            scenario_active = self._active_dataset != DEFAULT_DATASET_ID
            self._latest = result
            response: dict[str, Any] = {
                "analysis": result.to_dict(),
                "daily_policy": (
                    None if scenario_active else self._ensure_daily_policy(result)
                ),
                "delivered": False,
            }
            if scenario_active and deliver:
                response["delivery_reason"] = "scenario_auto_delivery_blocked"
                return response
            if (
                deliver
                and self.action_executor is not None
                and getattr(
                    self.settings,
                    "automatic_morning_light_enabled",
                    True,
                )
                and result.decision.trigger == TriggerKind.MORNING_FIRST_SEEN
            ):
                local_day = result.generated_at.astimezone(
                    self.analyzer.timezone
                ).date()
                if self._last_morning_light_day != local_day:
                    self._last_morning_light_day = local_day
                    action_result = await self.action_executor.execute_automatic(
                        "TURN_ON_BEDROOM_LIGHT"
                    )
                    response["automatic_action"] = {
                        "action": "TURN_ON_BEDROOM_LIGHT",
                        "result": action_result,
                    }
            if not deliver or not result.decision.should_speak:
                return response
            target = session_id or self.proactive_inbox.active_session_id
            if not target:
                target = self.conversation_store.latest_session_id()
            if not target:
                response["delivery_reason"] = "no_active_session"
                return response
            delivery = await self._deliver_result(
                result,
                target,
                source="health_monitor",
                record_outreach=True,
            )
            response.update(
                {"delivered": bool(delivery.get("created")), "delivery": delivery}
            )
            return response

    async def opening_for_session(self, session_id: str) -> OpeningQuestion:
        await self.run_once(deliver=False)
        result = self._latest
        if result is None or not result.decision.should_speak:
            return OpeningQuestion(FALLBACK_QUESTION, "fallback", "GENERAL_CHECK_IN")
        opening, _, _ = await self._generate_opening(result)
        prompt = self.prompts.build(result)
        self.conversation_store.set_session_context(
            session_id,
            prompt,
            ttl_minutes=180,
        )
        (self._scenario_store or self.store).record_outreach(
            session_id,
            result.decision,
            opening,
            result.decision.calendar_event_id,
            result.generated_at,
        )
        topic = (
            result.decision.category.value
            if result.decision.category
            else result.decision.trigger.value
            if result.decision.trigger
            else "GENERAL_CHECK_IN"
        )
        return OpeningQuestion(opening, "health_monitor", topic)

    async def test_scenario(
        self,
        scenario: str,
        session_id: str | None = None,
    ) -> dict[str, Any]:
        if scenario not in {item["id"] for item in TEST_SCENARIOS}:
            raise ValueError("지원하지 않는 능동 상담 테스트 시나리오입니다.")
        await self.run_once(deliver=False)
        if self._latest is None:
            raise RuntimeError("위험 분석 결과가 아직 없습니다.")
        target = (
            session_id
            or self.proactive_inbox.active_session_id
            or self.conversation_store.latest_session_id()
        )
        if not target:
            raise RuntimeError("능동 상담을 받을 활성 상담 세션이 없습니다.")
        self.proactive_inbox.set_active_session(target)
        forced = replace(
            self._latest,
            decision=_test_decision(scenario),
            generated_at=self.now_provider(),
        )
        delivery = await self._deliver_result(
            forced,
            target,
            source="dashboard_test",
            record_outreach=False,
        )
        return {
            "scenario": scenario,
            "test": True,
            "daily_policy": self.current_daily_policy(),
            "delivery": delivery,
        }

    @staticmethod
    def test_scenarios() -> list[dict[str, str]]:
        return [dict(item) for item in TEST_SCENARIOS]

    def datasets(self) -> dict[str, Any]:
        datasets = self.scenarios.datasets()
        active = next(
            (item for item in datasets if item["id"] == self._active_dataset),
            datasets[0],
        )
        return {"active_dataset": active, "datasets": datasets}

    async def select_dataset(self, dataset_id: str) -> dict[str, Any]:
        available = {item["id"] for item in self.scenarios.datasets()}
        if dataset_id not in available:
            raise ValueError("???? ?? ???????.")
        async with self._run_lock:
            self._active_dataset = dataset_id
            if dataset_id == DEFAULT_DATASET_ID:
                self._scenario_store = None
                result = await asyncio.to_thread(self._analyze_real_sync)
            else:
                path = self.settings.health_monitor_database_path.with_name(
                    f"health-proactive-scenario-{dataset_id}.sqlite3"
                )
                store = HealthMonitorStore(path)
                store.initialize()
                store.clear()
                analyzer = HealthRiskAnalyzer(
                    store,
                    self.settings.health_monitor_timezone,
                    self.settings.proactive_max_nonurgent_per_day,
                )
                result = await asyncio.to_thread(
                    analyzer.analyze,
                    self.scenarios.collect(dataset_id),
                    self.now_provider(),
                )
                self._scenario_store = store
            self._latest = result
        return {
            **self.datasets(),
            "analysis": result.to_dict(),
            "system_prompt": self.prompts.build(result),
        }

    async def refresh_daily_policy(self) -> dict[str, Any]:
        result = await asyncio.to_thread(self._analyze_sync)
        self._latest = result
        return self._ensure_daily_policy(result, force=True)

    def current_daily_policy(self) -> dict[str, Any] | None:
        if self._active_dataset != DEFAULT_DATASET_ID:
            return None
        local_day = self.now_provider().astimezone(
            self.analyzer.timezone
        ).date()
        return self.store.latest_daily_policy(local_day)

    def daily_system_prompt(self) -> str | None:
        policy = self.current_daily_policy()
        prompt = policy.get("system_prompt") if policy else None
        return str(prompt) if prompt else None

    async def _generate_opening(
        self,
        result: AnalysisResult,
    ) -> tuple[str, bool, bool]:
        fallback = result.decision.fallback_opening or FALLBACK_QUESTION
        was_sleeping = self.llm.runtime_status().get("state") == "sleeping"
        try:
            event_prompt = self.prompts.build(result)
            daily_prompt = self.daily_system_prompt()
            combined_prompt = "\n\n".join(
                item for item in (daily_prompt, event_prompt) if item
            )
            generated, _ = await self.llm.generate_proactive_opening(
                combined_prompt,
                self.prompts.user_instruction(result),
            )
        except RuntimeError:
            logger.exception("proactive_opening_generation_failed using_fallback")
            return fallback, False, was_sleeping
        validated = _validated_opening(generated)
        return validated or fallback, validated is not None, was_sleeping

    async def _deliver_result(
        self,
        result: AnalysisResult,
        target: str,
        *,
        source: str,
        record_outreach: bool,
    ) -> dict[str, Any]:
        opening, llm_used, was_sleeping = await self._generate_opening(result)
        event_prompt = self.prompts.build(result)
        self.conversation_store.set_session_context(
            target,
            event_prompt,
            ttl_minutes=180,
        )
        topic = (
            result.decision.category.value
            if result.decision.category
            else result.decision.trigger.value
            if result.decision.trigger
            else "GENERAL_CHECK_IN"
        )
        request = ProactiveMessageRequest(
            event_id=f"{source}-{uuid.uuid4().hex}",
            session_id=target,
            text=opening,
            source=source,
            topic=topic,
            priority=max(0, min(100, round(result.decision.priority))),
            expires_in_seconds=300,
            greeting_pose=True,
        )
        event, created = self.proactive_inbox.submit(request)
        if created and record_outreach:
            self.store.record_outreach(
                target,
                result.decision,
                opening,
                result.decision.calendar_event_id,
                result.generated_at,
            )
        delivery = {
            "created": created,
            "event_id": event["event_id"],
            "session_id": target,
            "text": opening,
            "source": source,
            "topic": topic,
            "llm_used": llm_used,
            "llm_was_sleeping": was_sleeping,
            "delivery_mode": "active_session_inbox",
        }
        self._last_delivery = delivery
        return delivery

    def _ensure_daily_policy(
        self,
        result: AnalysisResult,
        *,
        force: bool = False,
    ) -> dict[str, Any]:
        local = result.generated_at.astimezone(self.analyzer.timezone)
        today = local.date()
        asleep = bool(
            result.context.get("current_likely_sleep")
            and result.context.get("target_present")
            and result.context.get("current_posture") == "lying"
        )

        current = self.store.daily_policy(today)
        if force or current is None or (asleep and not current.get("generated_while_asleep")):
            reason = (
                "manual_refresh"
                if force
                else "sleep_cycle"
                if asleep
                else "missing_policy_fallback"
            )
            policy = self.daily_policies.build(
                result,
                today,
                generated_while_asleep=asleep,
                generation_reason=reason,
            )
            self.store.save_daily_policy(policy)
            current = policy.to_dict()

        if asleep and local.hour >= 18:
            next_day = today + timedelta(days=1)
            scheduled = self.store.daily_policy(next_day)
            if scheduled is None or not scheduled.get("generated_while_asleep"):
                policy = self.daily_policies.build(
                    result,
                    next_day,
                    generated_while_asleep=True,
                    generation_reason="sleep_cycle_next_day",
                )
                self.store.save_daily_policy(policy)
        return current

    def _analyze_sync(self) -> AnalysisResult:
        if self._active_dataset != DEFAULT_DATASET_ID and self._latest is not None:
            return self._latest
        return self._analyze_real_sync()

    def _analyze_real_sync(self) -> AnalysisResult:
        now = self.now_provider()
        history_days = {
            source: (
                2
                if self.store.source_backfilled(source)
                else getattr(self.settings, "health_analysis_window_days", 21)
            )
            for source in ("room", "home_assistant")
        }
        bundle = self.reader.collect(now, history_days=history_days)
        result = self.analyzer.analyze(bundle, now)
        room = bundle.source_status.get("room", {})
        if room.get("available") and int(room.get("rows") or 0) > 0:
            self.store.mark_source_backfilled("room", now)
        home = bundle.source_status.get("home_assistant", {})
        if home.get("available") and (
            int(home.get("light_events") or 0) > 0
            or int(home.get("access_events_after_debounce") or 0) > 0
        ):
            self.store.mark_source_backfilled("home_assistant", now)
        return result

    def status(self) -> dict[str, Any]:
        return {
            "enabled": bool(self.settings.health_monitor_enabled),
            "running": self._task is not None and not self._task.done(),
            "interval_seconds": self.settings.health_monitor_interval_seconds,
            "last_error": self._last_error,
            "last_delivery": self._last_delivery,
            "latest": self._latest.to_dict() if self._latest else None,
            **self.datasets(),
            "daily_policy": self.current_daily_policy(),
            "test_scenarios": self.test_scenarios(),
            "excluded_sources": [
                "voice_emotion",
                "rppg",
                "facial_expression",
                "gallery",
            ],
        }


def _test_decision(scenario: str) -> TopicDecision:
    if scenario == "morning_checkin":
        return TopicDecision(
            True, TriggerKind.MORNING_FIRST_SEEN, None, None, 70,
            "좋은 아침이에요. 물 한 잔을 드시고 천천히 몸을 깨워보실까요?",
            "대시보드 아침 안부 테스트", AlertLevel.NORMAL,
        )
    if scenario == "sleep_shortage":
        return TopicDecision(
            True, TriggerKind.WEEKLY_CHANGE, RiskCategory.SLEEP_CIRCADIAN,
            "sleep_hours", 82,
            "잠이 부족한 날에는 무리하지 않는 게 좋아요. 오늘은 하던 일을 나눠서 하고 중간중간 짧게 쉬어가실까요?",
            "대시보드 수면 부족 테스트", AlertLevel.CONCERN,
            ("sleep_hours",),
        )
    if scenario == "prolonged_sitting":
        return TopicDecision(
            True, TriggerKind.PROLONGED_POSTURE, RiskCategory.MUSCULOSKELETAL,
            "max_sitting_bout_minutes", 84,
            "한 자세가 오래 이어지면 몸이 뻣뻣해질 수 있어요. 저리거나 심하게 아픈 곳이 없다면 천천히 일어나 몸을 펴고 자세를 바꿔보실까요?",
            "대시보드 장시간 착석 테스트", AlertLevel.WATCH,
            ("max_sitting_bout_minutes",),
        )
    if scenario == "inactivity":
        return TopicDecision(
            True, TriggerKind.INTRADAY_ANOMALY, RiskCategory.APATHY_FATIGUE,
            "max_inactivity_bout_minutes", 84,
            "한동안 움직임이 적었어요. 몸이 괜찮다면 어깨를 펴고 실내를 1~2분 천천히 걸어보실까요?",
            "대시보드 무활동 테스트", AlertLevel.CONCERN,
            ("max_inactivity_bout_minutes",),
        )
    if scenario == "screen_overuse":
        return TopicDecision(
            True, TriggerKind.WEEKLY_CHANGE, RiskCategory.DIGITAL_WELLBEING,
            "phone_screen_daily_minutes", 78,
            "화면을 오래 보셨다면 눈과 목도 쉬어야 해요. 하던 일을 마무리하고 1~2분 화면에서 시선을 떼어보실까요?",
            "대시보드 화면 사용 테스트", AlertLevel.WATCH,
            ("phone_screen_daily_minutes",),
        )
    return TopicDecision(
        True, TriggerKind.BEDTIME_LIGHT, RiskCategory.SLEEP_CIRCADIAN,
        "light_on_sleep_minutes", 74,
        "쉴 시간이 가까워졌어요. 이동할 일이 끝났다면 조명을 낮추고 편하게 잠들 준비를 해보실까요?",
        "대시보드 취침 조명 테스트", AlertLevel.WATCH,
        ("light_on_sleep_minutes",),
    )


def _validated_opening(text: str) -> str | None:
    cleaned = " ".join(text.replace("**", "").split()).strip("'\" ")
    cleaned = re.sub(r"^[\-#*]+\s*", "", cleaned)
    if not 8 <= len(cleaned) <= 240:
        return None
    if cleaned.count("?") != 1 or not cleaned.endswith("?"):
        return None
    forbidden = (
        "우울증",
        "치매",
        "무기력증",
        "중독",
        "센서",
        "카메라로 보니",
        "감시",
    )
    if any(token in cleaned for token in forbidden):
        return None
    action_cues = (
        "해보",
        "쉬어",
        "바꿔",
        "걸어",
        "낮추",
        "내려놓",
        "드셔",
        "드시",
        "마셔",
        "깨워",
        "떼어",
        "바라보",
        "확인해",
        "준비해",
        "나눠",
    )
    if not any(token in cleaned for token in action_cues):
        return None
    return cleaned
