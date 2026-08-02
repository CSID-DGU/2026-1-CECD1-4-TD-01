from __future__ import annotations

import math
import statistics
from collections import defaultdict
from datetime import date, datetime, time, timedelta, timezone
from typing import Iterable

from .models import (
    AlertLevel,
    AnalysisResult,
    DataBundle,
    Direction,
    ExternalComparison,
    MetricAssessment,
    RiskCategory,
    RiskCluster,
    TopicDecision,
    TriggerKind,
)
from .policies import POLICIES, SENSOR_ONLY_GATED, MetricPolicy
from .storage import HealthMonitorStore
from .timezones import load_timezone


UTC = timezone.utc


LEVEL_RANK = {
    AlertLevel.NORMAL: 0,
    AlertLevel.WATCH: 1,
    AlertLevel.CONCERN: 2,
    AlertLevel.MEDICAL: 3,
    AlertLevel.URGENT: 4,
}


class HealthRiskAnalyzer:
    def __init__(
        self,
        store: HealthMonitorStore,
        timezone_name: str = "Asia/Seoul",
        max_nonurgent_per_day: int = 4,
    ):
        self.store = store
        self.timezone = load_timezone(timezone_name)
        self.max_nonurgent_per_day = max_nonurgent_per_day

    def analyze(
        self,
        bundle: DataBundle,
        now: datetime | None = None,
    ) -> AnalysisResult:
        current = _aware(now or datetime.now(UTC))
        self.store.upsert_daily(bundle.daily_metrics)
        assessments = self._daily_assessments(current)
        assessments.extend(
            self._comparison_assessment(item) for item in bundle.comparisons
        )
        clusters = self._clusters(assessments)
        decision = self._select_topic(current, bundle, assessments, clusters)
        result = AnalysisResult(
            generated_at=current,
            assessments=tuple(
                sorted(
                    assessments,
                    key=lambda item: (LEVEL_RANK[item.level], item.score),
                    reverse=True,
                )
            ),
            clusters=tuple(clusters),
            decision=decision,
            context=bundle.context,
            source_status=bundle.source_status,
        )
        self.store.save_analysis(result)
        self.store.purge(current)
        return result

    def _daily_assessments(self, now: datetime) -> list[MetricAssessment]:
        today = now.astimezone(self.timezone).date()
        assessments: list[MetricAssessment] = []
        for metric, policy in POLICIES.items():
            history = self.store.history(
                metric,
                today - timedelta(days=policy.baseline_days + 2),
                today,
            )
            if not history:
                continue
            latest_index = len(history) - 1
            if (
                history[latest_index][0] == today
                and not metric.startswith("max_")
                and metric not in {"room_brightness", "light_on_sleep_minutes", "sleep_bedtime_deviation_minutes"}
                and len(history) > 1
            ):
                latest_index -= 1
            latest = history[latest_index]
            previous = history[:latest_index]
            same_weekday = [row for row in previous if row[0].weekday() == latest[0].weekday()]
            baseline_rows = same_weekday if len(same_weekday) >= 4 else previous
            assessments.append(
                self._assess_values(
                    metric=metric,
                    current=latest[1],
                    baseline=[row[1] for row in baseline_rows],
                    quality=latest[2],
                    source=latest[3],
                    observed_at=datetime.combine(
                        latest[0], time(12), self.timezone
                    ).astimezone(UTC),
                    policy=policy,
                )
            )
        return assessments

    def _comparison_assessment(
        self,
        item: ExternalComparison,
    ) -> MetricAssessment:
        policy = POLICIES[item.metric]
        has_baseline = item.metadata.get("baseline_available", True) is not False
        return self._assess_values(
            metric=item.metric,
            current=item.current,
            baseline=[item.baseline] if has_baseline else [],
            quality=item.quality,
            source=item.source,
            observed_at=item.observed_at,
            policy=policy,
            external=True,
        )

    def _assess_values(
        self,
        *,
        metric: str,
        current: float,
        baseline: list[float],
        quality: float,
        source: str,
        observed_at: datetime,
        policy: MetricPolicy,
        external: bool = False,
    ) -> MetricAssessment:
        center = statistics.median(baseline) if baseline else None
        direction = Direction.STABLE
        relative_change = None
        modified_z = None
        adverse_change = 0.0
        if center is not None:
            delta = current - center
            direction = Direction.UP if delta > 1e-9 else Direction.DOWN if delta < -1e-9 else Direction.STABLE
            relative_change = delta / max(abs(center), 1.0)
            modified_z = robust_modified_z(current, baseline)
            adverse_change = delta if policy.adverse_direction == Direction.UP else -delta

        absolute_watch = _absolute_trigger(current, policy, concern=False)
        absolute_concern = _absolute_trigger(current, policy, concern=True)
        minimum_met = adverse_change >= policy.min_change or absolute_watch
        adverse_relative = (
            (relative_change or 0.0)
            * (1 if policy.adverse_direction == Direction.UP else -1)
        )
        adverse_z = (
            (modified_z or 0.0)
            * (1 if policy.adverse_direction == Direction.UP else -1)
        )
        concern = minimum_met and (
            absolute_concern
            or adverse_relative >= policy.concern_relative
            or adverse_z >= 3.5
        )
        watch = minimum_met and (
            absolute_watch
            or adverse_relative >= policy.watch_relative
            or adverse_z >= 2.0
        )
        level = AlertLevel.CONCERN if concern else AlertLevel.WATCH if watch else AlertLevel.NORMAL
        points = len(baseline)
        baseline_factor = 1.0 if external else min(1.0, points / max(1, policy.min_baseline_points))
        confidence = max(0.0, min(1.0, quality * (0.45 + 0.55 * baseline_factor)))
        anomaly_strength = max(
            0.0,
            min(1.0, adverse_relative / max(policy.concern_relative, 0.01)),
            min(1.0, adverse_z / 3.5),
            1.0 if absolute_concern else 0.6 if absolute_watch else 0.0,
        )
        score = (
            round(100 * anomaly_strength * confidence, 1)
            if level != AlertLevel.NORMAL
            else 0.0
        )
        reasons: list[str] = []
        if center is None:
            reasons.append("개인 기준선 학습 중")
        elif adverse_change >= policy.min_change and level != AlertLevel.NORMAL:
            reasons.append("개인 기준선 대비 불리한 방향의 변화")
        if absolute_watch:
            reasons.append("지표별 즉시 확인 기준 도달")
        if external and center is not None:
            reasons.append("주간 요약의 현재 기간과 이전 기간 비교")
        persistence = _persistence_days(
            [row for row in baseline] + [current],
            center,
            policy,
        )
        gated = tuple(
            category for category in policy.categories if category in SENSOR_ONLY_GATED
        )
        return MetricAssessment(
            metric=metric,
            current_value=round(current, 3),
            unit=policy.unit,
            direction=direction,
            level=level,
            risk_categories=policy.categories,
            score=score,
            confidence=round(confidence, 3),
            baseline_median=round(center, 3) if center is not None else None,
            modified_z=round(modified_z, 3) if modified_z is not None else None,
            relative_change=round(relative_change, 3) if relative_change is not None else None,
            baseline_points=points,
            persistence_days=persistence,
            observed_at=observed_at,
            source=source,
            reasons=tuple(reasons),
            gated_categories=gated,
        )

    def _clusters(
        self,
        assessments: list[MetricAssessment],
    ) -> list[RiskCluster]:
        grouped: dict[RiskCategory, list[MetricAssessment]] = defaultdict(list)
        for item in assessments:
            if item.level == AlertLevel.NORMAL:
                continue
            for category in item.risk_categories:
                grouped[category].append(item)

        clusters: list[RiskCluster] = []
        for category, items in grouped.items():
            raw = sum((item.score / 100) * item.confidence for item in items)
            if len({item.metric for item in items}) >= 2:
                raw *= 1.15
            score = round(100 * (1 - math.exp(-raw)), 1)
            level = max(items, key=lambda item: LEVEL_RANK[item.level]).level
            gated = category in SENSOR_ONLY_GATED
            if gated:
                score = min(score, 29.0)
            clusters.append(
                RiskCluster(
                    category=category,
                    level=level,
                    score=score,
                    confidence=round(statistics.fmean(item.confidence for item in items), 3),
                    metrics=tuple(dict.fromkeys(item.metric for item in items)),
                    reasons=tuple(dict.fromkeys(reason for item in items for reason in item.reasons)),
                    persistence_days=max(item.persistence_days for item in items),
                    gated=gated,
                )
            )
        return sorted(
            clusters,
            key=lambda item: (LEVEL_RANK[item.level], item.score),
            reverse=True,
        )

    def _select_topic(
        self,
        now: datetime,
        bundle: DataBundle,
        assessments: list[MetricAssessment],
        clusters: list[RiskCluster],
    ) -> TopicDecision:
        context = bundle.context
        candidates: list[TopicDecision] = []
        posture = context.get("current_posture")
        bout = float(context.get("current_posture_bout_minutes") or 0)
        inactivity = float(context.get("current_inactivity_bout_minutes") or 0)
        if posture == "sitting" and bout >= 30:
            candidates.append(
                TopicDecision(
                    True,
                    TriggerKind.PROLONGED_POSTURE,
                    RiskCategory.MUSCULOSKELETAL,
                    "max_sitting_bout_minutes",
                    min(95.0, 68 + bout / 4),
                    "한 자세로 꽤 오래 앉아 계셨는데, 허리나 무릎이 불편하지는 않으세요?",
                    "현재 착석 지속시간 30분 이상",
                    AlertLevel.CONCERN if bout >= 60 else AlertLevel.WATCH,
                    ("max_sitting_bout_minutes",),
                )
            )
        if (
            posture == "lying"
            and not context.get("current_likely_sleep")
            and bout >= 120
        ):
            candidates.append(
                TopicDecision(
                    True,
                    TriggerKind.PROLONGED_POSTURE,
                    RiskCategory.APATHY_FATIGUE,
                    "max_lying_awake_bout_minutes",
                    min(96.0, 72 + bout / 12),
                    "깨어 계신 동안 오래 누워 계셨는데, 어디가 아프거나 기운이 많이 없으신가요?",
                    "수면 가능성을 제외한 누운 자세 120분 이상",
                    AlertLevel.CONCERN if bout >= 180 else AlertLevel.WATCH,
                    ("max_lying_awake_bout_minutes",),
                )
            )
        if inactivity >= 90 and posture not in {"lying", None}:
            candidates.append(
                TopicDecision(
                    True,
                    TriggerKind.INTRADAY_ANOMALY,
                    RiskCategory.APATHY_FATIGUE,
                    "max_inactivity_bout_minutes",
                    72.0,
                    "한동안 움직임이 거의 없었는데, 몸이 불편하거나 쉬고 계셨던 건가요?",
                    "현재 무활동 지속시간 90분 이상",
                    AlertLevel.CONCERN,
                    ("max_inactivity_bout_minutes",),
                )
            )

        for cluster in clusters:
            if cluster.gated or cluster.score < 28:
                continue
            metric = cluster.metrics[0] if cluster.metrics else None
            trigger = (
                TriggerKind.MULTI_SIGNAL
                if len(cluster.metrics) >= 2
                else TriggerKind.WEEKLY_CHANGE
            )
            priority = 48 + cluster.score * 0.42 + 6 * (len(cluster.metrics) >= 2)
            candidates.append(
                TopicDecision(
                    True,
                    trigger,
                    cluster.category,
                    metric,
                    min(94.0, priority),
                    _opening_for(cluster.category, metric),
                    f"{cluster.category.value} 관련 {len(cluster.metrics)}개 변화 신호",
                    cluster.level,
                    cluster.metrics,
                )
            )

        upcoming = context.get("calendar_upcoming") or []
        if upcoming:
            event = upcoming[0]
            candidates.append(
                TopicDecision(
                    True,
                    TriggerKind.CALENDAR_PREP,
                    RiskCategory.CALENDAR_ROUTINE,
                    None,
                    67.0 if event["hours_until"] <= 2 else 56.0,
                    f"{event['category']} 일정이 다가오는데, 미리 챙겨 드릴 것이 있을까요?",
                    "24시간 이내 사용자가 허용한 캘린더 일정",
                    AlertLevel.NORMAL,
                    (),
                    str(event["event_id"]),
                )
            )
        local = now.astimezone(self.timezone)
        if (
            context.get("bedroom_light_on")
            and _near_expected_bedtime(local, context.get("expected_sleep_time"))
            and context.get("target_present")
        ):
            candidates.append(
                TopicDecision(
                    True,
                    TriggerKind.BEDTIME_LIGHT,
                    RiskCategory.SLEEP_CIRCADIAN,
                    "light_on_sleep_minutes",
                    61.0,
                    "\uc7a0\ub4e4 \uc900\ube44\ub97c \ud558\uc2dc\ub294 \uc2dc\uac04 \uac19\uc740\ub370, \uc9c0\uae08 \ubb34\uc5c7\uc774 \uac00\uc7a5 \ubd88\ud3b8\ud558\uc2e0\uac00\uc694?",
                    "\ud3c9\uade0 \ucde8\uce68 \uc2dc\uac01 \uc8fc\ubcc0, \uce68\uc2e4 \uc870\uba85 \ucf1c\uc9d0",
                    AlertLevel.WATCH,
                    ("light_on_sleep_minutes",),
                )
            )
        if (
            context.get("morning_first_seen")
            and context.get("target_present")
            and not context.get("multiple_people_present")
        ):
            candidates.append(
                TopicDecision(
                    True,
                    TriggerKind.MORNING_FIRST_SEEN,
                    None,
                    None,
                    55.0,
                    "좋은 아침이에요. 오늘 몸 상태는 어떠세요?",
                    "오전 카메라 첫 재실 감지",
                    AlertLevel.NORMAL,
                )
            )

        allowed = [item for item in candidates if self._eligible(item, now)]
        if not allowed:
            return TopicDecision(False, None, None, None, 0.0, None, "발화 조건 또는 쿨다운 미충족")
        selected = max(allowed, key=lambda item: item.priority)
        if selected.priority < 50:
            return TopicDecision(False, None, None, None, selected.priority, None, "우선순위 기준 미달")
        return selected

    def _eligible(self, decision: TopicDecision, now: datetime) -> bool:
        if decision.level in {AlertLevel.URGENT, AlertLevel.MEDICAL}:
            return True
        local_hour = now.astimezone(self.timezone).hour
        if not 7 <= local_hour <= 21 and decision.trigger != TriggerKind.BEDTIME_LIGHT:
            return False
        recent = self.store.recent_outreach(now - timedelta(days=14))
        today = [item for item in recent if item["created_at"].astimezone(self.timezone).date() == now.astimezone(self.timezone).date()]
        if len(today) >= self.max_nonurgent_per_day:
            return False
        if recent and now - recent[-1]["created_at"] < timedelta(hours=2):
            return False
        for item in reversed(recent):
            age = now - item["created_at"]
            if item["trigger"] == (decision.trigger.value if decision.trigger else None) and age < timedelta(days=1):
                return False
            if decision.category and item["category"] == decision.category.value and age < timedelta(hours=8):
                return False
            if decision.metric and item["metric"] == decision.metric and age < timedelta(hours=24):
                return False
            if decision.calendar_event_id and item["event_key"] == decision.calendar_event_id:
                return False
        return True


def robust_modified_z(value: float, baseline: Iterable[float]) -> float | None:
    values = [float(item) for item in baseline if math.isfinite(item)]
    if not values:
        return None
    center = statistics.median(values)
    mad = statistics.median(abs(item - center) for item in values)
    if mad <= 1e-9:
        return 0.0 if abs(value - center) <= 1e-9 else math.copysign(9.0, value - center)
    return 0.6745 * (value - center) / mad


def _absolute_trigger(
    current: float,
    policy: MetricPolicy,
    *,
    concern: bool,
) -> bool:
    threshold = policy.concern_absolute if concern else policy.watch_absolute
    if threshold is None:
        return False
    if policy.adverse_direction == Direction.UP:
        return current >= threshold
    return current <= threshold


def _persistence_days(
    values: list[float],
    center: float | None,
    policy: MetricPolicy,
) -> int:
    if center is None:
        return 0
    count = 0
    for value in reversed(values):
        adverse = (
            value - center
            if policy.adverse_direction == Direction.UP
            else center - value
        )
        if adverse < policy.min_change:
            break
        count += 1
    return count


def _opening_for(category: RiskCategory, metric: str | None) -> str:
    if metric == "phone_screen_daily_minutes":
        return "화면을 오래 보셨다면 눈과 목도 쉬어야 해요. 하던 일을 마무리하고 1~2분 화면에서 시선을 떼어보실까요?"
    if metric == "phone_night_daily_minutes":
        return "밤에는 눈과 몸이 쉴 준비가 필요해요. 하던 일이 끝났다면 화면 밝기를 낮추고 휴대폰을 잠시 내려놓아보실까요?"
    if metric == "steps":
        return "몸이 괜찮다면 무리하지 않는 선에서 움직여보는 게 좋아요. 실내를 1~2분 천천히 걸어보실까요?"
    if metric in {"outing_count", "outing_duration_minutes"}:
        return "몸 상태와 날씨가 괜찮다면 잠깐 바깥 공기를 쐬는 것도 좋아요. 짧은 외출을 준비해보실까요?"
    if metric == "call_count_week":
        return "생각나는 분이 있다면 짧게 안부를 나누는 것도 좋아요. 편한 분께 연락할 준비를 해볼까요?"
    templates = {
        RiskCategory.APATHY_FATIGUE: "몸이 괜찮다면 오래 같은 자세로 있지 않는 게 좋아요. 어깨를 펴고 실내를 1~2분 천천히 걸어보실까요?",
        RiskCategory.SLEEP_CIRCADIAN: "잠이 부족한 날에는 무리하지 않는 게 좋아요. 오늘은 하던 일을 나눠서 하고 중간중간 짧게 쉬어가실까요?",
        RiskCategory.SOCIAL_CONNECTION: "생각나는 분이 있다면 짧게 안부를 나누는 것도 좋아요. 편한 방식으로 연락을 준비해볼까요?",
        RiskCategory.MOBILITY_SAFETY: "어지럽거나 심하게 아픈 곳이 없다면 안전하게 몸을 풀어보는 게 좋아요. 의자나 벽을 짚고 천천히 자세를 바꿔보실까요?",
        RiskCategory.MUSCULOSKELETAL: "한 자세가 오래 이어지면 몸이 뻣뻣해질 수 있어요. 저리거나 심하게 아픈 곳이 없다면 천천히 일어나 몸을 펴고 자세를 바꿔보실까요?",
        RiskCategory.ACTIVITY_EXERCISE: "몸이 괜찮다면 짧게라도 움직여보는 게 좋아요. 실내를 1~2분 천천히 걸어보실까요?",
        RiskCategory.DIGITAL_WELLBEING: "화면을 오래 보셨다면 눈과 목도 쉬어야 해요. 1~2분 화면에서 시선을 떼고 먼 곳을 바라보실까요?",
        RiskCategory.CALENDAR_ROUTINE: "다가오는 일정은 한 가지씩 준비하면 편해요. 지금 필요한 준비물을 같이 확인해볼까요?",
        RiskCategory.DEPRESSIVE_WELLBEING: "오늘은 부담 없는 일 하나만 해도 충분해요. 물 한 잔을 드시고 잠깐 이야기를 나눠볼까요?",
        RiskCategory.DEMENTIA_COGNITIVE: "챙길 일이 많을 때는 한 가지씩 확인하면 편해요. 지금 필요한 일 하나를 같이 확인해볼까요?",
    }
    return templates.get(category, "몸이 괜찮다면 물 한 잔을 드시고 가볍게 자세를 바꿔보실까요?")


def _aware(value: datetime) -> datetime:
    aware = value if value.tzinfo else value.replace(tzinfo=UTC)
    return aware.astimezone(UTC)


def _near_expected_bedtime(local: datetime, expected: object) -> bool:
    if not isinstance(expected, str):
        return False
    try:
        bedtime = time.fromisoformat(expected)
    except ValueError:
        return False
    now_minutes = local.hour * 60 + local.minute
    bedtime_minutes = bedtime.hour * 60 + bedtime.minute
    difference = (now_minutes - bedtime_minutes + 720) % 1440 - 720
    return -30 <= difference <= 120
