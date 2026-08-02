"""Read-only gateway adapter for local counseling-camera derived telemetry."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import time
from typing import Any


VISION_SCHEMAS = {"onmaum.counseling_vision.v1", "onmaum.counseling_vision.v2"}
DEFAULT_LATEST_PATH = Path("/data/counseling_vision_latest.json")
DEFAULT_SESSION_PATH = Path("/data/counseling_session.json")


def _path_from_environment(name: str, default: Path) -> Path:
    value = os.environ.get(name, "").strip()
    return Path(value).expanduser() if value else default


def latest_path() -> Path:
    return _path_from_environment("ONMOM_VISION_LATEST_PATH", DEFAULT_LATEST_PATH)


def session_path() -> Path:
    return _path_from_environment("ONMOM_COUNSELING_SESSION_PATH", DEFAULT_SESSION_PATH)


def write_session_marker(
    session_id: str,
    *,
    path: Path | None = None,
    observed_at_ms: int | None = None,
) -> None:
    if not session_id.strip():
        raise ValueError("session_id must not be empty")
    target = path or session_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    if os.name == "posix":
        target.parent.chmod(0o700)
    payload = {
        "schema": "onmom.counseling_session.v1",
        "session_id": session_id,
        "started_at_ms": (
            time.time_ns() // 1_000_000 if observed_at_ms is None else observed_at_ms
        ),
        "contains_raw_data": False,
    }
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{target.name}.",
        suffix=".tmp",
        dir=target.parent,
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(payload, stream, ensure_ascii=False, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        if os.name == "posix":
            # The gateway container writes this derived-only UUID marker as
            # root, while the host rPPG service reads it as the iot user.
            os.chmod(temporary_name, 0o644)
        os.replace(temporary_name, target)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def read_vision_status(
    *,
    path: Path | None = None,
    now_ms: int | None = None,
    stale_after_ms: int = 5_000,
) -> dict[str, Any]:
    target = path or latest_path()
    try:
        payload = json.loads(target.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {
            "available": False,
            "stale": True,
            "age_ms": None,
            "telemetry": None,
            "reason": "waiting_for_camera_stream",
        }
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {
            "available": False,
            "stale": True,
            "age_ms": None,
            "telemetry": None,
            "reason": "telemetry_read_error",
        }
    if (
        not isinstance(payload, dict)
        or payload.get("schema") not in VISION_SCHEMAS
        or payload.get("raw_media_stored") is not False
        or not isinstance(payload.get("observed_at_ms"), int)
    ):
        return {
            "available": False,
            "stale": True,
            "age_ms": None,
            "telemetry": None,
            "reason": "invalid_telemetry_contract",
        }
    reference = time.time_ns() // 1_000_000 if now_ms is None else int(now_ms)
    age_ms = max(0, reference - int(payload["observed_at_ms"]))
    stale = age_ms > stale_after_ms
    return {
        "available": not stale,
        "stale": stale,
        "age_ms": age_ms,
        "telemetry": payload,
        "reason": "stale_camera_stream" if stale else None,
    }


def build_llm_sensor_context(status: dict[str, Any]) -> str:
    """Build a bounded, explicitly non-diagnostic prompt hint for one turn."""

    telemetry = status.get("telemetry")
    if not status.get("available") or not isinstance(telemetry, dict):
        return ""
    face = telemetry.get("face")
    if not isinstance(face, dict) or face.get("status") != "verified":
        return ""
    emotion = telemetry.get("emotion")
    rppg = telemetry.get("rppg")
    values: list[str] = []
    if isinstance(emotion, dict) and emotion.get("status") == "valid":
        try:
            values.append(
                "FER+ 표정 모델 출력 "
                f"{emotion.get('label')} "
                f"(신뢰도 {float(emotion.get('confidence', 0.0)):.2f}, "
                f"정서가 {float(emotion.get('valence', 0.0)):+.2f}, "
                f"각성 {float(emotion.get('arousal', 0.0)):.2f})"
            )
        except (TypeError, ValueError):
            pass
    if (
        isinstance(rppg, dict)
        and rppg.get("status") == "valid"
        and rppg.get("heart_rate_bpm") is not None
    ):
        try:
            values.append(
                f"비접촉 심박 추정 {float(rppg['heart_rate_bpm']):.1f} bpm "
                f"(신호 품질 {float(rppg.get('quality', 0.0)):.2f})"
            )
        except (TypeError, ValueError):
            pass
    if not values:
        return ""
    return (
        "[현재 상담 카메라 파생 참고치]\n"
        + "\n".join(f"- {value}" for value in values)
        + "\n이 값은 센서·모델의 일시적 추정이며 실제 감정이나 의료 상태가 아니다. "
        "상태를 단정하거나 진단하지 말고, 필요하면 사용자가 직접 느끼는 바를 "
        "부드럽게 확인하는 질문에만 참고한다."
    )


def augment_system_prompt(
    system_prompt: str | None,
    *,
    status: dict[str, Any] | None = None,
) -> str | None:
    context = build_llm_sensor_context(status or read_vision_status())
    if not context:
        return system_prompt
    base = (system_prompt or "").strip()
    return f"{base}\n\n{context}" if base else context
