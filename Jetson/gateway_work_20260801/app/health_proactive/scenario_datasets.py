from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .models import DataBundle, DailyMetric
from .timezones import load_timezone


UTC = timezone.utc
DEFAULT_DATASET_ID = "default"


class ScenarioDatasetReader:
    """Read the allow-listed synthetic datasets without exposing file paths."""

    def __init__(self, root: Path, timezone_name: str = "Asia/Seoul"):
        self.root = root
        self.timezone = load_timezone(timezone_name)

    def datasets(self) -> list[dict[str, Any]]:
        items = [{
            "id": DEFAULT_DATASET_ID,
            "label": "기본 실데이터",
            "description": "실제 생활 데이터 기반 건강 점검으로 돌아갑니다.",
            "synthetic": False,
        }]
        index = self._index()
        for raw in index.get("scenarios", []):
            if not isinstance(raw, dict):
                continue
            dataset_id = raw.get("scenario")
            if not isinstance(dataset_id, str) or not self._database_path(dataset_id).is_file():
                continue
            items.append({
                "id": dataset_id,
                "label": str(raw.get("label") or dataset_id.replace("_", " ")),
                "description": str(raw.get("description") or "합성 건강 점검 시나리오입니다."),
                "synthetic": True,
            })
        return items

    def collect(self, dataset_id: str) -> DataBundle:
        if dataset_id == DEFAULT_DATASET_ID:
            raise ValueError("기본 데이터셋은 시나리오 DB로 읽을 수 없습니다.")
        if dataset_id not in {item["id"] for item in self.datasets()}:
            raise ValueError("지원하지 않는 데이터셋입니다.")
        path = self._database_path(dataset_id)
        metrics: dict[tuple[str, object], tuple[float, float, datetime]] = {}
        rejected = 0
        accepted = 0
        connection: sqlite3.Connection | None = None
        try:
            connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
            rows = connection.execute(
                """
                SELECT domain, event_type, occurred_at, quality_status,
                       confidence, coverage, payload_json
                FROM analysis_events ORDER BY occurred_at
                """
            ).fetchall()
        except (OSError, sqlite3.Error) as exc:
            raise ValueError("시나리오 데이터를 읽을 수 없습니다.") from exc

        finally:
            if connection is not None:
                connection.close()
        for domain, event_type, occurred_at, quality, confidence, coverage, raw in rows:
            if quality != "VALID" or float(confidence) < 0.5 or float(coverage) < 0.5:
                rejected += 1
                continue
            try:
                payload = json.loads(raw)
                observed_at = datetime.fromtimestamp(float(occurred_at) / 1000, UTC)
            except (TypeError, ValueError, OSError, json.JSONDecodeError):
                rejected += 1
                continue
            mapped = self._map_event(str(domain), str(event_type), payload, observed_at)
            if mapped is None:
                rejected += 1
                continue
            metric, value = mapped
            day = observed_at.astimezone(self.timezone).date()
            key = (metric, day)
            prior = metrics.get(key)
            if prior is None or observed_at >= prior[2]:
                metrics[key] = (value, min(float(confidence), float(coverage)), observed_at)
            accepted += 1

        bundle = DataBundle(
            context={
                "scenario_dataset": dataset_id,
                "excluded_sources": ["voice_emotion", "rppg", "facial_expression", "gallery"],
            },
            source_status={
                "scenario_dataset": {
                    "available": True,
                    "id": dataset_id,
                    "accepted_events": accepted,
                    "ignored_events": rejected,
                }
            },
        )
        for (metric, day), (value, quality, _) in sorted(metrics.items()):
            bundle.daily_metrics.append(
                DailyMetric(metric, day, value, quality, f"scenario:{dataset_id}")
            )
        return bundle

    def _index(self) -> dict[str, Any]:
        try:
            raw = json.loads((self.root / "scenario_index.json").read_text(encoding="utf-8"))
            return raw if isinstance(raw, dict) else {}
        except (OSError, ValueError, json.JSONDecodeError):
            return {}

    def _database_path(self, dataset_id: str) -> Path:
        return self.root / f"{dataset_id}.db"

    @staticmethod
    def _map_event(
        domain: str,
        event_type: str,
        payload: dict[str, Any],
        observed_at: datetime,
    ) -> tuple[str, float] | None:
        metrics = payload.get("metrics")
        if not isinstance(metrics, dict):
            return None
        if domain == "PHYSICAL_ACTIVITY" and event_type == "daily_features":
            return _number("steps", metrics)
        if domain == "SEDENTARY_AND_POSTURE" and event_type == "posture_window":
            return _number("slouch_minutes", metrics)
        if (
            domain == "DAILY_ROUTINE"
            and event_type == "access_transition"
            and metrics.get("direction") == "ENTER"
        ):
            local = observed_at.astimezone(load_timezone("Asia/Seoul"))
            return "entry_time_minutes", float(local.hour * 60 + local.minute)
        return None


def _number(metric: str, values: dict[str, Any]) -> tuple[str, float] | None:
    try:
        value = float(values[metric])
    except (KeyError, TypeError, ValueError):
        return None
    return (metric, value) if value >= 0 else None
