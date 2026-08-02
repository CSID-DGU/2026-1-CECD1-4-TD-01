"""Streaming wrapper for the open-source Silero VAD ONNX model."""

from pathlib import Path

import numpy as np
import onnxruntime


class SileroStreamingVad:
    """Runs Silero VAD on consecutive 512-sample, 16 kHz PCM frames."""

    sample_rate = 16000
    frame_samples = 512
    frame_bytes = frame_samples * 2

    def __init__(self, model_path: Path):
        if not model_path.exists():
            raise FileNotFoundError(model_path)
        options = onnxruntime.SessionOptions()
        options.inter_op_num_threads = 1
        options.intra_op_num_threads = 1
        self.session = onnxruntime.InferenceSession(
            str(model_path),
            providers=["CPUExecutionProvider"],
            sess_options=options,
        )
        self.reset()

    def reset(self) -> None:
        self.state = np.zeros((2, 1, 128), dtype=np.float32)
        self.context = np.zeros((1, 64), dtype=np.float32)

    def confidence(self, pcm: bytes) -> float:
        samples = np.frombuffer(pcm, dtype=np.int16)
        if samples.size != self.frame_samples:
            raise ValueError(
                f"Silero VAD requires {self.frame_samples} samples, got {samples.size}"
            )
        audio = samples.astype(np.float32)[None, :] / 32768.0
        model_input = np.concatenate((self.context, audio), axis=1)
        output, self.state = self.session.run(
            None,
            {
                "input": model_input,
                "state": self.state,
                "sr": np.array(self.sample_rate, dtype=np.int64),
            },
        )
        self.context = model_input[:, -64:]
        return float(output.reshape(-1)[0])
