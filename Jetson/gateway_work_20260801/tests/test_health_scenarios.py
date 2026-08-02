from __future__ import annotations

import asyncio
import json
import sqlite3
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

from app.health_proactive.scenario_datasets import ScenarioDatasetReader
from app.health_proactive.service import HealthProactiveService
from app.schemas import DatasetSelectionRequest


UTC = timezone.utc


def _scenario(root, dataset_id="02_activity_posture_decline"):
    root.mkdir(exist_ok=True)
    root.joinpath("scenario_index.json").write_text(
        json.dumps({"scenarios": [{"scenario": dataset_id, "description": "test"}]}),
        encoding="utf-8",
    )
    path = root / f"{dataset_id}.db"
    with sqlite3.connect(path) as connection:
        connection.execute(
            """CREATE TABLE analysis_events (
                domain TEXT, event_type TEXT, occurred_at INTEGER,
                quality_status TEXT, confidence REAL, coverage REAL, payload_json TEXT
            )"""
        )
        start = datetime(2026, 7, 20, 10, tzinfo=UTC)
        rows = []
        for index, value in enumerate((16, 17, 18, 16, 70)):
            at = start + timedelta(days=index)
            rows.append((
                "SEDENTARY_AND_POSTURE", "posture_window", int(at.timestamp() * 1000),
                "VALID", 0.92, 0.92, json.dumps({"metrics": {"slouch_minutes": value}}),
            ))
        rows.append((
            "FACIAL_EXPRESSION", "face_emotion_window", int(start.timestamp() * 1000),
            "VALID", 0.92, 0.92, json.dumps({"metrics": {"arousal": 0.9}}),
        ))
        rows.append((
            "PHYSICAL_ACTIVITY", "daily_features", int(start.timestamp() * 1000),
            "SUSPECT", 0.92, 0.92, json.dumps({"metrics": {"steps": 10}}),
        ))
        connection.executemany("INSERT INTO analysis_events VALUES (?, ?, ?, ?, ?, ?, ?)", rows)
    return dataset_id


def test_scenario_reader_uses_only_allowed_valid_metrics(tmp_path) -> None:
    dataset_id = _scenario(tmp_path)
    bundle = ScenarioDatasetReader(tmp_path).collect(dataset_id)

    assert [item.metric for item in bundle.daily_metrics] == ["slouch_minutes"] * 5
    assert bundle.source_status["scenario_dataset"]["accepted_events"] == 5
    assert bundle.source_status["scenario_dataset"]["ignored_events"] == 2


class _Llm:
    def runtime_status(self):
        return {"state": "sleeping"}


class _Conversations:
    def latest_session_id(self):
        return "session-1"

    def set_session_context(self, *args):
        pass


class _Inbox:
    active_session_id = "session-1"


def test_selected_scenario_blocks_automatic_delivery(tmp_path) -> None:
    dataset_id = _scenario(tmp_path / "scenarios")
    settings = SimpleNamespace(
        health_monitor_database_path=tmp_path / "health.sqlite3",
        health_scenario_dataset_root=tmp_path / "scenarios",
        room_database_path=tmp_path / "room.sqlite3",
        home_assistant_database_path=tmp_path / "home.sqlite3",
        derived_insights_path=tmp_path / "derived.json",
        calendar_events_path=tmp_path / "calendar.json",
        health_monitor_timezone="Asia/Seoul",
        proactive_max_nonurgent_per_day=4,
        health_monitor_enabled=True,
        health_monitor_startup_delay_seconds=1,
        health_monitor_interval_seconds=60,
        health_analysis_window_days=21,
    )
    service = HealthProactiveService(settings, _Llm(), _Conversations(), _Inbox())
    service.initialize()
    service.now_provider = lambda: datetime(2026, 7, 25, 12, tzinfo=UTC)

    async def run():
        selected = await service.select_dataset(dataset_id)
        checked = await service.run_once(deliver=True)
        return selected, checked

    selected, checked = asyncio.run(run())

    assert selected["active_dataset"]["id"] == dataset_id
    assert checked["delivered"] is False
    assert checked["delivery_reason"] == "scenario_auto_delivery_blocked"


def test_dataset_selection_accepts_dashboard_alias() -> None:
    canonical = DatasetSelectionRequest.model_validate({"dataset": "default"})
    dashboard = DatasetSelectionRequest.model_validate(
        {"dataset_id": "01_stable_baseline"}
    )
    assert canonical.dataset == "default"
    assert dashboard.dataset == "01_stable_baseline"
