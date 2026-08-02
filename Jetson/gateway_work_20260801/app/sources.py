from __future__ import annotations

import bisect
import json
import re
import sqlite3
from collections import defaultdict
from contextlib import contextmanager
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any

from .models import DataBundle, DailyMetric, ExternalComparison
from .timezones import load_timezone


UTC = timezone.utc


class LightTimeline:
    def __init__(self, points: list[tuple[datetime, str]] | None = None):
        ordered = sorted(points or [], key=lambda item: item[0])
        self.timestamps = [item[0] for item in ordered]
        self.states = [item[1] for item in ordered]

    def state_at(self, timestamp: datetime) -> str | None:
        index = bisect.bisect_right(self.timestamps, timestamp) - 1
        return self.states[index] if index >= 0 else None


class UnifiedJetsonDataReader:
    """Read only the Jetson data sources authorized for proactive counseling."""

    def __init__(
        self,
        room_database: Path,
        home_assistant_database: Path,
        derived_insights_path: Path,
        calendar_path: Path,
        timezone_name: str = "Asia/Seoul",
    ):
        self.room_database = room_database
        self.home_assistant_database = home_assistant_database
        self.derived_insights_path = derived_insights_path
        self.calendar_path = calendar_path
        self.timezone = load_timezone(timezone_name)

    def collect(
        self,
        now: datetime | None = None,
        history_days: int | dict[str, int] = 60,
    ) -> DataBundle:
        current = _aware(now or datetime.now(UTC))
        if isinstance(history_days, dict):
            room_days = history_days.get("room", 60)
            home_days = history_days.get("home_assistant", 60)
        else:
            room_days = home_days = history_days
        room_start = self._history_start(current, room_days)
        home_start = self._history_start(current, home_days)
        bundle = DataBundle(
            context={
                "now": current.isoformat(),
                "timezone": str(self.timezone),
                "excluded_sources": [
                    "voice_emotion",
                    "rppg",
                    "facial_expression",
                    "gallery",
                ],
            }
        )
        light_timeline = self._read_home_assistant(bundle, current, home_start)
        self._read_room(bundle, current, room_start, light_timeline)
        self._read_derived_insights(bundle, current)
        self._read_calendar(bundle, current)
        return bundle

    def _read_room(
        self,
        bundle: DataBundle,
        now: datetime,
        history_start: datetime,
        lights: LightTimeline,
    ) -> None:
        path = self.room_database
        if not path.is_file():
            bundle.source_status["room"] = {"available": False}
            return
        try:
            with _read_only(path) as connection:
                rows = connection.execute(
                    """
                    SELECT f.observed_at, o.subject_id, o.track_id,
                           o.posture, o.motion, o.posture_duration_sec,
                           o.inactivity_duration_sec, o.posture_confidence,
                           o.keypoint_confidence, o.frame_brightness,
                           o.lighting_state, o.analysis_status
                    FROM room_observations o
                    JOIN room_frames f ON f.id = o.frame_id
                    WHERE f.observed_at_ms >= ?
                    ORDER BY f.observed_at, o.id
                    """,
                    (round(history_start.timestamp() * 1000),),
                ).fetchall()
                frame = connection.execute(
                    """
                    SELECT id, observed_at, target_observed,
                           target_analysis_usable,
                           COALESCE(target_identity_confidence, 0)
                    FROM room_frames ORDER BY id DESC LIMIT 1
                    """
                ).fetchone()
                current_observation = connection.execute(
                    """
                    SELECT f.observed_at, o.posture, o.motion,
                           o.posture_duration_sec, o.inactivity_duration_sec,
                           o.posture_confidence, o.keypoint_confidence,
                           o.analysis_status
                    FROM room_observations o
                    JOIN room_frames f ON f.id = o.frame_id
                    ORDER BY o.id DESC LIMIT 1
                    """
                ).fetchone()
                morning = self._morning_first_seen(connection, now)
                people_count = 0
                if frame:
                    people_count = connection.execute(
                        """
                        SELECT COUNT(DISTINCT track_id)
                        FROM room_observations WHERE frame_id = ?
                        """,
                        (frame[0],),
                    ).fetchone()[0]
        except (OSError, sqlite3.Error, ValueError) as exc:
            bundle.source_status["room"] = {
                "available": False,
                "error": type(exc).__name__,
            }
            return

        sums: dict[tuple[date, str], float] = defaultdict(float)
        maxima: dict[tuple[date, str], float] = defaultdict(float)
        qualities: dict[date, list[float]] = defaultdict(list)
        previous: dict[tuple[str, int], tuple] = {}
        for row in rows:
            try:
                timestamp = _parse_timestamp(row[0])
            except ValueError:
                continue
            key = (str(row[1]), int(row[2]))
            prior = previous.get(key)
            previous[key] = (timestamp, *row[3:])
            local_day = timestamp.astimezone(self.timezone).date()
            quality = _quality(float(row[7]), float(row[8]))
            qualities[local_day].append(quality)
            maxima[(local_day, "max_sitting_bout_minutes")] = max(
                maxima[(local_day, "max_sitting_bout_minutes")],
                float(row[5]) / 60 if row[3] == "sitting" else 0.0,
            )
            maxima[(local_day, "max_lying_awake_bout_minutes")] = max(
                maxima[(local_day, "max_lying_awake_bout_minutes")],
                (
                    float(row[5]) / 60
                    if row[3] == "lying" and not self._likely_sleep(timestamp, lights)
                    else 0.0
                ),
            )
            maxima[(local_day, "max_inactivity_bout_minutes")] = max(
                maxima[(local_day, "max_inactivity_bout_minutes")],
                float(row[6]) / 60,
            )
            if prior is None:
                continue
            prior_timestamp = prior[0]
            seconds = (timestamp - prior_timestamp).total_seconds()
            if not 0 < seconds <= 5:
                continue
            day = prior_timestamp.astimezone(self.timezone).date()
            posture, motion, _, _, posture_confidence, keypoint_confidence, brightness = prior[1:8]
            qualities[day].append(
                _quality(float(posture_confidence), float(keypoint_confidence))
            )
            if posture in {"sitting", "standing"}:
                sums[(day, f"{posture}_minutes")] += seconds / 60
            if posture == "lying" and not self._likely_sleep(prior_timestamp, lights):
                sums[(day, "lying_awake_minutes")] += seconds / 60
            if motion == "walking":
                sums[(day, "walking_minutes")] += seconds / 60
            sums[(day, "brightness_total")] += float(brightness) * seconds
            sums[(day, "brightness_seconds")] += seconds

        days = {key[0] for key in sums} | {key[0] for key in maxima}
        for day in sorted(days):
            quality = (
                sum(qualities[day]) / len(qualities[day])
                if qualities[day]
                else 0.5
            )
            for metric in (
                "sitting_minutes",
                "standing_minutes",
                "walking_minutes",
                "lying_awake_minutes",
            ):
                if (day, metric) in sums:
                    bundle.daily_metrics.append(
                        DailyMetric(metric, day, sums[(day, metric)], quality, "room_db")
                    )
            seconds = sums.get((day, "brightness_seconds"), 0.0)
            if seconds:
                bundle.daily_metrics.append(
                    DailyMetric(
                        "room_brightness",
                        day,
                        sums[(day, "brightness_total")] / seconds,
                        quality,
                        "room_db",
                    )
                )
            for metric in (
                "max_sitting_bout_minutes",
                "max_lying_awake_bout_minutes",
                "max_inactivity_bout_minutes",
            ):
                if maxima.get((day, metric), 0) > 0:
                    bundle.daily_metrics.append(
                        DailyMetric(metric, day, maxima[(day, metric)], quality, "room_db")
                    )

        if frame:
            observed_at = _parse_timestamp(frame[1])
            fresh = 0 <= (now - observed_at).total_seconds() <= 120
            bundle.context.update(
                {
                    "target_present": bool(frame[2]) and bool(frame[3]) and fresh,
                    "target_identity_confidence": float(frame[4] or 0),
                    "multiple_people_present": int(people_count) > 1,
                    "room_observed_at": observed_at.isoformat(),
                    "morning_first_seen": bool(morning and fresh),
                }
            )
        if current_observation:
            observed_at = _parse_timestamp(current_observation[0])
            fresh = 0 <= (now - observed_at).total_seconds() <= 120
            likely_sleep = self._likely_sleep(observed_at, lights)
            bundle.context.update(
                {
                    "current_posture": current_observation[1] if fresh else None,
                    "current_motion": current_observation[2] if fresh else None,
                    "current_posture_bout_minutes": (
                        round(float(current_observation[3]) / 60, 2) if fresh else 0
                    ),
                    "current_inactivity_bout_minutes": (
                        round(float(current_observation[4]) / 60, 2) if fresh else 0
                    ),
                    "current_likely_sleep": likely_sleep if fresh else False,
                }
            )
        bundle.source_status["room"] = {
            "available": True,
            "rows": len(rows),
            "latest_fresh": bool(bundle.context.get("target_present")),
            "history_start": history_start.isoformat(),
        }

    def _read_home_assistant(
        self,
        bundle: DataBundle,
        now: datetime,
        history_start: datetime,
    ) -> LightTimeline:
        path = self.home_assistant_database
        if not path.is_file():
            bundle.source_status["home_assistant"] = {"available": False}
            return LightTimeline()
        try:
            with _read_only(path) as connection:
                light_rows = connection.execute(
                    """
                    SELECT occurred_at, entity_id, state, friendly_name
                    FROM light_events
                    WHERE occurred_at_ts >= ? ORDER BY occurred_at_ts
                    """,
                    (history_start.timestamp(),),
                ).fetchall()
                previous_lights = connection.execute(
                    """
                    SELECT current.occurred_at, current.entity_id,
                           current.state, current.friendly_name
                    FROM light_events current
                    JOIN (
                        SELECT entity_id, MAX(occurred_at_ts) AS latest
                        FROM light_events
                        WHERE occurred_at_ts < ? GROUP BY entity_id
                    ) previous
                    ON previous.entity_id = current.entity_id
                       AND previous.latest = current.occurred_at_ts
                    """,
                    (history_start.timestamp(),),
                ).fetchall()
                access_rows = connection.execute(
                    """
                    SELECT occurred_at, direction, authorized, result
                    FROM access_events
                    WHERE occurred_at_ts >= ? ORDER BY occurred_at_ts
                    """,
                    (history_start.timestamp(),),
                ).fetchall()
                previous_access = connection.execute(
                    """
                    SELECT occurred_at, direction, authorized, result
                    FROM access_events
                    WHERE occurred_at_ts < ?
                    ORDER BY occurred_at_ts DESC LIMIT 1
                    """,
                    (history_start.timestamp(),),
                ).fetchone()
        except (OSError, sqlite3.Error) as exc:
            bundle.source_status["home_assistant"] = {
                "available": False,
                "error": type(exc).__name__,
            }
            return LightTimeline()

        light_rows = sorted(
            [*previous_lights, *light_rows],
            key=lambda row: str(row[0]),
        )
        if previous_access:
            access_rows.insert(0, previous_access)
        bedroom_entity = _select_bedroom_entity(light_rows)
        points = [
            (_parse_timestamp(row[0]), str(row[2]))
            for row in light_rows
            if row[1] == bedroom_entity
        ]
        timeline = LightTimeline(points)
        if points:
            bundle.context["bedroom_light_on"] = points[-1][1] == "on"
            bundle.context["bedroom_light_observed_at"] = points[-1][0].isoformat()
            sleep_light: dict[date, float] = defaultdict(float)
            for index, (started, state) in enumerate(points):
                started = max(started, history_start)
                ended = points[index + 1][0] if index + 1 < len(points) else now
                if state != "on" or ended <= started:
                    continue
                cursor = started
                while cursor < ended:
                    chunk_end = min(ended, cursor + timedelta(minutes=5))
                    local = cursor.astimezone(self.timezone)
                    if local.hour >= 22 or local.hour < 7:
                        sleep_light[local.date()] += (chunk_end - cursor).total_seconds() / 60
                    cursor = chunk_end
            for day, value in sleep_light.items():
                bundle.daily_metrics.append(
                    DailyMetric(
                        "light_on_sleep_minutes",
                        day,
                        value,
                        0.85,
                        "home_assistant_db",
                    )
                )

        deduped: list[tuple[datetime, str]] = []
        for occurred_at, direction, authorized, _ in access_rows:
            if not bool(authorized) or direction not in {"ENTER", "EXIT"}:
                continue
            timestamp = _parse_timestamp(occurred_at)
            if (
                deduped
                and deduped[-1][1] == direction
                and timestamp - deduped[-1][0] < timedelta(seconds=90)
            ):
                continue
            deduped.append((timestamp, str(direction)))
        outing_count: dict[date, float] = defaultdict(float)
        outing_duration: dict[date, float] = defaultdict(float)
        pending_exit: datetime | None = None
        for timestamp, direction in deduped:
            local_day = timestamp.astimezone(self.timezone).date()
            if direction == "EXIT":
                pending_exit = timestamp
                outing_count[local_day] += 1
            elif pending_exit and timedelta(0) < timestamp - pending_exit <= timedelta(days=1):
                exit_day = pending_exit.astimezone(self.timezone).date()
                outing_duration[exit_day] += (timestamp - pending_exit).total_seconds() / 60
                pending_exit = None
        for day in sorted(set(outing_count) | set(outing_duration)):
            bundle.daily_metrics.append(
                DailyMetric("outing_count", day, outing_count[day], 0.6, "home_assistant_db")
            )
            if outing_duration[day] > 0:
                bundle.daily_metrics.append(
                    DailyMetric(
                        "outing_duration_minutes",
                        day,
                        outing_duration[day],
                        0.6,
                        "home_assistant_db",
                    )
                )
        if deduped:
            bundle.context["latest_access_direction"] = deduped[-1][1]
            bundle.context["latest_access_at"] = deduped[-1][0].isoformat()
        bundle.source_status["home_assistant"] = {
            "available": True,
            "light_events": len(light_rows),
            "access_events_after_debounce": len(deduped),
            "access_identity_scope": "household_not_person_specific",
            "history_start": history_start.isoformat(),
        }
        return timeline

    def _history_start(self, now: datetime, days: int) -> datetime:
        safe_days = max(2, min(60, int(days)))
        local_day = now.astimezone(self.timezone).date()
        first_day = local_day - timedelta(days=safe_days - 1)
        return datetime.combine(first_day, time.min, self.timezone).astimezone(UTC)

    def _read_derived_insights(self, bundle: DataBundle, now: datetime) -> None:
        path = self.derived_insights_path
        if not path.is_file():
            bundle.source_status["android_summaries"] = {"available": False}
            return
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            generated_at = _epoch(payload.get("generated_at"))
        except (OSError, UnicodeError, ValueError, json.JSONDecodeError) as exc:
            bundle.source_status["android_summaries"] = {
                "available": False,
                "error": type(exc).__name__,
            }
            return
        age = now - generated_at
        if age < timedelta(hours=-24) or age > timedelta(days=14):
            bundle.source_status["android_summaries"] = {
                "available": False,
                "reason": "stale",
            }
            return
        summaries = {
            str(item.get("category")): str(item.get("summary", ""))
            for item in payload.get("summaries", [])
            if isinstance(item, dict)
            and item.get("category") in {"HEALTH", "PHENOTYPE", "CALENDAR"}
        }
        health = summaries.get("HEALTH", "")
        for raw_day, raw_steps in re.findall(
            r"(\d{4}\.\d{2}\.\d{2}):\s*걸음\s*([\d,]+)", health
        ):
            bundle.daily_metrics.append(
                DailyMetric(
                    "steps",
                    datetime.strptime(raw_day, "%Y.%m.%d").date(),
                    float(raw_steps.replace(",", "")),
                    0.8,
                    "android_health_summary",
                )
            )
        sleep_profile = self._sleep_profile(payload.get("sleep_profile"))
        if sleep_profile:
            bundle.context["sleep_profile"] = sleep_profile
            bundle.context["expected_sleep_time"] = sleep_profile["average_bedtime"]
        phenotype = summaries.get("PHENOTYPE", "")
        self._parse_phone_summary(bundle, phenotype, generated_at)
        bundle.source_status["android_summaries"] = {
            "available": bool(health or phenotype),
            "generated_at": generated_at.isoformat(),
            "accepted_categories": sorted(summaries),
            "ignored_categories": ["GALLERY"],
            "sleep_profile_available": bool(sleep_profile),
        }

    @staticmethod
    def _sleep_profile(value: Any) -> dict[str, Any] | None:
        if not isinstance(value, dict):
            return None
        raw_bedtime = value.get("average_bedtime")
        if not isinstance(raw_bedtime, str):
            return None
        try:
            bedtime = time.fromisoformat(raw_bedtime.strip())
        except ValueError:
            return None
        profile: dict[str, Any] = {"average_bedtime": bedtime.strftime("%H:%M")}
        try:
            sample_size = int(value.get("sample_size", 0))
        except (TypeError, ValueError):
            sample_size = 0
        if sample_size > 0:
            profile["sample_size"] = min(sample_size, 60)
        return profile

    def _parse_phone_summary(
        self,
        bundle: DataBundle,
        summary: str,
        observed_at: datetime,
    ) -> None:
        call = re.search(
            r"이번 주 통화\s*([\d,]+)회,\s*지난 주 통화\s*([\d,]+)회",
            summary,
        )
        if call:
            bundle.comparisons.append(
                ExternalComparison(
                    "call_count_week",
                    float(call.group(1).replace(",", "")),
                    float(call.group(2).replace(",", "")),
                    0.85,
                    "android_phenotype_summary",
                    observed_at,
                )
            )
        patterns = {
            "phone_screen_daily_minutes": r"일평균 스크린타임\s*([\d,]+)분",
            "phone_night_daily_minutes": r"일평균 야간 사용\s*([\d,]+)분",
            "phone_longest_session_minutes": r"최장 연속 세션\s*([\d,]+)분",
        }
        weekly = re.search(r"주간 스크린타임 변화\s*(-?[\d.]+)%", summary)
        weekly_change = float(weekly.group(1)) / 100 if weekly else None
        for metric, pattern in patterns.items():
            match = re.search(pattern, summary)
            if not match:
                continue
            current = float(match.group(1).replace(",", ""))
            if metric == "phone_screen_daily_minutes" and weekly_change is not None and weekly_change > -0.99:
                baseline = current / (1 + weekly_change)
            else:
                baseline = current
            bundle.comparisons.append(
                ExternalComparison(
                    metric,
                    current,
                    baseline,
                    0.8,
                    "android_phenotype_summary",
                    observed_at,
                    {
                        "reported_weekly_change": weekly_change,
                        "baseline_available": (
                            metric == "phone_screen_daily_minutes"
                            and weekly_change is not None
                        ),
                    },
                )
            )
        apps = re.findall(r"^-\s*([^:\n]+):\s*([\d,]+)분\s*\(([^)]+)\)", summary, re.MULTILINE)
        bundle.context["phone_top_app_categories"] = [
            {"label": name.strip()[:40], "minutes": int(minutes.replace(",", "")), "category": category[:30]}
            for name, minutes, category in apps[:5]
        ]

    def _read_calendar(self, bundle: DataBundle, now: datetime) -> None:
        path = self.calendar_path
        if not path.is_file():
            bundle.source_status["calendar"] = {
                "available": False,
                "reason": "optional_adapter_waiting_for_calendar_export",
            }
            return
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            events = payload.get("events", payload) if isinstance(payload, dict) else payload
            if not isinstance(events, list):
                raise ValueError("events must be a list")
        except (OSError, UnicodeError, ValueError, json.JSONDecodeError) as exc:
            bundle.source_status["calendar"] = {
                "available": False,
                "error": type(exc).__name__,
            }
            return
        upcoming = []
        for item in events:
            if not isinstance(item, dict) or item.get("status") == "cancelled":
                continue
            try:
                start = _parse_timestamp(item["start"])
            except (KeyError, TypeError, ValueError):
                continue
            hours = (start - now).total_seconds() / 3600
            if 0 <= hours <= 24:
                upcoming.append(
                    {
                        "event_id": str(item.get("id") or f"calendar-{int(start.timestamp())}"),
                        "category": _calendar_category(item),
                        "start": start.isoformat(),
                        "hours_until": round(hours, 2),
                    }
                )
        bundle.context["calendar_upcoming"] = sorted(
            upcoming, key=lambda item: item["hours_until"]
        )[:5]
        bundle.source_status["calendar"] = {
            "available": True,
            "upcoming_count": len(upcoming),
        }

    def _morning_first_seen(
        self,
        connection: sqlite3.Connection,
        now: datetime,
    ) -> bool:
        local = now.astimezone(self.timezone)
        if not time(5, 30) <= local.time().replace(tzinfo=None) <= time(10, 30):
            return False
        start = datetime.combine(local.date(), time(5, 30), self.timezone).astimezone(UTC)
        row = connection.execute(
            """
            SELECT observed_at FROM room_events
            WHERE event_type = 'PERSON_APPEAR' AND observed_at >= ?
            ORDER BY observed_at LIMIT 1
            """,
            (start.isoformat(),),
        ).fetchone()
        if not row:
            return False
        first = _parse_timestamp(row[0])
        return timedelta(0) <= now - first <= timedelta(minutes=2)

    def _likely_sleep(self, timestamp: datetime, lights: LightTimeline) -> bool:
        local_hour = timestamp.astimezone(self.timezone).hour
        if 7 <= local_hour < 22:
            return False
        state = lights.state_at(timestamp)
        return state != "on"


@contextmanager
def _read_only(path: Path):
    uri = path.resolve().as_uri() + "?mode=ro"
    connection = sqlite3.connect(uri, uri=True)
    try:
        yield connection
    finally:
        connection.close()


def _parse_timestamp(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ValueError("timestamp must be text")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return _aware(parsed)


def _aware(value: datetime) -> datetime:
    aware = value if value.tzinfo else value.replace(tzinfo=UTC)
    return aware.astimezone(UTC)


def _epoch(value: Any) -> datetime:
    timestamp = float(value)
    if timestamp > 10_000_000_000:
        timestamp /= 1000
    return datetime.fromtimestamp(timestamp, UTC)


def _quality(posture_confidence: float, keypoint_confidence: float) -> float:
    if posture_confidence <= 0:
        posture_confidence = 0.5
    return max(0.0, min(1.0, (posture_confidence + keypoint_confidence) / 2))


def _select_bedroom_entity(rows: list[tuple]) -> str | None:
    entities: list[str] = []
    for _, entity_id, _, friendly_name in rows:
        entity = str(entity_id)
        label = f"{entity} {friendly_name or ''}".lower()
        if any(token in label for token in ("cimsil", "bedroom", "침실")):
            return entity
        if entity not in entities:
            entities.append(entity)
    return entities[0] if entities else None


def _calendar_category(item: dict[str, Any]) -> str:
    raw = str(item.get("category") or item.get("title_category") or "일정")
    allowed = {"진료", "운동", "모임", "외출", "복약", "일정"}
    return raw if raw in allowed else "일정"
