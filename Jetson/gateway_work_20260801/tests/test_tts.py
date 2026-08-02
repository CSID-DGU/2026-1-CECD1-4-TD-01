from __future__ import annotations

import asyncio
import io
from pathlib import Path
from types import SimpleNamespace
import wave

import numpy as np
import pytest

from app.tts import OnMomTts


class _FakePiperVoice:
    def __init__(self, chunks: list[bytes]) -> None:
        self._chunks = chunks

    def synthesize(self, _text: str, *, syn_config):
        assert syn_config is not None
        for audio in self._chunks:
            yield SimpleNamespace(
                sample_rate=22_050,
                sample_width=2,
                sample_channels=1,
                audio_int16_bytes=audio,
            )


class _FakeSherpaVoice:
    def __init__(self) -> None:
        self.generation = None

    def generate(self, _text: str, generation):
        self.generation = generation
        return SimpleNamespace(
            samples=np.asarray([0.0, 0.25, -0.25], dtype=np.float32),
            sample_rate=44_100,
        )


def _tts_with_voice(voice: _FakePiperVoice) -> OnMomTts:
    tts = OnMomTts(SimpleNamespace(tts_backend="piper"))
    tts._piper = voice
    return tts


def test_piper_writes_all_audio_chunks_to_valid_wav() -> None:
    pcm_chunks = [b"\x01\x00\x02\x00", b"\x03\x00\x04\x00"]
    audio = _tts_with_voice(_FakePiperVoice(pcm_chunks))._synthesize_piper(
        "hello",
        1.0,
    )

    with wave.open(io.BytesIO(audio), "rb") as wav_file:
        assert wav_file.getframerate() == 22_050
        assert wav_file.getsampwidth() == 2
        assert wav_file.getnchannels() == 1
        assert wav_file.readframes(wav_file.getnframes()) == b"".join(pcm_chunks)


def test_piper_reports_clear_error_when_no_audio_is_generated() -> None:
    with pytest.raises(RuntimeError, match="TTS"):
        _tts_with_voice(_FakePiperVoice([]))._synthesize_piper("???", 1.0)


def test_piper_warmup_runs_inference_before_service_is_ready() -> None:
    tts = _tts_with_voice(_FakePiperVoice([b"\x01\x00\x02\x00"]))

    asyncio.run(tts.warmup())


def test_supertonic_int8_uses_f5_and_writes_44khz_wav() -> None:
    settings = SimpleNamespace(
        tts_backend="supertonic_int8",
        supertonic_voice="F5",
        supertonic_steps=8,
    )
    tts = OnMomTts(settings)
    engine = _FakeSherpaVoice()
    tts._sherpa_supertonic = engine

    audio = tts._synthesize_supertonic_int8("안녕하세요.", 0.96)

    assert engine.generation.sid == 9
    assert engine.generation.num_steps == 8
    assert engine.generation.speed == pytest.approx(0.96)
    assert engine.generation.extra == {"lang": "ko"}
    with wave.open(io.BytesIO(audio), "rb") as wav_file:
        assert wav_file.getframerate() == 44_100
        assert wav_file.getsampwidth() == 2
        assert wav_file.getnchannels() == 1


def test_tts_priority_marker_is_removed_after_synthesis(
    tmp_path: Path,
) -> None:
    marker = tmp_path / "speech_priority.lock"
    settings = SimpleNamespace(
        tts_backend="piper",
        speech_priority_marker=marker,
    )
    tts = OnMomTts(settings)
    tts._piper = _FakePiperVoice([b"\x01\x00\x02\x00"])

    tts._synthesize_sync("안녕하세요.", 1.0)

    assert not marker.exists()
