from __future__ import annotations

from dataclasses import dataclass

from .models import Direction, RiskCategory


@dataclass(frozen=True)
class MetricPolicy:
    unit: str
    adverse_direction: Direction
    categories: tuple[RiskCategory, ...]
    min_change: float
    watch_relative: float = 0.25
    concern_relative: float = 0.50
    watch_absolute: float | None = None
    concern_absolute: float | None = None
    baseline_days: int = 21
    min_baseline_points: int = 4


POLICIES: dict[str, MetricPolicy] = {
    "sitting_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.MUSCULOSKELETAL, RiskCategory.ACTIVITY_EXERCISE),
        30.0,
    ),
    "standing_minutes": MetricPolicy(
        "min", Direction.DOWN,
        (RiskCategory.ACTIVITY_EXERCISE, RiskCategory.MOBILITY_SAFETY),
        20.0,
    ),
    "walking_minutes": MetricPolicy(
        "min", Direction.DOWN,
        (RiskCategory.ACTIVITY_EXERCISE, RiskCategory.APATHY_FATIGUE),
        10.0,
    ),
    "lying_awake_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.APATHY_FATIGUE, RiskCategory.MOBILITY_SAFETY),
        30.0,
    ),
    "max_sitting_bout_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.MUSCULOSKELETAL, RiskCategory.ACTIVITY_EXERCISE),
        10.0, watch_absolute=30.0, concern_absolute=60.0,
    ),
    "max_lying_awake_bout_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.APATHY_FATIGUE, RiskCategory.MOBILITY_SAFETY),
        30.0, watch_absolute=120.0, concern_absolute=180.0,
    ),
    "max_inactivity_bout_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.APATHY_FATIGUE, RiskCategory.ACTIVITY_EXERCISE),
        15.0, watch_absolute=30.0, concern_absolute=90.0,
    ),
    "room_brightness": MetricPolicy(
        "index", Direction.DOWN,
        (RiskCategory.SLEEP_CIRCADIAN,),
        20.0, watch_absolute=45.0, concern_absolute=25.0,
    ),
    "light_on_sleep_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.SLEEP_CIRCADIAN,),
        30.0, watch_absolute=60.0, concern_absolute=180.0,
    ),
    "outing_count": MetricPolicy(
        "count", Direction.DOWN,
        (RiskCategory.SOCIAL_CONNECTION, RiskCategory.MOBILITY_SAFETY),
        1.0,
    ),
    "outing_duration_minutes": MetricPolicy(
        "min", Direction.DOWN,
        (RiskCategory.SOCIAL_CONNECTION, RiskCategory.ACTIVITY_EXERCISE),
        30.0,
    ),
    "steps": MetricPolicy(
        "steps", Direction.DOWN,
        (RiskCategory.ACTIVITY_EXERCISE, RiskCategory.APATHY_FATIGUE),
        1000.0, watch_absolute=1500.0, concern_absolute=500.0,
    ),
    "slouch_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.MUSCULOSKELETAL, RiskCategory.MOBILITY_SAFETY),
        10.0, watch_absolute=45.0, concern_absolute=75.0,
    ),
    "entry_time_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.CALENDAR_ROUTINE,),
        60.0,
    ),
    "sleep_hours": MetricPolicy(
        "hours", Direction.DOWN,
        (RiskCategory.SLEEP_CIRCADIAN, RiskCategory.APATHY_FATIGUE),
        1.0, watch_absolute=5.5, concern_absolute=4.0,
    ),
    "sleep_bedtime_deviation_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.SLEEP_CIRCADIAN,),
        30.0, watch_absolute=90.0, concern_absolute=180.0,
    ),
    "call_count_week": MetricPolicy(
        "count", Direction.DOWN,
        (RiskCategory.SOCIAL_CONNECTION,),
        2.0,
    ),
    "phone_screen_daily_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.DIGITAL_WELLBEING, RiskCategory.SLEEP_CIRCADIAN),
        60.0, watch_absolute=360.0, concern_absolute=600.0,
    ),
    "phone_night_daily_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.SLEEP_CIRCADIAN, RiskCategory.DIGITAL_WELLBEING),
        20.0, watch_absolute=30.0, concern_absolute=90.0,
    ),
    "phone_longest_session_minutes": MetricPolicy(
        "min", Direction.UP,
        (RiskCategory.DIGITAL_WELLBEING, RiskCategory.MUSCULOSKELETAL),
        20.0, watch_absolute=45.0, concern_absolute=120.0,
    ),
}


SENSOR_ONLY_GATED = {
    RiskCategory.DEPRESSIVE_WELLBEING,
    RiskCategory.DEMENTIA_COGNITIVE,
}
