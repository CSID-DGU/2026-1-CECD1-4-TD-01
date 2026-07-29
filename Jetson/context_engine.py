#!/usr/bin/env python3
"""Persistent adaptive context engine for the On-mom Jetson hub.

The engine deliberately learns small, inspectable response-policy weights
instead of modifying the language model itself.  It stores only derived
events, builds personal baselines, creates prompt-safe context cards, and
performs tiny bounded updates while the user is believed to be asleep.

Only the Python standard library is required so the same code can be tested
off-device and copied to a Jetson Nano.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import sqlite3
import statistics
import threading
import time
import uuid
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Iterable, Iterator, Sequence

from adaptive_config import (
    DEFAULT_ADAPTIVE_CONFIG_PATH,
    EMOTION_BUCKET_NAMES,
    STRATEGY_NAMES,
    STYLE_DIMENSION_NAMES,
    AdaptivePolicyConfig,
    load_adaptive_policy_config,
)

MILLIS_PER_MINUTE = 60_000
MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR

MAX_EVENT_BYTES = 32 * 1024
MAX_EVENT_STRING_CHARS = 4_000

QUALITY_STATUSES = {
    "VALID",
    "MISSING",
    "SUSPECT",
    "CONTRADICTED",
    "EXPIRED",
    "EXCLUDED",
}

ALLOWED_DOMAINS = {
    "HEALTH",
    "PHENOTYPE",
    "GALLERY",
    "CONVERSATION",
    "FACIAL_EXPRESSION",
    "VOICE_EMOTION",
    "SLEEP_AND_ROUTINE",
    "PHYSICAL_ACTIVITY",
    "SEDENTARY_AND_POSTURE",
    "SOCIAL_CONNECTION",
    "SCHOOL_OR_WORK_LOAD",
    "RECOVERY_RESOURCE",
    "MEAL_ROUTINE",
    "PHYSIOLOGICAL_STATE",
    "DAILY_ROUTINE",
    "HOME_ENVIRONMENT",
    "GENERAL_CHECK_IN",
}

ANALYSIS_EVENT_KEYS = {
    "schema_version",
    "event_id",
    "source",
    "source_family",
    "domain",
    "event_type",
    "occurred_at",
    "expires_at",
    "contains_raw_data",
    "metrics",
    "quality",
}

QUALITY_KEYS = {"status", "confidence", "coverage", "reasons"}

SESSION_OUTCOME_KEYS = {
    "schema_version",
    "session_id",
    "started_at",
    "ended_at",
    "before",
    "after",
    "strategies",
    "explicit_feedback",
    "contains_raw_data",
}

EMOTION_KEYS = {"valence", "arousal", "confidence"}
SESSION_OUTCOME_OPTIONAL_KEYS = {"response_style"}
RESPONSE_STYLE_KEYS = {"values", "baseline_values", "explored_dimensions"}

FORBIDDEN_KEYS = {
    "raw",
    "raw_data",
    "raw_audio",
    "raw_image",
    "image_bytes",
    "audio_bytes",
    "image_path",
    "audio_path",
    "uri",
    "card_uid",
    "rfid_uid",
    "phone_number",
    "package_name",
    "transcript",
    "conversation_text",
    "user_message",
}

FORBIDDEN_MARKERS = (
    "content://",
    "file://",
    "/storage/",
    "/sdcard/",
    "data:image",
    "data:audio",
    ";base64,",
    "[현재 사용자 메시지]",
    "[사용자 입력]",
)

STRATEGIES = STRATEGY_NAMES
EMOTION_BUCKETS = EMOTION_BUCKET_NAMES
STYLE_DIMENSIONS = STYLE_DIMENSION_NAMES

DERIVED_CATEGORY_DOMAIN = {
    "HEALTH": "HEALTH",
    "PHENOTYPE": "PHENOTYPE",
    "GALLERY": "GALLERY",
    "VOICE_EMOTION": "VOICE_EMOTION",
    "CONVERSATION": "CONVERSATION",
}

IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class ContextEngineError(ValueError):
    """Raised when derived input does not satisfy the service contract."""


DEFAULT_ADAPTIVE_CONFIG = load_adaptive_policy_config(DEFAULT_ADAPTIVE_CONFIG_PATH)


def now_epoch_ms() -> int:
    return int(time.time() * 1000)


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(float(value))


def _bounded_number(value: Any, field: str, minimum: float, maximum: float) -> float:
    if not _is_number(value):
        raise ContextEngineError(f"{field} must be a finite number")
    result = float(value)
    if not minimum <= result <= maximum:
        raise ContextEngineError(f"{field} must be between {minimum} and {maximum}")
    return result


def _positive_millis(value: Any, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ContextEngineError(f"{field} must be a positive epoch-millis integer")
    return value


def _validate_identifier(value: Any, field: str) -> str:
    if not isinstance(value, str) or not IDENTIFIER_RE.fullmatch(value):
        raise ContextEngineError(f"{field} contains unsupported characters")
    return value


def _validate_derived_value(value: Any, path: str) -> None:
    if value is None or isinstance(value, bool):
        return
    if _is_number(value):
        return
    if isinstance(value, str):
        if len(value) > MAX_EVENT_STRING_CHARS:
            raise ContextEngineError(f"{path} is too long")
        lowered = value.lower()
        if any(marker in lowered for marker in FORBIDDEN_MARKERS):
            raise ContextEngineError(f"{path} contains a raw-data marker")
        return
    if isinstance(value, list):
        if len(value) > 32:
            raise ContextEngineError(f"{path} has too many values")
        for index, item in enumerate(value):
            if isinstance(item, (dict, list)):
                raise ContextEngineError(f"{path}[{index}] must be a scalar")
            _validate_derived_value(item, f"{path}[{index}]")
        return
    raise ContextEngineError(f"{path} must be a derived scalar or scalar list")


def validate_analysis_event(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ContextEngineError("analysis event must be a JSON object")
    if set(payload) != ANALYSIS_EVENT_KEYS:
        raise ContextEngineError("analysis event fields do not match analysis-event-v1")
    if payload.get("schema_version") != 1:
        raise ContextEngineError("unsupported analysis event schema_version")
    if payload.get("contains_raw_data") is not False:
        raise ContextEngineError("raw data is not accepted")

    _validate_identifier(payload.get("event_id"), "event_id")
    _validate_identifier(payload.get("source"), "source")
    _validate_identifier(payload.get("source_family"), "source_family")
    _validate_identifier(payload.get("event_type"), "event_type")

    domain = payload.get("domain")
    if domain not in ALLOWED_DOMAINS:
        raise ContextEngineError("unsupported domain")

    occurred_at = _positive_millis(payload.get("occurred_at"), "occurred_at")
    expires_at = payload.get("expires_at")
    if expires_at is not None:
        _positive_millis(expires_at, "expires_at")
        if expires_at <= occurred_at:
            raise ContextEngineError("expires_at must be later than occurred_at")

    metrics = payload.get("metrics")
    if not isinstance(metrics, dict) or len(metrics) > 32:
        raise ContextEngineError("metrics must be an object with at most 32 derived values")
    for key, value in metrics.items():
        _validate_identifier(key, "metric key")
        if key.lower() in FORBIDDEN_KEYS:
            raise ContextEngineError(f"forbidden metric field: {key}")
        _validate_derived_value(value, f"metrics.{key}")

    quality = payload.get("quality")
    if not isinstance(quality, dict) or set(quality) != QUALITY_KEYS:
        raise ContextEngineError("quality fields do not match analysis-event-v1")
    if quality.get("status") not in QUALITY_STATUSES:
        raise ContextEngineError("unsupported quality status")
    _bounded_number(quality.get("confidence"), "quality.confidence", 0.0, 1.0)
    _bounded_number(quality.get("coverage"), "quality.coverage", 0.0, 1.0)
    reasons = quality.get("reasons")
    if not isinstance(reasons, list) or len(reasons) > 16:
        raise ContextEngineError("quality.reasons must be a list with at most 16 values")
    for index, reason in enumerate(reasons):
        if not isinstance(reason, str) or not reason.strip() or len(reason) > 300:
            raise ContextEngineError(f"quality.reasons[{index}] is invalid")
        _validate_derived_value(reason, f"quality.reasons[{index}]")

    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(encoded) > MAX_EVENT_BYTES:
        raise ContextEngineError("analysis event is too large")
    return payload


def apply_source_quality_gate(
    validated: dict[str, Any],
    config: AdaptivePolicyConfig | None = None,
) -> dict[str, Any]:
    """Return a sanitized copy whose implausible metrics cannot affect learning."""

    thresholds = (config or DEFAULT_ADAPTIVE_CONFIG).quality
    event = json.loads(json.dumps(validated, ensure_ascii=False))
    quality = event["quality"]
    if quality["status"] != "VALID":
        return event

    metrics = event["metrics"]
    reasons = list(quality["reasons"])
    excluded: list[str] = []

    def exclude(metric: str, reason: str) -> None:
        if metric in metrics and metrics[metric] is not None:
            metrics[metric] = None
            excluded.append(metric)
            reasons.append(reason)

    steps = metrics.get("steps")
    if (
        _is_number(steps)
        and float(steps) <= thresholds.steps_missing_at_or_below
    ):
        exclude("steps", "zero_or_negative_steps_treated_as_missing")

    exercise_minutes = metrics.get("exercise_minutes")
    if (
        _is_number(exercise_minutes)
        and float(exercise_minutes) <= thresholds.exercise_minutes_missing_at_or_below
    ):
        exclude("exercise_minutes", "zero_exercise_record_treated_as_missing")

    distance_km = metrics.get("distance_km")
    if (
        _is_number(distance_km)
        and float(distance_km) <= thresholds.distance_km_missing_at_or_below
    ):
        exclude("distance_km", "distance_at_or_below_0_02km_treated_as_missing")
    valid_steps = metrics.get("steps")
    valid_distance = metrics.get("distance_km")
    if _is_number(valid_steps) and _is_number(valid_distance) and float(valid_steps) > 0:
        meters_per_step = float(valid_distance) * 1000.0 / float(valid_steps)
        if (
            meters_per_step < thresholds.meters_per_step_min
            or meters_per_step > thresholds.meters_per_step_max
        ):
            exclude("distance_km", "distance_contradicts_step_count")

    total_calories = metrics.get("total_calories_kcal")
    if (
        _is_number(total_calories)
        and float(total_calories) < thresholds.total_calories_kcal_min
    ):
        exclude("total_calories_kcal", "implausibly_low_total_calories")
    active_calories = metrics.get("active_calories_kcal")
    if (
        _is_number(active_calories)
        and float(active_calories) < thresholds.active_calories_kcal_min
    ):
        exclude("active_calories_kcal", "implausibly_low_active_calories")
    if _is_number(metrics.get("total_calories_kcal")) and _is_number(metrics.get("active_calories_kcal")):
        if float(metrics["active_calories_kcal"]) > float(metrics["total_calories_kcal"]):
            exclude("total_calories_kcal", "total_calories_below_active_calories")

    heart_rate = metrics.get("heart_rate_bpm")
    if _is_number(heart_rate) and not (
        thresholds.heart_rate_bpm_min
        <= float(heart_rate)
        <= thresholds.heart_rate_bpm_max
    ):
        exclude("heart_rate_bpm", "heart_rate_outside_supported_range")

    if event["source_family"] == "physiology.heart_rate":
        signal_quality = metrics.get("signal_quality")
        if _is_number(signal_quality):
            quality["confidence"] = min(
                float(quality["confidence"]),
                max(0.0, min(1.0, float(signal_quality))),
            )
            if float(signal_quality) < thresholds.rppg_signal_quality_min:
                exclude("heart_rate_bpm", "rppg_signal_quality_below_0_45")
        measurement_seconds = metrics.get("measurement_seconds")
        if (
            _is_number(measurement_seconds)
            and float(measurement_seconds) < thresholds.rppg_measurement_seconds_min
        ):
            exclude("heart_rate_bpm", "rppg_measurement_shorter_than_10_seconds")
        if metrics.get("heart_rate_bpm") is None:
            quality["status"] = "SUSPECT"

    if event["domain"] == "SEDENTARY_AND_POSTURE":
        observed_seconds = metrics.get("observed_seconds")
        if (
            _is_number(observed_seconds)
            and float(observed_seconds) < thresholds.posture_observation_seconds_min
        ):
            for metric in list(metrics):
                if metric.endswith("_minutes") or metric.endswith("_ratio"):
                    exclude(metric, "posture_observation_shorter_than_60_seconds")
            quality["status"] = "SUSPECT"
        for metric, value in list(metrics.items()):
            if metric.endswith("_ratio") and _is_number(value) and not 0.0 <= float(value) <= 1.0:
                exclude(metric, "posture_ratio_outside_0_to_1")

    if event["event_type"] == "access_transition":
        direction = str(metrics.get("direction", "")).upper()
        if direction not in {"ENTER", "EXIT"}:
            quality["status"] = "SUSPECT"
            reasons.append("access_direction_unknown")
        if metrics.get("authorized") is False:
            quality["status"] = "EXCLUDED"
            reasons.append("denied_access_is_security_log_not_wellbeing_context")

    if event["event_type"] == "door_state":
        state = str(metrics.get("state", "")).upper()
        if state not in {"OPEN", "CLOSED"}:
            quality["status"] = "SUSPECT"
            reasons.append("door_state_unknown")

    if event["event_type"] == "sleep_state":
        state = str(metrics.get("state", "")).upper()
        if state not in {"ASLEEP", "LIKELY_ASLEEP", "AWAKE"}:
            quality["status"] = "SUSPECT"
            reasons.append("sleep_state_unknown")

    quality["reasons"] = list(dict.fromkeys(reasons))[:16]
    event["_excluded_metrics"] = excluded
    return event


def _validate_response_style(
    response_style: Any,
    config: AdaptivePolicyConfig,
) -> None:
    if not isinstance(response_style, dict) or set(response_style) != RESPONSE_STYLE_KEYS:
        raise ContextEngineError("response_style fields do not match session-outcome-v1")
    values = response_style.get("values")
    baselines = response_style.get("baseline_values")
    explored = response_style.get("explored_dimensions")
    if not isinstance(values, dict) or set(values) != set(STYLE_DIMENSIONS):
        raise ContextEngineError("response_style values must contain all style dimensions")
    if not isinstance(baselines, dict) or set(baselines) != set(STYLE_DIMENSIONS):
        raise ContextEngineError(
            "response_style baseline_values must contain all style dimensions"
        )
    if (
        not isinstance(explored, list)
        or len(explored) > config.style_learning.explored_dimensions_per_response
        or len(set(explored)) != len(explored)
        or any(dimension not in STYLE_DIMENSIONS for dimension in explored)
    ):
        raise ContextEngineError("response_style explored_dimensions are invalid")
    explored_set = set(explored)
    for dimension in STYLE_DIMENSIONS:
        value = _bounded_number(
            values.get(dimension),
            f"response_style.values.{dimension}",
            config.style_learning.min_value,
            config.style_learning.max_value,
        )
        baseline = _bounded_number(
            baselines.get(dimension),
            f"response_style.baseline_values.{dimension}",
            config.style_learning.min_value,
            config.style_learning.max_value,
        )
        difference = abs(float(value) - float(baseline))
        if dimension not in explored_set and difference > 0.000001:
            raise ContextEngineError(
                "only explored response style dimensions may differ from baseline"
            )
        if (
            dimension in explored_set
            and difference > config.style_learning.exploration_initial + 0.000001
        ):
            raise ContextEngineError("response style exploration exceeds safety range")

def validate_session_outcome(
    payload: Any,
    config: AdaptivePolicyConfig | None = None,
) -> dict[str, Any]:
    settings = config or DEFAULT_ADAPTIVE_CONFIG
    if not isinstance(payload, dict):
        raise ContextEngineError("session outcome must be a JSON object")
    payload_keys = set(payload)
    if (
        not SESSION_OUTCOME_KEYS.issubset(payload_keys)
        or payload_keys - SESSION_OUTCOME_KEYS - SESSION_OUTCOME_OPTIONAL_KEYS
    ):
        raise ContextEngineError(
            "session outcome fields do not match session-outcome-v1"
        )
    if payload.get("schema_version") != 1:
        raise ContextEngineError("unsupported session outcome schema_version")
    if payload.get("contains_raw_data") is not False:
        raise ContextEngineError("raw conversation data is not accepted")

    _validate_identifier(payload.get("session_id"), "session_id")
    started_at = _positive_millis(payload.get("started_at"), "started_at")
    ended_at = _positive_millis(payload.get("ended_at"), "ended_at")
    if ended_at < started_at:
        raise ContextEngineError("ended_at must not be before started_at")
    max_interval_ms = int(
        settings.learning.max_session_outcome_interval_hours * MILLIS_PER_HOUR
    )
    if ended_at - started_at > max_interval_ms:
        raise ContextEngineError(
            "session outcome interval must not exceed "
            f"{settings.learning.max_session_outcome_interval_hours:g} hours"
        )

    for stage in ("before", "after"):
        emotion = payload.get(stage)
        if not isinstance(emotion, dict) or set(emotion) != EMOTION_KEYS:
            raise ContextEngineError(f"{stage} fields do not match session-outcome-v1")
        _bounded_number(emotion.get("valence"), f"{stage}.valence", -1.0, 1.0)
        _bounded_number(emotion.get("arousal"), f"{stage}.arousal", 0.0, 1.0)
        _bounded_number(emotion.get("confidence"), f"{stage}.confidence", 0.0, 1.0)

    strategies = payload.get("strategies")
    if not isinstance(strategies, list) or not strategies or len(strategies) > 4:
        raise ContextEngineError("one to four response strategies are required")
    if len(set(strategies)) != len(strategies) or any(strategy not in STRATEGIES for strategy in strategies):
        raise ContextEngineError("invalid or duplicate response strategy")

    response_style = payload.get("response_style")
    if response_style is not None:
        _validate_response_style(response_style, settings)

    feedback = payload.get("explicit_feedback")
    if feedback is not None:
        _bounded_number(feedback, "explicit_feedback", -1.0, 1.0)
    return payload


def emotion_bucket(
    valence: float,
    arousal: float,
    config: AdaptivePolicyConfig | None = None,
) -> str:
    thresholds = (config or DEFAULT_ADAPTIVE_CONFIG).emotion
    if valence <= thresholds.negative_threshold:
        return (
            "NEGATIVE_HIGH_AROUSAL"
            if arousal >= thresholds.high_arousal_threshold
            else "NEGATIVE_LOW_AROUSAL"
        )
    if valence >= thresholds.positive_threshold:
        return "POSITIVE"
    return "NEUTRAL"


def outcome_reward(
    payload: dict[str, Any],
    config: AdaptivePolicyConfig | None = None,
) -> float:
    """Score gentle movement toward comfort without rewarding engagement time."""

    settings = config or DEFAULT_ADAPTIVE_CONFIG
    emotion = settings.emotion
    reward = settings.reward
    before = payload["before"]
    after = payload["after"]
    before_valence = float(before["valence"])
    after_valence = float(after["valence"])
    before_arousal = float(before["arousal"])
    after_arousal = float(after["arousal"])
    confidence = min(float(before["confidence"]), float(after["confidence"]))

    valence_delta = (after_valence - before_valence) / 2.0
    arousal_relief = before_arousal - after_arousal

    if before_valence <= emotion.negative_threshold:
        # When distress is present, reward a gentle positive shift and calming.
        observed = (
            reward.negative_valence_weight * valence_delta
            + reward.negative_arousal_relief_weight * arousal_relief
        )
    elif before_valence >= emotion.positive_threshold:
        # Preserve a positive state, but do not reward escalating arousal.
        retained_positive = max(
            reward.reward_min,
            min(
                reward.reward_max,
                (after_valence - reward.positive_retention_floor)
                / reward.positive_retention_scale,
            ),
        )
        observed = (
            reward.positive_retention_weight * retained_positive
            + reward.positive_valence_delta_weight * valence_delta
            - reward.positive_arousal_increase_penalty
            * max(0.0, -arousal_relief)
        )
    else:
        observed = (
            reward.neutral_valence_weight * valence_delta
            + reward.neutral_arousal_relief_weight * arousal_relief
        )

    observed *= confidence
    explicit_feedback = payload.get("explicit_feedback")
    if explicit_feedback is not None:
        observed = (
            reward.observed_reward_weight * observed
            + reward.explicit_feedback_weight * float(explicit_feedback)
        )
    return max(reward.reward_min, min(reward.reward_max, observed))


def implicit_feedback_label(
    reward_value: float,
    config: AdaptivePolicyConfig | None = None,
) -> str:
    """Interpret an emotion-derived reward as an implicit preference signal."""

    thresholds = (config or DEFAULT_ADAPTIVE_CONFIG).reward
    if reward_value >= thresholds.implicit_like_threshold:
        return "LIKE"
    if reward_value <= thresholds.implicit_dislike_threshold:
        return "DISLIKE"
    return "NEUTRAL"


def _median_absolute_deviation(values: Sequence[float], center: float) -> float:
    return statistics.median(abs(value - center) for value in values)


def _freshness(age_ms: int, ttl_ms: int) -> float:
    if age_ms <= 0:
        return 1.0
    return math.exp(-float(age_ms) / float(max(1, ttl_ms)))


def _round(value: float, digits: int = 3) -> float:
    return round(float(value), digits)


class ContextEngine:
    def __init__(
        self,
        database_path: Path,
        clock: Callable[[], int] = now_epoch_ms,
        config: AdaptivePolicyConfig | None = None,
        config_path: Path | None = None,
    ):
        if config is not None and config_path is not None:
            raise ContextEngineError("provide config or config_path, not both")
        self.database_path = database_path.resolve()
        self.clock = clock
        self.config = config or load_adaptive_policy_config(
            config_path or DEFAULT_ADAPTIVE_CONFIG_PATH
        )
        self._schema_lock = threading.Lock()
        self._initialized = False
        self.initialize()

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.database_path, timeout=20.0)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 20000")
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def initialize(self) -> None:
        with self._schema_lock:
            if self._initialized:
                return
            self.database_path.parent.mkdir(parents=True, exist_ok=True)
            with self._connect() as connection:
                connection.executescript(
                    """
                    PRAGMA journal_mode = WAL;
                    PRAGMA synchronous = NORMAL;

                    CREATE TABLE IF NOT EXISTS analysis_events (
                        event_id TEXT PRIMARY KEY,
                        source TEXT NOT NULL,
                        source_family TEXT NOT NULL,
                        domain TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        occurred_at INTEGER NOT NULL,
                        received_at INTEGER NOT NULL,
                        expires_at INTEGER,
                        quality_status TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        coverage REAL NOT NULL,
                        payload_json TEXT NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_events_time
                        ON analysis_events(occurred_at);
                    CREATE INDEX IF NOT EXISTS idx_events_domain_time
                        ON analysis_events(domain, occurred_at);
                    CREATE INDEX IF NOT EXISTS idx_events_type_time
                        ON analysis_events(event_type, occurred_at);

                    CREATE TABLE IF NOT EXISTS derived_summaries (
                        transfer_id TEXT NOT NULL,
                        category TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        expires_at INTEGER,
                        received_at INTEGER NOT NULL,
                        summary TEXT NOT NULL,
                        PRIMARY KEY(transfer_id, category)
                    );
                    CREATE INDEX IF NOT EXISTS idx_summaries_category_time
                        ON derived_summaries(category, updated_at);

                    CREATE TABLE IF NOT EXISTS session_outcomes (
                        session_id TEXT PRIMARY KEY,
                        started_at INTEGER NOT NULL,
                        ended_at INTEGER NOT NULL,
                        bucket TEXT NOT NULL,
                        before_valence REAL NOT NULL,
                        before_arousal REAL NOT NULL,
                        before_confidence REAL NOT NULL,
                        after_valence REAL NOT NULL,
                        after_arousal REAL NOT NULL,
                        after_confidence REAL NOT NULL,
                        strategies_json TEXT NOT NULL,
                        style_json TEXT NOT NULL DEFAULT '{}',
                        explicit_feedback REAL,
                        reward REAL NOT NULL,
                        trained INTEGER NOT NULL DEFAULT 0,
                        received_at INTEGER NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_outcomes_trained
                        ON session_outcomes(trained, ended_at);

                    CREATE TABLE IF NOT EXISTS policy_weights (
                        bucket TEXT NOT NULL,
                        strategy TEXT NOT NULL,
                        weight REAL NOT NULL,
                        samples INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(bucket, strategy)
                    );


                    CREATE TABLE IF NOT EXISTS policy_style_values (
                        bucket TEXT NOT NULL,
                        dimension TEXT NOT NULL,
                        value REAL NOT NULL,
                        samples INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(bucket, dimension)
                    );
                    CREATE TABLE IF NOT EXISTS policy_versions (
                        version_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        trained_sessions INTEGER NOT NULL,
                        mean_reward REAL,
                        weights_json TEXT NOT NULL,
                        style_json TEXT NOT NULL DEFAULT '{}'
                    );

                    CREATE TABLE IF NOT EXISTS context_cards (
                        card_id TEXT PRIMARY KEY,
                        generated_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        domain TEXT NOT NULL,
                        evidence_group TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        card_json TEXT NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_cards_generated
                        ON context_cards(generated_at);

                    CREATE TABLE IF NOT EXISTS system_state (
                        state_key TEXT PRIMARY KEY,
                        state_value TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    );

                    CREATE TABLE IF NOT EXISTS audit_log (
                        audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        reference_id TEXT,
                        details_json TEXT NOT NULL
                    );
                    """
                )
                outcome_columns = {
                    row["name"]
                    for row in connection.execute(
                        "PRAGMA table_info(session_outcomes)"
                    ).fetchall()
                }
                if "style_json" not in outcome_columns:
                    connection.execute(
                        "ALTER TABLE session_outcomes "
                        "ADD COLUMN style_json TEXT NOT NULL DEFAULT '{}'"
                    )
                version_columns = {
                    row["name"]
                    for row in connection.execute(
                        "PRAGMA table_info(policy_versions)"
                    ).fetchall()
                }
                if "style_json" not in version_columns:
                    connection.execute(
                        "ALTER TABLE policy_versions "
                        "ADD COLUMN style_json TEXT NOT NULL DEFAULT '{}'"
                    )
                timestamp = self.clock()
                for bucket in EMOTION_BUCKETS:
                    for strategy in STRATEGIES:
                        connection.execute(
                            """
                            INSERT OR IGNORE INTO policy_weights
                                (bucket, strategy, weight, samples, updated_at)
                            VALUES (?, ?, ?, 0, ?)
                            """,
                            (
                                bucket,
                                strategy,
                                self.config.learning.initial_weight,
                                timestamp,
                            ),
                        )
                for bucket in EMOTION_BUCKETS:
                    for dimension in STYLE_DIMENSIONS:
                        connection.execute(
                            """
                            INSERT OR IGNORE INTO policy_style_values
                                (bucket, dimension, value, samples, updated_at)
                            VALUES (?, ?, ?, 0, ?)
                            """,
                            (
                                bucket,
                                dimension,
                                self.config.style_defaults[bucket][dimension],
                                timestamp,
                            ),
                        )
                styles = self._style_policy_snapshot(connection)
                connection.execute(
                    """
                    UPDATE policy_versions
                    SET style_json = ?
                    WHERE style_json IS NULL OR style_json = '{}'
                    """,
                    (json.dumps(styles, sort_keys=True),),
                )
                if not connection.execute("SELECT 1 FROM policy_versions LIMIT 1").fetchone():
                    weights = self._policy_snapshot(connection)
                    connection.execute(
                        """
                        INSERT INTO policy_versions
                            (created_at, reason, trained_sessions, mean_reward,
                             weights_json, style_json)
                        VALUES (?, 'initial', 0, NULL, ?, ?)
                        """,
                        (
                            timestamp,
                            json.dumps(weights, sort_keys=True),
                            json.dumps(styles, sort_keys=True),
                        ),
                    )
            if os.name == "posix":
                os.chmod(self.database_path, 0o600)
            self._initialized = True

    def _audit(
        self,
        connection: sqlite3.Connection,
        action: str,
        reference_id: str | None,
        details: dict[str, Any],
        timestamp: int | None = None,
    ) -> None:
        connection.execute(
            """
            INSERT INTO audit_log(created_at, action, reference_id, details_json)
            VALUES (?, ?, ?, ?)
            """,
            (
                timestamp if timestamp is not None else self.clock(),
                action,
                reference_id,
                json.dumps(details, ensure_ascii=False, sort_keys=True),
            ),
        )

    def ingest_event(self, payload: Any) -> dict[str, Any]:
        validated = validate_analysis_event(payload)
        event = apply_source_quality_gate(validated, self.config)
        excluded_metrics = event.pop("_excluded_metrics", [])
        received_at = self.clock()
        quality = event["quality"]
        encoded = json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        with self._connect() as connection:
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO analysis_events(
                    event_id, source, source_family, domain, event_type,
                    occurred_at, received_at, expires_at, quality_status,
                    confidence, coverage, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    event["event_id"],
                    event["source"],
                    event["source_family"],
                    event["domain"],
                    event["event_type"],
                    event["occurred_at"],
                    received_at,
                    event["expires_at"],
                    quality["status"],
                    float(quality["confidence"]),
                    float(quality["coverage"]),
                    encoded,
                ),
            )
            inserted = cursor.rowcount == 1
            self._audit(
                connection,
                "event_ingested" if inserted else "event_duplicate",
                event["event_id"],
                {
                    "domain": event["domain"],
                    "event_type": event["event_type"],
                    "quality_status": quality["status"],
                },
                received_at,
            )
        return {
            "accepted": True,
            "inserted": inserted,
            "event_id": event["event_id"],
            "quality_status": quality["status"],
            "excluded_metrics": excluded_metrics,
        }

    def ingest_derived_payload(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Persist an already validated legacy derived-only-v1 envelope."""

        received_at = self.clock()
        inserted = 0
        with self._connect() as connection:
            for summary in payload["summaries"]:
                cursor = connection.execute(
                    """
                    INSERT OR IGNORE INTO derived_summaries(
                        transfer_id, category, updated_at, expires_at,
                        received_at, summary
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (
                        payload["transfer_id"],
                        summary["category"],
                        summary["updated_at"],
                        summary["expires_at"],
                        received_at,
                        summary["summary"].strip(),
                    ),
                )
                inserted += cursor.rowcount
            self._audit(
                connection,
                "derived_payload_ingested",
                payload["transfer_id"],
                {
                    "categories": [item["category"] for item in payload["summaries"]],
                    "inserted": inserted,
                },
                received_at,
            )
        return {
            "accepted": True,
            "inserted": inserted,
            "transfer_id": payload["transfer_id"],
        }

    def record_session_outcome(self, payload: Any) -> dict[str, Any]:
        outcome = validate_session_outcome(payload, self.config)
        before = outcome["before"]
        after = outcome["after"]
        bucket = emotion_bucket(
            float(before["valence"]),
            float(before["arousal"]),
            self.config,
        )
        reward = outcome_reward(outcome, self.config)
        implicit_feedback = implicit_feedback_label(reward, self.config)
        received_at = self.clock()
        with self._connect() as connection:
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO session_outcomes(
                    session_id, started_at, ended_at, bucket,
                    before_valence, before_arousal, before_confidence,
                    after_valence, after_arousal, after_confidence,
                    strategies_json, style_json, explicit_feedback, reward,
                    trained, received_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                (
                    outcome["session_id"],
                    outcome["started_at"],
                    outcome["ended_at"],
                    bucket,
                    float(before["valence"]),
                    float(before["arousal"]),
                    float(before["confidence"]),
                    float(after["valence"]),
                    float(after["arousal"]),
                    float(after["confidence"]),
                    json.dumps(outcome["strategies"], sort_keys=True),
                    json.dumps(outcome.get("response_style") or {}, sort_keys=True),
                    outcome["explicit_feedback"],
                    reward,
                    received_at,
                ),
            )
            inserted = cursor.rowcount == 1
            self._audit(
                connection,
                "session_outcome_recorded" if inserted else "session_outcome_duplicate",
                outcome["session_id"],
                {
                    "bucket": bucket,
                    "reward": _round(reward),
                    "implicit_feedback": implicit_feedback,
                },
                received_at,
            )
        return {
            "accepted": True,
            "inserted": inserted,
            "session_id": outcome["session_id"],
            "bucket": bucket,
            "reward": _round(reward),
            "implicit_feedback": implicit_feedback,
        }

    def _read_valid_events(
        self,
        connection: sqlite3.Connection,
        start_at: int,
        end_at: int,
        domains: Iterable[str] | None = None,
        event_type: str | None = None,
    ) -> list[dict[str, Any]]:
        clauses = [
            "occurred_at >= ?",
            "occurred_at < ?",
            "quality_status = 'VALID'",
            "(expires_at IS NULL OR expires_at > ?)",
        ]
        parameters: list[Any] = [start_at, end_at, end_at]
        if domains:
            domain_values = list(domains)
            placeholders = ",".join("?" for _ in domain_values)
            clauses.append(f"domain IN ({placeholders})")
            parameters.extend(domain_values)
        if event_type:
            clauses.append("event_type = ?")
            parameters.append(event_type)
        rows = connection.execute(
            f"""
            SELECT payload_json
            FROM analysis_events
            WHERE {' AND '.join(clauses)}
            ORDER BY occurred_at ASC
            """,
            parameters,
        ).fetchall()
        return [json.loads(row["payload_json"]) for row in rows]

    def _numeric_trend_cards(
        self,
        connection: sqlite3.Connection,
        now_ms: int,
    ) -> list[dict[str, Any]]:
        thresholds = self.config.trend
        recent_start = now_ms - int(thresholds.recent_window_hours * MILLIS_PER_HOUR)
        baseline_start = recent_start - int(
            thresholds.baseline_window_days * MILLIS_PER_DAY
        )
        events = self._read_valid_events(connection, baseline_start, now_ms)
        grouped_recent: dict[tuple[str, str, str], list[tuple[float, float, float]]] = {}
        grouped_baseline: dict[tuple[str, str, str], list[tuple[float, float, float]]] = {}

        for event in events:
            quality = event["quality"]
            confidence = float(quality["confidence"])
            coverage = float(quality["coverage"])
            for metric, value in event["metrics"].items():
                if not _is_number(value):
                    continue
                key = (event["domain"], event["source_family"], metric)
                target = grouped_recent if event["occurred_at"] >= recent_start else grouped_baseline
                target.setdefault(key, []).append((float(value), confidence, coverage))

        cards: list[dict[str, Any]] = []
        for key, recent_rows in grouped_recent.items():
            baseline_rows = grouped_baseline.get(key, [])
            if len(baseline_rows) < thresholds.min_baseline_samples:
                continue
            recent_values = [row[0] for row in recent_rows]
            baseline_values = [row[0] for row in baseline_rows]
            recent_median = statistics.median(recent_values)
            baseline_median = statistics.median(baseline_values)
            mad = _median_absolute_deviation(baseline_values, baseline_median)
            delta = recent_median - baseline_median
            robust_z = (
                None
                if mad <= thresholds.zero_epsilon
                else 0.6745 * delta / mad
            )
            relative_delta = (
                None
                if abs(baseline_median) <= thresholds.zero_epsilon
                else delta / abs(baseline_median)
            )
            meaningful = (
                robust_z is not None
                and abs(robust_z) >= thresholds.robust_z_candidate_threshold
            ) or (
                relative_delta is not None
                and abs(relative_delta)
                >= thresholds.relative_change_candidate_threshold
            )
            if not meaningful:
                continue

            support = min(
                1.0,
                math.log1p(len(recent_rows))
                / math.log1p(thresholds.recent_support_target_samples),
            )
            measurement_quality = statistics.mean(row[1] * row[2] for row in recent_rows)
            confidence = max(0.0, min(1.0, measurement_quality * support))
            if confidence < thresholds.min_card_confidence:
                continue

            domain, source_family, metric = key
            direction = "증가" if delta > 0 else "감소"
            card = {
                "card_id": str(uuid.uuid4()),
                "domain": domain,
                "evidence_group": source_family,
                "generated_at": now_ms,
                "expires_at": now_ms + int(
                    thresholds.card_ttl_hours * MILLIS_PER_HOUR
                ),
                "code": "PERSONAL_BASELINE_CHANGE",
                "observation": (
                    f"{metric}의 최근 유효 중앙값이 개인 기준선보다 {direction}했습니다."
                ),
                "confidence": _round(confidence),
                "quality": {
                    "status": "VALID",
                    "recent_samples": len(recent_rows),
                    "baseline_samples": len(baseline_rows),
                    "recent_median": _round(recent_median),
                    "baseline_median": _round(baseline_median),
                    "robust_z": None if robust_z is None else _round(robust_z),
                    "relative_delta": None if relative_delta is None else _round(relative_delta),
                },
                "prompt_policy": {
                    "priority": "SUPPORTING",
                    "allowed_use": "SOFT_CHECK_IN",
                    "forbidden_claims": ["diagnosis", "causation"],
                },
            }
            cards.append(card)
        return cards

    def _latest_derived_cards(
        self,
        connection: sqlite3.Connection,
        now_ms: int,
    ) -> list[dict[str, Any]]:
        rows = connection.execute(
            """
            SELECT category, updated_at, expires_at, summary
            FROM derived_summaries AS candidate
            WHERE updated_at = (
                SELECT MAX(updated_at)
                FROM derived_summaries
                WHERE category = candidate.category
            )
            AND (expires_at IS NULL OR expires_at > ?)
            ORDER BY updated_at DESC
            """,
            (now_ms,),
        ).fetchall()
        thresholds = self.config.derived_summary
        cards: list[dict[str, Any]] = []
        for row in rows:
            category = row["category"]
            updated_at = int(row["updated_at"])
            if category == "VOICE_EMOTION":
                ttl = int(
                    thresholds.voice_emotion_ttl_minutes * MILLIS_PER_MINUTE
                )
            else:
                ttl = int(thresholds.other_summary_ttl_days * MILLIS_PER_DAY)
            confidence = thresholds.initial_confidence * _freshness(
                max(0, now_ms - updated_at),
                ttl,
            )
            if confidence < thresholds.min_confidence:
                continue
            cards.append(
                {
                    "card_id": str(uuid.uuid4()),
                    "domain": DERIVED_CATEGORY_DOMAIN.get(category, "GENERAL_CHECK_IN"),
                    "evidence_group": f"android.{category.lower()}",
                    "generated_at": now_ms,
                    "expires_at": int(row["expires_at"] or (updated_at + ttl)),
                    "code": "DERIVED_SUMMARY",
                    "observation": row["summary"],
                    "confidence": _round(confidence),
                    "quality": {
                        "status": "VALID",
                        "updated_at": updated_at,
                        "source": "onmom-android",
                    },
                    "prompt_policy": {
                        "priority": "SUPPORTING",
                        "allowed_use": "SOFT_CHECK_IN",
                        "forbidden_claims": ["diagnosis", "sensor_certainty"],
                    },
                }
            )
        return cards

    def _rfid_routine_card(
        self,
        connection: sqlite3.Connection,
        now_ms: int,
    ) -> dict[str, Any] | None:
        thresholds = self.config.rfid
        total_window_ms = int(
            (thresholds.recent_window_days + thresholds.baseline_window_days)
            * MILLIS_PER_DAY
        )
        recent_window_ms = int(thresholds.recent_window_days * MILLIS_PER_DAY)
        events = self._read_valid_events(
            connection,
            now_ms - total_window_ms,
            now_ms,
            domains={"DAILY_ROUTINE"},
            event_type="access_transition",
        )
        entries: list[tuple[int, float, float]] = []
        seen_recently: dict[tuple[str, str], int] = {}
        for event in events:
            direction = str(event["metrics"].get("direction", "")).upper()
            if direction != "ENTER":
                continue
            zone = str(event["metrics"].get("zone", "unknown"))
            dedupe_key = (event["source"], zone)
            previous = seen_recently.get(dedupe_key)
            if (
                previous is not None
                and event["occurred_at"] - previous
                < thresholds.dedupe_seconds * 1000
            ):
                continue
            seen_recently[dedupe_key] = event["occurred_at"]
            moment = datetime.fromtimestamp(event["occurred_at"] / 1000).astimezone()
            minute_of_day = moment.hour * 60 + moment.minute
            entries.append(
                (
                    event["occurred_at"],
                    float(minute_of_day),
                    float(event["quality"]["confidence"]) * float(event["quality"]["coverage"]),
                )
            )

        recent = [
            item for item in entries
            if item[0] >= now_ms - recent_window_ms
        ]
        baseline = [
            item
            for item in entries
            if now_ms - total_window_ms
            <= item[0]
            < now_ms - recent_window_ms
        ]
        if (
            len(recent) < thresholds.min_recent_samples
            or len(baseline) < thresholds.min_baseline_samples
        ):
            return None
        recent_median = statistics.median(item[1] for item in recent)
        baseline_median = statistics.median(item[1] for item in baseline)
        delta_minutes = recent_median - baseline_median
        if abs(delta_minutes) < thresholds.entry_shift_minutes:
            return None
        confidence = statistics.mean(item[2] for item in recent)
        confidence *= min(
            1.0,
            len(recent) / thresholds.recent_support_target_samples,
        )
        if confidence < thresholds.min_card_confidence:
            return None
        direction = "늦어졌습니다" if delta_minutes > 0 else "빨라졌습니다"
        return {
            "card_id": str(uuid.uuid4()),
            "domain": "DAILY_ROUTINE",
            "evidence_group": "presence.access",
            "generated_at": now_ms,
            "expires_at": now_ms + int(
                thresholds.card_ttl_days * MILLIS_PER_DAY
            ),
            "code": "RFID_ENTRY_TIME_SHIFT",
            "observation": (
                f"최근 RFID 입실 기록의 대표 시각이 개인 기준선보다 약 "
                f"{abs(int(round(delta_minutes)))}분 {direction}"
            ),
            "confidence": _round(confidence),
            "quality": {
                "status": "VALID",
                "recent_samples": len(recent),
                "baseline_samples": len(baseline),
                "warnings": [
                    "입실 기록만으로 실제 재실 시간이나 생활 상태를 확정할 수 없습니다."
                ],
            },
            "prompt_policy": {
                "priority": "SUPPORTING",
                "allowed_use": "SOFT_CHECK_IN",
                "forbidden_claims": ["social_isolation", "sleep_disorder", "home_duration"],
            },
        }

    def _session_change_card(
        self,
        connection: sqlite3.Connection,
        now_ms: int,
    ) -> dict[str, Any] | None:
        thresholds = self.config.session_card
        rows = connection.execute(
            """
            SELECT before_valence, before_arousal, after_valence, after_arousal,
                   before_confidence, after_confidence
            FROM session_outcomes
            WHERE ended_at >= ?
            ORDER BY ended_at DESC
            LIMIT ?
            """,
            (
                now_ms - int(thresholds.lookback_days * MILLIS_PER_DAY),
                thresholds.max_sessions,
            ),
        ).fetchall()
        if len(rows) < thresholds.min_sessions:
            return None
        valence_changes = [
            (float(row["after_valence"]) - float(row["before_valence"]))
            * min(float(row["before_confidence"]), float(row["after_confidence"]))
            for row in rows
        ]
        arousal_changes = [
            (float(row["after_arousal"]) - float(row["before_arousal"]))
            * min(float(row["before_confidence"]), float(row["after_confidence"]))
            for row in rows
        ]
        mean_valence_change = statistics.mean(valence_changes)
        mean_arousal_change = statistics.mean(arousal_changes)
        confidence = min(
            thresholds.max_confidence,
            statistics.mean(
                min(
                    float(row["before_confidence"]),
                    float(row["after_confidence"]),
                )
                for row in rows
            )
            * min(1.0, len(rows) / thresholds.support_target_sessions),
        )
        if confidence < thresholds.min_confidence:
            return None
        return {
            "card_id": str(uuid.uuid4()),
            "domain": "CONVERSATION",
            "evidence_group": "counseling.session_outcome",
            "generated_at": now_ms,
            "expires_at": now_ms + int(
                thresholds.card_ttl_days * MILLIS_PER_DAY
            ),
            "code": "RECENT_CONVERSATION_EFFECT",
            "observation": (
                "최근 상담 전후의 파생 정서 지표에서 "
                f"정서가 {_round(mean_valence_change)}만큼 변하고 "
                f"각성도가 {_round(mean_arousal_change)}만큼 변했습니다."
            ),
            "confidence": _round(confidence),
            "quality": {
                "status": "VALID",
                "sessions": len(rows),
                "mean_valence_change": _round(mean_valence_change),
                "mean_arousal_change": _round(mean_arousal_change),
                "warnings": [
                    "정서 분석은 사용자의 직접 표현보다 우선할 수 없습니다."
                ],
            },
            "prompt_policy": {
                "priority": "SUPPORTING",
                "allowed_use": "STRATEGY_SELECTION_ONLY",
                "forbidden_claims": ["diagnosis", "emotion_certainty"],
            },
        }

    def _current_emotion(
        self,
        connection: sqlite3.Connection,
        now_ms: int,
    ) -> tuple[float, float, float] | None:
        thresholds = self.config.emotion
        events = self._read_valid_events(
            connection,
            now_ms - int(
                thresholds.current_emotion_window_minutes * MILLIS_PER_MINUTE
            ),
            now_ms + 1,
            domains={
                "VOICE_EMOTION",
                "FACIAL_EXPRESSION",
                "PHYSIOLOGICAL_STATE",
            },
        )
        for event in reversed(events):
            metrics = event["metrics"]
            valence = metrics.get("valence")
            arousal = metrics.get("arousal")
            if _is_number(valence) and _is_number(arousal):
                confidence = float(event["quality"]["confidence"]) * float(event["quality"]["coverage"])
                if confidence >= thresholds.current_emotion_min_confidence:
                    return float(valence), float(arousal), confidence
        return None

    def _policy_snapshot(self, connection: sqlite3.Connection) -> dict[str, dict[str, float]]:
        snapshot = {bucket: {} for bucket in EMOTION_BUCKETS}
        rows = connection.execute(
            """
            SELECT bucket, strategy, weight
            FROM policy_weights
            ORDER BY bucket, strategy
            """
        ).fetchall()
        for row in rows:
            snapshot[row["bucket"]][row["strategy"]] = _round(row["weight"], 6)
        return snapshot

    def _style_policy_snapshot(
        self,
        connection: sqlite3.Connection,
    ) -> dict[str, dict[str, float]]:
        snapshot = {bucket: {} for bucket in EMOTION_BUCKETS}
        rows = connection.execute(
            """
            SELECT bucket, dimension, value
            FROM policy_style_values
            ORDER BY bucket, dimension
            """
        ).fetchall()
        for row in rows:
            snapshot[row["bucket"]][row["dimension"]] = _round(row["value"], 6)
        return snapshot

    def _style_exploration_amplitude(self, samples: int) -> float:
        settings = self.config.style_learning
        decayed = settings.exploration_initial / math.sqrt(
            1.0 + max(0, samples) / settings.exploration_decay_samples
        )
        return max(settings.exploration_min, decayed)

    @staticmethod
    def _stable_fraction(*parts: object) -> float:
        material = ":".join(str(part) for part in parts).encode("utf-8")
        integer = int.from_bytes(hashlib.sha256(material).digest()[:8], "big")
        return integer / float((1 << 64) - 1)

    def _response_style_for_turn(
        self,
        connection: sqlite3.Connection,
        bucket: str,
        timestamp: int,
    ) -> dict[str, Any]:
        rows = connection.execute(
            """
            SELECT dimension, value, samples
            FROM policy_style_values
            WHERE bucket = ?
            ORDER BY dimension
            """,
            (bucket,),
        ).fetchall()
        baselines = {
            row["dimension"]: float(row["value"])
            for row in rows
        }
        ranked_dimensions = sorted(
            STYLE_DIMENSIONS,
            key=lambda dimension: self._stable_fraction(
                timestamp,
                bucket,
                dimension,
                "select",
            ),
        )
        explored = ranked_dimensions[
            :self.config.style_learning.explored_dimensions_per_response
        ]
        values = dict(baselines)
        row_by_dimension = {row["dimension"]: row for row in rows}
        for dimension in explored:
            row = row_by_dimension[dimension]
            amplitude = self._style_exploration_amplitude(int(row["samples"]))
            unit = self._stable_fraction(
                timestamp,
                bucket,
                dimension,
                row["samples"],
                "noise",
            )
            signed = unit * 2.0 - 1.0
            if abs(signed) < 0.25:
                signed = 0.25 if signed >= 0.0 else -0.25
            values[dimension] = max(
                self.config.style_learning.min_value,
                min(
                    self.config.style_learning.max_value,
                    baselines[dimension] + signed * amplitude,
                ),
            )
        return {
            "values": {
                dimension: _round(values[dimension], 6)
                for dimension in STYLE_DIMENSIONS
            },
            "baseline_values": {
                dimension: _round(baselines[dimension], 6)
                for dimension in STYLE_DIMENSIONS
            },
            "explored_dimensions": list(explored),
        }

    def _render_style_instructions(
        self,
        response_style: dict[str, Any],
    ) -> list[str]:
        instructions: list[str] = []
        for dimension in STYLE_DIMENSIONS:
            value = float(response_style["values"][dimension])
            level = (
                "\ub0ae\uc74c"
                if value < 0.34
                else "\ub192\uc74c"
                if value >= 0.67
                else "\uc911\uac04"
            )
            fields = {
                "value": value,
                "percent": int(round(value * 100)),
                "level": level,
                "target_sentences": int(round(2 + value * 6)),
                "max_questions": 0 if value < 0.34 else 1,
                "detail_points": int(round(1 + value * 3)),
                "action_minutes": int(round(2 + value * 13)),
            }
            instructions.append(
                self.config.prompts.style_templates[dimension].format(**fields)
            )
        return instructions

    def policy_snapshot(self) -> dict[str, Any]:
        with self._connect() as connection:
            version_row = connection.execute(
                """
                SELECT version_id, created_at, reason, trained_sessions, mean_reward
                FROM policy_versions
                ORDER BY version_id DESC
                LIMIT 1
                """
            ).fetchone()
            weights = self._policy_snapshot(connection)
            response_styles = self._style_policy_snapshot(connection)
        return {
            "schema": "adaptive-policy-v1",
            "version": int(version_row["version_id"]),
            "created_at": int(version_row["created_at"]),
            "reason": version_row["reason"],
            "trained_sessions": int(version_row["trained_sessions"]),
            "mean_reward": version_row["mean_reward"],
            "weights": weights,
            "response_styles": response_styles,
        }

    def _ranked_strategies(
        self,
        connection: sqlite3.Connection,
        bucket: str,
    ) -> list[dict[str, Any]]:
        rows = connection.execute(
            """
            SELECT strategy, weight, samples
            FROM policy_weights
            WHERE bucket = ?
            ORDER BY weight DESC, strategy ASC
            """,
            (bucket,),
        ).fetchall()
        return [
            {
                "strategy": row["strategy"],
                "weight": _round(row["weight"], 6),
                "samples": int(row["samples"]),
            }
            for row in rows
        ]

    def learning_status(self) -> dict[str, Any]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT bucket, reward, trained, ended_at
                FROM session_outcomes
                ORDER BY ended_at DESC
                LIMIT ?
                """,
                (self.config.learning.status_history_sessions,),
            ).fetchall()
            versions = connection.execute(
                """
                SELECT version_id, created_at, reason, trained_sessions, mean_reward
                FROM policy_versions
                ORDER BY version_id DESC
                LIMIT ?
                """,
                (self.config.learning.policy_history_versions,),
            ).fetchall()
            weights = connection.execute(
                "SELECT weight FROM policy_weights"
            ).fetchall()
            style_rows = connection.execute(
                "SELECT bucket, dimension, value, samples "
                "FROM policy_style_values ORDER BY bucket, dimension"
            ).fetchall()

        rewards = [float(row["reward"]) for row in rows]
        implicit_feedback = [
            implicit_feedback_label(reward, self.config)
            for reward in rewards
        ]
        feedback_counts = {
            label: implicit_feedback.count(label)
            for label in ("LIKE", "NEUTRAL", "DISLIKE")
        }
        trend_window = self.config.learning.reward_trend_window_sessions
        recent = rewards[:trend_window]
        previous = rewards[trend_window:2 * trend_window]
        recent_mean = statistics.mean(recent) if recent else None
        previous_mean = statistics.mean(previous) if previous else None
        trend_delta = (
            recent_mean - previous_mean
            if recent_mean is not None and previous_mean is not None
            else None
        )
        bucket_rewards: dict[str, list[float]] = {bucket: [] for bucket in EMOTION_BUCKETS}
        for row in rows:
            bucket_rewards.setdefault(row["bucket"], []).append(float(row["reward"]))
        if len(rows) < self.config.learning.early_signal_min_outcomes:
            learning_state = "COLLECTING"
        elif len(rows) < self.config.learning.trend_available_min_outcomes:
            learning_state = "EARLY_SIGNAL"
        else:
            learning_state = "TREND_AVAILABLE"
        weight_values = [float(row["weight"]) for row in weights] or [1.0]
        style_profiles = {bucket: {} for bucket in EMOTION_BUCKETS}
        style_observations = 0
        changed_style_dimensions: set[str] = set()
        for row in style_rows:
            bucket = row["bucket"]
            dimension = row["dimension"]
            value = float(row["value"])
            style_profiles[bucket][dimension] = _round(value, 6)
            style_observations += int(row["samples"])
            if abs(value - self.config.style_defaults[bucket][dimension]) >= 0.001:
                changed_style_dimensions.add(dimension)
        return {
            "schema": "learning-status-v1",
            "learning_state": learning_state,
            "interpretation": (
                "\ud3b8\uc548 \ubc29\ud5a5\uc740 \uc554\ubb35\uc801 LIKE, \uaca9\uc591 \ubc29\ud5a5\uc740 \uc554\ubb35\uc801 DISLIKE\ub85c "
                "\uc804\ub7b5 \uac00\uc911\uce58\uc5d0 \uc5f0\uacb0\ub429\ub2c8\ub2e4. \uc774\ub294 \ud559\uc2b5 \uad00\ucc30 \uc9c0\ud45c\uc774\uba70 \uc0c1\ub2f4 \ud6a8\uacfc\ub098 "
                "\uac74\uac15 \uac1c\uc120\uc744 \uc99d\uba85\ud558\uc9c0 \uc54a\uc2b5\ub2c8\ub2e4."
            ),
            "implicit_feedback_counts": feedback_counts,
            "session_outcomes": len(rows),
            "trained_outcomes": sum(int(row["trained"]) for row in rows),
            "pending_outcomes": sum(1 - int(row["trained"]) for row in rows),
            "recent_mean_reward": None if recent_mean is None else _round(recent_mean),
            "previous_mean_reward": None if previous_mean is None else _round(previous_mean),
            "reward_trend_delta": None if trend_delta is None else _round(trend_delta),
            "bucket_mean_rewards": {
                bucket: None if not values else _round(statistics.mean(values))
                for bucket, values in bucket_rewards.items()
            },
            "policy_weight_range": {
                "minimum": _round(min(weight_values), 6),
                "maximum": _round(max(weight_values), 6),
            },
            "response_style_policy": {
                "dimensions": len(STYLE_DIMENSIONS),
                "observations": style_observations,
                "changed_dimensions": len(changed_style_dimensions),
                "value_range": [
                    self.config.style_learning.min_value,
                    self.config.style_learning.max_value,
                ],
                "profiles": style_profiles,
            },
            "policy_versions": [
                {
                    "version": int(row["version_id"]),
                    "created_at": int(row["created_at"]),
                    "reason": row["reason"],
                    "trained_sessions": int(row["trained_sessions"]),
                    "mean_reward": row["mean_reward"],
                }
                for row in versions
            ],
        }

    def rollback_policy(self, target_version: int) -> dict[str, Any]:
        timestamp = self.clock()
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT weights_json, style_json
                FROM policy_versions
                WHERE version_id = ?
                """,
                (target_version,),
            ).fetchone()
            if row is None:
                raise ContextEngineError("policy version not found")
            snapshot = json.loads(row["weights_json"])
            if set(snapshot) != set(EMOTION_BUCKETS):
                raise ContextEngineError("stored policy snapshot has invalid buckets")
            for bucket in EMOTION_BUCKETS:
                if set(snapshot[bucket]) != set(STRATEGIES):
                    raise ContextEngineError("stored policy snapshot has invalid strategies")
                for strategy in STRATEGIES:
                    weight = float(snapshot[bucket][strategy])
                    if not (
                        self.config.learning.min_weight
                        <= weight
                        <= self.config.learning.max_weight
                    ):
                        raise ContextEngineError("stored policy weight is outside safety bounds")
                    connection.execute(
                        """
                        UPDATE policy_weights
                        SET weight = ?, updated_at = ?
                        WHERE bucket = ? AND strategy = ?
                        """,
                        (weight, timestamp, bucket, strategy),
                    )
            style_snapshot = json.loads(row["style_json"] or "{}")
            if not style_snapshot:
                style_snapshot = {
                    bucket: dict(self.config.style_defaults[bucket])
                    for bucket in EMOTION_BUCKETS
                }
            if set(style_snapshot) != set(EMOTION_BUCKETS):
                raise ContextEngineError("stored style snapshot has invalid buckets")
            for bucket in EMOTION_BUCKETS:
                if set(style_snapshot[bucket]) != set(STYLE_DIMENSIONS):
                    raise ContextEngineError(
                        "stored style snapshot has invalid dimensions"
                    )
                for dimension in STYLE_DIMENSIONS:
                    value = float(style_snapshot[bucket][dimension])
                    if not (
                        self.config.style_learning.min_value
                        <= value
                        <= self.config.style_learning.max_value
                    ):
                        raise ContextEngineError(
                            "stored style value is outside safety bounds"
                        )
                    connection.execute(
                        """
                        UPDATE policy_style_values
                        SET value = ?, updated_at = ?
                        WHERE bucket = ? AND dimension = ?
                        """,
                        (value, timestamp, bucket, dimension),
                    )
            restored = self._policy_snapshot(connection)
            restored_styles = self._style_policy_snapshot(connection)
            cursor = connection.execute(
                """
                INSERT INTO policy_versions(
                    created_at, reason, trained_sessions, mean_reward,
                    weights_json, style_json
                ) VALUES (?, ?, 0, NULL, ?, ?)
                """,
                (
                    timestamp,
                    f"rollback_to_{target_version}",
                    json.dumps(restored, sort_keys=True),
                    json.dumps(restored_styles, sort_keys=True),
                ),
            )
            self._audit(
                connection,
                "policy_rollback",
                str(cursor.lastrowid),
                {"target_version": target_version},
                timestamp,
            )
        return {
            "rolled_back": True,
            "target_version": target_version,
            "new_policy_version": int(cursor.lastrowid),
        }

    def _card_selection_score(
        self,
        card: dict[str, Any],
        now_ms: int,
        topic_domains: set[str],
    ) -> float:
        confidence = float(card["confidence"])
        ttl = max(1, int(card["expires_at"]) - int(card["generated_at"]))
        freshness = _freshness(max(0, now_ms - int(card["generated_at"])), ttl)
        relevance = (
            self.config.context.topic_relevance_multiplier
            if card["domain"] in topic_domains
            else 1.0
        )
        if card["domain"] == "VOICE_EMOTION":
            relevance += self.config.context.voice_emotion_relevance_bonus
        return confidence * freshness * relevance

    def build_context_bundle(
        self,
        topic_domains: Iterable[str] = (),
        max_cards: int | None = None,
        current_valence: float | None = None,
        current_arousal: float | None = None,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        timestamp = self.clock() if now_ms is None else now_ms
        topics = {domain for domain in topic_domains if domain in ALLOWED_DOMAINS}
        requested_cards = (
            self.config.context.default_cards
            if max_cards is None
            else int(max_cards)
        )
        max_cards = max(
            0,
            min(self.config.context.max_cards, requested_cards),
        )

        with self._connect() as connection:
            cards = self._latest_derived_cards(connection, timestamp)
            cards.extend(self._numeric_trend_cards(connection, timestamp))
            rfid_card = self._rfid_routine_card(connection, timestamp)
            if rfid_card:
                cards.append(rfid_card)
            session_card = self._session_change_card(connection, timestamp)
            if session_card:
                cards.append(session_card)

            # Correlated sources do not multiply confidence. Keep one card from
            # each evidence group after scoring.
            cards.sort(
                key=lambda card: self._card_selection_score(card, timestamp, topics),
                reverse=True,
            )
            selected_by_group: dict[str, dict[str, Any]] = {}
            for card in cards:
                if card["expires_at"] <= timestamp:
                    continue
                selected_by_group.setdefault(card["evidence_group"], card)
            selected = list(selected_by_group.values())[:max_cards]

            sensed_emotion = self._current_emotion(connection, timestamp)
            if current_valence is None or current_arousal is None:
                if sensed_emotion is None:
                    valence, arousal, emotion_confidence = 0.0, 0.3, 0.0
                else:
                    valence, arousal, emotion_confidence = sensed_emotion
            else:
                valence = max(-1.0, min(1.0, float(current_valence)))
                arousal = max(0.0, min(1.0, float(current_arousal)))
                emotion_confidence = 1.0
            bucket = emotion_bucket(valence, arousal, self.config)
            strategies = self._ranked_strategies(connection, bucket)
            response_style = self._response_style_for_turn(
                connection, bucket, timestamp
            )

            for card in selected:
                connection.execute(
                    """
                    INSERT OR REPLACE INTO context_cards(
                        card_id, generated_at, expires_at, domain,
                        evidence_group, confidence, card_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        card["card_id"],
                        card["generated_at"],
                        card["expires_at"],
                        card["domain"],
                        card["evidence_group"],
                        card["confidence"],
                        json.dumps(card, ensure_ascii=False, sort_keys=True),
                    ),
                )
            self._audit(
                connection,
                "context_bundle_built",
                None,
                {
                    "topics": sorted(topics),
                    "selected_cards": [card["card_id"] for card in selected],
                    "emotion_bucket": bucket,
                    "explored_style_dimensions": (
                        response_style["explored_dimensions"]
                    ),
                },
                timestamp,
            )

        prompt_context = self._compile_prompt_context(
            selected, bucket, strategies, response_style
        )
        return {
            "schema_version": 1,
            "generated_at": timestamp,
            "contains_raw_data": False,
            "emotion_state": {
                "bucket": bucket,
                "valence": _round(valence),
                "arousal": _round(arousal),
                "confidence": _round(emotion_confidence),
                "policy": (
                    "정서 추정은 응답 전략 선택에만 사용하며 사용자의 직접 표현이 항상 우선합니다."
                ),
            },
            "cards": selected,
            "response_style": response_style,
            "recommended_strategies": strategies[
                :self.config.context.recommended_strategies
            ],
            "prompt_context": prompt_context,
        }

    def _compile_prompt_context(
        self,
        cards: list[dict[str, Any]],
        bucket: str,
        strategies: list[dict[str, Any]],
        response_style: dict[str, Any],
    ) -> str:
        prompts = self.config.prompts
        lines = [
            "[JETSON 통합 파생 맥락 v1]",
            f"- 현재 응답 정책 버킷: {bucket}",
            "",
            "[공통 안전 규칙]",
        ]
        lines.extend(
            f"- {rule.strip()}"
            for rule in prompts.common_rules.splitlines()
            if rule.strip()
        )
        lines.extend(
            [
                "",
                "[현재 상태에 맞춘 응답 방향]",
            ]
        )
        lines.extend(
            f"- {instruction.strip()}"
            for instruction in prompts.bucket_instructions[bucket].splitlines()
            if instruction.strip()
        )
        if strategies:
            lines.extend(["", "[우선 응답 전략]"])
            for rank, item in enumerate(
                strategies[:self.config.context.recommended_strategies],
                start=1,
            ):
                strategy = item["strategy"]
                lines.append(f"{rank}. {strategy}")
                lines.extend(
                    f"   - {instruction.strip()}"
                    for instruction in prompts.strategy_instructions[strategy].splitlines()
                    if instruction.strip()
                )
        lines.extend(
            [
                "",
                "[\uac1c\uc778\ud654 \uc751\ub2f5 \uc2a4\ud0c0\uc77c]",
            ]
        )
        lines.extend(
            f"- {instruction}"
            for instruction in self._render_style_instructions(response_style)
        )
        if not cards:
            lines.extend(
                [
                    "",
                    "[선택된 맥락]",
                    "- 사용할 만큼 신뢰할 수 있고 관련 있는 누적 맥락이 없습니다.",
                ]
            )
        else:
            lines.extend(["", "[선택된 맥락]"])
            for index, card in enumerate(cards, start=1):
                lines.append(
                    f"{index}. domain={card['domain']}; "
                    f"confidence={float(card['confidence']):.2f}; "
                    f"observation={card['observation']}"
                )
                warnings = card.get("quality", {}).get("warnings", [])
                if warnings:
                    lines.append(f"   한계: {' | '.join(warnings)}")
        return "\n".join(lines)

    def is_user_asleep(self, now_ms: int | None = None) -> dict[str, Any]:
        timestamp = self.clock() if now_ms is None else now_ms
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT occurred_at, confidence, coverage, payload_json
                FROM analysis_events
                WHERE domain = 'SLEEP_AND_ROUTINE'
                  AND event_type = 'sleep_state'
                  AND quality_status = 'VALID'
                  AND occurred_at <= ?
                  AND occurred_at >= ?
                  AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY occurred_at DESC
                LIMIT 1
                """,
                (
                    timestamp,
                    timestamp - int(
                        self.config.learning.sleep_evidence_max_age_minutes
                        * MILLIS_PER_MINUTE
                    ),
                    timestamp,
                ),
            ).fetchone()
        if row is None:
            return {
                "asleep": False,
                "reason": "no_recent_sleep_evidence",
                "confidence": 0.0,
            }
        event = json.loads(row["payload_json"])
        state = str(event["metrics"].get("state", "")).upper()
        confidence = float(row["confidence"]) * float(row["coverage"])
        asleep = (
            state in {"ASLEEP", "LIKELY_ASLEEP"}
            and confidence >= self.config.learning.sleep_min_confidence
        )
        return {
            "asleep": asleep,
            "reason": "recent_sleep_state" if asleep else "sleep_evidence_below_threshold",
            "state": state,
            "confidence": _round(confidence),
            "occurred_at": int(row["occurred_at"]),
        }

    def run_nightly_adaptation(
        self,
        force: bool = False,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        timestamp = self.clock() if now_ms is None else now_ms
        sleep = self.is_user_asleep(timestamp)
        if not force and not sleep["asleep"]:
            return {
                "trained": False,
                "reason": "user_not_asleep",
                "sleep": sleep,
            }

        with self._connect() as connection:
            last_run_row = connection.execute(
                """
                SELECT state_value
                FROM system_state
                WHERE state_key = 'last_nightly_adaptation_at'
                """
            ).fetchone()
            last_run = int(last_run_row["state_value"]) if last_run_row else 0
            min_training_interval_ms = int(
                self.config.learning.min_training_interval_hours * MILLIS_PER_HOUR
            )
            if not force and timestamp - last_run < min_training_interval_ms:
                return {
                    "trained": False,
                    "reason": "training_interval_not_elapsed",
                    "last_run_at": last_run,
                    "sleep": sleep,
                }

            outcomes = connection.execute(
                """
                SELECT session_id, bucket, strategies_json, style_json, reward
                FROM session_outcomes
                WHERE trained = 0
                ORDER BY ended_at ASC
                LIMIT ?
                """,
                (self.config.learning.max_outcomes_per_run,),
            ).fetchall()
            if not outcomes:
                return {
                    "trained": False,
                    "reason": "no_untrained_session_outcomes",
                    "sleep": sleep,
                }

            rewards: list[float] = []
            affected_buckets: set[str] = set()
            pending_deltas: dict[tuple[str, str], float] = {}
            sample_increments: dict[tuple[str, str], int] = {}
            pending_style_deltas: dict[tuple[str, str], float] = {}
            style_sample_increments: dict[tuple[str, str], int] = {}
            for outcome in outcomes:
                reward = float(outcome["reward"])
                strategies = json.loads(outcome["strategies_json"])
                per_strategy_delta = (
                    self.config.learning.learning_rate
                    * reward
                    / max(1, len(strategies))
                )
                for strategy in strategies:
                    key = (outcome["bucket"], strategy)
                    pending_deltas[key] = pending_deltas.get(key, 0.0) + per_strategy_delta
                    sample_increments[key] = sample_increments.get(key, 0) + 1
                response_style = json.loads(outcome["style_json"] or "{}")
                if response_style:
                    for dimension in response_style["explored_dimensions"]:
                        applied = float(response_style["values"][dimension])
                        baseline = float(
                            response_style["baseline_values"][dimension]
                        )
                        difference = applied - baseline
                        if abs(difference) <= 0.000001:
                            continue
                        direction = 1.0 if difference > 0.0 else -1.0
                        key = (outcome["bucket"], dimension)
                        style_delta = (
                            self.config.style_learning.learning_rate
                            * reward
                            * direction
                        )
                        pending_style_deltas[key] = (
                            pending_style_deltas.get(key, 0.0) + style_delta
                        )
                        style_sample_increments[key] = (
                            style_sample_increments.get(key, 0) + 1
                        )
                connection.execute(
                    "UPDATE session_outcomes SET trained = 1 WHERE session_id = ?",
                    (outcome["session_id"],),
                )
                rewards.append(reward)
                affected_buckets.add(outcome["bucket"])

            # A large backlog must not create an abrupt overnight policy jump.
            # Accumulate all evidence, but cap each strategy's net movement in
            # one sleep-gated run.
            for (bucket, strategy), raw_delta in pending_deltas.items():
                row = connection.execute(
                    """
                    SELECT weight, samples
                    FROM policy_weights
                    WHERE bucket = ? AND strategy = ?
                    """,
                    (bucket, strategy),
                ).fetchone()
                bounded_delta = max(
                    -self.config.learning.max_delta_per_run,
                    min(self.config.learning.max_delta_per_run, raw_delta),
                )
                new_weight = max(
                    self.config.learning.min_weight,
                    min(
                        self.config.learning.max_weight,
                        float(row["weight"]) + bounded_delta,
                    ),
                )
                connection.execute(
                    """
                    UPDATE policy_weights
                    SET weight = ?, samples = ?, updated_at = ?
                    WHERE bucket = ? AND strategy = ?
                    """,
                    (
                        new_weight,
                        int(row["samples"]) + sample_increments[(bucket, strategy)],
                        timestamp,
                        bucket,
                        strategy,
                    ),
                )

            # Keep each bucket centered at one so updates remain small and
            # comparable across long-running installations.
            for bucket in affected_buckets:
                rows = connection.execute(
                    """
                    SELECT strategy, weight
                    FROM policy_weights
                    WHERE bucket = ?
                    """,
                    (bucket,),
                ).fetchall()
                mean_weight = statistics.mean(float(row["weight"]) for row in rows)
                for row in rows:
                    normalized = float(row["weight"]) / mean_weight
                    normalized = max(
                        self.config.learning.min_weight,
                        min(self.config.learning.max_weight, normalized),
                    )
                    connection.execute(
                        """
                        UPDATE policy_weights
                        SET weight = ?, updated_at = ?
                        WHERE bucket = ? AND strategy = ?
                        """,
                        (normalized, timestamp, bucket, row["strategy"]),
                    )

            affected_style_dimensions: set[tuple[str, str]] = set()
            for (bucket, dimension), raw_delta in pending_style_deltas.items():
                row = connection.execute(
                    """
                    SELECT value, samples
                    FROM policy_style_values
                    WHERE bucket = ? AND dimension = ?
                    """,
                    (bucket, dimension),
                ).fetchone()
                bounded_delta = max(
                    -self.config.style_learning.max_delta_per_run,
                    min(
                        self.config.style_learning.max_delta_per_run,
                        raw_delta,
                    ),
                )
                new_value = max(
                    self.config.style_learning.min_value,
                    min(
                        self.config.style_learning.max_value,
                        float(row["value"]) + bounded_delta,
                    ),
                )
                connection.execute(
                    """
                    UPDATE policy_style_values
                    SET value = ?, samples = ?, updated_at = ?
                    WHERE bucket = ? AND dimension = ?
                    """,
                    (
                        new_value,
                        int(row["samples"])
                        + style_sample_increments[(bucket, dimension)],
                        timestamp,
                        bucket,
                        dimension,
                    ),
                )
                affected_style_dimensions.add((bucket, dimension))
            snapshot = self._policy_snapshot(connection)
            style_snapshot = self._style_policy_snapshot(connection)
            mean_reward = statistics.mean(rewards)
            cursor = connection.execute(
                """
                INSERT INTO policy_versions(
                    created_at, reason, trained_sessions, mean_reward,
                    weights_json, style_json
                ) VALUES (?, 'sleep_adaptation', ?, ?, ?, ?)
                """,
                (
                    timestamp,
                    len(outcomes),
                    mean_reward,
                    json.dumps(snapshot, sort_keys=True),
                    json.dumps(style_snapshot, sort_keys=True),
                ),
            )
            connection.execute(
                """
                INSERT OR REPLACE INTO system_state(state_key, state_value, updated_at)
                VALUES ('last_nightly_adaptation_at', ?, ?)
                """,
                (str(timestamp), timestamp),
            )
            self._audit(
                connection,
                "nightly_adaptation_completed",
                str(cursor.lastrowid),
                {
                    "trained_sessions": len(outcomes),
                    "mean_reward": _round(mean_reward),
                    "affected_buckets": sorted(affected_buckets),
                    "forced": force,
                    "max_policy_delta_per_run": self.config.learning.max_delta_per_run,
                    "affected_style_dimensions": [
                        f"{bucket}.{dimension}"
                        for bucket, dimension in sorted(
                            affected_style_dimensions
                        )
                    ],
                    "max_style_delta_per_run": (
                        self.config.style_learning.max_delta_per_run
                    ),
                },
                timestamp,
            )
            version = int(cursor.lastrowid)

        return {
            "trained": True,
            "reason": "forced" if force else "user_asleep",
            "policy_version": version,
            "trained_sessions": len(outcomes),
            "mean_reward": _round(mean_reward),
            "affected_buckets": sorted(affected_buckets),
            "max_policy_delta_per_run": self.config.learning.max_delta_per_run,
            "affected_style_dimensions": [
                f"{bucket}.{dimension}"
                for bucket, dimension in sorted(affected_style_dimensions)
            ],
            "max_style_delta_per_run": (
                self.config.style_learning.max_delta_per_run
            ),
            "sleep": sleep,
        }

    def cleanup_expired(self, now_ms: int | None = None) -> dict[str, int]:
        timestamp = self.clock() if now_ms is None else now_ms
        event_cutoff = timestamp - int(
            self.config.retention.analysis_events_days * MILLIS_PER_DAY
        )
        card_cutoff = timestamp - int(
            self.config.retention.context_cards_days * MILLIS_PER_DAY
        )
        audit_cutoff = timestamp - int(
            self.config.retention.audit_log_days * MILLIS_PER_DAY
        )
        with self._connect() as connection:
            expired_events = connection.execute(
                """
                DELETE FROM analysis_events
                WHERE occurred_at < ?
                   OR (expires_at IS NOT NULL AND expires_at < ?)
                """,
                (
                    event_cutoff,
                    timestamp - int(
                        self.config.retention.expired_event_grace_days
                        * MILLIS_PER_DAY
                    ),
                ),
            ).rowcount
            expired_cards = connection.execute(
                "DELETE FROM context_cards WHERE expires_at < ? OR generated_at < ?",
                (timestamp, card_cutoff),
            ).rowcount
            expired_audit = connection.execute(
                "DELETE FROM audit_log WHERE created_at < ?",
                (audit_cutoff,),
            ).rowcount
        return {
            "events": expired_events,
            "context_cards": expired_cards,
            "audit_rows": expired_audit,
        }

    def health_status(self, now_ms: int | None = None) -> dict[str, Any]:
        timestamp = self.clock() if now_ms is None else now_ms
        with self._connect() as connection:
            event_count = int(connection.execute("SELECT COUNT(*) FROM analysis_events").fetchone()[0])
            summary_count = int(connection.execute("SELECT COUNT(*) FROM derived_summaries").fetchone()[0])
            outcome_count = int(connection.execute("SELECT COUNT(*) FROM session_outcomes").fetchone()[0])
            pending_outcomes = int(
                connection.execute("SELECT COUNT(*) FROM session_outcomes WHERE trained = 0").fetchone()[0]
            )
            version = int(
                connection.execute("SELECT MAX(version_id) FROM policy_versions").fetchone()[0] or 0
            )
            last_event = connection.execute(
                "SELECT MAX(received_at) FROM analysis_events"
            ).fetchone()[0]
        return {
            "status": "ok",
            "schema": "adaptive-context-v1",
            "database_ready": self.database_path.exists(),
            "events": event_count,
            "derived_summaries": summary_count,
            "session_outcomes": outcome_count,
            "pending_training_outcomes": pending_outcomes,
            "policy_version": version,
            "last_event_received_at": last_event,
            "sleep": self.is_user_asleep(timestamp),
            "adaptive_config": self.config.health_summary(),
        }


class NightlyAdaptationWorker:
    """Small background loop used by the HTTP service."""

    def __init__(
        self,
        engine: ContextEngine,
        interval_seconds: float = 60.0,
    ):
        self.engine = engine
        self.interval_seconds = max(1.0, float(interval_seconds))
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._thread = threading.Thread(
            target=self._run,
            name="onmom-nightly-adaptation",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=min(5.0, self.interval_seconds + 1.0))

    def _run(self) -> None:
        while not self._stop_event.wait(self.interval_seconds):
            try:
                result = self.engine.run_nightly_adaptation()
                if result.get("trained"):
                    self.engine.cleanup_expired()
            except Exception:
                # The HTTP service must stay available. Detailed failures are
                # visible through manual adaptation and service stderr.
                continue

