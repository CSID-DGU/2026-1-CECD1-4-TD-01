from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import date, datetime
from enum import Enum
from typing import Any


class AlertLevel(str, Enum):
    NORMAL = "normal"
    WATCH = "watch"
    CONCERN = "concern"
    MEDICAL = "medical"
    URGENT = "urgent"


class Direction(str, Enum):
    UP = "up"
    DOWN = "down"
    STABLE = "stable"


class RiskCategory(str, Enum):
    DEPRESSIVE_WELLBEING = "DEPRESSIVE_WELLBEING"
    APATHY_FATIGUE = "APATHY_FATIGUE"
    DEMENTIA_COGNITIVE = "DEMENTIA_COGNITIVE"
    SLEEP_CIRCADIAN = "SLEEP_CIRCADIAN"
    SOCIAL_CONNECTION = "SOCIAL_CONNECTION"
    MOBILITY_SAFETY = "MOBILITY_SAFETY"
    MUSCULOSKELETAL = "MUSCULOSKELETAL"
    ACTIVITY_EXERCISE = "ACTIVITY_EXERCISE"
    DIGITAL_WELLBEING = "DIGITAL_WELLBEING"
    CALENDAR_ROUTINE = "CALENDAR_ROUTINE"


class TriggerKind(str, Enum):
    MORNING_FIRST_SEEN = "morning_first_seen"
    PROLONGED_POSTURE = "prolonged_posture"
    INTRADAY_ANOMALY = "intraday_anomaly"
    WEEKLY_CHANGE = "weekly_change"
    MULTI_SIGNAL = "multi_signal"
    BEDTIME_LIGHT = "bedtime_light"
    CALENDAR_PREP = "calendar_prep"
    SAFETY = "safety"
    TEST = "dashboard_test"


@dataclass(frozen=True)
class DailyMetric:
    metric: str
    day: date
    value: float
    quality: float
    source: str
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ExternalComparison:
    metric: str
    current: float
    baseline: float
    quality: float
    source: str
    observed_at: datetime
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass
class DataBundle:
    daily_metrics: list[DailyMetric] = field(default_factory=list)
    comparisons: list[ExternalComparison] = field(default_factory=list)
    context: dict[str, Any] = field(default_factory=dict)
    source_status: dict[str, dict[str, Any]] = field(default_factory=dict)


@dataclass(frozen=True)
class MetricAssessment:
    metric: str
    current_value: float
    unit: str
    direction: Direction
    level: AlertLevel
    risk_categories: tuple[RiskCategory, ...]
    score: float
    confidence: float
    baseline_median: float | None
    modified_z: float | None
    relative_change: float | None
    baseline_points: int
    persistence_days: int
    observed_at: datetime
    source: str
    reasons: tuple[str, ...]
    gated_categories: tuple[RiskCategory, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["direction"] = self.direction.value
        result["level"] = self.level.value
        result["risk_categories"] = [item.value for item in self.risk_categories]
        result["gated_categories"] = [item.value for item in self.gated_categories]
        result["observed_at"] = self.observed_at.isoformat()
        return result


@dataclass(frozen=True)
class RiskCluster:
    category: RiskCategory
    level: AlertLevel
    score: float
    confidence: float
    metrics: tuple[str, ...]
    reasons: tuple[str, ...]
    persistence_days: int
    gated: bool = False

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["category"] = self.category.value
        result["level"] = self.level.value
        return result


@dataclass(frozen=True)
class TopicDecision:
    should_speak: bool
    trigger: TriggerKind | None
    category: RiskCategory | None
    metric: str | None
    priority: float
    fallback_opening: str | None
    reason: str
    level: AlertLevel = AlertLevel.NORMAL
    related_metrics: tuple[str, ...] = ()
    calendar_event_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["trigger"] = self.trigger.value if self.trigger else None
        result["category"] = self.category.value if self.category else None
        result["level"] = self.level.value
        return result


@dataclass(frozen=True)
class AnalysisResult:
    generated_at: datetime
    assessments: tuple[MetricAssessment, ...]
    clusters: tuple[RiskCluster, ...]
    decision: TopicDecision
    context: dict[str, Any]
    source_status: dict[str, dict[str, Any]]

    def to_dict(self) -> dict[str, Any]:
        return {
            "generated_at": self.generated_at.isoformat(),
            "assessments": [item.to_dict() for item in self.assessments],
            "clusters": [item.to_dict() for item in self.clusters],
            "decision": self.decision.to_dict(),
            "context": self.context,
            "source_status": self.source_status,
            "excluded_sources": [
                "voice_emotion",
                "rppg",
                "facial_expression",
                "gallery",
            ],
        }


@dataclass(frozen=True)
class DailyPolicy:
    policy_day: date
    generated_at: datetime
    window_start: date
    window_end: date
    generated_while_asleep: bool
    generation_reason: str
    risk_level: AlertLevel
    risk_score: float
    recommendations: tuple[str, ...]
    prohibitions: tuple[str, ...]
    system_prompt: str
    analysis: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return {
            "policy_day": self.policy_day.isoformat(),
            "generated_at": self.generated_at.isoformat(),
            "window_start": self.window_start.isoformat(),
            "window_end": self.window_end.isoformat(),
            "analysis_days": (self.window_end - self.window_start).days + 1,
            "generated_while_asleep": self.generated_while_asleep,
            "generation_reason": self.generation_reason,
            "risk_level": self.risk_level.value,
            "risk_score": self.risk_score,
            "recommendations": list(self.recommendations),
            "prohibitions": list(self.prohibitions),
            "system_prompt": self.system_prompt,
            "analysis": self.analysis,
        }
