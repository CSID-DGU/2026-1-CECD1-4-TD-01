import argparse
import json
import subprocess
import sys
from pathlib import Path

import torch
from transformers import Wav2Vec2ForSequenceClassification


DEFAULT_MODEL_DIR = Path(__file__).resolve().parent / "voice_emotion" / "final_korean_emotion_model"
DEFAULT_OUTPUT_DIR = Path(__file__).resolve().parent / "converted"


class VoiceEmotionOnnxWrapper(torch.nn.Module):
    def __init__(self, model: Wav2Vec2ForSequenceClassification) -> None:
        super().__init__()
        self.model = model

    def forward(self, input_values: torch.Tensor) -> torch.Tensor:
        return self.model(input_values=input_values).logits


def ensure_package(import_name: str, pip_name: str | None = None) -> None:
    try:
        __import__(import_name)
    except ImportError as error:
        package = pip_name or import_name
        raise SystemExit(
            f"Missing Python package '{package}'. Install it first:\n"
            f"  python -m pip install {package}\n"
        ) from error


def install_export_dependencies() -> None:
    packages = ["onnx", "onnxruntime", "onnxscript"]
    subprocess.check_call([sys.executable, "-m", "pip", "install", *packages])


def export_onnx(model_dir: Path, output_dir: Path, sample_seconds: int, static_shape: bool) -> Path:
    ensure_package("onnx")
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / ("voice_emotion_static.onnx" if static_shape else "voice_emotion.onnx")

    model = Wav2Vec2ForSequenceClassification.from_pretrained(model_dir)
    model.eval()
    model.config.apply_spec_augment = False

    wrapper = VoiceEmotionOnnxWrapper(model).eval()
    sample_count = 16_000 * sample_seconds
    dummy_input = torch.zeros(1, sample_count, dtype=torch.float32)

    export_kwargs = {
        "input_names": ["input_values"],
        "output_names": ["logits"],
        "opset_version": 18,
        "do_constant_folding": True,
    }
    if not static_shape:
        export_kwargs["dynamic_axes"] = {
            "input_values": {0: "batch", 1: "samples"},
            "logits": {0: "batch"},
        }

    with torch.no_grad():
        torch.onnx.export(wrapper, dummy_input, output_path.as_posix(), **export_kwargs)

    return output_path


def validate_onnx(onnx_path: Path, sample_seconds: int) -> None:
    ensure_package("onnx")
    ensure_package("onnxruntime")
    import onnx
    import onnxruntime as ort
    import numpy as np

    model = onnx.load(onnx_path.as_posix())
    onnx.checker.check_model(model)

    session = ort.InferenceSession(onnx_path.as_posix(), providers=["CPUExecutionProvider"])
    sample = np.zeros((1, 16_000 * sample_seconds), dtype=np.float32)
    outputs = session.run(["logits"], {"input_values": sample})
    logits = outputs[0]
    if logits.shape[-1] != 5:
        raise RuntimeError(f"Expected 5 emotion logits, got shape {logits.shape}")


def write_metadata(model_dir: Path, output_dir: Path, sample_seconds: int, static_shape: bool) -> None:
    config_path = model_dir / "config.json"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    metadata = {
        "source_model_dir": model_dir.as_posix(),
        "sample_rate": 16000,
        "sample_seconds": sample_seconds,
        "input_name": "input_values",
        "input_shape": [1, 16000 * sample_seconds] if static_shape else ["batch", "samples"],
        "output_name": "logits",
        "labels": [config["id2label"][str(index)] for index in range(5)],
        "notes": [
            "The exported ONNX model accepts mono float32 waveform values normalized to [-1, 1].",
            "Static export pads/truncates audio to the configured sample_seconds before inference.",
            "Voice emotion is current-turn prompt context, not a long-lived RAG slot.",
            "For TFLite/LiteRT conversion, use Python 3.11 or 3.12 with TensorFlow/onnx2tf installed.",
        ],
    }
    (output_dir / "voice_emotion_metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def print_tflite_guidance(output_dir: Path) -> None:
    onnx_path = output_dir / "voice_emotion.onnx"
    print("\nONNX export is ready.")
    print(f"  {onnx_path}")
    print("\nOptional TFLite/LiteRT conversion should be run in Python 3.11/3.12:")
    print("  python -m venv .venv_voice_convert")
    print("  .venv_voice_convert\\Scripts\\activate")
    print("  python -m pip install tensorflow onnx onnx2tf")
    print(f"  onnx2tf -i {onnx_path.as_posix()} -o {(output_dir / 'tflite_work').as_posix()}")
    print("\nIf onnx2tf fails on Wav2Vec2 ops, use ONNX Runtime Mobile on Android or export with ExecuTorch.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export Korean Wav2Vec2 emotion model from safetensors/HF format.")
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--sample-seconds", type=int, default=12)
    parser.add_argument("--static-shape", action="store_true", help="Export fixed [1, sample_seconds*16000] input shape.")
    parser.add_argument("--install-deps", action="store_true", help="Install onnx and onnxruntime before export.")
    parser.add_argument("--skip-validate", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.install_deps:
        install_export_dependencies()

    model_dir = args.model_dir.resolve()
    output_dir = args.output_dir.resolve()
    if not model_dir.exists():
        raise SystemExit(f"Model directory not found: {model_dir}")

    onnx_path = export_onnx(model_dir, output_dir, args.sample_seconds, args.static_shape)
    if not args.skip_validate:
        validate_onnx(onnx_path, args.sample_seconds)
    write_metadata(model_dir, output_dir, args.sample_seconds, args.static_shape)
    print_tflite_guidance(output_dir)


if __name__ == "__main__":
    main()
