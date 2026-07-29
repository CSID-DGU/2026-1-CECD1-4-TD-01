#!/usr/bin/env python3
"""Standard-library client for On-mom analysis producers.

Examples:

    python3 onmom_client.py event \
      --source hub.camera --source-family physiology.heart_rate \
      --domain PHYSIOLOGICAL_STATE --event-type rppg_window \
      --metrics-json '{"heart_rate_bpm":72,"signal_quality":0.91}'

    python3 onmom_client.py sleep --state ASLEEP --confidence 0.95

    python3 onmom_client.py context \
      --topic PHYSIOLOGICAL_STATE --valence -0.4 --arousal 0.7
"""

from __future__ import annotations

import argparse
import json
import time
import uuid
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen

from context_engine import (
    ALLOWED_DOMAINS,
    QUALITY_STATUSES,
    STRATEGIES,
)
from derived_insight_server import default_token_file, load_bearer_token


def epoch_ms() -> int:
    return int(time.time() * 1000)


def service_url(base_url: str, path: str, query: dict[str, Any] | None = None) -> str:
    parsed = urlsplit(base_url.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("base URL must be an http(s) URL")
    encoded_query = urlencode(query or {}, doseq=True)
    return urlunsplit((parsed.scheme, parsed.netloc, path, encoded_query, ""))


def request_json(
    url: str,
    token: str,
    *,
    method: str = "GET",
    schema: str | None = None,
    body: dict[str, Any] | None = None,
    timeout: float = 10.0,
) -> dict[str, Any]:
    encoded = None if body is None else json.dumps(
        body,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
    }
    if encoded is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
    if schema:
        headers["X-OnMom-Schema"] = schema
    request = Request(url, data=encoded, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"On-mom HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        raise RuntimeError(f"Could not connect to On-mom service: {exc.reason}") from exc


def build_event(
    *,
    source: str,
    source_family: str,
    domain: str,
    event_type: str,
    metrics: dict[str, Any],
    status: str = "VALID",
    confidence: float = 1.0,
    coverage: float = 1.0,
    reasons: list[str] | None = None,
    occurred_at: int | None = None,
    ttl_minutes: int | None = None,
    event_id: str | None = None,
) -> dict[str, Any]:
    timestamp = epoch_ms() if occurred_at is None else occurred_at
    return {
        "schema_version": 1,
        "event_id": event_id or str(uuid.uuid4()),
        "source": source,
        "source_family": source_family,
        "domain": domain,
        "event_type": event_type,
        "occurred_at": timestamp,
        "expires_at": None if ttl_minutes is None else timestamp + ttl_minutes * 60_000,
        "contains_raw_data": False,
        "metrics": metrics,
        "quality": {
            "status": status,
            "confidence": confidence,
            "coverage": coverage,
            "reasons": reasons or [],
        },
    }


def send_event(base_url: str, token: str, event: dict[str, Any]) -> dict[str, Any]:
    return request_json(
        service_url(base_url, "/v1/analysis-events"),
        token,
        method="POST",
        schema="analysis-event-v1",
        body=event,
    )


def send_session_outcome(
    base_url: str,
    token: str,
    outcome: dict[str, Any],
) -> dict[str, Any]:
    return request_json(
        service_url(base_url, "/v1/session-outcomes"),
        token,
        method="POST",
        schema="session-outcome-v1",
        body=outcome,
    )


def read_context(
    base_url: str,
    token: str,
    *,
    topics: list[str],
    max_cards: int = 3,
    valence: float | None = None,
    arousal: float | None = None,
) -> dict[str, Any]:
    query: dict[str, Any] = {
        "topic": ",".join(topics),
        "max_cards": max_cards,
    }
    if valence is not None:
        query["valence"] = valence
    if arousal is not None:
        query["arousal"] = arousal
    return request_json(service_url(base_url, "/v1/context", query), token)


def _json_object(text: str) -> dict[str, Any]:
    value = json.loads(text)
    if not isinstance(value, dict):
        raise argparse.ArgumentTypeError("value must be a JSON object")
    return value


def _comma_values(text: str) -> list[str]:
    return [item.strip().upper() for item in text.split(",") if item.strip()]


def _add_connection_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--base-url", default="http://localhost:8765")
    parser.add_argument("--token-file", type=Path, default=default_token_file())
    parser.add_argument("--timeout", type=float, default=10.0)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Send derived data to On-mom Jetson")
    subparsers = parser.add_subparsers(dest="command", required=True)

    event = subparsers.add_parser("event", help="send an analysis-event-v1")
    _add_connection_args(event)
    event.add_argument("--source", required=True)
    event.add_argument("--source-family", required=True)
    event.add_argument("--domain", choices=sorted(ALLOWED_DOMAINS), required=True)
    event.add_argument("--event-type", required=True)
    event.add_argument("--metrics-json", type=_json_object, required=True)
    event.add_argument("--status", choices=sorted(QUALITY_STATUSES), default="VALID")
    event.add_argument("--confidence", type=float, default=1.0)
    event.add_argument("--coverage", type=float, default=1.0)
    event.add_argument("--reason", action="append", default=[])
    event.add_argument("--occurred-at", type=int)
    event.add_argument("--ttl-minutes", type=int)
    event.add_argument("--event-id")

    sleep = subparsers.add_parser("sleep", help="send a sleep gate state")
    _add_connection_args(sleep)
    sleep.add_argument("--state", choices=["ASLEEP", "LIKELY_ASLEEP", "AWAKE"], required=True)
    sleep.add_argument("--confidence", type=float, default=0.9)
    sleep.add_argument("--coverage", type=float, default=1.0)
    sleep.add_argument("--ttl-minutes", type=int, default=30)

    session = subparsers.add_parser("session", help="record a derived counseling outcome")
    _add_connection_args(session)
    session.add_argument("--session-id", default=None)
    session.add_argument("--started-at", type=int, required=True)
    session.add_argument("--ended-at", type=int, default=None)
    session.add_argument("--before-valence", type=float, required=True)
    session.add_argument("--before-arousal", type=float, required=True)
    session.add_argument("--before-confidence", type=float, required=True)
    session.add_argument("--after-valence", type=float, required=True)
    session.add_argument("--after-arousal", type=float, required=True)
    session.add_argument("--after-confidence", type=float, required=True)
    session.add_argument("--strategies", type=_comma_values, required=True)
    session.add_argument("--feedback", type=float)

    context = subparsers.add_parser("context", help="read prompt-safe adaptive context")
    _add_connection_args(context)
    context.add_argument("--topic", action="append", default=[])
    context.add_argument("--max-cards", type=int, default=3)
    context.add_argument("--valence", type=float)
    context.add_argument("--arousal", type=float)

    policy = subparsers.add_parser("policy", help="inspect adaptive policy weights")
    _add_connection_args(policy)

    status = subparsers.add_parser("status", help="inspect learning progress")
    _add_connection_args(status)

    rollback = subparsers.add_parser("rollback", help="restore a saved policy version")
    _add_connection_args(rollback)
    rollback.add_argument("--version", type=int, required=True)

    adapt = subparsers.add_parser("adapt", help="request sleep-gated or forced adaptation")
    _add_connection_args(adapt)
    adapt.add_argument("--force", action="store_true")
    return parser.parse_args()


def _load_required_token(path: Path) -> str:
    token = load_bearer_token(path)
    if not token:
        raise RuntimeError(f"token is missing: {path}")
    return token


def main() -> None:
    args = parse_args()
    token = _load_required_token(args.token_file)

    if args.command == "event":
        payload = build_event(
            source=args.source,
            source_family=args.source_family,
            domain=args.domain,
            event_type=args.event_type,
            metrics=args.metrics_json,
            status=args.status,
            confidence=args.confidence,
            coverage=args.coverage,
            reasons=args.reason,
            occurred_at=args.occurred_at,
            ttl_minutes=args.ttl_minutes,
            event_id=args.event_id,
        )
        result = send_event(args.base_url, token, payload)
    elif args.command == "sleep":
        payload = build_event(
            source="home_assistant",
            source_family="sleep.presence",
            domain="SLEEP_AND_ROUTINE",
            event_type="sleep_state",
            metrics={"state": args.state},
            confidence=args.confidence,
            coverage=args.coverage,
            ttl_minutes=args.ttl_minutes,
        )
        result = send_event(args.base_url, token, payload)
    elif args.command == "session":
        unsupported = [strategy for strategy in args.strategies if strategy not in STRATEGIES]
        if unsupported:
            raise SystemExit(f"unsupported strategies: {', '.join(unsupported)}")
        result = send_session_outcome(
            args.base_url,
            token,
            {
                "schema_version": 1,
                "session_id": args.session_id or str(uuid.uuid4()),
                "started_at": args.started_at,
                "ended_at": args.ended_at or epoch_ms(),
                "before": {
                    "valence": args.before_valence,
                    "arousal": args.before_arousal,
                    "confidence": args.before_confidence,
                },
                "after": {
                    "valence": args.after_valence,
                    "arousal": args.after_arousal,
                    "confidence": args.after_confidence,
                },
                "strategies": args.strategies,
                "explicit_feedback": args.feedback,
                "contains_raw_data": False,
            },
        )
    elif args.command == "context":
        topics = [
            topic.strip().upper()
            for value in args.topic
            for topic in value.split(",")
            if topic.strip()
        ]
        result = read_context(
            args.base_url,
            token,
            topics=topics,
            max_cards=args.max_cards,
            valence=args.valence,
            arousal=args.arousal,
        )
    elif args.command == "policy":
        result = request_json(service_url(args.base_url, "/v1/policy"), token)
    elif args.command == "status":
        result = request_json(service_url(args.base_url, "/v1/learning-status"), token)
    elif args.command == "rollback":
        result = request_json(
            service_url(args.base_url, "/v1/policy/rollback"),
            token,
            method="POST",
            schema="policy-rollback-v1",
            body={"version": args.version},
        )
    else:
        result = request_json(
            service_url(args.base_url, "/v1/nightly-adapt"),
            token,
            method="POST",
            schema="nightly-adapt-v1",
            body={"force": args.force},
        )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

