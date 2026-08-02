from __future__ import annotations

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
import wave

import numpy as np

from app.voice_emotion import (
    CounselingSignalsStore,
    VoiceEmotionAnalyzer,
    assess_arousal,
    VoiceTurnProcessor,
)


class _ModelInput:
    name = "input_values"


class _FakeSession:
    def get_inputs(self):
        return [_ModelInput()]

    def run(self, _outputs, feed):
        assert feed["input_values"].dtype == np.float32
        return [np.asarray([[4.0, 0.2, 0.3, 0.4, 0.5]], dtype=np.float32)]


def _settings(tmp_path: Path):
    model = tmp_path / "voice.onnx"
    model.write_bytes(b"fake")
    return SimpleNamespace(
        voice_emotion_enabled=True,
        voice_emotion_model=model,
        voice_emotion_model_name="test-model",
        voice_emotion_model_source="test/source",
        voice_emotion_model_revision="abc",
        voice_emotion_max_seconds=12,
        voice_emotion_threads=1,
        voice_emotion_min_confidence=0.35,
        voice_emotion_prompt_min_confidence=0.50,
        voice_emotion_keep_loaded=False,
        voice_emotion_normalize_input=True,
        min_audio_rms=100,
        keep_recordings=False,
        speech_priority_marker=tmp_path / "speech_priority.lock",
        speech_priority_settle_ms=0,
    )


def _write_wav(path: Path, seconds: float = 0.5) -> None:
    count = round(16_000 * seconds)
    phase = np.arange(count, dtype=np.float32)
    samples = (np.sin(phase * 2 * np.pi * 220 / 16_000) * 2_000).astype("<i2")
    with wave.open(str(path), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(16_000)
        audio.writeframes(samples.tobytes())


def _vision_status():
    return {
        "available": True,
        "stale": False,
        "telemetry": {
            "session_id": "session-a",
            "observed_at_ms": 123,
            "face": {"status": "verified"},
            "emotion": {
                "status": "valid",
                "label": "Neutral",
                "confidence": 0.8,
            },
            "rppg": {
                "status": "valid",
                "heart_rate_bpm": 71.2,
                "quality": 0.9,
            },
        },
    }


def test_analyzer_returns_probabilities_without_raw_audio(tmp_path: Path) -> None:
    wav_path = tmp_path / "turn.wav"
    _write_wav(wav_path)
    analyzer = VoiceEmotionAnalyzer(_settings(tmp_path))
    analyzer._create_session = lambda: _FakeSession()  # type: ignore[method-assign]

    result = asyncio.run(analyzer.analyze(wav_path, "session-a", "utterance-a"))

    assert result["status"] == "valid"
    assert result["label"] == "Happy"
    assert result["confidence"] > 0.8
    assert set(result["probabilities"]) == {
        "Happy",
        "Sad",
        "Angry",
        "Fearful",
        "Neutral",
    }
    assert result["raw_audio_stored"] is False
    assert "audio_path" not in result


def test_signals_store_correlates_all_three_modalities(tmp_path: Path) -> None:
    store = CounselingSignalsStore(
        tmp_path / "signals.jsonl",
        tmp_path / "latest.json",
        vision_reader=_vision_status,
        prompt_min_confidence=0.5,
    )
    voice = {
        "schema": "onmom.voice_emotion.v1",
        "session_id": "session-a",
        "utterance_id": "utterance-a",
        "observed_at_ms": 456,
        "status": "valid",
        "label": "Sad",
        "display_label": "슬픔",
        "confidence": 0.77,
        "probabilities": {"Sad": 0.77},
        "raw_audio_stored": False,
    }

    event = store.record_voice_turn(
        voice,
        transcription_status="completed",
        transcript_characters=14,
    )

    assert event["face_emotion"]["label"] == "Neutral"
    assert event["rppg"]["heart_rate_bpm"] == 71.2
    assert event["voice_emotion"]["label"] == "Sad"
    assert event["visual_session_matches"] is True
    assert event["raw_media_stored"] is False
    assert store.timeline("session-a") == [event]
    assert "음성 감정 모델" in store.voice_prompt_context(
        "session-a",
        "utterance-a",
    )
    assert store.voice_prompt_context("session-a", "another-turn") == ""
    serialized = (tmp_path / "signals.jsonl").read_text(encoding="utf-8")
    assert "content_stored_in_this_log" in serialized
    assert "transcript_text" not in serialized
    assert json.loads((tmp_path / "latest.json").read_text(encoding="utf-8")) == event


def test_turn_processor_deletes_wav_after_both_consumers(tmp_path: Path) -> None:
    wav_path = tmp_path / "turn.wav"
    _write_wav(wav_path)
    settings = _settings(tmp_path)
    analyzer = VoiceEmotionAnalyzer(settings)
    analyzer._create_session = lambda: _FakeSession()  # type: ignore[method-assign]
    store = CounselingSignalsStore(
        tmp_path / "signals.jsonl",
        tmp_path / "latest.json",
        vision_reader=_vision_status,
        prompt_min_confidence=0.5,
    )

    class _Transcriber:
        async def transcribe(self, audio_path, *, cleanup=True):
            assert audio_path.exists()
            assert cleanup is False
            assert settings.speech_priority_marker.exists()
            return "오늘은 기분이 좋아요.", 120

    handled = []

    async def handle_signal(event):
        handled.append(event)

    processor = VoiceTurnProcessor(
        settings, _Transcriber(), analyzer, store, handle_signal
    )
    transcript, stt_ms, emotion, utterance_id = asyncio.run(
        processor.process("session-a", wav_path, utterance_id="utterance-a")
    )

    assert transcript == "오늘은 기분이 좋아요."
    assert stt_ms == 120
    assert emotion["label"] == "Happy"
    assert utterance_id == "utterance-a"
    assert handled[0]["utterance_id"] == "utterance-a"
    assert not wav_path.exists()
    assert not settings.speech_priority_marker.exists()


def test_arousal_requires_two_independent_valid_signals() -> None:
    event = {
        "face_emotion": {
            "status": "valid",
            "confidence": 0.9,
            "arousal": 0.8,
        },
        "rppg": {
            "status": "valid",
            "heart_rate_bpm": 108,
            "quality": 0.9,
        },
        "voice_emotion": {
            "status": "valid",
            "label": "Neutral",
            "confidence": 0.9,
        },
    }

    assessment = assess_arousal(event)

    assert assessment["level"] == "high"
    assert assessment["signal_count"] == 2
    assert assessment["automatic_guardian_alert"] is False


def test_three_arousal_signals_trigger_guardian_alert_recommendation(
    tmp_path: Path,
) -> None:
    def high_vision_status():
        return {
            "available": True,
            "stale": False,
            "telemetry": {
                "session_id": "session-a",
                "observed_at_ms": 123,
                "face": {"status": "verified"},
                "emotion": {
                    "status": "valid",
                    "label": "Angry",
                    "confidence": 0.9,
                    "arousal": 0.85,
                },
                "rppg": {
                    "status": "valid",
                    "heart_rate_bpm": 110,
                    "quality": 0.9,
                },
            },
        }

    store = CounselingSignalsStore(
        tmp_path / "signals.jsonl",
        tmp_path / "latest.json",
        vision_reader=high_vision_status,
        prompt_min_confidence=0.5,
    )
    event = store.record_voice_turn(
        {
            "session_id": "session-a",
            "utterance_id": "utterance-alert",
            "status": "valid",
            "label": "Angry",
            "confidence": 0.9,
        },
        transcription_status="completed",
        transcript_characters=4,
    )

    assert event["arousal_assessment"]["level"] == "critical"
    assert event["arousal_assessment"]["automatic_guardian_alert"] is True
    prompt = store.voice_prompt_context("session-a", "utterance-alert")
    assert "\uc2ec\ud638\ud761" in prompt
    assert "1~3\ubd84" in prompt
