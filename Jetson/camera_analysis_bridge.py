#!/usr/bin/env python3
"""Local camera-analysis bridge for the On-mom Jetson service.

Camera inference processes can publish small derived samples over loopback UDP.
This module validates and aggregates those samples into one-minute
``analysis-event-v1`` windows. It never accepts frames, image paths, face
landmarks, embeddings, or other raw biometric material.
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import math
import re
import socket
import statistics
import threading
import time
import uuid
from collections import deque
from typing import Any, Callable, Protocol

MAX_CAMERA_DATAGRAM_BYTES = 16 * 1024
MAX_CLOCK_SKEW_MS = 2 * 60 * 1000
MAX_SAMPLE_AGE_MS = 24 * 60 * 60 * 1000
DEFAULT_CAMERA_UDP_HOST = "127.0.0.1"
DEFAULT_CAMERA_UDP_PORT = 8766
DEFAULT_WINDOW_SECONDS = 60.0

SAMPLE_BASE_KEYS = {
    "schema_version",
    "sample_id",
    "observed_at",
    "observation_seconds",
    "contains_raw_data",
}
SAMPLE_MODALITY_KEYS = {"activity", "posture", "face_emotion", "rppg"}
SAMPLE_KEYS = SAMPLE_BASE_KEYS | SAMPLE_MODALITY_KEYS
ACTIVITY_KEYS = {"presence", "motion_score", "confidence"}
POSTURE_KEYS = {"label", "confidence"}
FACE_EMOTION_KEYS = {"valence", "arousal", "confidence", "face_count"}
RPPG_KEYS = {
    "heart_rate_bpm",
    "signal_quality",
    "measurement_seconds",
    "confidence",
}
POSTURE_LABELS = {"UPRIGHT", "SLOUCH", "AWAY", "UNKNOWN"}
IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class CameraSampleError(ValueError):
    """Raised when a camera producer violates the derived-only contract."""


class EventSink(Protocol):
    def ingest_event(self, payload: dict[str, Any]) -> dict[str, Any]:
        ...


def now_epoch_ms() -> int:
    return int(time.time() * 1000)


def _is_number(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
    )


def _bounded(
    value: Any,
    field: str,
    minimum: float,
    maximum: float,
) -> float:
    if not _is_number(value):
        raise CameraSampleError(f"{field} must be a finite number")
    result = float(value)
    if not minimum <= result <= maximum:
        raise CameraSampleError(
            f"{field} must be between {minimum} and {maximum}"
        )
    return result


def _require_exact_object(
    value: Any,
    field: str,
    allowed_keys: set[str],
) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != allowed_keys:
        raise CameraSampleError(f"{field} fields do not match camera-sample-v1")
    return value


def validate_camera_sample(
    payload: Any,
    *,
    now_ms: int | None = None,
) -> dict[str, Any]:
    """Validate a strictly derived camera sample and return it unchanged."""

    if not isinstance(payload, dict):
        raise CameraSampleError("camera sample must be a JSON object")
    if not SAMPLE_BASE_KEYS <= set(payload) <= SAMPLE_KEYS:
        raise CameraSampleError("camera sample fields do not match camera-sample-v1")
    if not (set(payload) & SAMPLE_MODALITY_KEYS):
        raise CameraSampleError("at least one derived camera modality is required")
    if payload.get("schema_version") != 1:
        raise CameraSampleError("unsupported camera sample schema_version")
    if payload.get("contains_raw_data") is not False:
        raise CameraSampleError("raw camera data is not accepted")

    sample_id = payload.get("sample_id")
    if not isinstance(sample_id, str) or not IDENTIFIER_RE.fullmatch(sample_id):
        raise CameraSampleError("sample_id contains unsupported characters")
    observed_at = payload.get("observed_at")
    if (
        not isinstance(observed_at, int)
        or isinstance(observed_at, bool)
        or observed_at <= 0
    ):
        raise CameraSampleError("observed_at must be positive epoch milliseconds")
    _bounded(
        payload.get("observation_seconds"),
        "observation_seconds",
        0.05,
        60.0,
    )

    reference_time = now_epoch_ms() if now_ms is None else now_ms
    if observed_at > reference_time + MAX_CLOCK_SKEW_MS:
        raise CameraSampleError("camera sample is too far in the future")
    if observed_at < reference_time - MAX_SAMPLE_AGE_MS:
        raise CameraSampleError("camera sample is older than 24 hours")

    if "activity" in payload:
        activity = _require_exact_object(
            payload["activity"],
            "activity",
            ACTIVITY_KEYS,
        )
        if not isinstance(activity["presence"], bool):
            raise CameraSampleError("activity.presence must be boolean")
        _bounded(activity["motion_score"], "activity.motion_score", 0.0, 1.0)
        _bounded(activity["confidence"], "activity.confidence", 0.0, 1.0)

    if "posture" in payload:
        posture = _require_exact_object(
            payload["posture"],
            "posture",
            POSTURE_KEYS,
        )
        label = posture["label"]
        if not isinstance(label, str) or label.upper() not in POSTURE_LABELS:
            raise CameraSampleError("unsupported posture.label")
        _bounded(posture["confidence"], "posture.confidence", 0.0, 1.0)

    if "face_emotion" in payload:
        emotion = _require_exact_object(
            payload["face_emotion"],
            "face_emotion",
            FACE_EMOTION_KEYS,
        )
        _bounded(emotion["valence"], "face_emotion.valence", -1.0, 1.0)
        _bounded(emotion["arousal"], "face_emotion.arousal", 0.0, 1.0)
        _bounded(emotion["confidence"], "face_emotion.confidence", 0.0, 1.0)
        face_count = emotion["face_count"]
        if (
            not isinstance(face_count, int)
            or isinstance(face_count, bool)
            or not 0 <= face_count <= 8
        ):
            raise CameraSampleError("face_emotion.face_count must be 0 to 8")

    if "rppg" in payload:
        rppg = _require_exact_object(payload["rppg"], "rppg", RPPG_KEYS)
        _bounded(rppg["heart_rate_bpm"], "rppg.heart_rate_bpm", 1.0, 300.0)
        _bounded(rppg["signal_quality"], "rppg.signal_quality", 0.0, 1.0)
        _bounded(
            rppg["measurement_seconds"],
            "rppg.measurement_seconds",
            0.1,
            120.0,
        )
        _bounded(rppg["confidence"], "rppg.confidence", 0.0, 1.0)

    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
        "utf-8"
    )
    if len(encoded) > MAX_CAMERA_DATAGRAM_BYTES:
        raise CameraSampleError("camera sample is too large")
    return payload


def parse_camera_datagram(raw: bytes, *, now_ms: int | None = None) -> dict[str, Any]:
    if not raw or len(raw) > MAX_CAMERA_DATAGRAM_BYTES:
        raise CameraSampleError("camera datagram is empty or too large")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CameraSampleError("camera datagram must be UTF-8 JSON") from exc
    return validate_camera_sample(payload, now_ms=now_ms)


def _weighted_mean(
    items: list[tuple[float, float]],
    default: float = 0.0,
) -> float:
    total_weight = sum(max(0.0, weight) for _, weight in items)
    if total_weight <= 0.0:
        return default
    return sum(value * max(0.0, weight) for value, weight in items) / total_weight


def _quality(
    confidence_items: list[tuple[float, float]],
    observed_seconds: float,
    window_seconds: float,
    reasons: list[str] | None = None,
) -> dict[str, Any]:
    return {
        "status": "VALID",
        "confidence": max(
            0.0,
            min(1.0, _weighted_mean(confidence_items, default=0.0)),
        ),
        "coverage": max(0.0, min(1.0, observed_seconds / window_seconds)),
        "reasons": list(dict.fromkeys(reasons or []))[:16],
    }


class CameraWindowAggregator:
    """Aggregate high-frequency derived camera samples into durable events."""

    def __init__(
        self,
        event_sink: EventSink,
        *,
        window_seconds: float = DEFAULT_WINDOW_SECONDS,
        clock: Callable[[], int] = now_epoch_ms,
    ):
        self.event_sink = event_sink
        self.window_seconds = max(1.0, float(window_seconds))
        self.window_ms = int(self.window_seconds * 1000)
        self.clock = clock
        self._lock = threading.RLock()
        self._samples: list[dict[str, Any]] = []
        self._collection_started_at: int | None = None
        self._seen_ids: set[str] = set()
        self._seen_order: deque[str] = deque()
        self._received_samples = 0
        self._duplicate_samples = 0
        self._rejected_samples = 0
        self._flushes = 0
        self._events_emitted = 0
        self._last_received_at: int | None = None
        self._last_flush_at: int | None = None
        self._last_error: str | None = None

    def note_rejection(self, error: BaseException) -> None:
        with self._lock:
            self._rejected_samples += 1
            self._last_error = str(error)[:300]

    def ingest(self, payload: Any) -> dict[str, Any]:
        timestamp = self.clock()
        try:
            sample = validate_camera_sample(payload, now_ms=timestamp)
        except CameraSampleError as exc:
            self.note_rejection(exc)
            raise

        emitted: list[dict[str, Any]] = []
        with self._lock:
            sample_id = sample["sample_id"]
            if sample_id in self._seen_ids:
                self._duplicate_samples += 1
                return {
                    "accepted": True,
                    "duplicate": True,
                    "events_emitted": 0,
                }

            if (
                self._collection_started_at is not None
                and timestamp - self._collection_started_at >= self.window_ms
            ):
                emitted = self._flush_locked(timestamp)

            self._remember_id(sample_id)
            self._samples.append(sample)
            if self._collection_started_at is None:
                self._collection_started_at = timestamp
            self._received_samples += 1
            self._last_received_at = timestamp
            self._last_error = None
        return {
            "accepted": True,
            "duplicate": False,
            "events_emitted": len(emitted),
        }

    def flush(
        self,
        *,
        force: bool = False,
        now_ms: int | None = None,
    ) -> list[dict[str, Any]]:
        timestamp = self.clock() if now_ms is None else now_ms
        with self._lock:
            if not self._samples:
                return []
            if (
                not force
                and self._collection_started_at is not None
                and timestamp - self._collection_started_at < self.window_ms
            ):
                return []
            return self._flush_locked(timestamp)

    def status(self) -> dict[str, Any]:
        with self._lock:
            return {
                "schema": "camera-bridge-status-v1",
                "window_seconds": self.window_seconds,
                "buffered_samples": len(self._samples),
                "received_samples": self._received_samples,
                "duplicate_samples": self._duplicate_samples,
                "rejected_samples": self._rejected_samples,
                "flushes": self._flushes,
                "events_emitted": self._events_emitted,
                "last_received_at": self._last_received_at,
                "last_flush_at": self._last_flush_at,
                "last_error": self._last_error,
            }

    def _remember_id(self, sample_id: str) -> None:
        if len(self._seen_order) >= 4096:
            oldest = self._seen_order.popleft()
            self._seen_ids.discard(oldest)
        self._seen_order.append(sample_id)
        self._seen_ids.add(sample_id)

    def _flush_locked(self, timestamp: int) -> list[dict[str, Any]]:
        samples = self._samples
        self._samples = []
        self._collection_started_at = None
        if not samples:
            return []

        events = self._build_events(samples, timestamp)
        results: list[dict[str, Any]] = []
        try:
            for event in events:
                results.append(self.event_sink.ingest_event(event))
        except Exception as exc:
            self._last_error = str(exc)[:300]
            raise
        self._flushes += 1
        self._events_emitted += len(results)
        self._last_flush_at = timestamp
        self._last_error = None
        return results

    def _build_events(
        self,
        samples: list[dict[str, Any]],
        timestamp: int,
    ) -> list[dict[str, Any]]:
        occurred_at = max(int(sample["observed_at"]) for sample in samples)
        events: list[dict[str, Any]] = []

        activities = [sample for sample in samples if "activity" in sample]
        if activities:
            durations = [float(sample["observation_seconds"]) for sample in activities]
            observed_seconds = sum(durations)
            motion_items = [
                (
                    float(sample["activity"]["motion_score"]),
                    float(sample["observation_seconds"]),
                )
                for sample in activities
            ]
            presence_seconds = sum(
                duration
                for sample, duration in zip(activities, durations)
                if sample["activity"]["presence"]
            )
            active_seconds = sum(
                duration
                for sample, duration in zip(activities, durations)
                if sample["activity"]["presence"]
                and float(sample["activity"]["motion_score"]) >= 0.12
            )
            events.append(
                self._event(
                    source_family="activity.camera",
                    domain="PHYSICAL_ACTIVITY",
                    event_type="camera_activity_window",
                    occurred_at=occurred_at,
                    expires_at=occurred_at + 6 * 60 * 60 * 1000,
                    metrics={
                        "observed_seconds": round(observed_seconds, 3),
                        "presence_ratio": round(
                            presence_seconds / max(observed_seconds, 0.001),
                            4,
                        ),
                        "active_ratio": round(
                            active_seconds / max(observed_seconds, 0.001),
                            4,
                        ),
                        "mean_motion_score": round(
                            _weighted_mean(motion_items),
                            4,
                        ),
                    },
                    quality=_quality(
                        [
                            (
                                float(sample["activity"]["confidence"]),
                                float(sample["observation_seconds"]),
                            )
                            for sample in activities
                        ],
                        observed_seconds,
                        self.window_seconds,
                    ),
                )
            )

        postures = [
            sample
            for sample in samples
            if "posture" in sample
            and sample["posture"]["label"].upper() != "UNKNOWN"
        ]
        if postures:
            observed_seconds = sum(
                float(sample["observation_seconds"]) for sample in postures
            )
            label_seconds = {
                label: sum(
                    float(sample["observation_seconds"])
                    for sample in postures
                    if sample["posture"]["label"].upper() == label
                )
                for label in ("UPRIGHT", "SLOUCH", "AWAY")
            }
            events.append(
                self._event(
                    source_family="posture.camera",
                    domain="SEDENTARY_AND_POSTURE",
                    event_type="posture_window",
                    occurred_at=occurred_at,
                    expires_at=occurred_at + 6 * 60 * 60 * 1000,
                    metrics={
                        "observed_seconds": round(observed_seconds, 3),
                        "upright_ratio": round(
                            label_seconds["UPRIGHT"] / max(observed_seconds, 0.001),
                            4,
                        ),
                        "slouch_ratio": round(
                            label_seconds["SLOUCH"] / max(observed_seconds, 0.001),
                            4,
                        ),
                        "away_ratio": round(
                            label_seconds["AWAY"] / max(observed_seconds, 0.001),
                            4,
                        ),
                        "upright_minutes": round(label_seconds["UPRIGHT"] / 60.0, 3),
                        "slouch_minutes": round(label_seconds["SLOUCH"] / 60.0, 3),
                    },
                    quality=_quality(
                        [
                            (
                                float(sample["posture"]["confidence"]),
                                float(sample["observation_seconds"]),
                            )
                            for sample in postures
                        ],
                        observed_seconds,
                        self.window_seconds,
                    ),
                )
            )

        emotion_candidates = [
            sample for sample in samples if "face_emotion" in sample
        ]
        emotions = [
            sample
            for sample in emotion_candidates
            if sample["face_emotion"]["face_count"] == 1
        ]
        if emotions:
            observed_seconds = sum(
                float(sample["observation_seconds"]) for sample in emotions
            )
            confidence_items = [
                (
                    float(sample["face_emotion"]["confidence"]),
                    float(sample["observation_seconds"]),
                )
                for sample in emotions
            ]
            weights = [
                (
                    float(sample["face_emotion"]["confidence"])
                    * float(sample["observation_seconds"])
                )
                for sample in emotions
            ]
            reasons = []
            if len(emotions) < len(emotion_candidates):
                reasons.append("zero_or_multiple_faces_excluded")
            events.append(
                self._event(
                    source_family="emotion.face",
                    domain="FACIAL_EXPRESSION",
                    event_type="face_emotion_window",
                    occurred_at=occurred_at,
                    expires_at=occurred_at + 15 * 60 * 1000,
                    metrics={
                        "valence": round(
                            _weighted_mean(
                                [
                                    (
                                        float(sample["face_emotion"]["valence"]),
                                        weight,
                                    )
                                    for sample, weight in zip(emotions, weights)
                                ]
                            ),
                            4,
                        ),
                        "arousal": round(
                            _weighted_mean(
                                [
                                    (
                                        float(sample["face_emotion"]["arousal"]),
                                        weight,
                                    )
                                    for sample, weight in zip(emotions, weights)
                                ]
                            ),
                            4,
                        ),
                        "face_observation_seconds": round(observed_seconds, 3),
                    },
                    quality=_quality(
                        confidence_items,
                        observed_seconds,
                        self.window_seconds,
                        reasons,
                    ),
                )
            )

        rppg_samples = [sample for sample in samples if "rppg" in sample]
        if rppg_samples:
            measurement_seconds = sum(
                float(sample["rppg"]["measurement_seconds"])
                for sample in rppg_samples
            )
            signal_quality_items = [
                (
                    float(sample["rppg"]["signal_quality"]),
                    float(sample["rppg"]["measurement_seconds"]),
                )
                for sample in rppg_samples
            ]
            heart_rates = [
                float(sample["rppg"]["heart_rate_bpm"])
                for sample in rppg_samples
            ]
            events.append(
                self._event(
                    source_family="physiology.heart_rate",
                    domain="PHYSIOLOGICAL_STATE",
                    event_type="rppg_window",
                    occurred_at=occurred_at,
                    expires_at=occurred_at + 30 * 60 * 1000,
                    metrics={
                        "heart_rate_bpm": round(statistics.median(heart_rates), 2),
                        "signal_quality": round(
                            _weighted_mean(signal_quality_items),
                            4,
                        ),
                        "measurement_seconds": round(measurement_seconds, 3),
                    },
                    quality=_quality(
                        [
                            (
                                min(
                                    float(sample["rppg"]["confidence"]),
                                    float(sample["rppg"]["signal_quality"]),
                                ),
                                float(sample["rppg"]["measurement_seconds"]),
                            )
                            for sample in rppg_samples
                        ],
                        measurement_seconds,
                        self.window_seconds,
                    ),
                )
            )
        return events

    @staticmethod
    def _event(
        *,
        source_family: str,
        domain: str,
        event_type: str,
        occurred_at: int,
        expires_at: int,
        metrics: dict[str, Any],
        quality: dict[str, Any],
    ) -> dict[str, Any]:
        return {
            "schema_version": 1,
            "event_id": f"camera-{uuid.uuid4()}",
            "source": "hub.camera",
            "source_family": source_family,
            "domain": domain,
            "event_type": event_type,
            "occurred_at": occurred_at,
            "expires_at": expires_at,
            "contains_raw_data": False,
            "metrics": metrics,
            "quality": quality,
        }


class CameraUdpWorker:
    """Loopback-only UDP receiver for local camera inference processes."""

    def __init__(
        self,
        event_sink: EventSink,
        *,
        host: str = DEFAULT_CAMERA_UDP_HOST,
        port: int = DEFAULT_CAMERA_UDP_PORT,
        window_seconds: float = DEFAULT_WINDOW_SECONDS,
        clock: Callable[[], int] = now_epoch_ms,
    ):
        address = ipaddress.ip_address(host)
        if address.version != 4 or not address.is_loopback:
            raise ValueError("camera UDP host must be an IPv4 loopback address")
        if not 0 <= int(port) <= 65535:
            raise ValueError("camera UDP port must be between 0 and 65535")
        self.host = host
        self.port = int(port)
        self.clock = clock
        self.aggregator = CameraWindowAggregator(
            event_sink,
            window_seconds=window_seconds,
            clock=clock,
        )
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._socket: socket.socket | None = None

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp_socket.bind((self.host, self.port))
        self.port = int(udp_socket.getsockname()[1])
        udp_socket.settimeout(
            max(0.1, min(1.0, self.aggregator.window_seconds / 2.0))
        )
        self._socket = udp_socket
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._run,
            name="onmom-camera-analysis-bridge",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        thread = self._thread
        if thread:
            thread.join(timeout=3.0)
        udp_socket = self._socket
        self._socket = None
        if udp_socket:
            udp_socket.close()
        try:
            self.aggregator.flush(force=True)
        except Exception as exc:
            self.aggregator.note_rejection(exc)

    def status(self) -> dict[str, Any]:
        result = self.aggregator.status()
        result.update(
            {
                "running": bool(self._thread and self._thread.is_alive()),
                "host": self.host,
                "port": self.port,
                "transport": "udp-loopback",
                "accepts_raw_data": False,
            }
        )
        return result

    def _run(self) -> None:
        udp_socket = self._socket
        if udp_socket is None:
            return
        while not self._stop_event.is_set():
            try:
                raw, _address = udp_socket.recvfrom(MAX_CAMERA_DATAGRAM_BYTES + 1)
            except socket.timeout:
                try:
                    self.aggregator.flush(now_ms=self.clock())
                except Exception as exc:
                    self.aggregator.note_rejection(exc)
                continue
            except OSError as exc:
                if not self._stop_event.is_set():
                    self.aggregator.note_rejection(exc)
                break
            try:
                sample = parse_camera_datagram(raw, now_ms=self.clock())
                self.aggregator.ingest(sample)
            except (CameraSampleError, ValueError, TypeError) as exc:
                self.aggregator.note_rejection(exc)
            except Exception as exc:
                self.aggregator.note_rejection(exc)


def send_camera_sample(
    payload: dict[str, Any],
    *,
    host: str = DEFAULT_CAMERA_UDP_HOST,
    port: int = DEFAULT_CAMERA_UDP_PORT,
    timeout_seconds: float = 1.0,
) -> None:
    """Validate and send one derived camera sample to the local bridge."""

    validated = validate_camera_sample(payload)
    encoded = json.dumps(
        validated,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp_socket:
        udp_socket.settimeout(timeout_seconds)
        udp_socket.sendto(encoded, (host, int(port)))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Send one derived camera-sample-v1 JSON object over loopback UDP"
    )
    parser.add_argument("--host", default=DEFAULT_CAMERA_UDP_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_CAMERA_UDP_PORT)
    parser.add_argument(
        "--json",
        required=True,
        help="camera-sample-v1 JSON object; raw frames and landmarks are rejected",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    payload = json.loads(args.json)
    send_camera_sample(payload, host=args.host, port=args.port)
    print("accepted_by_local_udp_socket")


if __name__ == "__main__":
    main()
