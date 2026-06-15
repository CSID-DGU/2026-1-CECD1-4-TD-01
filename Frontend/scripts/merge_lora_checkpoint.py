import argparse
import shutil
from pathlib import Path

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer


LITERT_HELPERS = [
    "check_litert_output.py",
    "convert_litert_parallel.sh",
    "convert_litert_single.sh",
    "inspect_model_for_litert.py",
    "prepare_litert_env.sh",
    "prepare_tokenizer_for_litert.py",
    "README_litert_conversion.md",
    "requirements_litert.txt",
    "RUN_ON_GPU_SERVER.md",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Merge a PEFT/LoRA checkpoint into a Hugging Face base model.",
    )
    parser.add_argument(
        "--base-model",
        required=True,
        help="Path to the Hugging Face base model, e.g. models/hf/gemma-4-E4B-it.",
    )
    parser.add_argument(
        "--checkpoint",
        default="checkpoint-11500",
        help="Path to the PEFT checkpoint directory.",
    )
    parser.add_argument(
        "--output",
        default="merged_checkpoint_11500",
        help="Output directory for the merged Hugging Face model.",
    )
    parser.add_argument(
        "--helper-source",
        default="merged_model",
        help="Directory containing existing LiteRT helper scripts to copy.",
    )
    parser.add_argument(
        "--dtype",
        choices=["auto", "bfloat16", "float16", "float32"],
        default="bfloat16",
        help="Torch dtype used while loading and saving the merged model.",
    )
    parser.add_argument(
        "--device-map",
        default="auto",
        help="Transformers device_map. Use 'cpu' to force CPU.",
    )
    return parser.parse_args()


def dtype_from_name(name: str):
    return {
        "auto": "auto",
        "bfloat16": torch.bfloat16,
        "float16": torch.float16,
        "float32": torch.float32,
    }[name]


def copy_litert_helpers(helper_source: Path, output_dir: Path) -> None:
    if not helper_source.exists():
        return
    for name in LITERT_HELPERS:
        source = helper_source / name
        if source.exists():
            shutil.copy2(source, output_dir / name)


def main() -> None:
    args = parse_args()
    base_model = Path(args.base_model).expanduser().resolve()
    checkpoint = Path(args.checkpoint).expanduser().resolve()
    output = Path(args.output).expanduser().resolve()
    helper_source = Path(args.helper_source).expanduser().resolve()

    if not base_model.exists():
        raise FileNotFoundError(f"Base model not found: {base_model}")
    if not checkpoint.exists():
        raise FileNotFoundError(f"Checkpoint not found: {checkpoint}")

    output.mkdir(parents=True, exist_ok=True)
    torch_dtype = dtype_from_name(args.dtype)

    print(f"Loading base model: {base_model}")
    model = AutoModelForCausalLM.from_pretrained(
        base_model,
        torch_dtype=torch_dtype,
        device_map=args.device_map,
        trust_remote_code=True,
    )

    print(f"Loading LoRA checkpoint: {checkpoint}")
    model = PeftModel.from_pretrained(model, checkpoint)

    print("Merging LoRA weights into base model")
    model = model.merge_and_unload()

    print(f"Saving merged model: {output}")
    model.save_pretrained(output, safe_serialization=True, max_shard_size="20GB")

    tokenizer = AutoTokenizer.from_pretrained(base_model, trust_remote_code=True)
    tokenizer.save_pretrained(output)

    copy_litert_helpers(helper_source, output)
    print("Done.")
    print(f"Next: cd {output} && bash prepare_litert_env.sh && bash convert_litert_single.sh")


if __name__ == "__main__":
    main()
