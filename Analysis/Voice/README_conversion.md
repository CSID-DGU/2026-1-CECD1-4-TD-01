# Voice Emotion Model Conversion

Source model:

```text
Analysis/Voice/voice_emotion/final_korean_emotion_model/
```

The source is a HuggingFace `Wav2Vec2ForSequenceClassification` checkpoint with:

```text
Happy, Sad, Angry, Fearful, Neutral
```

## Recommended Path

Export first to ONNX, validate the output, then convert to TFLite/LiteRT only in a Python version that supports TensorFlow.

The current local Python is 3.13, which is suitable for PyTorch export but may not support TensorFlow conversion packages cleanly.

## Export ONNX

```powershell
cd C:\Users\baak_jun\Desktop\Comprehensive_design
python .\Analysis\Voice\convert_voice_emotion_model.py --install-deps
```

Expected outputs:

```text
Analysis/Voice/converted/voice_emotion.onnx
Analysis/Voice/converted/voice_emotion_metadata.json
```

## TFLite/LiteRT Conversion

The conversion was completed in project-local Python 3.13 virtual environments:

```text
.venv_voice_onnx
.venv_voice_tflite
```

Export static ONNX first:

```powershell
.\.venv_voice_onnx\Scripts\python.exe .\Analysis\Voice\convert_voice_emotion_model.py --static-shape
```

Then convert the static ONNX to TFLite:

```powershell
.\.venv_voice_tflite\Scripts\onnx2tf.exe -i .\Analysis\Voice\converted\voice_emotion_static.onnx -o .\Analysis\Voice\converted\tflite_work_static2 -n
```

Generated files:

```text
Analysis/Voice/converted/tflite_work_static2/voice_emotion_static_float16.tflite
Analysis/Voice/converted/tflite_work_static2/voice_emotion_static_float32.tflite
```

The app uses the float16 model:

```text
Frontend/app/src/main/assets/models/voice_emotion.tflite
```

The float16 model is copied from:

```text
Analysis/Voice/converted/tflite_work_static2/voice_emotion_static_float16.tflite
```

## App Assumptions

Input:

```text
float32 mono waveform normalized to [-1, 1]
shape: [1, samples]
sample rate: 16000 Hz
```

For the current static TFLite model:

```text
shape: [1, 192000]
duration: 12 seconds at 16kHz
```

The Android app pads shorter recordings with zeros and truncates longer recordings to 12 seconds before inference.

Output:

```text
logits shape: [1, 5]
labels: Happy, Sad, Angry, Fearful, Neutral
```

## Current Artifact Sizes

```text
voice_emotion_static_float16.tflite: ~602.5 MB
voice_emotion_static_float32.tflite: ~1,204.6 MB
debug APK with float16 model: ~831.3 MB
```
