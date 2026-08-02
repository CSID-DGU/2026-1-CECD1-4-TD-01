import json

try:
    from app.vision_status import (
        augment_system_prompt,
        build_llm_sensor_context,
        read_vision_status,
        write_session_marker,
    )
except ModuleNotFoundError:
    from jetson_gateway_patch.vision_status import (
        augment_system_prompt,
        build_llm_sensor_context,
        read_vision_status,
        write_session_marker,
    )


def test_session_marker_is_derived_only(tmp_path) -> None:
    marker = tmp_path / "session.json"
    write_session_marker("session-1", path=marker, observed_at_ms=1000)
    payload = json.loads(marker.read_text(encoding="utf-8"))
    assert payload == {
        "schema": "onmom.counseling_session.v1",
        "session_id": "session-1",
        "started_at_ms": 1000,
        "contains_raw_data": False,
    }


def test_latest_telemetry_reports_fresh_and_stale_state(tmp_path) -> None:
    latest = tmp_path / "latest.json"
    latest.write_text(
        json.dumps(
            {
                "schema": "onmaum.counseling_vision.v1",
                "observed_at_ms": 10_000,
                "raw_media_stored": False,
                "face": {"status": "verified"},
                "emotion": {"status": "valid"},
                "rppg": {"status": "valid"},
            }
        ),
        encoding="utf-8",
    )
    fresh = read_vision_status(path=latest, now_ms=12_000)
    stale = read_vision_status(path=latest, now_ms=20_000)
    assert fresh["available"] is True
    assert fresh["age_ms"] == 2_000
    assert stale["available"] is False
    assert stale["reason"] == "stale_camera_stream"


def test_latest_telemetry_accepts_v2_derived_contract(tmp_path) -> None:
    latest = tmp_path / "latest.json"
    latest.write_text(
        json.dumps(
            {
                "schema": "onmaum.counseling_vision.v2",
                "observed_at_ms": 10_000,
                "raw_media_stored": False,
                "face": {"status": "detected"},
                "emotion": {"status": "valid"},
                "rppg": {"status": "valid"},
            }
        ),
        encoding="utf-8",
    )
    status = read_vision_status(path=latest, now_ms=10_100)
    assert status["available"] is True
    assert status["reason"] is None


def test_latest_telemetry_rejects_raw_media_contract(tmp_path) -> None:
    latest = tmp_path / "latest.json"
    latest.write_text(
        json.dumps(
            {
                "schema": "onmaum.counseling_vision.v1",
                "observed_at_ms": 10_000,
                "raw_media_stored": True,
            }
        ),
        encoding="utf-8",
    )
    status = read_vision_status(path=latest, now_ms=10_100)
    assert status["available"] is False
    assert status["reason"] == "invalid_telemetry_contract"


def test_llm_context_requires_verified_face_and_valid_values() -> None:
    telemetry = {
        "face": {"status": "verified"},
        "emotion": {
            "status": "valid",
            "label": "Neutral",
            "confidence": 0.7,
            "valence": -0.1,
            "arousal": 0.3,
        },
        "rppg": {
            "status": "valid",
            "heart_rate_bpm": 72.4,
            "quality": 0.8,
        },
    }
    context = build_llm_sensor_context(
        {"available": True, "telemetry": telemetry}
    )
    assert "72.4 bpm" in context
    assert "실제 감정이나 의료 상태가 아니다" in context
    assert "사용자가 직접 느끼는 바" in context

    telemetry["face"] = {"status": "not_verified"}
    assert (
        build_llm_sensor_context({"available": True, "telemetry": telemetry}) == ""
    )


def test_augment_system_prompt_preserves_existing_prompt() -> None:
    status = {
        "available": True,
        "telemetry": {
            "face": {"status": "verified"},
            "emotion": {"status": "invalid"},
            "rppg": {
                "status": "valid",
                "heart_rate_bpm": 70.0,
                "quality": 0.9,
            },
        },
    }
    combined = augment_system_prompt("기존 안전 지침", status=status)
    assert combined is not None
    assert combined.startswith("기존 안전 지침")
    assert "70.0 bpm" in combined
