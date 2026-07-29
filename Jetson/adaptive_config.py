#!/usr/bin/env python3
"""Load and validate the human-editable adaptive counseling policy.

The operational values live in ``adaptive_policy.ini``.  This module keeps
parsing and safety validation separate from the context engine so an invalid
edit fails loudly instead of silently changing counseling behavior.
"""

from __future__ import annotations

import configparser
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping


DEFAULT_ADAPTIVE_CONFIG_PATH = Path(__file__).with_name("adaptive_policy.ini")
SUPPORTED_CONFIG_VERSION = 2

STRATEGY_NAMES = (
    "EMPATHIC_REFLECTION",
    "OPEN_QUESTION",
    "GROUNDING",
    "POSITIVE_REINFORCEMENT",
    "MICRO_ACTION",
    "QUIET_PRESENCE",
)

EMOTION_BUCKET_NAMES = (
    "NEGATIVE_HIGH_AROUSAL",
    "NEGATIVE_LOW_AROUSAL",
    "NEUTRAL",
    "POSITIVE",
)

STYLE_DIMENSION_NAMES = (
    "RESPONSE_LENGTH",
    "EMPATHY_RATIO",
    "QUESTION_FREQUENCY",
    "ADVICE_DIRECTNESS",
    "GROUNDING_INTENSITY",
    "EXPLANATION_DETAIL",
    "PROACTIVITY",
    "WARMTH",
    "ACTION_SIZE",
    "HEALTH_MENTION",
)


class AdaptiveConfigError(ValueError):
    """Raised when adaptive_policy.ini is missing or contains unsafe values."""


@dataclass(frozen=True)
class QualityThresholds:
    steps_missing_at_or_below: float
    exercise_minutes_missing_at_or_below: float
    distance_km_missing_at_or_below: float
    meters_per_step_min: float
    meters_per_step_max: float
    total_calories_kcal_min: float
    active_calories_kcal_min: float
    heart_rate_bpm_min: float
    heart_rate_bpm_max: float
    rppg_signal_quality_min: float
    rppg_measurement_seconds_min: float
    posture_observation_seconds_min: float


@dataclass(frozen=True)
class TrendThresholds:
    recent_window_hours: float
    baseline_window_days: float
    min_baseline_samples: int
    robust_z_candidate_threshold: float
    relative_change_candidate_threshold: float
    zero_epsilon: float
    recent_support_target_samples: int
    min_card_confidence: float
    card_ttl_hours: float


@dataclass(frozen=True)
class DerivedSummaryThresholds:
    voice_emotion_ttl_minutes: float
    other_summary_ttl_days: float
    initial_confidence: float
    min_confidence: float


@dataclass(frozen=True)
class RfidThresholds:
    recent_window_days: float
    baseline_window_days: float
    min_recent_samples: int
    min_baseline_samples: int
    entry_shift_minutes: float
    min_card_confidence: float
    recent_support_target_samples: int
    dedupe_seconds: float
    card_ttl_days: float


@dataclass(frozen=True)
class SessionCardThresholds:
    lookback_days: float
    max_sessions: int
    min_sessions: int
    min_confidence: float
    support_target_sessions: int
    max_confidence: float
    card_ttl_days: float


@dataclass(frozen=True)
class EmotionThresholds:
    negative_threshold: float
    high_arousal_threshold: float
    positive_threshold: float
    current_emotion_window_minutes: float
    current_emotion_min_confidence: float


@dataclass(frozen=True)
class RewardThresholds:
    negative_valence_weight: float
    negative_arousal_relief_weight: float
    neutral_valence_weight: float
    neutral_arousal_relief_weight: float
    positive_retention_weight: float
    positive_valence_delta_weight: float
    positive_arousal_increase_penalty: float
    positive_retention_floor: float
    positive_retention_scale: float
    observed_reward_weight: float
    explicit_feedback_weight: float
    implicit_like_threshold: float
    implicit_dislike_threshold: float
    reward_min: float
    reward_max: float


@dataclass(frozen=True)
class LearningThresholds:
    learning_rate: float
    max_delta_per_run: float
    min_weight: float
    max_weight: float
    initial_weight: float
    min_training_interval_hours: float
    sleep_evidence_max_age_minutes: float
    sleep_min_confidence: float
    max_outcomes_per_run: int
    status_history_sessions: int
    reward_trend_window_sessions: int
    policy_history_versions: int
    early_signal_min_outcomes: int
    trend_available_min_outcomes: int
    max_session_outcome_interval_hours: float



@dataclass(frozen=True)
class StyleLearningThresholds:
    learning_rate: float
    max_delta_per_run: float
    min_value: float
    max_value: float
    exploration_initial: float
    exploration_min: float
    exploration_decay_samples: int
    explored_dimensions_per_response: int

@dataclass(frozen=True)
class ContextThresholds:
    default_cards: int
    max_cards: int
    recommended_strategies: int
    topic_relevance_multiplier: float
    voice_emotion_relevance_bonus: float


@dataclass(frozen=True)
class RetentionThresholds:
    analysis_events_days: float
    context_cards_days: float
    audit_log_days: float
    expired_event_grace_days: float


@dataclass(frozen=True)
class PromptPolicy:
    common_rules: str
    bucket_instructions: Mapping[str, str]
    strategy_instructions: Mapping[str, str]
    style_templates: Mapping[str, str]


@dataclass(frozen=True)
class AdaptivePolicyConfig:
    source_path: Path
    config_version: int
    quality: QualityThresholds
    trend: TrendThresholds
    derived_summary: DerivedSummaryThresholds
    rfid: RfidThresholds
    session_card: SessionCardThresholds
    emotion: EmotionThresholds
    reward: RewardThresholds
    learning: LearningThresholds
    style_learning: StyleLearningThresholds
    style_defaults: Mapping[str, Mapping[str, float]]
    context: ContextThresholds
    retention: RetentionThresholds
    prompts: PromptPolicy

    def health_summary(self) -> dict[str, object]:
        """Return non-sensitive metadata suitable for the /health response."""

        return {
            "config_version": self.config_version,
            "file": self.source_path.name,
            "learning_rate": self.learning.learning_rate,
            "max_policy_delta_per_run": self.learning.max_delta_per_run,
            "weight_range": [
                self.learning.min_weight,
                self.learning.max_weight,
            ],
            "style_personalization": {
                "dimensions": len(STYLE_DIMENSION_NAMES),
                "learning_rate": self.style_learning.learning_rate,
                "max_delta_per_run": self.style_learning.max_delta_per_run,
                "explored_per_response": (
                    self.style_learning.explored_dimensions_per_response
                ),
            },
            "implicit_feedback_thresholds": {
                "like_at_or_above": self.reward.implicit_like_threshold,
                "dislike_at_or_below": self.reward.implicit_dislike_threshold,
            },
        }


def _parser_for(path: Path) -> configparser.ConfigParser:
    parser = configparser.ConfigParser(
        interpolation=None,
        strict=True,
        empty_lines_in_values=True,
    )
    # Prompt and strategy identifiers are intentionally uppercase.
    parser.optionxform = str
    if not path.is_file():
        raise AdaptiveConfigError(f"adaptive config file not found: {path}")
    try:
        with path.open("r", encoding="utf-8") as stream:
            parser.read_file(stream)
    except (OSError, configparser.Error) as exc:
        raise AdaptiveConfigError(f"could not read adaptive config {path}: {exc}") from exc
    return parser


def _require_section(
    parser: configparser.ConfigParser,
    section: str,
) -> configparser.SectionProxy:
    if not parser.has_section(section):
        raise AdaptiveConfigError(f"missing [{section}] section")
    return parser[section]


def _float(
    parser: configparser.ConfigParser,
    section: str,
    key: str,
    *,
    minimum: float | None = None,
    maximum: float | None = None,
) -> float:
    values = _require_section(parser, section)
    if key not in values:
        raise AdaptiveConfigError(f"missing [{section}] {key}")
    try:
        value = float(values[key])
    except ValueError as exc:
        raise AdaptiveConfigError(f"[{section}] {key} must be a number") from exc
    if not math.isfinite(value):
        raise AdaptiveConfigError(f"[{section}] {key} must be finite")
    if minimum is not None and value < minimum:
        raise AdaptiveConfigError(f"[{section}] {key} must be at least {minimum}")
    if maximum is not None and value > maximum:
        raise AdaptiveConfigError(f"[{section}] {key} must be at most {maximum}")
    return value


def _int(
    parser: configparser.ConfigParser,
    section: str,
    key: str,
    *,
    minimum: int | None = None,
    maximum: int | None = None,
) -> int:
    values = _require_section(parser, section)
    if key not in values:
        raise AdaptiveConfigError(f"missing [{section}] {key}")
    try:
        value = int(values[key])
    except ValueError as exc:
        raise AdaptiveConfigError(f"[{section}] {key} must be an integer") from exc
    if minimum is not None and value < minimum:
        raise AdaptiveConfigError(f"[{section}] {key} must be at least {minimum}")
    if maximum is not None and value > maximum:
        raise AdaptiveConfigError(f"[{section}] {key} must be at most {maximum}")
    return value


def _text(
    parser: configparser.ConfigParser,
    section: str,
    key: str,
) -> str:
    values = _require_section(parser, section)
    if key not in values:
        raise AdaptiveConfigError(f"missing [{section}] {key}")
    value = values[key].strip()
    if not value:
        raise AdaptiveConfigError(f"[{section}] {key} must not be blank")
    return value


def _require_approximately_one(name: str, *weights: float) -> None:
    if not math.isclose(sum(weights), 1.0, rel_tol=0.0, abs_tol=0.000001):
        raise AdaptiveConfigError(f"{name} weights must add up to 1.0")


def load_adaptive_policy_config(
    path: Path | str = DEFAULT_ADAPTIVE_CONFIG_PATH,
) -> AdaptivePolicyConfig:
    """Load a complete, validated adaptive configuration from an INI file."""

    source_path = Path(path).expanduser().resolve()
    parser = _parser_for(source_path)
    config_version = _int(parser, "meta", "config_version", minimum=1)
    if config_version != SUPPORTED_CONFIG_VERSION:
        raise AdaptiveConfigError(
            f"unsupported adaptive config_version {config_version}; "
            f"expected {SUPPORTED_CONFIG_VERSION}"
        )

    quality = QualityThresholds(
        steps_missing_at_or_below=_float(parser, "quality", "steps_missing_at_or_below"),
        exercise_minutes_missing_at_or_below=_float(
            parser,
            "quality",
            "exercise_minutes_missing_at_or_below",
        ),
        distance_km_missing_at_or_below=_float(
            parser,
            "quality",
            "distance_km_missing_at_or_below",
            minimum=0.0,
        ),
        meters_per_step_min=_float(
            parser,
            "quality",
            "meters_per_step_min",
            minimum=0.01,
        ),
        meters_per_step_max=_float(
            parser,
            "quality",
            "meters_per_step_max",
            minimum=0.01,
        ),
        total_calories_kcal_min=_float(
            parser,
            "quality",
            "total_calories_kcal_min",
            minimum=0.0,
        ),
        active_calories_kcal_min=_float(
            parser,
            "quality",
            "active_calories_kcal_min",
            minimum=0.0,
        ),
        heart_rate_bpm_min=_float(
            parser,
            "quality",
            "heart_rate_bpm_min",
            minimum=1.0,
        ),
        heart_rate_bpm_max=_float(
            parser,
            "quality",
            "heart_rate_bpm_max",
            minimum=1.0,
        ),
        rppg_signal_quality_min=_float(
            parser,
            "quality",
            "rppg_signal_quality_min",
            minimum=0.0,
            maximum=1.0,
        ),
        rppg_measurement_seconds_min=_float(
            parser,
            "quality",
            "rppg_measurement_seconds_min",
            minimum=0.0,
        ),
        posture_observation_seconds_min=_float(
            parser,
            "quality",
            "posture_observation_seconds_min",
            minimum=0.0,
        ),
    )
    if quality.meters_per_step_min >= quality.meters_per_step_max:
        raise AdaptiveConfigError("meters_per_step_min must be below meters_per_step_max")
    if quality.heart_rate_bpm_min >= quality.heart_rate_bpm_max:
        raise AdaptiveConfigError("heart_rate_bpm_min must be below heart_rate_bpm_max")

    trend = TrendThresholds(
        recent_window_hours=_float(
            parser,
            "trend",
            "recent_window_hours",
            minimum=0.01,
        ),
        baseline_window_days=_float(
            parser,
            "trend",
            "baseline_window_days",
            minimum=0.01,
        ),
        min_baseline_samples=_int(
            parser,
            "trend",
            "min_baseline_samples",
            minimum=2,
        ),
        robust_z_candidate_threshold=_float(
            parser,
            "trend",
            "robust_z_candidate_threshold",
            minimum=0.0,
        ),
        relative_change_candidate_threshold=_float(
            parser,
            "trend",
            "relative_change_candidate_threshold",
            minimum=0.0,
        ),
        zero_epsilon=_float(
            parser,
            "trend",
            "zero_epsilon",
            minimum=0.0,
        ),
        recent_support_target_samples=_int(
            parser,
            "trend",
            "recent_support_target_samples",
            minimum=1,
        ),
        min_card_confidence=_float(
            parser,
            "trend",
            "min_card_confidence",
            minimum=0.0,
            maximum=1.0,
        ),
        card_ttl_hours=_float(
            parser,
            "trend",
            "card_ttl_hours",
            minimum=0.01,
        ),
    )

    derived_summary = DerivedSummaryThresholds(
        voice_emotion_ttl_minutes=_float(
            parser,
            "derived_summary",
            "voice_emotion_ttl_minutes",
            minimum=0.01,
        ),
        other_summary_ttl_days=_float(
            parser,
            "derived_summary",
            "other_summary_ttl_days",
            minimum=0.01,
        ),
        initial_confidence=_float(
            parser,
            "derived_summary",
            "initial_confidence",
            minimum=0.0,
            maximum=1.0,
        ),
        min_confidence=_float(
            parser,
            "derived_summary",
            "min_confidence",
            minimum=0.0,
            maximum=1.0,
        ),
    )
    if derived_summary.min_confidence > derived_summary.initial_confidence:
        raise AdaptiveConfigError(
            "derived_summary min_confidence must not exceed initial_confidence"
        )

    rfid = RfidThresholds(
        recent_window_days=_float(
            parser,
            "rfid",
            "recent_window_days",
            minimum=0.01,
        ),
        baseline_window_days=_float(
            parser,
            "rfid",
            "baseline_window_days",
            minimum=0.01,
        ),
        min_recent_samples=_int(parser, "rfid", "min_recent_samples", minimum=1),
        min_baseline_samples=_int(parser, "rfid", "min_baseline_samples", minimum=2),
        entry_shift_minutes=_float(
            parser,
            "rfid",
            "entry_shift_minutes",
            minimum=0.0,
        ),
        min_card_confidence=_float(
            parser,
            "rfid",
            "min_card_confidence",
            minimum=0.0,
            maximum=1.0,
        ),
        recent_support_target_samples=_int(
            parser,
            "rfid",
            "recent_support_target_samples",
            minimum=1,
        ),
        dedupe_seconds=_float(parser, "rfid", "dedupe_seconds", minimum=0.0),
        card_ttl_days=_float(parser, "rfid", "card_ttl_days", minimum=0.01),
    )

    session_card = SessionCardThresholds(
        lookback_days=_float(
            parser,
            "session_card",
            "lookback_days",
            minimum=0.01,
        ),
        max_sessions=_int(parser, "session_card", "max_sessions", minimum=1),
        min_sessions=_int(parser, "session_card", "min_sessions", minimum=1),
        min_confidence=_float(
            parser,
            "session_card",
            "min_confidence",
            minimum=0.0,
            maximum=1.0,
        ),
        support_target_sessions=_int(
            parser,
            "session_card",
            "support_target_sessions",
            minimum=1,
        ),
        max_confidence=_float(
            parser,
            "session_card",
            "max_confidence",
            minimum=0.0,
            maximum=1.0,
        ),
        card_ttl_days=_float(
            parser,
            "session_card",
            "card_ttl_days",
            minimum=0.01,
        ),
    )
    if session_card.min_sessions > session_card.max_sessions:
        raise AdaptiveConfigError("session_card min_sessions must not exceed max_sessions")
    if session_card.min_confidence > session_card.max_confidence:
        raise AdaptiveConfigError(
            "session_card min_confidence must not exceed max_confidence"
        )

    emotion = EmotionThresholds(
        negative_threshold=_float(
            parser,
            "emotion",
            "negative_threshold",
            minimum=-1.0,
            maximum=1.0,
        ),
        high_arousal_threshold=_float(
            parser,
            "emotion",
            "high_arousal_threshold",
            minimum=0.0,
            maximum=1.0,
        ),
        positive_threshold=_float(
            parser,
            "emotion",
            "positive_threshold",
            minimum=-1.0,
            maximum=1.0,
        ),
        current_emotion_window_minutes=_float(
            parser,
            "emotion",
            "current_emotion_window_minutes",
            minimum=0.01,
        ),
        current_emotion_min_confidence=_float(
            parser,
            "emotion",
            "current_emotion_min_confidence",
            minimum=0.0,
            maximum=1.0,
        ),
    )
    if emotion.negative_threshold >= emotion.positive_threshold:
        raise AdaptiveConfigError(
            "emotion negative_threshold must be below positive_threshold"
        )

    reward = RewardThresholds(
        negative_valence_weight=_float(
            parser,
            "reward",
            "negative_valence_weight",
            minimum=0.0,
        ),
        negative_arousal_relief_weight=_float(
            parser,
            "reward",
            "negative_arousal_relief_weight",
            minimum=0.0,
        ),
        neutral_valence_weight=_float(
            parser,
            "reward",
            "neutral_valence_weight",
            minimum=0.0,
        ),
        neutral_arousal_relief_weight=_float(
            parser,
            "reward",
            "neutral_arousal_relief_weight",
            minimum=0.0,
        ),
        positive_retention_weight=_float(
            parser,
            "reward",
            "positive_retention_weight",
            minimum=0.0,
        ),
        positive_valence_delta_weight=_float(
            parser,
            "reward",
            "positive_valence_delta_weight",
            minimum=0.0,
        ),
        positive_arousal_increase_penalty=_float(
            parser,
            "reward",
            "positive_arousal_increase_penalty",
            minimum=0.0,
        ),
        positive_retention_floor=_float(
            parser,
            "reward",
            "positive_retention_floor",
            minimum=-1.0,
            maximum=1.0,
        ),
        positive_retention_scale=_float(
            parser,
            "reward",
            "positive_retention_scale",
            minimum=0.000001,
        ),
        observed_reward_weight=_float(
            parser,
            "reward",
            "observed_reward_weight",
            minimum=0.0,
        ),
        explicit_feedback_weight=_float(
            parser,
            "reward",
            "explicit_feedback_weight",
            minimum=0.0,
        ),
        implicit_like_threshold=_float(
            parser,
            "reward",
            "implicit_like_threshold",
        ),
        implicit_dislike_threshold=_float(
            parser,
            "reward",
            "implicit_dislike_threshold",
        ),
        reward_min=_float(parser, "reward", "reward_min"),
        reward_max=_float(parser, "reward", "reward_max"),
    )
    _require_approximately_one(
        "negative reward",
        reward.negative_valence_weight,
        reward.negative_arousal_relief_weight,
    )
    _require_approximately_one(
        "neutral reward",
        reward.neutral_valence_weight,
        reward.neutral_arousal_relief_weight,
    )
    _require_approximately_one(
        "positive reward",
        reward.positive_retention_weight,
        reward.positive_valence_delta_weight,
        reward.positive_arousal_increase_penalty,
    )
    _require_approximately_one(
        "explicit-feedback mixing",
        reward.observed_reward_weight,
        reward.explicit_feedback_weight,
    )
    if reward.reward_min >= reward.reward_max:
        raise AdaptiveConfigError("reward_min must be below reward_max")
    if not (
        reward.reward_min
        <= reward.implicit_dislike_threshold
        < reward.implicit_like_threshold
        <= reward.reward_max
    ):
        raise AdaptiveConfigError(
            "implicit feedback thresholds must be ordered inside reward range"
        )

    learning = LearningThresholds(
        learning_rate=_float(parser, "learning", "learning_rate", minimum=0.0),
        max_delta_per_run=_float(
            parser,
            "learning",
            "max_delta_per_run",
            minimum=0.0,
        ),
        min_weight=_float(parser, "learning", "min_weight", minimum=0.000001),
        max_weight=_float(parser, "learning", "max_weight", minimum=0.000001),
        initial_weight=_float(
            parser,
            "learning",
            "initial_weight",
            minimum=0.000001,
        ),
        min_training_interval_hours=_float(
            parser,
            "learning",
            "min_training_interval_hours",
            minimum=0.0,
        ),
        sleep_evidence_max_age_minutes=_float(
            parser,
            "learning",
            "sleep_evidence_max_age_minutes",
            minimum=0.01,
        ),
        sleep_min_confidence=_float(
            parser,
            "learning",
            "sleep_min_confidence",
            minimum=0.0,
            maximum=1.0,
        ),
        max_outcomes_per_run=_int(
            parser,
            "learning",
            "max_outcomes_per_run",
            minimum=1,
        ),
        status_history_sessions=_int(
            parser,
            "learning",
            "status_history_sessions",
            minimum=1,
        ),
        reward_trend_window_sessions=_int(
            parser,
            "learning",
            "reward_trend_window_sessions",
            minimum=1,
        ),
        policy_history_versions=_int(
            parser,
            "learning",
            "policy_history_versions",
            minimum=1,
        ),
        early_signal_min_outcomes=_int(
            parser,
            "learning",
            "early_signal_min_outcomes",
            minimum=1,
        ),
        trend_available_min_outcomes=_int(
            parser,
            "learning",
            "trend_available_min_outcomes",
            minimum=2,
        ),
        max_session_outcome_interval_hours=_float(
            parser,
            "learning",
            "max_session_outcome_interval_hours",
            minimum=0.01,
        ),
    )
    if learning.min_weight >= learning.max_weight:
        raise AdaptiveConfigError("learning min_weight must be below max_weight")
    if not learning.min_weight <= learning.initial_weight <= learning.max_weight:
        raise AdaptiveConfigError(
            "learning initial_weight must be inside min_weight..max_weight"
        )
    if learning.max_delta_per_run > learning.max_weight - learning.min_weight:
        raise AdaptiveConfigError(
            "learning max_delta_per_run must not exceed the full weight range"
        )
    if (
        2 * learning.reward_trend_window_sessions
        > learning.status_history_sessions
    ):
        raise AdaptiveConfigError(
            "two reward trend windows must fit inside status_history_sessions"
        )
    if (
        learning.early_signal_min_outcomes
        >= learning.trend_available_min_outcomes
    ):
        raise AdaptiveConfigError(
            "early_signal_min_outcomes must be below trend_available_min_outcomes"
        )

    style_learning = StyleLearningThresholds(
        learning_rate=_float(
            parser,
            "style.learning",
            "learning_rate",
            minimum=0.0,
            maximum=0.2,
        ),
        max_delta_per_run=_float(
            parser,
            "style.learning",
            "max_delta_per_run",
            minimum=0.0,
            maximum=0.2,
        ),
        min_value=_float(
            parser,
            "style.learning",
            "min_value",
            minimum=0.0,
            maximum=1.0,
        ),
        max_value=_float(
            parser,
            "style.learning",
            "max_value",
            minimum=0.0,
            maximum=1.0,
        ),
        exploration_initial=_float(
            parser,
            "style.learning",
            "exploration_initial",
            minimum=0.0,
            maximum=0.5,
        ),
        exploration_min=_float(
            parser,
            "style.learning",
            "exploration_min",
            minimum=0.0,
            maximum=0.5,
        ),
        exploration_decay_samples=_int(
            parser,
            "style.learning",
            "exploration_decay_samples",
            minimum=1,
        ),
        explored_dimensions_per_response=_int(
            parser,
            "style.learning",
            "explored_dimensions_per_response",
            minimum=1,
            maximum=len(STYLE_DIMENSION_NAMES),
        ),
    )
    if style_learning.min_value >= style_learning.max_value:
        raise AdaptiveConfigError("style min_value must be below max_value")
    if style_learning.exploration_min > style_learning.exploration_initial:
        raise AdaptiveConfigError(
            "style exploration_min must not exceed exploration_initial"
        )
    if (
        style_learning.max_delta_per_run
        > style_learning.max_value - style_learning.min_value
    ):
        raise AdaptiveConfigError(
            "style max_delta_per_run must fit inside the value range"
        )

    style_defaults: dict[str, dict[str, float]] = {}
    for bucket in EMOTION_BUCKET_NAMES:
        section = f"style.{bucket}"
        values = _require_section(parser, section)
        if set(values) != set(STYLE_DIMENSION_NAMES):
            raise AdaptiveConfigError(
                f"[{section}] keys must exactly match the ten style dimensions"
            )
        style_defaults[bucket] = {
            dimension: _float(
                parser,
                section,
                dimension,
                minimum=style_learning.min_value,
                maximum=style_learning.max_value,
            )
            for dimension in STYLE_DIMENSION_NAMES
        }
    context = ContextThresholds(
        default_cards=_int(parser, "context", "default_cards", minimum=0),
        max_cards=_int(parser, "context", "max_cards", minimum=0),
        recommended_strategies=_int(
            parser,
            "context",
            "recommended_strategies",
            minimum=1,
            maximum=len(STRATEGY_NAMES),
        ),
        topic_relevance_multiplier=_float(
            parser,
            "context",
            "topic_relevance_multiplier",
            minimum=0.0,
        ),
        voice_emotion_relevance_bonus=_float(
            parser,
            "context",
            "voice_emotion_relevance_bonus",
            minimum=0.0,
        ),
    )
    if context.default_cards > context.max_cards:
        raise AdaptiveConfigError("context default_cards must not exceed max_cards")

    retention = RetentionThresholds(
        analysis_events_days=_float(
            parser,
            "retention",
            "analysis_events_days",
            minimum=0.01,
        ),
        context_cards_days=_float(
            parser,
            "retention",
            "context_cards_days",
            minimum=0.01,
        ),
        audit_log_days=_float(
            parser,
            "retention",
            "audit_log_days",
            minimum=0.01,
        ),
        expired_event_grace_days=_float(
            parser,
            "retention",
            "expired_event_grace_days",
            minimum=0.0,
        ),
    )

    common_section = _require_section(parser, "prompt.common")
    if set(common_section) != {"rules"}:
        raise AdaptiveConfigError("[prompt.common] must contain only rules")
    bucket_section = _require_section(parser, "prompt.bucket")
    if set(bucket_section) != set(EMOTION_BUCKET_NAMES):
        raise AdaptiveConfigError(
            "[prompt.bucket] keys must exactly match the four emotion buckets"
        )
    strategy_section = _require_section(parser, "prompt.strategy")
    if set(strategy_section) != set(STRATEGY_NAMES):
        raise AdaptiveConfigError(
            "[prompt.strategy] keys must exactly match the six response strategies"
        )
    style_section = _require_section(parser, "prompt.style")
    if set(style_section) != set(STYLE_DIMENSION_NAMES):
        raise AdaptiveConfigError(
            "[prompt.style] keys must exactly match the ten style dimensions"
        )
    style_templates = {
        dimension: _text(parser, "prompt.style", dimension)
        for dimension in STYLE_DIMENSION_NAMES
    }
    sample_fields = {
        "value": 0.5,
        "percent": 50,
        "level": "middle",
        "target_sentences": 4,
        "max_questions": 1,
        "detail_points": 2,
        "action_minutes": 5,
    }
    for dimension, template in style_templates.items():
        try:
            template.format(**sample_fields)
        except (KeyError, ValueError) as exc:
            raise AdaptiveConfigError(
                f"[prompt.style] {dimension} has an invalid placeholder: {exc}"
            ) from exc
    prompts = PromptPolicy(
        common_rules=_text(parser, "prompt.common", "rules"),
        bucket_instructions={
            bucket: _text(parser, "prompt.bucket", bucket)
            for bucket in EMOTION_BUCKET_NAMES
        },
        strategy_instructions={
            strategy: _text(parser, "prompt.strategy", strategy)
            for strategy in STRATEGY_NAMES
        },
        style_templates=style_templates,
    )

    return AdaptivePolicyConfig(
        source_path=source_path,
        config_version=config_version,
        quality=quality,
        trend=trend,
        derived_summary=derived_summary,
        rfid=rfid,
        session_card=session_card,
        emotion=emotion,
        reward=reward,
        learning=learning,
        style_learning=style_learning,
        style_defaults=style_defaults,
        context=context,
        retention=retention,
        prompts=prompts,
    )
