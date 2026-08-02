import asyncio
from array import array
import json
import logging
import math
import signal
import sys
import time
import uuid
import wave
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import AsyncIterator

from .config import Settings
from .silero_vad import SileroStreamingVad

logger = logging.getLogger("onmom.audio")


@dataclass
class ActiveRecording:
    process: asyncio.subprocess.Process
    path: Path
    timeout_task: asyncio.Task[None]


class AudioRecorder:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.active: dict[str, ActiveRecording] = {}
        self._lock = asyncio.Lock()

    async def start(self, session_id: str) -> None:
        async with self._lock:
            if session_id in self.active:
                raise RuntimeError("이미 녹음 중입니다.")
            self.settings.recordings_dir.mkdir(parents=True, exist_ok=True)
            path = self.settings.recordings_dir / f"{session_id}-{uuid.uuid4().hex}.pcm"
            process = await asyncio.create_subprocess_exec(
                "arecord",
                "-D",
                self.settings.alsa_device,
                "-f",
                "S16_LE",
                "-r",
                "16000",
                "-c",
                "1",
                "-t",
                "raw",
                str(path),
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.PIPE,
            )
            await asyncio.sleep(0.15)
            if process.returncode is not None:
                error = (await process.stderr.read()).decode(errors="replace").strip()
                raise RuntimeError(f"Jetson 마이크를 열 수 없습니다: {error}")
            timeout_task = asyncio.create_task(self._stop_after_limit(session_id))
            self.active[session_id] = ActiveRecording(process, path, timeout_task)

    async def stop(self, session_id: str) -> Path:
        async with self._lock:
            recording = self.active.pop(session_id, None)
        if recording is None:
            raise RuntimeError("진행 중인 녹음이 없습니다.")
        recording.timeout_task.cancel()
        if recording.process.returncode is None:
            recording.process.send_signal(signal.SIGINT)
        try:
            await asyncio.wait_for(recording.process.wait(), timeout=3)
        except asyncio.TimeoutError:
            recording.process.kill()
            await recording.process.wait()
        if not recording.path.exists() or recording.path.stat().st_size < 1000:
            recording.path.unlink(missing_ok=True)
            raise RuntimeError("녹음된 음성이 너무 짧습니다.")
        wav_path = recording.path.with_suffix(".wav")
        with wave.open(str(wav_path), "wb") as audio_file:
            audio_file.setnchannels(1)
            audio_file.setsampwidth(2)
            audio_file.setframerate(16000)
            audio_file.writeframes(recording.path.read_bytes())
        recording.path.unlink(missing_ok=True)
        return wav_path

    async def _stop_after_limit(self, session_id: str) -> None:
        await asyncio.sleep(self.settings.max_recording_seconds)
        async with self._lock:
            recording = self.active.get(session_id)
        if recording and recording.process.returncode is None:
            recording.process.send_signal(signal.SIGINT)


class WhisperTranscriber:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.last_model = settings.whisper_model.name
        self.fallback_count = 0

    async def transcribe(
        self,
        audio_path: Path,
        *,
        cleanup: bool = True,
    ) -> tuple[str, int]:
        if not self.settings.whisper_model.exists():
            raise RuntimeError("whisper.cpp 모델 파일을 찾을 수 없습니다.")
        output_base = audio_path.with_suffix("")
        with wave.open(str(audio_path), "rb") as audio_file:
            duration_seconds = audio_file.getnframes() / audio_file.getframerate()
            samples = array("h", audio_file.readframes(audio_file.getnframes()))
        if sys.byteorder == "big":
            samples.byteswap()
        rms = round(math.sqrt(sum(sample * sample for sample in samples) / max(1, len(samples))))
        logger.info(
            "transcription_start file=%s bytes=%d duration_seconds=%.2f rms=%d",
            audio_path.name,
            audio_path.stat().st_size,
            duration_seconds,
            rms,
        )
        if rms < self.settings.min_audio_rms:
            if cleanup and not self.settings.keep_recordings:
                audio_path.unlink(missing_ok=True)
            raise RuntimeError("목소리가 너무 작거나 들리지 않았습니다. 마이크 가까이에서 다시 말씀해 주세요.")
        output_path = Path(f"{output_base}.json")
        started = time.perf_counter()

        async def run_model(model_path: Path) -> tuple[int, bytes, bytes]:
            output_path.unlink(missing_ok=True)
            process = await asyncio.create_subprocess_exec(
                self.settings.whisper_binary,
                "-m",
                str(model_path),
                "-f",
                str(audio_path),
                "-l",
                "ko",
                "-oj",
                "-of",
                str(output_base),
                "-np",
                "-sns",
                "-nth",
                "0.60",
                "-bo",
                "1",
                "-bs",
                "1",
                "-fa",
                "--prompt",
                self.settings.whisper_prompt,
                "-t",
                str(max(1, self.settings.whisper_threads)),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            try:
                stdout, stderr = await process.communicate()
            except asyncio.CancelledError:
                if process.returncode is None:
                    process.terminate()
                    try:
                        await asyncio.wait_for(process.wait(), timeout=2)
                    except asyncio.TimeoutError:
                        process.kill()
                        await process.wait()
                raise
            return process.returncode or 0, stdout, stderr

        try:
            model_used = self.settings.whisper_model
            returncode, stdout, stderr = await run_model(model_used)
            error_text = (
                stderr.decode(errors="replace")
                + " "
                + stdout.decode(errors="replace")
            )
            memory_failure = returncode == -11 or any(
                marker in error_text
                for marker in (
                    "NvMapMem",
                    "error 12",
                    "CUDA out of memory",
                    "failed to allocate",
                )
            )
            fallback = self.settings.whisper_fallback_model
            if (
                returncode != 0
                and memory_failure
                and fallback.exists()
                and fallback != model_used
            ):
                logger.warning(
                    "whisper_memory_fallback primary=%s fallback=%s code=%d",
                    model_used.name,
                    fallback.name,
                    returncode,
                )
                model_used = fallback
                returncode, stdout, stderr = await run_model(model_used)
                self.fallback_count += 1

            if returncode != 0:
                detail = (
                    stderr.decode(errors="replace").strip()
                    or stdout.decode(errors="replace").strip()
                )
                raise RuntimeError(
                    "음성 인식에 실패했습니다"
                    f"(종료 코드 {returncode}): {detail[-500:]}"
                )
            data = json.loads(output_path.read_text(encoding="utf-8"))
            text = "".join(
                segment.get("text", "")
                for segment in data.get("transcription", [])
            ).strip()
            if not text:
                raise RuntimeError("음성을 인식하지 못했습니다. 조금 더 가까이 말씀해 주세요.")
            elapsed_ms = round((time.perf_counter() - started) * 1000)
            self.last_model = model_used.name
            logger.info(
                "transcription_done elapsed_ms=%d text_length=%d model=%s",
                elapsed_ms,
                len(text),
                model_used.name,
            )
            return text, elapsed_ms
        finally:
            output_path.unlink(missing_ok=True)
            if cleanup and not self.settings.keep_recordings:
                audio_path.unlink(missing_ok=True)


class AutomaticVoiceDetector:
    """Detects one spoken turn from the Jetson microphone without a button."""

    sample_rate = 16000
    frame_ms = 32
    frame_bytes = SileroStreamingVad.frame_bytes

    def __init__(self, settings: Settings, turn_processor):
        self.settings = settings
        self.turn_processor = turn_processor
        self._device_lock = asyncio.Lock()

    @staticmethod
    def _rms(frame: bytes) -> int:
        samples = array("h")
        samples.frombytes(frame)
        if sys.byteorder == "big":
            samples.byteswap()
        if not samples:
            return 0
        return round(math.sqrt(sum(value * value for value in samples) / len(samples)))

    async def events(
        self,
        session_id: str,
        *,
        barge_in: bool = False,
    ) -> AsyncIterator[tuple[str, dict]]:
        async with self._device_lock:
            process = await asyncio.create_subprocess_exec(
                "arecord",
                "-D",
                self.settings.alsa_device,
                "-f",
                "S16_LE",
                "-r",
                str(self.sample_rate),
                "-c",
                "1",
                "-t",
                "raw",
                "-",
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            await asyncio.sleep(0.15)
            if process.returncode is not None:
                error = (await process.stderr.read()).decode(errors="replace").strip()
                raise RuntimeError(f"Jetson 마이크를 열 수 없습니다: {error}")

            pre_roll: deque[bytes] = deque(maxlen=15)
            captured: list[bytes] = []
            noise_rms = 120.0
            voiced_frames = 0
            silent_frames = 0
            speech_started = False
            # During playback, learn the room/speaker leakage first and require a
            # longer, more confident speech run before treating it as a barge-in.
            calibration_frames = 18 if barge_in else 25
            calibration_levels: list[int] = []
            required_voice_frames = 8 if barge_in else 4
            threshold_multiplier = 2.5 if barge_in else 1.8
            confidence_threshold = (
                max(0.80, self.settings.vad_confidence)
                if barge_in
                else self.settings.vad_confidence
            )
            required_silence_frames = max(
                1, self.settings.vad_silence_ms // self.frame_ms
            )
            minimum_speech_frames = max(
                1, self.settings.vad_min_speech_ms // self.frame_ms
            )
            max_frames = self.settings.max_recording_seconds * 1000 // self.frame_ms
            heartbeat_frames = max(1, 2000 // self.frame_ms)
            frames_since_heartbeat = 0
            try:
                neural_vad = SileroStreamingVad(self.settings.silero_vad_model)
                vad_engine = "silero-onnx"
            except (FileNotFoundError, RuntimeError) as exc:
                logger.warning("silero_vad_unavailable fallback=energy error=%s", exc)
                neural_vad = None
                vad_engine = "energy-fallback"
            yield "calibrating", {"session_id": session_id}

            try:
                assert process.stdout is not None
                while True:
                    try:
                        frame = await asyncio.wait_for(
                            process.stdout.readexactly(self.frame_bytes),
                            timeout=0.6,
                        )
                    except asyncio.TimeoutError:
                        yield "heartbeat", {}
                        continue
                    except asyncio.IncompleteReadError:
                        break

                    level = self._rms(frame)
                    voice_confidence = (
                        neural_vad.confidence(frame) if neural_vad else 0.0
                    )
                    frames_since_heartbeat += 1
                    if frames_since_heartbeat >= heartbeat_frames:
                        frames_since_heartbeat = 0
                        yield "heartbeat", {
                            "session_id": session_id,
                            "mode": "barge_in" if barge_in else "normal",
                        }
                    if calibration_frames > 0:
                        calibration_levels.append(level)
                        calibration_frames -= 1
                        if calibration_frames == 0:
                            ordered = sorted(calibration_levels)
                            noise_rms = float(ordered[int(len(ordered) * 0.8)])
                            yield "calibrated", {
                                "session_id": session_id,
                                "noise_level": round(noise_rms),
                                "speech_threshold": max(
                                    self.settings.vad_speech_rms,
                                    round(noise_rms * threshold_multiplier),
                                ),
                                "engine": vad_engine,
                                "mode": "barge_in" if barge_in else "normal",
                            }
                            yield "listening", {"session_id": session_id}
                        continue
                    threshold = max(
                        self.settings.vad_speech_rms,
                        round(noise_rms * threshold_multiplier),
                    )
                    is_voice = level >= threshold and (
                        voice_confidence >= confidence_threshold
                        if neural_vad
                        else True
                    )

                    if not speech_started:
                        pre_roll.append(frame)
                        if is_voice:
                            voiced_frames += 1
                        else:
                            voiced_frames = 0
                            noise_rms = noise_rms * 0.96 + level * 0.04
                        if voiced_frames >= required_voice_frames:
                            speech_started = True
                            captured.extend(pre_roll)
                            pre_roll.clear()
                            yield "speech_start", {
                                "session_id": session_id,
                                "level": level,
                                "threshold": threshold,
                                "confidence": round(voice_confidence, 3),
                                "engine": vad_engine,
                                "mode": "barge_in" if barge_in else "normal",
                            }
                        continue

                    captured.append(frame)
                    silent_frames = 0 if is_voice else silent_frames + 1
                    if (
                        silent_frames >= required_silence_frames
                        and len(captured) >= minimum_speech_frames
                    ) or len(captured) >= max_frames:
                        break

                if not speech_started:
                    raise RuntimeError("마이크 입력이 종료되었습니다.")

                yield "speech_end", {"session_id": session_id}
                wav_path = self._write_wav(session_id, captured)
                utterance_id = uuid.uuid4().hex
                yield "transcribing", {
                    "session_id": session_id,
                    "utterance_id": utterance_id,
                    "voice_emotion": "analyzing",
                }
                transcription_task = asyncio.create_task(
                    self.turn_processor.process(
                        session_id,
                        wav_path,
                        utterance_id=utterance_id,
                    )
                )
                try:
                    while not transcription_task.done():
                        done, _ = await asyncio.wait(
                            {transcription_task},
                            timeout=2.0,
                        )
                        if not done:
                            yield "heartbeat", {
                                "session_id": session_id,
                                "stage": "transcribing",
                            }
                    transcript, stt_ms, voice_emotion, utterance_id = (
                        await transcription_task
                    )
                finally:
                    if not transcription_task.done():
                        transcription_task.cancel()
                        await asyncio.gather(
                            transcription_task,
                            return_exceptions=True,
                        )
                yield "transcript", {
                    "session_id": session_id,
                    "utterance_id": utterance_id,
                    "transcript": transcript,
                    "stt_ms": stt_ms,
                    "voice_emotion": voice_emotion,
                }
            finally:
                if process.returncode is None:
                    process.send_signal(signal.SIGINT)
                try:
                    await asyncio.wait_for(process.wait(), timeout=2)
                except asyncio.TimeoutError:
                    process.kill()
                    await process.wait()

    def _write_wav(self, session_id: str, frames: list[bytes]) -> Path:
        self.settings.recordings_dir.mkdir(parents=True, exist_ok=True)
        path = self.settings.recordings_dir / (
            f"{session_id}-{uuid.uuid4().hex}-auto.wav"
        )
        with wave.open(str(path), "wb") as audio_file:
            audio_file.setnchannels(1)
            audio_file.setsampwidth(2)
            audio_file.setframerate(self.sample_rate)
            audio_file.writeframes(b"".join(frames))
        return path


async def list_audio_devices() -> str:
    process = await asyncio.create_subprocess_exec(
        "arecord",
        "-l",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    stdout, _ = await process.communicate()
    return stdout.decode(errors="replace")
