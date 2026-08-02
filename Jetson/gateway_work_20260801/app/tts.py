import asyncio
import io
import logging
import os
import wave
from pathlib import Path
from threading import Lock
from typing import Any

import numpy as np
import soundfile as sf
from piper.voice import PiperVoice, SynthesisConfig
try:
    import sherpa_onnx
except ImportError:  # Piper remains available in minimal development installs.
    sherpa_onnx = None
try:
    from supertonic import TTS as SupertonicEngine
except ImportError:  # Piper remains available in minimal development installs.
    SupertonicEngine = None

from .config import Settings

logger = logging.getLogger("onmom.tts")


class OnMomTts:
    """Natural Korean Supertonic TTS with a local Piper fallback."""

    _VOICE_IDS = {
        "M1": 0,
        "M2": 1,
        "M3": 2,
        "M4": 3,
        "M5": 4,
        "F1": 5,
        "F2": 6,
        "F3": 7,
        "F4": 8,
        "F5": 9,
    }

    def __init__(self, settings: Settings):
        self.settings = settings
        self._sherpa_supertonic: Any = None
        self._supertonic: Any = None
        self._voice_style = None
        self._piper: PiperVoice | None = None
        self._load_lock = Lock()
        self._synthesis_lock = Lock()

    @property
    def backend(self) -> str:
        return self.settings.tts_backend.lower().strip()

    @property
    def model_name(self) -> str:
        if self.backend == "supertonic_int8":
            return "Supertonic 3 INT8 (sherpa-onnx)"
        if self.backend == "supertonic":
            return "Supertonic 3 FP32"
        return "Piper Korean medium"

    @property
    def available(self) -> bool:
        if self.backend == "supertonic_int8":
            return (
                sherpa_onnx is not None
                and self._supertonic_int8_paths_available()
            )
        if self.backend == "supertonic":
            return SupertonicEngine is not None
        model = self.settings.piper_model
        return model.exists() and model.with_suffix(
            f"{model.suffix}.json"
        ).exists()

    async def warmup(self) -> None:
        started = asyncio.get_running_loop().time()
        if self.backend == "supertonic_int8":
            # Load the optimized model and exercise every synthesis stage so
            # the first counselor response does not pay a cold-start penalty.
            await asyncio.to_thread(
                self._synthesize_supertonic_int8,
                "안녕하세요.",
                1.0,
            )
        elif self.backend == "supertonic":
            await asyncio.to_thread(self._get_supertonic)
        else:
            # Load the ONNX session and run one short inference before the API
            # reports ready. This removes the large first-request cold start.
            await asyncio.to_thread(
                self._synthesize_piper,
                "음성 안내를 준비했습니다.",
                1.0,
            )
        logger.info(
            "tts_warmup_done backend=%s elapsed_ms=%d",
            self.backend,
            round((asyncio.get_running_loop().time() - started) * 1000),
        )

    async def synthesize(self, text: str, speech_rate: float = 0.92) -> bytes:
        return await asyncio.to_thread(self._synthesize_sync, text, speech_rate)

    def _synthesize_sync(self, text: str, speech_rate: float) -> bytes:
        with self._synthesis_lock:
            owns_priority_marker = self._enable_speech_priority()
            try:
                if self.backend == "supertonic_int8":
                    try:
                        return self._synthesize_supertonic_int8(
                            text,
                            speech_rate,
                        )
                    except Exception:
                        logger.exception(
                            "supertonic_int8_failed falling_back_to_piper"
                        )
                elif self.backend == "supertonic":
                    try:
                        return self._synthesize_supertonic(text, speech_rate)
                    except Exception:
                        logger.exception(
                            "supertonic_failed falling_back_to_piper"
                        )
                return self._synthesize_piper(text, speech_rate)
            finally:
                self._disable_speech_priority(owns_priority_marker)

    def _synthesize_supertonic_int8(
        self,
        text: str,
        speech_rate: float,
    ) -> bytes:
        engine = self._get_sherpa_supertonic()
        voice = self.settings.supertonic_voice.upper().strip()
        try:
            voice_id = self._VOICE_IDS[voice]
        except KeyError as exc:
            raise RuntimeError(
                f"지원하지 않는 Supertonic 음색입니다: {voice}"
            ) from exc

        generation = sherpa_onnx.GenerationConfig()
        generation.sid = voice_id
        generation.num_steps = max(
            5,
            min(12, self.settings.supertonic_steps),
        )
        generation.speed = max(0.70, min(1.20, speech_rate))
        generation.extra = {"lang": "ko"}
        audio = engine.generate(text, generation)
        samples = np.asarray(audio.samples, dtype=np.float32)
        if samples.size == 0:
            raise RuntimeError(
                "Supertonic 3가 음성을 생성하지 못했습니다. "
                "입력 문장을 확인해 주세요."
            )

        output = io.BytesIO()
        sf.write(
            output,
            samples,
            int(audio.sample_rate),
            format="WAV",
            subtype="PCM_16",
        )
        logger.info(
            "tts_done backend=supertonic_int8 voice=%s steps=%d chars=%d",
            voice,
            generation.num_steps,
            len(text),
        )
        return output.getvalue()

    def _get_sherpa_supertonic(self) -> Any:
        if sherpa_onnx is None:
            raise RuntimeError(
                "sherpa-onnx TTS 런타임이 설치되지 않았습니다."
            )
        if self._sherpa_supertonic is None:
            with self._load_lock:
                if self._sherpa_supertonic is None:
                    model_dir = Path(
                        self.settings.supertonic_int8_model_dir
                    )
                    paths = self._supertonic_int8_paths(model_dir)
                    missing = [
                        str(path)
                        for path in paths.values()
                        if not path.exists()
                    ]
                    if missing:
                        raise RuntimeError(
                            "Supertonic 3 INT8 모델 파일을 찾을 수 없습니다: "
                            + ", ".join(missing)
                        )
                    logger.info(
                        "loading_supertonic_int8_model path=%s threads=%d",
                        model_dir,
                        self.settings.supertonic_threads,
                    )
                    model_config = (
                        sherpa_onnx.OfflineTtsSupertonicModelConfig(
                            duration_predictor=str(
                                paths["duration_predictor"]
                            ),
                            text_encoder=str(paths["text_encoder"]),
                            vector_estimator=str(
                                paths["vector_estimator"]
                            ),
                            vocoder=str(paths["vocoder"]),
                            tts_json=str(paths["tts_json"]),
                            unicode_indexer=str(paths["unicode_indexer"]),
                            voice_style=str(paths["voice_style"]),
                        )
                    )
                    config = sherpa_onnx.OfflineTtsConfig(
                        model=sherpa_onnx.OfflineTtsModelConfig(
                            supertonic=model_config,
                            num_threads=max(
                                1,
                                min(
                                    6,
                                    self.settings.supertonic_threads,
                                ),
                            ),
                            provider="cpu",
                            debug=False,
                        )
                    )
                    self._sherpa_supertonic = (
                        sherpa_onnx.OfflineTts(config)
                    )
        return self._sherpa_supertonic

    @staticmethod
    def _supertonic_int8_paths(
        model_dir: Path,
    ) -> dict[str, Path]:
        return {
            "duration_predictor": (
                model_dir / "duration_predictor.int8.onnx"
            ),
            "text_encoder": model_dir / "text_encoder.int8.onnx",
            "vector_estimator": (
                model_dir / "vector_estimator.int8.onnx"
            ),
            "vocoder": model_dir / "vocoder.int8.onnx",
            "tts_json": model_dir / "tts.json",
            "unicode_indexer": model_dir / "unicode_indexer.bin",
            "voice_style": model_dir / "voice.bin",
        }

    def _supertonic_int8_paths_available(self) -> bool:
        model_dir = Path(self.settings.supertonic_int8_model_dir)
        return all(
            path.exists()
            for path in self._supertonic_int8_paths(model_dir).values()
        )

    def _enable_speech_priority(self) -> bool:
        marker = getattr(self.settings, "speech_priority_marker", None)
        if marker is None:
            return False
        path = Path(marker)
        if path.exists():
            return False
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                f"phase=tts\npid={os.getpid()}\n",
                encoding="utf-8",
            )
            return True
        except OSError:
            logger.exception(
                "tts_priority_marker_write_failed path=%s",
                path,
            )
            return False

    def _disable_speech_priority(self, owned: bool) -> None:
        if not owned:
            return
        marker = getattr(self.settings, "speech_priority_marker", None)
        if marker is None:
            return
        try:
            Path(marker).unlink(missing_ok=True)
        except OSError:
            logger.exception(
                "tts_priority_marker_remove_failed path=%s",
                marker,
            )

    def _synthesize_supertonic(self, text: str, speech_rate: float) -> bytes:
        engine, style = self._get_supertonic()
        speed = max(0.70, min(1.20, speech_rate))
        wav, _duration = engine.synthesize(
            text=text,
            voice_style=style,
            lang="ko",
            total_steps=max(5, min(12, self.settings.supertonic_steps)),
            speed=speed,
            max_chunk_length=120,
            silence_duration=0.22,
            verbose=False,
        )
        output = io.BytesIO()
        sf.write(
            output,
            np.asarray(wav, dtype=np.float32).squeeze(),
            44100,
            format="WAV",
            subtype="PCM_16",
        )
        logger.info(
            "tts_done backend=supertonic voice=%s chars=%d",
            self.settings.supertonic_voice,
            len(text),
        )
        return output.getvalue()

    def _get_supertonic(self) -> tuple[Any, object]:
        if SupertonicEngine is None:
            raise RuntimeError("Supertonic TTS 패키지가 설치되지 않았습니다.")
        if self._supertonic is None:
            with self._load_lock:
                if self._supertonic is None:
                    logger.info("loading_supertonic_model")
                    self._supertonic = SupertonicEngine(auto_download=True)
                    self._voice_style = self._supertonic.get_voice_style(
                        voice_name=self.settings.supertonic_voice
                    )
        assert self._voice_style is not None
        return self._supertonic, self._voice_style

    def _synthesize_piper(self, text: str, speech_rate: float) -> bytes:
        voice = self._get_piper()
        chunks = iter(
            voice.synthesize(
                text,
                syn_config=SynthesisConfig(
                    length_scale=1.0
                    / max(0.70, min(1.20, speech_rate)),
                    volume=1.0,
                ),
            )
        )
        first_chunk = next(chunks, None)
        if first_chunk is None:
            logger.warning("tts_no_audio backend=piper chars=%d", len(text))
            raise RuntimeError(
                "TTS\uac00 \ud569\uc131\ud560 \uc218 \uc788\ub294 "
                "\ubc1c\uc74c\uc774 \uc5c6\uc2b5\ub2c8\ub2e4. "
                "\uc785\ub825 \ubb38\uc790\ub97c \ud655\uc778\ud574 \uc8fc\uc138\uc694."
            )

        output = io.BytesIO()
        with wave.open(output, "wb") as wav_file:
            wav_file.setframerate(first_chunk.sample_rate)
            wav_file.setsampwidth(first_chunk.sample_width)
            wav_file.setnchannels(first_chunk.sample_channels)
            wav_file.writeframes(first_chunk.audio_int16_bytes)
            for chunk in chunks:
                wav_file.writeframes(chunk.audio_int16_bytes)
        logger.info("tts_done backend=piper chars=%d", len(text))
        return output.getvalue()

    def _get_piper(self) -> PiperVoice:
        if self._piper is None:
            with self._load_lock:
                if self._piper is None:
                    model = self.settings.piper_model
                    config = model.with_suffix(f"{model.suffix}.json")
                    if not model.exists() or not config.exists():
                        raise RuntimeError(
                            "한국어 Piper 대체 TTS 모델을 찾을 수 없습니다."
                        )
                    logger.info("loading_piper_model path=%s", model)
                    self._piper = PiperVoice.load(model)
        return self._piper
