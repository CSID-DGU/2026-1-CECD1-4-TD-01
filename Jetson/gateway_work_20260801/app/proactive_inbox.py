from __future__ import annotations

import json
import time
from collections import defaultdict, deque
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock

from .schemas import ProactiveMessageRequest


class ProactiveMessageInbox:
    """Session-scoped, deduplicated handoff for an external proactive algorithm."""

    def __init__(self, log_path: Path, max_events_per_session: int = 100):
        self.log_path = log_path
        self.max_events_per_session = max_events_per_session
        self._lock = Lock()
        self._active_session_id: str | None = None
        self._events: dict[str, deque[dict]] = defaultdict(
            lambda: deque(maxlen=max_events_per_session)
        )
        self._event_ids: set[str] = set()
        self._acknowledged: set[str] = set()

    def set_active_session(self, session_id: str) -> None:
        with self._lock:
            self._active_session_id = session_id

    @property
    def active_session_id(self) -> str | None:
        with self._lock:
            return self._active_session_id

    def submit(self, request: ProactiveMessageRequest) -> tuple[dict, bool]:
        now = time.time()
        with self._lock:
            target = request.session_id or self._active_session_id
            if not target:
                raise RuntimeError("활성 상담 세션이 없습니다.")
            if request.event_id in self._event_ids:
                existing = self._find_locked(request.event_id)
                if existing is None:
                    raise RuntimeError("이미 사용된 event_id입니다.")
                return dict(existing), False
            event = {
                "event_id": request.event_id,
                "session_id": target,
                "text": request.text.strip(),
                "source": request.source,
                "topic": request.topic,
                "priority": request.priority,
                "greeting_pose": request.greeting_pose,
                "created_at": _iso_now(),
                "expires_at_epoch": now + request.expires_in_seconds,
            }
            self._events[target].append(event)
            self._event_ids.add(request.event_id)
            self._append_log_locked({"event": "submitted", **event})
            return dict(event), True

    def next_event(
        self,
        session_id: str,
        after_event_id: str | None = None,
    ) -> dict | None:
        now = time.time()
        with self._lock:
            queue = self._events.get(session_id)
            if not queue:
                return None
            valid = [
                event
                for event in queue
                if float(event["expires_at_epoch"]) > now
            ]
            self._events[session_id] = deque(
                valid,
                maxlen=self.max_events_per_session,
            )
            start = 0
            if after_event_id:
                for index, event in enumerate(valid):
                    if event["event_id"] == after_event_id:
                        start = index + 1
                        break
            return dict(valid[start]) if start < len(valid) else None

    def acknowledge(
        self,
        session_id: str,
        event_id: str,
    ) -> tuple[dict | None, bool]:
        with self._lock:
            event = self._find_locked(event_id, session_id)
            if event is None:
                return None, False
            first_ack = event_id not in self._acknowledged
            if first_ack:
                self._acknowledged.add(event_id)
                self._append_log_locked(
                    {
                        "event": "acknowledged",
                        "event_id": event_id,
                        "session_id": session_id,
                        "acknowledged_at": _iso_now(),
                    }
                )
            return dict(event), first_ack

    def _find_locked(
        self,
        event_id: str,
        session_id: str | None = None,
    ) -> dict | None:
        queues = (
            [self._events.get(session_id, ())]
            if session_id
            else self._events.values()
        )
        for queue in queues:
            for event in queue:
                if event["event_id"] == event_id:
                    return event
        return None

    def _append_log_locked(self, event: dict) -> None:
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        with self.log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, ensure_ascii=False) + "\n")


def _iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()
