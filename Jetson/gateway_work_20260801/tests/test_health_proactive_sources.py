from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone

from app.health_proactive.sources import UnifiedJetsonDataReader


UTC = timezone.utc


def _room_database(path) -> None:
    with sqlite3.connect(path) as connection:
        connection.executescript(
            """
            CREATE TABLE room_frames (
                id INTEGER PRIMARY KEY,
                observed_at_ms INTEGER NOT NULL,
                observed_at TEXT NOT NULL,
                target_observed INTEGER NOT NULL,
                target_analysis_usable INTEGER NOT NULL,
                target_identity_confidence REAL
            );
            CREATE TABLE room_observations (
                id INTEGER PRIMARY KEY,
                frame_id INTEGER NOT NULL,
                subject_id TEXT NOT NULL,
                track_id INTEGER NOT NULL,
                posture TEXT NOT NULL,
                motion TEXT NOT NULL,
                posture_duration_sec REAL NOT NULL,
                inactivity_duration_sec REAL NOT NULL,
                posture_confidence REAL NOT NULL,
                keypoint_confidence REAL NOT NULL,
                frame_brightness REAL NOT NULL,
                lighting_state TEXT NOT NULL,
                analysis_status TEXT NOT NULL
            );
            CREATE TABLE room_events (
                id INTEGER PRIMARY KEY,
                observed_at TEXT NOT NULL,
                event_type TEXT NOT NULL
            );
            """
        )
        frames = [
            (1, 1_785_456_000_000, "2026-07-31T00:00:00+00:00", 1, 1, 0.95),
            (2, 1_785_456_001_000, "2026-07-31T00:00:01+00:00", 1, 1, 0.95),
            (3, 1_785_456_030_000, "2026-07-31T00:00:30+00:00", 1, 1, 0.95),
        ]
        connection.executemany(
            "INSERT INTO room_frames VALUES (?, ?, ?, ?, ?, ?)", frames
        )
        observations = [
            (1, 1, "subject_elder", 1, "sitting", "micro_motion", 1860, 60, 0.9, 0.8, 120, "normal", "usable"),
            (2, 2, "subject_elder", 1, "sitting", "micro_motion", 1861, 61, 0.9, 0.8, 120, "normal", "usable"),
            (3, 3, "subject_elder", 1, "sitting", "micro_motion", 1890, 90, 0.9, 0.8, 120, "normal", "usable"),
        ]
        connection.executemany(
            "INSERT INTO room_observations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            observations,
        )
        connection.execute(
            "INSERT INTO room_events VALUES (1, ?, 'PERSON_APPEAR')",
            ("2026-07-31T00:00:30+00:00",),
        )


def _home_assistant_database(path) -> None:
    with sqlite3.connect(path) as connection:
        connection.executescript(
            """
            CREATE TABLE light_events (
                event_key TEXT PRIMARY KEY,
                occurred_at TEXT NOT NULL,
                occurred_at_ts REAL NOT NULL,
                entity_id TEXT NOT NULL,
                state TEXT NOT NULL,
                friendly_name TEXT
            );
            CREATE TABLE access_events (
                event_key TEXT PRIMARY KEY,
                occurred_at TEXT NOT NULL,
                occurred_at_ts REAL NOT NULL,
                direction TEXT NOT NULL,
                authorized INTEGER,
                result TEXT NOT NULL
            );
            """
        )
        connection.executemany(
            "INSERT INTO light_events VALUES (?, ?, ?, ?, ?, ?)",
            [
                ("l1", "2026-07-30T13:00:00Z", 1_785_416_400, "light.cimsil_main", "on", "침실"),
                ("l2", "2026-07-30T14:00:00Z", 1_785_420_000, "light.cimsil_main", "off", "침실"),
            ],
        )
        connection.executemany(
            "INSERT INTO access_events VALUES (?, ?, ?, ?, ?, ?)",
            [
                ("a1", "2026-07-30T01:00:00Z", 1_785_373_200, "EXIT", 1, "button"),
                ("a2", "2026-07-30T01:00:20Z", 1_785_373_220, "EXIT", 1, "button"),
                ("a3", "2026-07-30T02:00:00Z", 1_785_376_800, "ENTER", 1, "rfid"),
            ],
        )


def test_reads_actual_jetson_shapes_and_excludes_unused_sources(tmp_path) -> None:
    room = tmp_path / "room.sqlite3"
    home = tmp_path / "home.sqlite3"
    derived = tmp_path / "latest.json"
    calendar = tmp_path / "calendar.json"
    _room_database(room)
    _home_assistant_database(home)
    now = datetime(2026, 7, 31, 0, 1, tzinfo=UTC)
    derived.write_text(
        json.dumps(
            {
                "generated_at": now.timestamp() * 1000,
                "sleep_profile": {
                    "average_bedtime": "22:30",
                    "sample_size": 7,
                },
                "summaries": [
                    {
                        "category": "HEALTH",
                        "summary": "- 2026.07.29: 걸음 2,315\n- 2026.07.28: 걸음 6,142",
                    },
                    {
                        "category": "PHENOTYPE",
                        "summary": (
                            "이번 주 통화 6회, 지난 주 통화 12회\n"
                            "- 최근 7일 일평균 스크린타임 400분\n"
                            "- 일평균 야간 사용 45분\n"
                            "- 최장 연속 세션 70분\n"
                            "- 주간 스크린타임 변화 50.0%"
                        ),
                    },
                    {"category": "GALLERY", "summary": "사용하면 안 되는 자료"},
                ],
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    calendar.write_text(
        json.dumps(
            {
                "events": [
                    {
                        "id": "visit-1",
                        "category": "진료",
                        "start": (now + timedelta(hours=2)).isoformat(),
                    }
                ]
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    bundle = UnifiedJetsonDataReader(room, home, derived, calendar, raw_exports_path=tmp_path / "empty_raw_exports").collect(now)

    metrics = {item.metric for item in bundle.daily_metrics}
    comparisons = {item.metric for item in bundle.comparisons}
    assert {"max_sitting_bout_minutes", "steps", "outing_count"} <= metrics
    assert {
        "call_count_week",
        "phone_screen_daily_minutes",
        "phone_night_daily_minutes",
    } <= comparisons
    assert bundle.context["current_posture"] == "sitting"
    assert bundle.context["current_posture_bout_minutes"] >= 30
    assert bundle.context["morning_first_seen"] is True
    assert bundle.context["expected_sleep_time"] == "22:30"
    assert bundle.context["sleep_profile"]["sample_size"] == 7
    assert bundle.context["calendar_upcoming"][0]["category"] == "진료"
    assert bundle.source_status["android_summaries"]["ignored_categories"] == ["GALLERY"]
    assert bundle.context["excluded_sources"] == [
        "voice_emotion",
        "rppg",
        "facial_expression",
        "gallery",
    ]


def test_raw_sleep_sessions_add_profile_and_timing_metrics(tmp_path) -> None:
    raw_exports = tmp_path / "raw_exports"
    raw_exports.mkdir()
    starts = [
        datetime(2026, 7, 20, 14, 0, tzinfo=UTC),
        datetime(2026, 7, 21, 14, 5, tzinfo=UTC),
        datetime(2026, 7, 22, 13, 55, tzinfo=UTC),
        datetime(2026, 7, 23, 14, 3, tzinfo=UTC),
        datetime(2026, 7, 24, 13, 58, tzinfo=UTC),
        datetime(2026, 7, 25, 22, 0, tzinfo=UTC),
    ]
    raw_exports.joinpath("export_1.json").write_text(
        json.dumps(
            {
                "raw_sleep_sessions": [
                    {
                        "startTimeMs": int(start.timestamp() * 1000),
                        "endTimeMs": int((start + timedelta(hours=8)).timestamp() * 1000),
                    }
                    for start in starts
                ]
            }
        ),
        encoding="utf-8",
    )
    reader = UnifiedJetsonDataReader(
        tmp_path / "room.sqlite3",
        tmp_path / "home.sqlite3",
        tmp_path / "latest.json",
        tmp_path / "calendar.json",
        raw_exports_path=raw_exports,
    )

    bundle = reader.collect(datetime(2026, 7, 27, tzinfo=UTC))

    assert bundle.context["expected_sleep_time"] == "23:00"
    assert bundle.context["sleep_profile"]["sample_size"] == 6
    assert {item.metric for item in bundle.daily_metrics} >= {"sleep_bedtime_deviation_minutes"}