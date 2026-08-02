from __future__ import annotations

import json
import sqlite3
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from threading import Lock
from typing import Iterable

from .models import AnalysisResult, DailyMetric, DailyPolicy, TopicDecision


UTC = timezone.utc


class HealthMonitorStore:
    """Private derived storage; source databases remain read-only."""

    def __init__(self, path: Path):
        self.path = path
        self._lock = Lock()

    def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.executescript(
                """
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS daily_metrics (
                    metric TEXT NOT NULL,
                    day TEXT NOT NULL,
                    value REAL NOT NULL,
                    quality REAL NOT NULL,
                    source TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY(metric, day, source)
                );
                CREATE INDEX IF NOT EXISTS idx_daily_metric_lookup
                    ON daily_metrics(metric, day);

                CREATE TABLE IF NOT EXISTS analysis_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    generated_at TEXT NOT NULL,
                    decision_topic TEXT,
                    payload_json TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_analysis_generated
                    ON analysis_runs(generated_at);

                CREATE TABLE IF NOT EXISTS proactive_outreach (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    trigger TEXT NOT NULL,
                    category TEXT,
                    metric TEXT,
                    event_key TEXT,
                    opening TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_health_outreach_lookup
                    ON proactive_outreach(created_at, category, metric);

                CREATE TABLE IF NOT EXISTS source_backfills (
                    source TEXT PRIMARY KEY,
                    completed_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS daily_policies (
                    policy_day TEXT PRIMARY KEY,
                    generated_at TEXT NOT NULL,
                    window_start TEXT NOT NULL,
                    window_end TEXT NOT NULL,
                    generated_while_asleep INTEGER NOT NULL,
                    generation_reason TEXT NOT NULL,
                    risk_level TEXT NOT NULL,
                    risk_score REAL NOT NULL,
                    system_prompt TEXT NOT NULL,
                    payload_json TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_daily_policies_generated
                    ON daily_policies(generated_at);
                PRAGMA optimize;
                """
            )

    def save_daily_policy(self, policy: DailyPolicy) -> None:
        payload = json.dumps(policy.to_dict(), ensure_ascii=False)
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO daily_policies(
                    policy_day, generated_at, window_start, window_end,
                    generated_while_asleep, generation_reason, risk_level,
                    risk_score, system_prompt, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(policy_day) DO UPDATE SET
                    generated_at=excluded.generated_at,
                    window_start=excluded.window_start,
                    window_end=excluded.window_end,
                    generated_while_asleep=excluded.generated_while_asleep,
                    generation_reason=excluded.generation_reason,
                    risk_level=excluded.risk_level,
                    risk_score=excluded.risk_score,
                    system_prompt=excluded.system_prompt,
                    payload_json=excluded.payload_json
                """,
                (
                    policy.policy_day.isoformat(),
                    policy.generated_at.isoformat(),
                    policy.window_start.isoformat(),
                    policy.window_end.isoformat(),
                    int(policy.generated_while_asleep),
                    policy.generation_reason,
                    policy.risk_level.value,
                    policy.risk_score,
                    policy.system_prompt,
                    payload,
                ),
            )

    def daily_policy(self, policy_day: date) -> dict | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT payload_json FROM daily_policies WHERE policy_day = ?",
                (policy_day.isoformat(),),
            ).fetchone()
        return json.loads(row[0]) if row else None

    def latest_daily_policy(self, on_or_before: date) -> dict | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                """
                SELECT payload_json FROM daily_policies
                WHERE policy_day <= ? ORDER BY policy_day DESC LIMIT 1
                """,
                (on_or_before.isoformat(),),
            ).fetchone()
        return json.loads(row[0]) if row else None

    def upsert_daily(self, metrics: Iterable[DailyMetric]) -> int:
        rows = list(metrics)
        now = datetime.now(UTC).isoformat()
        with self._lock, self._connect() as connection:
            for item in rows:
                connection.execute(
                    """
                    INSERT INTO daily_metrics(
                        metric, day, value, quality, source,
                        metadata_json, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(metric, day, source) DO UPDATE SET
                        value=excluded.value,
                        quality=excluded.quality,
                        metadata_json=excluded.metadata_json,
                        updated_at=excluded.updated_at
                    """,
                    (
                        item.metric,
                        item.day.isoformat(),
                        item.value,
                        item.quality,
                        item.source,
                        json.dumps(item.metadata, ensure_ascii=False),
                        now,
                    ),
                )
        return len(rows)

    def clear(self) -> None:
        """Clear only this derived store; never source datasets."""
        with self._lock, self._connect() as connection:
            connection.executescript(
                """
                DELETE FROM daily_metrics;
                DELETE FROM analysis_runs;
                DELETE FROM proactive_outreach;
                DELETE FROM source_backfills;
                DELETE FROM daily_policies;
                """
            )

    def source_backfilled(self, source: str) -> bool:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT 1 FROM source_backfills WHERE source = ?",
                (source,),
            ).fetchone()
        return row is not None

    def mark_source_backfilled(
        self,
        source: str,
        completed_at: datetime | None = None,
    ) -> None:
        timestamp = _utc(completed_at or datetime.now(UTC)).isoformat()
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO source_backfills(source, completed_at) VALUES (?, ?)
                ON CONFLICT(source) DO UPDATE SET completed_at=excluded.completed_at
                """,
                (source, timestamp),
            )

    def history(
        self,
        metric: str,
        start: date,
        end: date,
    ) -> list[tuple[date, float, float, str]]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT day, value, quality, source
                FROM daily_metrics
                WHERE metric = ? AND day >= ? AND day <= ?
                ORDER BY day
                """,
                (metric, start.isoformat(), end.isoformat()),
            ).fetchall()
        return [
            (date.fromisoformat(row[0]), float(row[1]), float(row[2]), str(row[3]))
            for row in rows
        ]

    def recent_outreach(
        self,
        since: datetime,
    ) -> list[dict]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT created_at, session_id, trigger, category,
                       metric, event_key, opening
                FROM proactive_outreach
                WHERE created_at >= ? ORDER BY created_at
                """,
                (_utc(since).isoformat(),),
            ).fetchall()
        return [
            {
                "created_at": datetime.fromisoformat(row[0]),
                "session_id": row[1],
                "trigger": row[2],
                "category": row[3],
                "metric": row[4],
                "event_key": row[5],
                "opening": row[6],
            }
            for row in rows
        ]

    def record_outreach(
        self,
        session_id: str,
        decision: TopicDecision,
        opening: str,
        event_key: str | None = None,
        created_at: datetime | None = None,
    ) -> None:
        if decision.trigger is None:
            return
        timestamp = _utc(created_at or datetime.now(UTC))
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO proactive_outreach(
                    created_at, session_id, trigger, category,
                    metric, event_key, opening
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    timestamp.isoformat(),
                    session_id,
                    decision.trigger.value,
                    decision.category.value if decision.category else None,
                    decision.metric,
                    event_key,
                    opening,
                ),
            )

    def save_analysis(self, result: AnalysisResult) -> None:
        payload = json.dumps(result.to_dict(), ensure_ascii=False)
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO analysis_runs(generated_at, decision_topic, payload_json)
                VALUES (?, ?, ?)
                """,
                (
                    result.generated_at.isoformat(),
                    result.decision.category.value
                    if result.decision.category
                    else result.decision.trigger.value
                    if result.decision.trigger
                    else None,
                    payload,
                ),
            )

    def purge(self, now: datetime | None = None) -> None:
        current = _utc(now or datetime.now(UTC))
        daily_cutoff = (current.date() - timedelta(days=400)).isoformat()
        analysis_cutoff = (current - timedelta(days=30)).isoformat()
        outreach_cutoff = (current - timedelta(days=90)).isoformat()
        policy_cutoff = (current.date() - timedelta(days=120)).isoformat()
        with self._lock, self._connect() as connection:
            connection.execute("DELETE FROM daily_metrics WHERE day < ?", (daily_cutoff,))
            connection.execute(
                "DELETE FROM analysis_runs WHERE generated_at < ?",
                (analysis_cutoff,),
            )
            connection.execute(
                "DELETE FROM proactive_outreach WHERE created_at < ?",
                (outreach_cutoff,),
            )
            connection.execute(
                "DELETE FROM daily_policies WHERE policy_day < ?",
                (policy_cutoff,),
            )

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path)


def _utc(value: datetime) -> datetime:
    aware = value if value.tzinfo else value.replace(tzinfo=UTC)
    return aware.astimezone(UTC)
