#!/usr/bin/env python3
"""Create a privacy-safe, deterministic 21-day On-mom Jetson demo database."""

from __future__ import annotations

import argparse
import json
import sqlite3
from datetime import date, datetime, time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from analysis_snapshot import validate_analysis_snapshot
from context_engine import ContextEngine, MILLIS_PER_HOUR, MILLIS_PER_MINUTE, validate_analysis_event
from guardian_alert import validate_guardian_alert


KST = ZoneInfo("Asia/Seoul")
DAY_COUNT = 21


def epoch_ms(day: date, hour: int, minute: int = 0) -> int:
    return int(datetime.combine(day, time(hour, minute), KST).timestamp() * 1000)


def event(
    event_id: str,
    day: date,
    hour: int,
    *,
    source: str,
    source_family: str,
    domain: str,
    event_type: str,
    metrics: dict,
    expires_hours: int | None = None,
    confidence: float = 0.9,
    coverage: float = 0.9,
) -> dict:
    occurred_at = epoch_ms(day, hour)
    return {
        "schema_version": 1,
        "event_id": event_id,
        "source": source,
        "source_family": source_family,
        "domain": domain,
        "event_type": event_type,
        "occurred_at": occurred_at,
        "expires_at": occurred_at + expires_hours * MILLIS_PER_HOUR if expires_hours else None,
        "contains_raw_data": False,
        "metrics": metrics,
        "quality": {
            "status": "VALID",
            "confidence": confidence,
            "coverage": coverage,
            "reasons": [],
        },
    }


def snapshot(day: date, index: int, steps: int, screen_minutes: int) -> dict:
    collected_at = epoch_ms(day, 21)
    return {
        "schema_version": 1,
        "snapshot_id": f"synthetic-21d-{day.isoformat()}",
        "generated_at": collected_at,
        "producer": "onmom-android",
        "privacy_level": "STRUCTURED_FEATURES",
        "contains_raw_data": False,
        "datasets": [
            {
                "category": "HEALTH",
                "collected_at": collected_at,
                "data": {
                    "period": "DAY",
                    "period_values": {"steps": steps, "distance_km": round(steps * 0.00068, 2)},
                    "daily_values": [{"date": day.isoformat(), "values": {"steps": steps}, "excluded": []}],
                },
            },
            {
                "category": "PHENOTYPE",
                "collected_at": collected_at,
                "data": {
                    "call_pattern": {"total_calls_this_week": 3 + index % 4, "missed_call_rate": round(0.1 + (index % 3) * 0.08, 2)},
                    "app_usage_pattern": {"daily_average_screen_minutes": screen_minutes, "top_apps": [{"app_name": "Browser", "category": "OTHER", "total_time_minutes": max(25, screen_minutes // 3)}]},
                },
            },
            {
                "category": "GALLERY",
                "collected_at": collected_at,
                "data": {"analysis_count": 1 + index % 3, "positive_ratio": round(0.58 - index * 0.004, 2), "safety_cue_count": 0},
            },
            {
                "category": "VOICE_EMOTION",
                "collected_at": collected_at,
                "data": {"dominant_emotion": "NEUTRAL" if index < 14 else "LOW_ENERGY", "confidence": round(0.72 + (index % 4) * 0.04, 2)},
            },
        ],
    }


def create_database(output: Path, end_day: date) -> dict:
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite existing file: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    start_day = end_day - timedelta(days=DAY_COUNT - 1)
    clock = [epoch_ms(start_day, 0)]
    engine = ContextEngine(output, clock=lambda: clock[0])

    for index in range(DAY_COUNT):
        day = start_day + timedelta(days=index)
        steps = 6900 + ((index * 431) % 2200) - (900 if index in {12, 13, 14} else 0)
        screen_minutes = 135 + (index % 5) * 14 + (35 if index >= 14 else 0)
        slouch = 19 + (index % 4) * 3 + (16 if index >= 14 else 0)
        valence = round(0.18 - index * 0.025, 2)
        arousal = round(0.32 + (index % 4) * 0.08, 2)
        records = [
            event(f"synthetic-sleep-{day}", day, 2, source="home_assistant", source_family="sleep.presence", domain="SLEEP_AND_ROUTINE", event_type="sleep_state", metrics={"state": "ASLEEP"}, expires_hours=8, confidence=0.95, coverage=0.95),
            event(f"synthetic-exit-{day}", day, 8, source="home_assistant.rfid", source_family="presence.access", domain="DAILY_ROUTINE", event_type="access_transition", metrics={"direction": "EXIT", "zone": "front_door"}, confidence=0.92, coverage=0.95),
            event(f"synthetic-health-{day}", day, 18, source="android.health", source_family="activity.motion", domain="PHYSICAL_ACTIVITY", event_type="daily_features", metrics={"steps": steps, "distance_km": round(steps * 0.00068, 2), "exercise_minutes": 28 + index % 25, "active_calories_kcal": 260 + index * 4, "total_calories_kcal": 1650 + index * 8}, confidence=0.91, coverage=0.93),
            event(f"synthetic-enter-{day}", day, 18, source="home_assistant.rfid", source_family="presence.access", domain="DAILY_ROUTINE", event_type="access_transition", metrics={"direction": "ENTER", "zone": "front_door"}, confidence=0.92, coverage=0.95),
            event(f"synthetic-activity-{day}", day, 20, source="hub.camera", source_family="activity.camera", domain="PHYSICAL_ACTIVITY", event_type="camera_activity_window", metrics={"observed_seconds": 600, "presence_ratio": 0.88, "active_ratio": round(0.22 + (index % 3) * 0.05, 2), "mean_motion_score": round(0.31 + (index % 4) * 0.04, 2)}, expires_hours=6, confidence=0.82, coverage=0.77),
            event(f"synthetic-posture-{day}", day, 20, source="hub.camera", source_family="posture.camera", domain="SEDENTARY_AND_POSTURE", event_type="posture_window", metrics={"observed_seconds": 600, "upright_ratio": round(0.67 - slouch / 100, 2), "slouch_ratio": round(slouch / 100, 2), "away_ratio": 0.14, "upright_minutes": 402, "slouch_minutes": slouch}, expires_hours=6, confidence=0.84, coverage=0.8),
            event(f"synthetic-face-{day}", day, 20, source="hub.camera", source_family="emotion.face", domain="FACIAL_EXPRESSION", event_type="face_emotion_window", metrics={"valence": valence, "arousal": arousal, "face_observation_seconds": 540}, expires_hours=1, confidence=0.78, coverage=0.75),
            event(f"synthetic-rppg-{day}", day, 20, source="hub.camera", source_family="physiology.heart_rate", domain="PHYSIOLOGICAL_STATE", event_type="rppg_window", metrics={"heart_rate_bpm": 68 + index % 8, "signal_quality": 0.38 if index == 9 else 0.74, "measurement_seconds": 42}, expires_hours=1, confidence=0.8, coverage=0.78),
            event(f"synthetic-voice-{day}", day, 21, source="android.voice", source_family="emotion.voice", domain="VOICE_EMOTION", event_type="voice_emotion_summary", metrics={"valence": valence, "arousal": arousal, "dominant_emotion": "NEUTRAL" if index < 14 else "LOW_ENERGY"}, expires_hours=12, confidence=0.79, coverage=0.74),
        ]
        for record in records:
            clock[0] = record["occurred_at"] + 2 * MILLIS_PER_MINUTE
            engine.ingest_event(validate_analysis_event(record))

        clock[0] = epoch_ms(day, 21, 5)
        engine.ingest_analysis_snapshot(validate_analysis_snapshot(snapshot(day, index, steps, screen_minutes)))
        engine.build_context_bundle(now_ms=clock[0], current_valence=valence, current_arousal=arousal)

        if index % 2 == 0:
            before_valence = min(-0.12, valence - 0.22)
            outcome = {
                "schema_version": 1,
                "session_id": f"synthetic-session-{day}",
                "started_at": epoch_ms(day, 21, 15),
                "ended_at": epoch_ms(day, 21, 35),
                "before": {"valence": before_valence, "arousal": min(0.9, arousal + 0.2), "confidence": 0.82},
                "after": {"valence": min(0.5, before_valence + 0.2), "arousal": max(0.2, arousal - 0.06), "confidence": 0.82},
                "strategies": ["EMPATHIC_REFLECTION", "GROUNDING"],
                "explicit_feedback": 0.5 if index < 14 else 0.25,
                "contains_raw_data": False,
            }
            clock[0] = outcome["ended_at"] + MILLIS_PER_MINUTE
            engine.record_session_outcome(outcome)
            engine.run_nightly_adaptation(force=True, now_ms=clock[0])

        if index in {6, 15}:
            alert = {
                "schema_version": 1,
                "alert_id": f"synthetic-alert-{day}",
                "occurred_at": epoch_ms(day, 22),
                "severity": "CAUTION",
                "category": "HEALTH",
                "title": "Synthetic trend review",
                "message": "Derived activity and posture trend requires a routine check-in.",
                "source": "synthetic.generator",
                "contains_raw_data": False,
            }
            clock[0] = alert["occurred_at"] + MILLIS_PER_MINUTE
            engine.ingest_guardian_alert(validate_guardian_alert(alert))
            if index == 6:
                clock[0] += 15 * MILLIS_PER_MINUTE
                engine.acknowledge_guardian_alert(alert["alert_id"])

    with sqlite3.connect(output) as connection:
        tables = [row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        counts = {table: connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in tables}
    return {"database": str(output), "synthetic": True, "timezone": "Asia/Seoul", "start_date": start_day.isoformat(), "end_date": end_day.isoformat(), "days": DAY_COUNT, "table_counts": counts}


def main() -> None:
    parser = argparse.ArgumentParser(description="Create a 21-day synthetic On-mom Jetson SQLite log database")
    parser.add_argument("--end-date", type=date.fromisoformat, default=date.today())
    parser.add_argument("--output", type=Path, default=Path("data/onmom_context_21days_synthetic.db"))
    parser.add_argument("--report", type=Path, default=Path("data/onmom_context_21days_synthetic_report.json"))
    args = parser.parse_args()
    report = create_database(args.output, args.end_date)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
