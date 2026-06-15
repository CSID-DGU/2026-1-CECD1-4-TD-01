# checkpoint-11500 병합 및 폰 적용 절차

`checkpoint-11500`은 PEFT/LoRA 체크포인트다. 폰 앱에서 쓰려면 다음 순서가 필요하다.

1. HF base 모델 `models/hf/gemma-4-E4B-it` 준비
2. `checkpoint-11500` LoRA를 base 모델에 병합
3. 병합된 HF 모델을 LiteRT-LM `.litertlm`으로 변환
4. 변환된 `.litertlm`을 폰에 넣고 앱 설정 탭에서 모델 파일 선택

## 로컬 현재 상태

- 체크포인트 있음: `checkpoint-11500`
- LoRA 파일: `checkpoint-11500/adapter_model.safetensors`
- base 모델 경로 요구값: `models/hf/gemma-4-E4B-it`
- 현재 로컬 `models/`에는 `.litertlm`만 있고 HF base 모델 폴더는 없다.
- 따라서 병합은 base HF 모델이 있는 PC/GPU 서버에서 실행해야 한다.

## 병합

### 서버 Python에 SSL이 되는 경우

필요 패키지:

```bash
python -m pip install torch transformers peft safetensors accelerate
```

실행:

```bash
python scripts/merge_lora_checkpoint.py \
  --base-model /path/to/models/hf/gemma-4-E4B-it \
  --checkpoint checkpoint-11500 \
  --output merged_checkpoint_11500
```

출력 폴더 `merged_checkpoint_11500`에는 병합된 HF 모델과 LiteRT 변환 helper 스크립트가 들어간다.

### 서버 Python에 SSL이 안 되는 경우

`checkpoint-11500` 폴더 안에 Linux Python 3.12용 오프라인 wheelhouse를 준비했다.

```text
checkpoint-11500/offline_wheels_py312_linux
```

현재 wheelhouse는 약 3.1GB이며, torch 2.6.0과 CUDA 12.4 의존 wheel을 포함한다. 서버로 `checkpoint-11500` 폴더 전체와 `scripts/merge_lora_checkpoint.py`, `merged_model` helper 파일들을 옮긴 뒤:

```bash
cd checkpoint-11500
PYTHON_BIN="$HOME/python312/bin/python3.12" bash install_offline_env.sh
source .venv-merge/bin/activate
python -c "import torch, transformers, peft; print(torch.__version__, torch.cuda.is_available())"
```

그 다음 프로젝트 루트에서 병합한다.

```bash
python scripts/merge_lora_checkpoint.py \
  --base-model models/hf/gemma-4-E4B-it \
  --checkpoint checkpoint-11500 \
  --output merged_checkpoint_11500
```

주의: SSL이 안 되는 Python은 Hugging Face 다운로드도 못 한다. base 모델 `google/gemma-4-E4B-it`은 SSL이 되는 환경에서 미리 받아 서버의 `models/hf/gemma-4-E4B-it`로 옮겨야 한다.

## LiteRT-LM 변환

GPU 서버에서:

```bash
cd merged_checkpoint_11500
bash prepare_litert_env.sh
source .venv/bin/activate
python inspect_model_for_litert.py
python prepare_tokenizer_for_litert.py
GPU_ID=0 RECIPE=dynamic_wi4_afp32 PREFILL_LENGTH=256 CACHE_LENGTH=1024 bash convert_litert_single.sh
```

결과는 보통 다음 위치에 생성된다.

```text
../litert_outputs/dynamic_wi4_afp32/model.litertlm
```

## 폰 적용

방법 A: 앱에서 직접 선택

1. `.litertlm` 파일을 폰으로 복사
2. 앱 하단 `설정` 탭 열기
3. `모델 파일 선택`
4. 변환된 `.litertlm` 선택

방법 B: ADB로 앱 설치와 모델 푸시

```powershell
.\scripts\push_gemma4_litertlm.ps1 -ModelPath "litert_outputs\dynamic_wi4_afp32\model.litertlm"
```

기기가 여러 개면:

```powershell
.\scripts\push_gemma4_litertlm.ps1 -DeviceSerial "DEVICE_SERIAL" -ModelPath "litert_outputs\dynamic_wi4_afp32\model.litertlm"
```

## 주의

- `.litertlm`에서 다시 LoRA 병합을 할 수는 없다. 반드시 HF base 모델이 필요하다.
- 기존 `merged_model`이 정확히 같은 base에 이전 LoRA가 이미 섞인 모델인지 불분명하므로, 새 checkpoint는 원본 `models/hf/gemma-4-E4B-it`에 병합하는 것이 안전하다.
- 변환 결과가 Android에서 깨지면 `dynamic_wi8_afp32` recipe도 시험한다.
