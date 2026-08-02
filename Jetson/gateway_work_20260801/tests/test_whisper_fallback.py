from __future__ import annotations

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
import wave

import numpy as np
import pytest

from app.audio import WhisperTranscriber


def _write_wav(path: Path) -> None:
    phase = np.arange(8_000, dtype=np.float32)
    samples = (np.sin(phase * 2 * np.pi * 220 / 16_000) * 2_000).astype("<i2")
    with wave.open(str(path), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(16_000)
        audio.writeframes(samples.tobytes())


def _settings(tmp_path: Path):
    primary = tmp_path / "large.bin"
    fallback = tmp_path / "small.bin"
    primary.write_bytes(b"primary")
    fallback.write_bytes(b"fallback")
    return SimpleNamespace(
        whisper_binary="whisper-cli",
        whisper_model=primary,
        whisper_fallback_model=fallback,
        whisper_prompt="한국어",
        whisper_threads=2,
        min_audio_rms=100,
        keep_recordings=False,
    )


class _FakeProcess:
    def __init__(
        self,
        *,
        returncode: int,
        stderr: bytes,
        output_base: Path,
        transcript: str = "",
    ) -> None:
        self.returncode = returncode
        self._stderr = stderr
        self._output_base = output_base
        self._transcript = transcript

    async def communicate(self):
        if self.returncode == 0:
            Path(f"{self._output_base}.json").write_text(
                json.dumps(
                    {"transcription": [{"text": self._transcript}]},
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
        return b"", self._stderr


def test_nvmap_failure_retries_small_gpu_model(monkeypatch, tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    audio_path = tmp_path / "turn.wav"
    _write_wav(audio_path)
    attempted_models: list[Path] = []

    async def fake_exec(*args, **_kwargs):
        model = Path(args[args.index("-m") + 1])
        output_base = Path(args[args.index("-of") + 1])
        attempted_models.append(model)
        if model == settings.whisper_model:
            return _FakeProcess(
                returncode=-11,
                stderr=b"NvMapMemAllocInternalTagged failed: error 12",
                output_base=output_base,
            )
        return _FakeProcess(
            returncode=0,
            stderr=b"",
            output_base=output_base,
            transcript="안녕하세요",
        )

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_exec)
    transcriber = WhisperTranscriber(settings)
    text, _elapsed_ms = asyncio.run(transcriber.transcribe(audio_path))

    assert text == "안녕하세요"
    assert attempted_models == [
        settings.whisper_model,
        settings.whisper_fallback_model,
    ]
    assert transcriber.last_model == settings.whisper_fallback_model.name
    assert transcriber.fallback_count == 1
    assert not audio_path.exists()


def test_failed_transcription_also_deletes_raw_audio(
    monkeypatch, tmp_path: Path
) -> None:
    settings = _settings(tmp_path)
    audio_path = tmp_path / "turn.wav"
    _write_wav(audio_path)

    async def fake_exec(*args, **_kwargs):
        output_base = Path(args[args.index("-of") + 1])
        return _FakeProcess(
            returncode=2,
            stderr=b"invalid model",
            output_base=output_base,
        )

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_exec)
    transcriber = WhisperTranscriber(settings)

    with pytest.raises(RuntimeError, match="음성 인식에 실패"):
        asyncio.run(transcriber.transcribe(audio_path))

    assert transcriber.fallback_count == 0
    assert not audio_path.exists()
