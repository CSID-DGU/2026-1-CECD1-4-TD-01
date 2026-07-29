#!/usr/bin/env python3
"""Minimal Jetson receiver for On-mom derived insights.

The receiver accepts the app's fixed derived-only schema and rejects extra
fields or markers that commonly identify raw files, URIs, or chat input.
"""

from __future__ import annotations

import argparse
import hmac
import json
import os
import ssl
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from camera_analysis_bridge import CameraUdpWorker
from context_engine import (
    ContextEngine,
    ContextEngineError,
    NightlyAdaptationWorker,
)

MAX_BODY_BYTES = 256 * 1024
MAX_SUMMARY_CHARS = 64_000
ALLOWED_CATEGORIES = {"HEALTH", "PHENOTYPE", "GALLERY", "VOICE_EMOTION"}
TOP_LEVEL_KEYS = {
    "schema_version",
    "transfer_id",
    "generated_at",
    "producer",
    "contains_raw_data",
    "summaries",
}
SUMMARY_KEYS = {"category", "updated_at", "expires_at", "summary"}
FORBIDDEN_MARKERS = (
    "content://",
    "file://",
    "/storage/",
    "/sdcard/",
    "data:image",
    "data:audio",
    ";base64,",
    '"cachedname"',
    '"packagename"',
    '"imagepath"',
    '"audiopath"',
    '"uri"',
    "[현재 사용자 메시지]",
    "[사용자 입력]",
)


class PayloadError(ValueError):
    pass


def default_token_file() -> Path:
    config_home = os.environ.get("XDG_CONFIG_HOME")
    base = Path(config_home).expanduser() if config_home else Path.home() / ".config"
    return base / "onmom" / "jetson_sync_token"


def load_bearer_token(
    token_file: Path,
    environ: dict[str, str] | None = None,
) -> str:
    environment = os.environ if environ is None else environ
    environment_token = environment.get("JETSON_SYNC_TOKEN", "").strip()
    if environment_token:
        return environment_token
    if not token_file.exists():
        return ""
    if os.name == "posix" and token_file.stat().st_mode & 0o077:
        raise ValueError(f"token file permissions must be 0600: {token_file}")
    token = token_file.read_text(encoding="utf-8").strip()
    if not token:
        raise ValueError(f"token file is empty: {token_file}")
    return token


def validate_payload(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise PayloadError("JSON object required")
    if set(payload) != TOP_LEVEL_KEYS:
        raise PayloadError("top-level fields do not match derived-only schema")
    if payload.get("schema_version") != 1:
        raise PayloadError("unsupported schema_version")
    if payload.get("contains_raw_data") is not False:
        raise PayloadError("raw data payloads are not accepted")
    if payload.get("producer") != "onmom-android":
        raise PayloadError("unexpected producer")
    if not isinstance(payload.get("transfer_id"), str) or not payload["transfer_id"].strip():
        raise PayloadError("transfer_id required")
    if not isinstance(payload.get("generated_at"), int) or payload["generated_at"] <= 0:
        raise PayloadError("generated_at must be a positive integer")

    summaries = payload.get("summaries")
    if not isinstance(summaries, list) or not summaries or len(summaries) > len(ALLOWED_CATEGORIES):
        raise PayloadError("one to four summaries required")

    seen_categories: set[str] = set()
    for item in summaries:
        if not isinstance(item, dict) or set(item) != SUMMARY_KEYS:
            raise PayloadError("summary fields do not match derived-only schema")
        category = item.get("category")
        if category not in ALLOWED_CATEGORIES or category in seen_categories:
            raise PayloadError("invalid or duplicate summary category")
        seen_categories.add(category)
        if not isinstance(item.get("updated_at"), int) or item["updated_at"] <= 0:
            raise PayloadError("updated_at must be a positive integer")
        expires_at = item.get("expires_at")
        if expires_at is not None and (not isinstance(expires_at, int) or expires_at <= item["updated_at"]):
            raise PayloadError("expires_at must be null or later than updated_at")
        summary = item.get("summary")
        if not isinstance(summary, str) or not summary.strip() or len(summary) > MAX_SUMMARY_CHARS:
            raise PayloadError("summary is empty or too large")
        lowered = summary.lower()
        if any(marker in lowered for marker in FORBIDDEN_MARKERS):
            raise PayloadError("raw-data marker detected")

    return payload


def save_payload_atomically(payload: dict[str, Any], output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output_path.name}.",
        suffix=".tmp",
        dir=output_path.parent,
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary_name, 0o600)
        os.replace(temporary_name, output_path)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


class DerivedInsightServer(ThreadingHTTPServer):
    def __init__(
        self,
        server_address: tuple[str, int],
        output_path: Path,
        token: str,
        context_engine: ContextEngine | None = None,
        camera_worker: CameraUdpWorker | None = None,
    ):
        super().__init__(server_address, DerivedInsightHandler)
        self.output_path = output_path
        self.token = token
        self.context_engine = context_engine
        self.camera_worker = camera_worker


class DerivedInsightHandler(BaseHTTPRequestHandler):
    server: DerivedInsightServer

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            body: dict[str, Any] = {"status": "ok", "schema": "derived-only-v1"}
            if self.server.context_engine is not None:
                body["context_engine"] = self.server.context_engine.health_status()
            if self.server.camera_worker is not None:
                body["camera_bridge"] = self.server.camera_worker.status()
            self._send_json(200, body)
            return
        if parsed.path not in {"/v1/context", "/v1/policy", "/v1/learning-status"}:
            self._send_json(404, {"error": "not found"})
            return
        if not self._authorized():
            self._send_json(401, {"error": "unauthorized"})
            return
        engine = self.server.context_engine
        if engine is None:
            self._send_json(503, {"error": "context engine disabled"})
            return
        if parsed.path == "/v1/policy":
            self._send_json(200, engine.policy_snapshot())
            return
        if parsed.path == "/v1/learning-status":
            self._send_json(200, engine.learning_status())
            return

        query = parse_qs(parsed.query)
        topics = [
            item.strip().upper()
            for value in query.get("topic", [])
            for item in value.split(",")
            if item.strip()
        ]
        try:
            max_cards = int(
                query.get(
                    "max_cards",
                    [str(engine.config.context.default_cards)],
                )[0]
            )
            valence_text = query.get("valence", [None])[0]
            arousal_text = query.get("arousal", [None])[0]
            valence = None if valence_text is None else float(valence_text)
            arousal = None if arousal_text is None else float(arousal_text)
            bundle = engine.build_context_bundle(
                topic_domains=topics,
                max_cards=max_cards,
                current_valence=valence,
                current_arousal=arousal,
            )
        except (TypeError, ValueError, ContextEngineError) as exc:
            self._send_json(400, {"error": str(exc)})
            return
        self._send_json(200, bundle)

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path not in {
            "/v1/derived-insights",
            "/v1/analysis-events",
            "/v1/session-outcomes",
            "/v1/nightly-adapt",
            "/v1/policy/rollback",
        }:
            self._send_json(404, {"error": "not found"})
            return
        if not self._authorized():
            self._send_json(401, {"error": "unauthorized"})
            return
        engine = self.server.context_engine
        if engine is None and parsed.path != "/v1/derived-insights":
            self._send_json(503, {"error": "context engine disabled"})
            return

        expected_schema = {
            "/v1/derived-insights": "derived-only-v1",
            "/v1/analysis-events": "analysis-event-v1",
            "/v1/session-outcomes": "session-outcome-v1",
            "/v1/nightly-adapt": "nightly-adapt-v1",
            "/v1/policy/rollback": "policy-rollback-v1",
        }[parsed.path]
        if self.headers.get("X-OnMom-Schema") != expected_schema:
            self._send_json(400, {"error": f"{expected_schema} schema header required"})
            return

        payload = self._read_json_body()
        if payload is None:
            return

        try:
            if parsed.path == "/v1/derived-insights":
                validated = validate_payload(payload)
                save_payload_atomically(validated, self.server.output_path)
                if engine is not None:
                    engine.ingest_derived_payload(validated)
                result = {
                    "accepted": True,
                    "transfer_id": validated["transfer_id"],
                    "categories": [item["category"] for item in validated["summaries"]],
                }
            elif parsed.path == "/v1/analysis-events":
                result = engine.ingest_event(payload)
            elif parsed.path == "/v1/session-outcomes":
                result = engine.record_session_outcome(payload)
            elif parsed.path == "/v1/nightly-adapt":
                if not isinstance(payload, dict) or set(payload) != {"force"}:
                    raise ContextEngineError("nightly adaptation body must contain only force")
                if not isinstance(payload["force"], bool):
                    raise ContextEngineError("force must be boolean")
                result = engine.run_nightly_adaptation(force=payload["force"])
            else:
                if not isinstance(payload, dict) or set(payload) != {"version"}:
                    raise ContextEngineError("policy rollback body must contain only version")
                if not isinstance(payload["version"], int) or isinstance(payload["version"], bool):
                    raise ContextEngineError("version must be an integer")
                result = engine.rollback_policy(payload["version"])
        except (PayloadError, ContextEngineError, TypeError, ValueError) as exc:
            self._send_json(400, {"error": str(exc)})
            return
        except OSError:
            self._send_json(500, {"error": "could not persist derived data"})
            return

        self._send_json(202, result)

    def _read_json_body(self) -> dict[str, Any] | None:
        length_text = self.headers.get("Content-Length")
        try:
            length = int(length_text or "")
        except ValueError:
            self._send_json(411, {"error": "valid Content-Length required"})
            return None
        if length <= 0 or length > MAX_BODY_BYTES:
            self._send_json(413, {"error": "payload too large or empty"})
            return None
        try:
            raw_body = self.rfile.read(length)
            payload = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            self._send_json(400, {"error": str(exc)})
            return None
        if not isinstance(payload, dict):
            self._send_json(400, {"error": "JSON object required"})
            return None
        return payload

    def _authorized(self) -> bool:
        expected = self.server.token
        if not expected:
            return True
        supplied = self.headers.get("Authorization", "")
        return hmac.compare_digest(supplied, f"Bearer {expected}")

    def _send_json(self, status: int, body: dict[str, Any]) -> None:
        encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, fmt: str, *args: object) -> None:
        # Request metadata is useful; payload contents are deliberately never logged.
        super().log_message(fmt, *args)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Receive On-mom derived insights on Jetson")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("./data/latest_derived_insights.json"),
    )
    parser.add_argument(
        "--database",
        type=Path,
        default=Path("./data/onmom_context.db"),
        help="persistent adaptive context SQLite database",
    )
    parser.add_argument(
        "--adaptive-config",
        type=Path,
        default=Path(__file__).with_name("adaptive_policy.ini"),
        help="commented thresholds and situation prompts INI",
    )
    parser.add_argument(
        "--token-file",
        type=Path,
        default=default_token_file(),
        help="persistent bearer token file (default: ~/.config/onmom/jetson_sync_token)",
    )
    parser.add_argument("--certfile", type=Path)
    parser.add_argument("--keyfile", type=Path)
    parser.add_argument(
        "--allow-unauthenticated",
        action="store_true",
        help="development only: start without JETSON_SYNC_TOKEN",
    )
    parser.add_argument(
        "--disable-nightly-adaptation",
        action="store_true",
        help="disable the background sleep-gated policy adaptation worker",
    )
    parser.add_argument(
        "--scheduler-interval",
        type=float,
        default=60.0,
        help="seconds between sleep-gated adaptation checks",
    )
    parser.add_argument(
        "--disable-camera-bridge",
        action="store_true",
        help="disable loopback UDP aggregation of derived camera samples",
    )
    parser.add_argument(
        "--camera-udp-host",
        default="127.0.0.1",
        help="loopback IPv4 address for local camera modules",
    )
    parser.add_argument(
        "--camera-udp-port",
        type=int,
        default=8766,
        help="loopback UDP port for camera-sample-v1 datagrams",
    )
    parser.add_argument(
        "--camera-window-seconds",
        type=float,
        default=60.0,
        help="aggregation window for camera-derived samples",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if bool(args.certfile) != bool(args.keyfile):
        raise SystemExit("--certfile and --keyfile must be provided together")
    try:
        token = load_bearer_token(args.token_file)
    except (OSError, ValueError) as exc:
        raise SystemExit(f"Could not load Jetson token: {exc}") from exc
    if not token and not args.allow_unauthenticated:
        raise SystemExit(
            "A token is required. Set JETSON_SYNC_TOKEN or create "
            f"{args.token_file} with permissions 0600."
        )
    context_engine = ContextEngine(
        args.database,
        config_path=args.adaptive_config,
    )
    server = DerivedInsightServer(
        (args.host, args.port),
        args.output.resolve(),
        token,
        context_engine=context_engine,
    )
    worker = None
    if not args.disable_nightly_adaptation:
        worker = NightlyAdaptationWorker(
            context_engine,
            interval_seconds=args.scheduler_interval,
        )
        worker.start()
    camera_worker = None
    if not args.disable_camera_bridge:
        try:
            camera_worker = CameraUdpWorker(
                context_engine,
                host=args.camera_udp_host,
                port=args.camera_udp_port,
                window_seconds=args.camera_window_seconds,
            )
            camera_worker.start()
            server.camera_worker = camera_worker
        except (OSError, ValueError) as exc:
            if worker:
                worker.stop()
            server.server_close()
            raise SystemExit(
                f"Could not start local camera analysis bridge: {exc}"
            ) from exc
    scheme = "http"
    if args.certfile and args.keyfile:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(args.certfile, args.keyfile)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        scheme = "https"
    print(f"Listening on {scheme}://{args.host}:{args.port}/v1/derived-insights")
    print(f"Writing derived-only snapshots to {server.output_path}")
    print(f"Accumulating adaptive context in {context_engine.database_path}")
    print(f"Adaptive policy config: {context_engine.config.source_path}")
    print("Sleep-gated nightly adaptation enabled." if worker else "Nightly adaptation disabled.")
    if camera_worker:
        print(
            "Derived camera bridge listening on "
            f"udp://{camera_worker.host}:{camera_worker.port} (loopback only)."
        )
    else:
        print("Camera analysis bridge disabled.")
    print("Bearer token is required." if token else "Unauthenticated development mode.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if worker:
            worker.stop()
        if camera_worker:
            camera_worker.stop()
        server.server_close()


if __name__ == "__main__":
    main()
