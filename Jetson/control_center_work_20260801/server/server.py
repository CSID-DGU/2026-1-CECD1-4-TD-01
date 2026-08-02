#!/usr/bin/env python3
"""OnMom unified operational dashboard server.

Serves the built dashboard, reads the existing derived-only Jetson databases,
proxies the room-vision MJPEG stream, and exposes allow-listed service actions.
"""

from __future__ import annotations

import argparse
from collections import deque
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime, timezone
from functools import lru_cache
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import threading
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


MERGE_ROOT = Path(os.environ.get("ONMOM_MERGE_ROOT", "/home/iot/onmom_mergeVer"))
DATA_ROOT = Path(os.environ.get("ONMOM_DATA_ROOT", str(MERGE_ROOT / "data")))
STATIC_ROOT = Path(
    os.environ.get(
        "ONMOM_CONTROL_CENTER_STATIC",
        str(Path(__file__).resolve().parents[1] / "dist" / "client"),
    )
)
ROOM_DB = DATA_ROOT / "room" / "room-analytics.sqlite3"
COUNSELING_DB = DATA_ROOT / "counseling" / "counseling-analytics.sqlite3"
COUNSELING_APP_DB = DATA_ROOT / "counseling" / "onmom.db"
CONTEXT_DB = DATA_ROOT / "insights" / "onmom_context.db"
MANAGEMENT_DB = DATA_ROOT / "management" / "onmom-control-center.sqlite3"
HOME_ASSISTANT_DB = (
    DATA_ROOT / "home_assistant" / "onmom-home-assistant-history.sqlite3"
)
ESP12_LITE_ENTITY = os.environ.get(
    "ONMOM_ESP12_LITE_ENTITY",
    "light.cimsil_esp12n_light_light",
)
ROOM_EVENTS_JSONL = DATA_ROOT / "room" / "jetson-target-events.jsonl"
COUNSELING_LATEST = DATA_ROOT / "counseling" / "counseling_vision_latest.json"
COUNSELING_SIGNALS_LOG = DATA_ROOT / "counseling" / "counseling_signals.jsonl"
COUNSELING_GATEWAY_URL = os.environ.get(
    "ONMOM_COUNSELING_GATEWAY_URL",
    "http://127.0.0.1:8000",
).rstrip("/")

SERVICE_MAP = {
    "room": "onmom-room-vision.service",
    "rppg": "onmom-rppg.service",
    "insights": "onmom-derived-insights.service",
}
DOCKER_MAP = {
    "gateway": "onmom_home-gateway-1",
    "llm": "onmom_home-llama-1",
    "mediamtx": "iotcam-mediamtx-1",
    "homeassistant": "homeassistant",
    "matterbridge": "matterbridge",
}
INTEGRATION_CATEGORIES = {"access", "light", "phenotype"}
RESTART_ACTIONS = {
    "room": ("systemd", "onmom-room-vision.service"),
    "rppg": ("systemd", "onmom-rppg.service"),
    "gateway": ("docker", "onmom_home-gateway-1"),
}

RESET_CONFIRMATION = "영구삭제"
RESET_DB_TABLES: dict[Path, tuple[str, ...]] = {
    ROOM_DB: (
        "room_observations",
        "room_identity_candidates",
        "room_events",
        "face_emotion_measurements",
        "rppg_measurements",
        "room_frames",
    ),
    COUNSELING_DB: (
        "room_observations",
        "room_identity_candidates",
        "room_events",
        "face_emotion_measurements",
        "rppg_measurements",
        "room_frames",
    ),
    COUNSELING_APP_DB: ("messages", "sessions"),
    CONTEXT_DB: (
        "guardian_alerts",
        "session_outcomes",
        "context_cards",
        "derived_summaries",
        "analysis_snapshots",
        "analysis_events",
        "audit_log",
        "system_state",
    ),
    MANAGEMENT_DB: ("integration_events",),
    HOME_ASSISTANT_DB: ("light_events", "access_events"),
}
RESET_GENERATED_JSON = (
    DATA_ROOT / "counseling" / "counseling_session.json",
    DATA_ROOT / "counseling" / "counseling_signals_latest.json",
    DATA_ROOT / "counseling" / "counseling_vision_latest.json",
    DATA_ROOT / "home_assistant" / "latest.json",
    DATA_ROOT / "insights" / "latest_derived_insights.json",
    DATA_ROOT / "room" / "debug-live-summary.json",
    DATA_ROOT / "room" / "jetson-benchmark" / "summary.json",
    DATA_ROOT / "room" / "jetson-smoke" / "summary.json",
    DATA_ROOT / "room" / "jetson-target-summary.json",
)
RESET_LOG_ROOTS = (
    DATA_ROOT / "room",
    DATA_ROOT / "counseling",
    DATA_ROOT / "home_assistant",
)
RESET_BACKUP_ROOT = DATA_ROOT / "backups"
RESET_BACKUP_PREFIX = "control-center-reset-"
RESET_SYSTEMD_WRITERS = (
    "onmom-room-vision.service",
    "onmom-rppg.service",
    "onmom-derived-insights.service",
)
RESET_DOCKER_WRITERS = ("onmom_home-gateway-1",)
RESET_PRESERVED = (
    "비활성 기존 얼굴 템플릿 파일(실행·조회 안 함)",
    "모델·설정·소스 코드",
    "카메라 archive 이미지와 latest.jpg",
    "SQLite 스키마와 LLM 정책 가중치(policy_* 테이블)",
)


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def epoch_to_iso(value: int | float | None) -> str | None:
    if value is None:
        return None
    seconds = float(value)
    if seconds > 10_000_000_000:
        seconds /= 1000.0
    return datetime.fromtimestamp(seconds, tz=timezone.utc).astimezone().isoformat(
        timespec="milliseconds"
    )


def safe_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def json_object(value: object) -> dict[str, Any]:
    if not isinstance(value, str) or not value:
        return {}
    try:
        parsed = json.loads(value)
    except (TypeError, json.JSONDecodeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def http_json(url: str, timeout: float = 0.7) -> dict[str, Any]:
    try:
        request = Request(url, headers={"Accept": "application/json"})
        with urlopen(request, timeout=timeout) as response:
            value = json.load(response)
            return value if isinstance(value, dict) else {}
    except (OSError, URLError, TimeoutError, json.JSONDecodeError):
        return {}


def counseling_gateway_json(
    path: str,
    *,
    method: str = "GET",
    payload: dict[str, Any] | None = None,
    timeout: float = 3.0,
) -> dict[str, Any]:
    body = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = Request(
        f"{COUNSELING_GATEWAY_URL}{path}",
        data=body,
        headers=headers,
        method=method,
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            value = json.load(response)
            return value if isinstance(value, dict) else {}
    except HTTPError as exc:
        try:
            error_payload = json.loads(exc.read().decode("utf-8"))
            detail = error_payload.get("detail") if isinstance(error_payload, dict) else None
        except (UnicodeDecodeError, json.JSONDecodeError):
            detail = None
        raise ValueError(str(detail or f"상담 게이트웨이 HTTP {exc.code}")) from exc
    except (OSError, URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise OSError("상담 게이트웨이에 연결할 수 없습니다.") from exc


def run_command(args: list[str], timeout: float = 1.5) -> tuple[int, str]:
    try:
        result = subprocess.run(
            args,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return result.returncode, result.stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return 1, ""


@contextmanager
def database(
    path: Path, writable: bool = False
) -> Iterator[sqlite3.Connection]:
    if writable:
        connection = sqlite3.connect(path, timeout=3)
    else:
        connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=1)
    connection.row_factory = sqlite3.Row
    try:
        yield connection
    finally:
        connection.close()


def init_management_db() -> None:
    MANAGEMENT_DB.parent.mkdir(parents=True, exist_ok=True)
    with database(MANAGEMENT_DB, writable=True) as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS integration_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT NOT NULL,
                event_type TEXT NOT NULL,
                state TEXT,
                subject_id TEXT,
                source TEXT NOT NULL,
                observed_at_ms INTEGER NOT NULL,
                observed_at TEXT NOT NULL,
                payload_json TEXT NOT NULL
            )
            """
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_integration_category_time
            ON integration_events(category, observed_at_ms DESC)
            """
        )


def query_rows(path: Path, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    try:
        with database(path) as connection:
            return [dict(row) for row in connection.execute(sql, params).fetchall()]
    except (sqlite3.Error, OSError):
        return []


def scalar(path: Path, sql: str, params: tuple[Any, ...] = (), default: Any = 0) -> Any:
    if not path.is_file():
        return default
    try:
        with database(path) as connection:
            row = connection.execute(sql, params).fetchone()
            return row[0] if row is not None else default
    except (sqlite3.Error, OSError):
        return default


def service_states() -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for key, unit in SERVICE_MAP.items():
        code, output = run_command(
            [
                "systemctl",
                "--user",
                "show",
                unit,
                "--property=ActiveState,SubState,Result,NRestarts",
                "--value",
            ]
        )
        values = output.splitlines()
        active = "active" in values and "running" in values
        result[key] = {
            "active": active,
            "status": "online" if active else "warning",
            "detail": "실행 중" if active else "중지 또는 재시작 중",
            "raw": values,
            "command_ok": code == 0,
        }
    return result


def docker_states() -> dict[str, dict[str, Any]]:
    code, output = run_command(
        ["docker", "ps", "--format", "{{.Names}}|{{.Status}}"], timeout=2.0
    )
    running: dict[str, str] = {}
    if code == 0:
        for line in output.splitlines():
            name, separator, status = line.partition("|")
            if separator:
                running[name] = status
    return {
        key: {
            "active": name in running,
            "status": "online" if name in running else "warning",
            "detail": running.get(name, "컨테이너 중지"),
        }
        for key, name in DOCKER_MAP.items()
    }


def tail_json_lines(path: Path, limit: int = 160) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    items: deque[dict[str, Any]] = deque(maxlen=limit)
    try:
        with path.open("r", encoding="utf-8", errors="replace") as stream:
            for line in stream:
                try:
                    value = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(value, dict):
                    items.append(value)
    except OSError:
        return []
    return list(items)


def room_event_candidates() -> list[dict[str, Any]]:
    rows = tail_json_lines(ROOM_EVENTS_JSONL)
    access = [
        row for row in rows
        if row.get("event_type") in {"PERSON_APPEAR", "PERSON_DISAPPEAR"}
    ]
    if not access:
        return []
    file_mtime = ROOM_EVENTS_JSONL.stat().st_mtime
    numeric_times = [
        float(row["timestamp"])
        for row in rows
        if isinstance(row.get("timestamp"), (int, float))
    ]
    last_monotonic = max(numeric_times) if numeric_times else None
    result: list[dict[str, Any]] = []
    for row in reversed(access[-16:]):
        timestamp = row.get("timestamp")
        observed_at = epoch_to_iso(file_mtime)
        if last_monotonic is not None and isinstance(timestamp, (int, float)):
            observed_at = epoch_to_iso(file_mtime - (last_monotonic - float(timestamp)))
        event_type = str(row.get("event_type"))
        result.append(
            {
                "observed_at": observed_at,
                "event_type": event_type,
                "label": (
                    "사람 등장 후보"
                    if event_type == "PERSON_APPEAR"
                    else "사람 사라짐 후보 · 퇴실 아님"
                ),
                "track_id": row.get("track_id"),
                "zone": row.get("zone"),
                "confirmed": False,
            }
        )
    return result


def integration_events(category: str, limit: int = 20) -> list[dict[str, Any]]:
    return query_rows(
        MANAGEMENT_DB,
        """
        SELECT event_type, state, subject_id, source, observed_at, payload_json
        FROM integration_events
        WHERE category = ?
        ORDER BY observed_at_ms DESC
        LIMIT ?
        """,
        (category, limit),
    )


def integration_status(category: str) -> dict[str, Any]:
    events = integration_events(category)
    latest = events[0] if events else None
    connected = False
    if latest:
        try:
            age = time.time() - datetime.fromisoformat(latest["observed_at"]).timestamp()
            connected = age < 600
        except (ValueError, TypeError):
            connected = False
    return {
        "connected": connected,
        "state": latest.get("state") if latest else "unknown",
        "last_observed_at": latest.get("observed_at") if latest else None,
        "events": events,
    }


def phenotype_data() -> dict[str, Any]:
    summary_rows = query_rows(
        CONTEXT_DB,
        """
        SELECT transfer_id, updated_at, received_at, summary
        FROM derived_summaries
        WHERE UPPER(category) = 'PHENOTYPE'
        ORDER BY received_at DESC, updated_at DESC
        LIMIT 20
        """,
    )
    snapshot_rows = query_rows(
        CONTEXT_DB,
        """
        SELECT snapshot_id, generated_at, received_at, producer, payload_json
        FROM analysis_snapshots
        WHERE UPPER(categories_json) LIKE '%PHENOTYPE%'
        ORDER BY received_at DESC, generated_at DESC
        LIMIT 20
        """,
    )

    snapshots: list[dict[str, Any]] = []
    latest_data: dict[str, Any] = {}
    latest_health_data: dict[str, Any] = {}
    latest_collected_at: int | float | None = None
    for row in snapshot_rows:
        payload = json_object(row.get("payload_json"))
        datasets = payload.get("datasets")
        if not isinstance(datasets, list):
            continue
        phenotype_dataset = next(
            (
                item
                for item in datasets
                if isinstance(item, dict)
                and str(item.get("category") or "").upper() == "PHENOTYPE"
            ),
            None,
        )
        if phenotype_dataset is None:
            continue
        health_dataset = next(
            (
                item
                for item in datasets
                if isinstance(item, dict)
                and str(item.get("category") or "").upper() == "HEALTH"
            ),
            None,
        )
        data = phenotype_dataset.get("data")
        if not isinstance(data, dict):
            data = {}
        collected_at = phenotype_dataset.get("collected_at") or row.get("generated_at")
        call_pattern = data.get("call_pattern")
        app_usage = data.get("app_usage_pattern")
        calendar_pattern = data.get("calendar_pattern")
        if not isinstance(call_pattern, dict):
            call_pattern = {}
        if not isinstance(app_usage, dict):
            app_usage = {}
        if not isinstance(calendar_pattern, dict):
            calendar_pattern = {}
        health_data = health_dataset.get("data") if isinstance(health_dataset, dict) else {}
        if not isinstance(health_data, dict):
            health_data = {}
        health_values = health_data.get("period_values")
        if not isinstance(health_values, dict):
            health_values = {}
        snapshots.append(
            {
                "snapshot_id": row.get("snapshot_id"),
                "observed_at": epoch_to_iso(collected_at),
                "received_at": epoch_to_iso(row.get("received_at")),
                "producer": row.get("producer") or "onmom-android",
                "calls_this_week": call_pattern.get("total_calls_this_week"),
                "unique_contacts": call_pattern.get("unique_contacts_this_week"),
                "screen_minutes": app_usage.get("daily_average_screen_minutes"),
                "late_night_minutes": app_usage.get(
                    "daily_average_late_night_minutes"
                ),
                "upcoming_events": calendar_pattern.get("upcoming_event_count"),
                "steps": health_values.get("steps"),
            }
        )
        if not latest_data:
            latest_data = data
            latest_health_data = health_data
            latest_collected_at = collected_at

    call_pattern = latest_data.get("call_pattern")
    app_usage = latest_data.get("app_usage_pattern")
    calendar_pattern = latest_data.get("calendar_pattern")
    if not isinstance(call_pattern, dict):
        call_pattern = {}
    if not isinstance(app_usage, dict):
        app_usage = {}
    if not isinstance(calendar_pattern, dict):
        calendar_pattern = {}
    top_apps = app_usage.get("top_apps")
    if not isinstance(top_apps, list):
        top_apps = []
    top_apps = [item for item in top_apps if isinstance(item, dict)][:10]

    daily_by_date: dict[str, dict[str, Any]] = {}

    def daily_row(value: Any) -> dict[str, Any] | None:
        if not isinstance(value, str) or not value.strip():
            return None
        date = value.strip()
        return daily_by_date.setdefault(date, {"date": date})

    health_daily = latest_health_data.get("daily_values")
    if isinstance(health_daily, list):
        for item in health_daily:
            if not isinstance(item, dict):
                continue
            row = daily_row(item.get("date"))
            values = item.get("values")
            if row is None or not isinstance(values, dict):
                continue
            row.update(
                {
                    key: values.get(source_key)
                    for key, source_key in (
                        ("steps", "steps"),
                        ("calories_kcal", "total_calories_kcal"),
                        ("distance_km", "distance_km"),
                        ("heart_rate_bpm", "heart_rate_bpm"),
                        ("sleep_hours", "sleep_hours"),
                    )
                    if values.get(source_key) is not None
                }
            )

    daily_usage = app_usage.get("daily_usage")
    if isinstance(daily_usage, list):
        for item in daily_usage:
            if not isinstance(item, dict):
                continue
            row = daily_row(item.get("date"))
            if row is not None and item.get("total_time_minutes") is not None:
                row["screen_minutes"] = item.get("total_time_minutes")

    daily_calls = call_pattern.get("daily_calls")
    if isinstance(daily_calls, list):
        for item in daily_calls:
            if not isinstance(item, dict):
                continue
            row = daily_row(item.get("date"))
            if row is not None:
                for key in ("call_count", "total_duration_seconds", "missed_call_count"):
                    if item.get(key) is not None:
                        row[key] = item.get(key)

    upcoming_days = calendar_pattern.get("upcoming_daily_events")
    if isinstance(upcoming_days, list):
        for item in upcoming_days:
            if not isinstance(item, dict):
                continue
            row = daily_row(item.get("date"))
            if row is not None and item.get("event_count") is not None:
                row["upcoming_events"] = item.get("event_count")

    daily_exercise = latest_health_data.get("daily_exercise")
    if isinstance(daily_exercise, list):
        for item in daily_exercise:
            if not isinstance(item, dict):
                continue
            row = daily_row(item.get("date"))
            if row is not None:
                if item.get("session_count") is not None:
                    row["exercise_sessions"] = item.get("session_count")
                if item.get("duration_minutes") is not None:
                    row["exercise_minutes"] = item.get("duration_minutes")

    daily_records = [daily_by_date[key] for key in sorted(daily_by_date, reverse=True)[:60]]

    latest_summary = summary_rows[0] if summary_rows else {}
    summary = str(latest_summary.get("summary") or "").strip()
    latest_timestamp_candidates = [
        latest_collected_at,
        latest_summary.get("updated_at"),
        latest_summary.get("received_at"),
    ]
    latest_timestamp = max(
        (
            float(value)
            for value in latest_timestamp_candidates
            if isinstance(value, (int, float))
        ),
        default=None,
    )
    available = bool(summary_rows or snapshots)
    return {
        "connected": available,
        "available": available,
        "state": "stored" if available else "empty",
        "source": "OnMom Android · Insights DB",
        "source_format": latest_data.get("source_format") or "analysis_snapshot_v1",
        "storage_path": str(CONTEXT_DB),
        "last_observed_at": epoch_to_iso(latest_timestamp),
        "summary_count": int(
            scalar(
                CONTEXT_DB,
                "SELECT COUNT(*) FROM derived_summaries "
                "WHERE UPPER(category) = 'PHENOTYPE'",
            )
        ),
        "snapshot_count": int(
            scalar(
                CONTEXT_DB,
                "SELECT COUNT(*) FROM analysis_snapshots "
                "WHERE UPPER(categories_json) LIKE '%PHENOTYPE%'",
            )
        ),
        "summary": summary,
        "call_pattern": call_pattern,
        "app_usage_pattern": {
            **app_usage,
            "top_apps": top_apps,
        },
        "calendar_pattern": calendar_pattern,
        "health_pattern": latest_health_data,
        "daily_records": daily_records,
        "snapshots": snapshots,
    }


def home_assistant_access() -> dict[str, Any]:
    rows = query_rows(
        HOME_ASSISTANT_DB,
        """
        SELECT occurred_at, entity_id, event_type, direction, authorized, result, zone
        FROM access_events
        ORDER BY occurred_at_ts DESC
        LIMIT 80
        """,
    )
    confirmed: list[dict[str, Any]] = []
    for row in rows:
        entity_id = str(row.get("entity_id") or "")
        stored_direction = str(row.get("direction") or "").upper()
        authorized = row.get("authorized")
        denied = str(row.get("event_type") or "") == "access_denied" or authorized == 0
        if "physical_exit_button" in entity_id:
            input_type = "button"
            input_label = "버튼키"
            direction = "EXIT"
            event_type = "access_exit"
            label = "버튼키 퇴실"
        elif "rfid" in entity_id.lower():
            input_type = "rfid"
            input_label = "RFID 키"
            direction = "UNKNOWN" if denied else "ENTER"
            event_type = "access_denied" if denied else "access_enter"
            label = "RFID 키 인증 거부" if denied else "RFID 키 입실"
        else:
            input_type = "home_assistant"
            input_label = "Home Assistant"
            direction = stored_direction
            if denied:
                event_type = "access_denied"
                label = f"{input_label} 인증 거부"
            elif direction == "ENTER":
                event_type = "access_enter"
                label = f"{input_label} 입실"
            elif direction == "EXIT":
                event_type = "access_exit"
                label = f"{input_label} 퇴실"
            else:
                event_type = "access_unknown"
                label = f"{input_label} 입력"

        confirmed.append(
            {
                "observed_at": row.get("occurred_at"),
                "event_type": event_type,
                "label": label,
                "source": f"Home Assistant · {input_label}",
                "input_type": input_type,
                "entity_id": entity_id,
                "direction": direction,
                "stored_direction": stored_direction,
                "authorized": None if authorized is None else bool(authorized),
                "result": row.get("result"),
                "zone": row.get("zone"),
                "confirmed": not denied,
            }
        )

    table_available = bool(
        scalar(
            HOME_ASSISTANT_DB,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='access_events'",
            default=0,
        )
    )
    return {
        "connected": table_available,
        "source": "Home Assistant",
        "inputs": ["RFID 키", "버튼키"],
        "rules": {"rfid": "ENTER", "button": "EXIT"},
        "last_observed_at": confirmed[0]["observed_at"] if confirmed else None,
        "confirmed": confirmed,
        "candidates": [],
    }


def home_assistant_light() -> dict[str, Any]:
    rows = query_rows(
        HOME_ASSISTANT_DB,
        """
        SELECT occurred_at, entity_id, state, brightness, friendly_name
        FROM light_events
        WHERE entity_id = ?
        ORDER BY occurred_at_ts DESC
        LIMIT 80
        """,
        (ESP12_LITE_ENTITY,),
    )
    events = [
        {
            "observed_at": row.get("occurred_at"),
            "event_type": "light_on" if row.get("state") == "on" else "light_off",
            "label": (
                "ESP12-Lite 전등 켜짐"
                if row.get("state") == "on"
                else "ESP12-Lite 전등 꺼짐"
            ),
            "state": row.get("state"),
            "brightness": row.get("brightness"),
            "source": "Home Assistant · ESP12-Lite",
            "entity_id": row.get("entity_id"),
            "friendly_name": row.get("friendly_name"),
        }
        for row in rows
        if row.get("state") in {"on", "off"}
    ]
    table_available = bool(
        scalar(
            HOME_ASSISTANT_DB,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='light_events'",
            default=0,
        )
    )
    total_events = int(
        scalar(
            HOME_ASSISTANT_DB,
            "SELECT COUNT(*) FROM light_events WHERE entity_id = ?",
            (ESP12_LITE_ENTITY,),
            default=0,
        )
    )
    latest = events[0] if events else None
    return {
        "connected": table_available and latest is not None,
        "device": "ESP12-Lite",
        "source": "Home Assistant",
        "storage_path": str(HOME_ASSISTANT_DB),
        "entity_id": ESP12_LITE_ENTITY,
        "state": latest["state"] if latest else "unknown",
        "last_observed_at": latest["observed_at"] if latest else None,
        "event_count": total_events,
        "events": events,
    }


def storage_item(
    item_id: str,
    label: str,
    path: Path,
    table_counts: tuple[str, ...],
) -> dict[str, Any]:
    rows = sum(int(scalar(path, f'SELECT COUNT(*) FROM "{table}"', default=0)) for table in table_counts)
    try:
        stat = path.stat()
        return {
            "id": item_id,
            "label": label,
            "path": str(path),
            "size": stat.st_size,
            "rows": rows,
            "updated_at": epoch_to_iso(stat.st_mtime),
            "status": "ok",
        }
    except OSError:
        return {
            "id": item_id,
            "label": label,
            "path": str(path),
            "size": 0,
            "rows": rows,
            "updated_at": None,
            "status": "missing",
        }


def room_data(room_live: dict[str, Any], service: dict[str, Any]) -> dict[str, Any]:
    candidates = query_rows(
        ROOM_DB,
        """
        SELECT rf.observed_at, ric.track_id, ric.target_probability AS probability,
               ric.selected, ric.identity_source AS source, ric.zone, ric.posture,
               ric.motion, ric.inactivity_duration_sec
        FROM room_identity_candidates ric
        JOIN room_frames rf ON rf.id = ric.frame_id
        ORDER BY ric.id DESC
        LIMIT 18
        """,
    )
    for row in candidates:
        row["selected"] = bool(row.get("selected"))
    events = query_rows(
        ROOM_DB,
        """
        SELECT observed_at, event_type, subject_id, track_id, zone, confidence
        FROM room_events
        ORDER BY id DESC
        LIMIT 18
        """,
    )
    latest = query_rows(
        ROOM_DB,
        """
        SELECT observed_at, frame_brightness, target_observed
        FROM room_frames
        ORDER BY id DESC
        LIMIT 1
        """,
    )
    dashboard_ready = bool(room_live.get("ready"))
    current_people = room_live.get("people") if isinstance(room_live.get("people"), list) else []
    last_observed_at = latest[0].get("observed_at") if latest else None
    return {
        "status": "online" if dashboard_ready else "offline",
        "camera_ready": dashboard_ready,
        "source_ready": dashboard_ready,
        "analysis_ready": dashboard_ready,
        "reason": (
            ""
            if dashboard_ready
            else "방 카메라 영상이 AI 분석 서비스에 들어오지 않습니다. USB/RTSP 입력을 확인하세요."
        ),
        "last_observed_at": last_observed_at,
        "processing_fps": room_live.get("processing_fps", 0),
        "inference_ms": room_live.get("inference_ms"),
        "frame_brightness": room_live.get(
            "frame_brightness",
            latest[0].get("frame_brightness") if latest else None,
        ),
        "person_count": int(room_live.get("person_count", 0)),
        "target_count": sum(1 for person in current_people if person.get("is_target")),
        "people": current_people,
        "candidates": candidates,
        "events": events,
        "service_active": service.get("active", False),
    }


def counseling_data() -> dict[str, Any]:
    latest = safe_json(COUNSELING_LATEST)
    observed_at_ms = latest.get("observed_at_ms")
    age_ms = (
        max(0, int(time.time() * 1000) - int(observed_at_ms))
        if isinstance(observed_at_ms, (int, float))
        else None
    )
    stream_ready = age_ms is not None and age_ms < 10_000
    recent_face = query_rows(
        COUNSELING_DB,
        """
        SELECT observed_at, status, label, confidence
        FROM face_emotion_measurements
        ORDER BY id DESC
        LIMIT 30
        """,
    )
    recent_rppg = query_rows(
        COUNSELING_DB,
        """
        SELECT observed_at, status, heart_rate_bpm, quality, reason_codes_json
        FROM rppg_measurements
        WHERE status = 'valid' AND heart_rate_bpm IS NOT NULL
        ORDER BY id DESC
        LIMIT 30
        """,
    )
    emotion_logs: list[dict[str, Any]] = []
    for row in recent_face:
        emotion_logs.append(
            {
                "observed_at": row.get("observed_at"),
                "status": row.get("status"),
                "label": row.get("label") or "표정 수집 대기",
                "confidence": row.get("confidence"),
            }
        )
    rppg_logs: list[dict[str, Any]] = []
    for row in recent_rppg:
        label = (
            f"{float(row['heart_rate_bpm']):.1f} bpm"
            if row.get("heart_rate_bpm") is not None
            else " · ".join(json.loads(row.get("reason_codes_json") or "[]"))
        )
        rppg_logs.append(
            {
                "observed_at": row.get("observed_at"),
                "status": row.get("status"),
                "label": label or "신호 수집 대기",
                "heart_rate_bpm": row.get("heart_rate_bpm"),
                "quality": row.get("quality"),
            }
        )
    voice_logs: list[dict[str, Any]] = []
    for event in reversed(tail_json_lines(COUNSELING_SIGNALS_LOG, limit=120)):
        voice = event.get("voice_emotion")
        if not isinstance(voice, dict):
            continue
        voice_logs.append(
            {
                "observed_at": epoch_to_iso(
                    voice.get("observed_at_ms") or event.get("observed_at_ms")
                ),
                "status": voice.get("status") or "unavailable",
                "label": voice.get("display_label") or voice.get("label"),
                "confidence": voice.get("confidence"),
                "reason": voice.get("reason"),
                "transcription_status": (
                    event.get("transcription", {}).get("status")
                    if isinstance(event.get("transcription"), dict)
                    else None
                ),
                "transcript_characters": (
                    event.get("transcription", {}).get("characters")
                    if isinstance(event.get("transcription"), dict)
                    else None
                ),
            }
        )
        if len(voice_logs) >= 30:
            break
    return {
        "stream_ready": stream_ready,
        "stale": not stream_ready,
        "age_ms": age_ms,
        "session_id": latest.get("session_id"),
        "face": latest.get("face") or {},
        "emotion": latest.get("emotion") or {},
        "rppg": latest.get("rppg") or {},
        "counts": {
            "face_emotion": scalar(COUNSELING_DB, "SELECT COUNT(*) FROM face_emotion_measurements"),
            "rppg": scalar(
                COUNSELING_DB,
                """
                SELECT COUNT(*) FROM rppg_measurements
                WHERE status = 'valid' AND heart_rate_bpm IS NOT NULL
                """,
            ),
            "messages": scalar(COUNSELING_APP_DB, "SELECT COUNT(*) FROM messages"),
            "voice": scalar(
                COUNSELING_APP_DB,
                "SELECT COUNT(*) FROM messages WHERE source = 'voice'",
            ),
        },
        "emotion_logs": emotion_logs,
        "rppg_logs": rppg_logs,
        "voice_logs": voice_logs,
    }


_CACHE_LOCK = threading.Lock()
_CACHE_AT = 0.0
_CACHE_VALUE: dict[str, Any] = {}
_RESET_LOCK = threading.Lock()


def overview() -> dict[str, Any]:
    global _CACHE_AT, _CACHE_VALUE
    with _CACHE_LOCK:
        if time.monotonic() - _CACHE_AT < 1.2 and _CACHE_VALUE:
            return _CACHE_VALUE

    services = service_states()
    containers = docker_states()
    room_live = http_json("http://127.0.0.1:8877/api/status")
    gateway_health = http_json("http://127.0.0.1:8000/health")
    light = home_assistant_light()
    phenotype = phenotype_data()
    access = home_assistant_access()

    service_list = [
        {
            "id": "room",
            "name": "방 카메라 AI",
            "kind": "service",
            "status": "online" if room_live.get("ready") else "warning",
            "detail": "실시간 분석 중" if room_live.get("ready") else "카메라 입력 대기",
        },
        {
            "id": "rppg",
            "name": "얼굴·rPPG",
            "kind": "service",
            "status": "online" if counseling_data()["stream_ready"] else services["rppg"]["status"],
            "detail": "실시간 분석 중" if counseling_data()["stream_ready"] else "상담 카메라 대기",
        },
        {
            "id": "gateway",
            "name": "상담·LLM",
            "kind": "docker",
            "status": "online" if gateway_health.get("status") == "ready" else containers["gateway"]["status"],
            "detail": "LLM 정상" if gateway_health.get("llm") else "LLM 확인 필요",
        },
        {
            "id": "insights",
            "name": "장기 맥락",
            "kind": "service",
            **services["insights"],
        },
        {
            "id": "light",
            "name": "ESP12-Lite 전등",
            "kind": "home_assistant",
            "status": "online" if light["connected"] else "offline",
            "detail": (
                ("켜짐" if light["state"] == "on" else "꺼짐") + " · HA 저장"
                if light["connected"]
                else "Home Assistant 기록 대기"
            ),
        },
        {
            "id": "phenotype",
            "name": "피노타입",
            "kind": "insights",
            "status": "online" if phenotype["connected"] else "offline",
            "detail": (
                f"저장 데이터 {phenotype['snapshot_count']}건"
                if phenotype["connected"]
                else "저장 데이터 없음"
            ),
        },
        {
            "id": "access",
            "name": "입출입 RFID·버튼키",
            "kind": "home_assistant",
            "status": "online" if access["connected"] else "warning",
            "detail": "Home Assistant 기록 연결" if access["connected"] else "기록 DB 확인 필요",
        },
    ]
    healthy = sum(1 for item in service_list if item["status"] == "online")
    value = {
        "mode": "live",
        "generated_at": now_iso(),
        "device": {
            "name": "Jetson Orin",
            "host": os.uname().nodename,
            "storage_root": str(DATA_ROOT),
        },
        "summary": {
            "healthy": healthy,
            "warning": len(service_list) - healthy,
            "total": len(service_list),
        },
        "services": service_list,
        "room": room_data(room_live, services["room"]),
        "access": access,
        "light": light,
        "phenotype": phenotype,
        "counseling": counseling_data(),
        "storage": [
            storage_item(
                "room-db",
                "방 분석 DB",
                ROOM_DB,
                ("room_frames", "room_identity_candidates", "room_observations", "room_events"),
            ),
            storage_item(
                "counseling-db",
                "상담 분석 DB",
                COUNSELING_DB,
                ("face_emotion_measurements", "rppg_measurements"),
            ),
            storage_item(
                "context-db",
                "LLM 맥락 DB",
                CONTEXT_DB,
                ("analysis_events", "context_cards", "derived_summaries"),
            ),
        ],
    }
    with _CACHE_LOCK:
        _CACHE_AT = time.monotonic()
        _CACHE_VALUE = value
    return value


def record_integration_event(payload: dict[str, Any]) -> dict[str, Any]:
    category = str(payload.get("category", "")).strip().lower()
    if category not in INTEGRATION_CATEGORIES:
        raise ValueError(f"category must be one of {sorted(INTEGRATION_CATEGORIES)}")
    event_type = str(payload.get("event_type", "")).strip()
    if not event_type or len(event_type) > 80:
        raise ValueError("event_type is required and must be at most 80 characters")
    observed_at_ms = payload.get("observed_at_ms")
    if not isinstance(observed_at_ms, (int, float)):
        observed_at_ms = int(time.time() * 1000)
    observed_at = epoch_to_iso(observed_at_ms) or now_iso()
    source = str(payload.get("source") or f"{category}_module")[:120]
    with database(MANAGEMENT_DB, writable=True) as connection:
        cursor = connection.execute(
            """
            INSERT INTO integration_events(
                category, event_type, state, subject_id, source,
                observed_at_ms, observed_at, payload_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                category,
                event_type,
                str(payload.get("state") or "")[:80] or None,
                str(payload.get("subject_id") or "")[:120] or None,
                source,
                int(observed_at_ms),
                observed_at,
                json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            ),
        )
        event_id = int(cursor.lastrowid)
    global _CACHE_AT
    _CACHE_AT = 0.0
    return {"ok": True, "id": event_id, "observed_at": observed_at}


def restart_action(action: str) -> dict[str, Any]:
    target = RESTART_ACTIONS.get(action)
    if target is None:
        raise ValueError("unsupported restart action")
    kind, name = target
    if kind == "systemd":
        code, output = run_command(["systemctl", "--user", "restart", name], timeout=8.0)
    else:
        code, output = run_command(["docker", "restart", name], timeout=20.0)
    global _CACHE_AT
    _CACHE_AT = 0.0
    return {"ok": code == 0, "action": action, "detail": output}


def reset_log_files() -> list[Path]:
    files = {path for path in RESET_GENERATED_JSON if path.is_file()}
    for root in RESET_LOG_ROOTS:
        if not root.is_dir():
            continue
        for pattern in ("*.jsonl", "*.log"):
            files.update(path for path in root.rglob(pattern) if path.is_file())
    return sorted(files)


def reset_backup_dirs() -> list[Path]:
    if not RESET_BACKUP_ROOT.is_dir():
        return []
    backup_root = RESET_BACKUP_ROOT.resolve()
    directories: list[Path] = []
    for path in RESET_BACKUP_ROOT.iterdir():
        if (
            path.name.startswith(RESET_BACKUP_PREFIX)
            and path.is_dir()
            and not path.is_symlink()
            and path.resolve().parent == backup_root
        ):
            directories.append(path)
    return sorted(directories)


def tree_stats(paths: list[Path]) -> tuple[int, int]:
    file_count = 0
    total_bytes = 0
    for path in paths:
        candidates = [path] if path.is_file() else path.rglob("*")
        for candidate in candidates:
            try:
                if candidate.is_file():
                    file_count += 1
                    total_bytes += candidate.stat().st_size
            except OSError:
                continue
    return file_count, total_bytes


def reset_preview() -> dict[str, Any]:
    labels = {
        ROOM_DB: "방 카메라 분석",
        COUNSELING_DB: "상담 표정·rPPG",
        COUNSELING_APP_DB: "상담 대화",
        CONTEXT_DB: "LLM 장기 맥락",
        MANAGEMENT_DB: "전등·피노타입 연동",
        HOME_ASSISTANT_DB: "Home Assistant 입출입·전등",
    }
    groups: list[dict[str, Any]] = []
    total_rows = 0
    for path, tables in RESET_DB_TABLES.items():
        rows = sum(
            int(scalar(path, f'SELECT COUNT(*) FROM "{table}"', default=0))
            for table in tables
        )
        total_rows += rows
        groups.append(
            {
                "id": path.stem,
                "label": labels[path],
                "path": str(path),
                "rows": rows,
                "tables": list(tables),
            }
        )
    log_files = reset_log_files()
    backup_dirs = reset_backup_dirs()
    direct_file_count, direct_bytes = tree_stats(log_files)
    backup_file_count, backup_bytes = tree_stats(backup_dirs)
    return {
        "confirmation_phrase": RESET_CONFIRMATION,
        "mode": "permanent_delete",
        "total_rows": total_rows,
        "groups": groups,
        "log_files": direct_file_count,
        "stale_backup_dirs": len(backup_dirs),
        "stale_backup_files": backup_file_count,
        "filesystem_files": direct_file_count + backup_file_count,
        "log_bytes": direct_bytes + backup_bytes,
        "preserved": list(RESET_PRESERVED),
    }


def clear_database_tables(path: Path, tables: tuple[str, ...]) -> int:
    if not path.is_file():
        return 0
    with database(path, writable=True) as connection:
        connection.execute("PRAGMA busy_timeout=8000")
        existing = {
            str(row[0])
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        selected = [table for table in tables if table in existing]
        connection.execute("PRAGMA foreign_keys=OFF")
        connection.execute("BEGIN IMMEDIATE")
        deleted_rows = 0
        try:
            for table in selected:
                cursor = connection.execute(f'DELETE FROM "{table}"')
                deleted_rows += max(0, int(cursor.rowcount))
            if "sqlite_sequence" in existing and selected:
                placeholders = ",".join("?" for _ in selected)
                connection.execute(
                    f"DELETE FROM sqlite_sequence WHERE name IN ({placeholders})",
                    selected,
                )
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        try:
            connection.execute("VACUUM")
        except sqlite3.Error:
            pass
        try:
            connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        except sqlite3.Error:
            pass
    return deleted_rows


def stop_reset_writers() -> tuple[list[str], list[str]]:
    stopped_units: list[str] = []
    stopped_containers: list[str] = []
    try:
        for unit in RESET_SYSTEMD_WRITERS:
            active, _ = run_command(["systemctl", "--user", "is-active", "--quiet", unit])
            if active != 0:
                continue
            code, _ = run_command(["systemctl", "--user", "stop", unit], timeout=15.0)
            if code != 0:
                raise OSError(f"로그 작성 서비스 중지 실패: {unit}")
            stopped_units.append(unit)
        for container in RESET_DOCKER_WRITERS:
            code, state = run_command(
                ["docker", "inspect", "-f", "{{.State.Running}}", container],
                timeout=4.0,
            )
            if code != 0 or state.strip().lower() != "true":
                continue
            stop_code, _ = run_command(
                ["docker", "stop", "-t", "10", container], timeout=18.0
            )
            if stop_code != 0:
                raise OSError(f"로그 작성 컨테이너 중지 실패: {container}")
            stopped_containers.append(container)
    except Exception:
        start_reset_writers(stopped_units, stopped_containers)
        raise
    return stopped_units, stopped_containers


def start_reset_writers(units: list[str], containers: list[str]) -> list[str]:
    failures: list[str] = []
    for container in containers:
        code, _ = run_command(["docker", "start", container], timeout=18.0)
        if code != 0:
            failures.append(container)
    for unit in units:
        code, _ = run_command(["systemctl", "--user", "start", unit], timeout=15.0)
        if code != 0:
            failures.append(unit)
    return failures


def permanently_delete_reset_files(
    log_files: list[Path], backup_dirs: list[Path]
) -> tuple[int, int]:
    data_root = DATA_ROOT.resolve()
    backup_root = RESET_BACKUP_ROOT.resolve()
    deleted_files = 0
    deleted_bytes = 0
    for path in log_files:
        resolved = path.resolve()
        if data_root not in resolved.parents or path.is_symlink():
            raise OSError(f"허용되지 않은 삭제 경로: {path}")
        try:
            size = path.stat().st_size
            path.unlink()
            deleted_files += 1
            deleted_bytes += size
        except FileNotFoundError:
            continue
    for path in backup_dirs:
        resolved = path.resolve()
        if (
            path.is_symlink()
            or resolved.parent != backup_root
            or not path.name.startswith(RESET_BACKUP_PREFIX)
        ):
            raise OSError(f"허용되지 않은 백업 삭제 경로: {path}")
        backup_file_count, backup_bytes = tree_stats([path])
        shutil.rmtree(path)
        deleted_files += backup_file_count
        deleted_bytes += backup_bytes
    return deleted_files, deleted_bytes


def reset_logs(payload: dict[str, Any]) -> dict[str, Any]:
    if payload.get("confirmation") != RESET_CONFIRMATION:
        raise ValueError("초기화 확인 문구가 일치하지 않습니다.")
    if not _RESET_LOCK.acquire(blocking=False):
        raise ValueError("이미 로그 초기화가 진행 중입니다.")
    started_at = now_iso()
    preview = reset_preview()
    log_files = reset_log_files()
    backup_dirs = reset_backup_dirs()
    stopped_units: list[str] = []
    stopped_containers: list[str] = []
    try:
        stopped_units, stopped_containers = stop_reset_writers()
        deleted_rows = 0
        for path, tables in RESET_DB_TABLES.items():
            deleted_rows += clear_database_tables(path, tables)
        deleted_files, deleted_bytes = permanently_delete_reset_files(
            log_files, backup_dirs
        )
        restart_failures = start_reset_writers(stopped_units, stopped_containers)
        if restart_failures:
            stopped_units = [
                unit for unit in stopped_units if unit in restart_failures
            ]
            stopped_containers = [
                container
                for container in stopped_containers
                if container in restart_failures
            ]
            raise OSError(
                "로그 삭제 후 서비스 재시작 실패: " + ", ".join(restart_failures)
            )
        stopped_units = []
        stopped_containers = []
        completed_at = now_iso()
        global _CACHE_AT
        _CACHE_AT = 0.0
        return {
            "ok": True,
            "mode": "permanent_delete",
            "started_at": started_at,
            "completed_at": completed_at,
            "deleted_rows": deleted_rows,
            "deleted_files": deleted_files,
            "deleted_bytes": deleted_bytes,
            "removed_backup_dirs": len(backup_dirs),
            "preserved": list(RESET_PRESERVED),
        }
    finally:
        if stopped_units or stopped_containers:
            start_reset_writers(stopped_units, stopped_containers)
        _RESET_LOCK.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "OnMomControlCenter/1.0"

    def send_bytes(
        self,
        status: HTTPStatus,
        body: bytes,
        content_type: str,
        *,
        cache: str = "no-store",
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, value: Any, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_bytes(status, body, "application/json; charset=utf-8")

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > 1_000_000:
            return {}
        value = json.loads(self.rfile.read(length).decode("utf-8"))
        return value if isinstance(value, dict) else {}

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/api/health":
            self.send_json({"ok": True, "generated_at": now_iso()})
            return
        if parsed.path == "/api/overview":
            self.send_json(overview())
            return
        if parsed.path == "/api/actions/reset-logs/preview":
            self.send_json(reset_preview())
            return
        if parsed.path == "/api/counseling/policy":
            try:
                self.send_json(counseling_gateway_json("/health-monitor/policy"))
            except (OSError, ValueError) as exc:
                self.send_json(
                    {"available": False, "error": str(exc)},
                    HTTPStatus.SERVICE_UNAVAILABLE,
                )
            return
        if parsed.path == "/api/counseling/datasets":
            try:
                self.send_json(
                    counseling_gateway_json("/health-monitor/datasets")
                )
            except (OSError, ValueError) as exc:
                self.send_json(
                    {"available": False, "error": str(exc)},
                    HTTPStatus.SERVICE_UNAVAILABLE,
                )
            return
        if parsed.path == "/api/room/stream":
            self.proxy_room_stream()
            return
        self.serve_static(parsed.path)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        try:
            if parsed.path == "/api/integrations/event":
                self.send_json(record_integration_event(self.read_json()), HTTPStatus.CREATED)
                return
            if parsed.path == "/api/actions/reset-logs":
                self.send_json(reset_logs(self.read_json()))
                return
            if parsed.path == "/api/counseling/policy/refresh":
                self.send_json(
                    counseling_gateway_json(
                        "/health-monitor/policy/refresh",
                        method="POST",
                        payload={},
                        timeout=20.0,
                    )
                )
                return
            if parsed.path == "/api/counseling/proactive-test":
                self.send_json(
                    counseling_gateway_json(
                        "/health-monitor/test",
                        method="POST",
                        payload=self.read_json(),
                        timeout=150.0,
                    )
                )
                return
            if parsed.path == "/api/counseling/datasets/select":
                self.send_json(
                    counseling_gateway_json(
                        "/health-monitor/datasets/select",
                        method="POST",
                        payload=self.read_json(),
                        timeout=20.0,
                    )
                )
                return
            prefix = "/api/actions/restart/"
            if parsed.path.startswith(prefix):
                self.send_json(restart_action(parsed.path[len(prefix):]))
                return
            self.send_json({"ok": False, "error": "not found"}, HTTPStatus.NOT_FOUND)
        except (ValueError, json.JSONDecodeError) as exc:
            self.send_json({"ok": False, "error": str(exc)}, HTTPStatus.BAD_REQUEST)
        except (OSError, sqlite3.Error, shutil.Error) as exc:
            self.send_json(
                {"ok": False, "error": f"로그 초기화 실패: {exc}"},
                HTTPStatus.INTERNAL_SERVER_ERROR,
            )

    def proxy_room_stream(self) -> None:
        try:
            request = Request("http://127.0.0.1:8877/stream.mjpg")
            with urlopen(request, timeout=4.0) as response:
                content_type = response.headers.get(
                    "Content-Type", "multipart/x-mixed-replace; boundary=frame"
                )
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", content_type)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except (OSError, URLError, TimeoutError, BrokenPipeError, ConnectionResetError):
            if not self.wfile.closed:
                try:
                    self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, "room stream unavailable")
                except (BrokenPipeError, ConnectionResetError):
                    pass

    def serve_static(self, request_path: str) -> None:
        clean = request_path.lstrip("/") or "index.html"
        target = (STATIC_ROOT / clean).resolve()
        static_root = STATIC_ROOT.resolve()
        if static_root not in target.parents and target != static_root:
            self.send_error(HTTPStatus.FORBIDDEN)
            return
        if target.is_file():
            content_type = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
            cache = "public, max-age=31536000, immutable" if "/assets/" in request_path else "no-cache"
            self.send_bytes(HTTPStatus.OK, target.read_bytes(), content_type, cache=cache)
            return
        index = STATIC_ROOT / "index.html"
        if index.is_file():
            self.send_bytes(HTTPStatus.OK, index.read_bytes(), "text/html; charset=utf-8")
            return
        self.send_error(HTTPStatus.NOT_FOUND, "dashboard build missing")

    def log_message(self, format: str, *args: object) -> None:
        print(f"{self.address_string()} - {format % args}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8890)
    args = parser.parse_args()
    init_management_db()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"OnMom Control Center listening on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
