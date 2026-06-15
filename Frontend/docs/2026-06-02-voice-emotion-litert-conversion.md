# 2026-06-02 Voice Emotion LiteRT Conversion

## Decision

The voice emotion model should use the converted TFLite/LiteRT artifact instead of the `.ptl` artifact.

Reasons:

- The PTL asset was about 1.2GB and produced a debug APK around 1.66GB.
- PTL inference can fail from mobile memory pressure or input/output signature mismatch.
- The converted float16 TFLite model is about 602.5MB.
- The debug APK with the TFLite model is about 831.3MB.

## Conversion Path

Source:

```text
Analysis/Voice/voice_emotion/final_korean_emotion_model/
```

ONNX export:

```powershell
.\.venv_voice_onnx\Scripts\python.exe .\Analysis\Voice\convert_voice_emotion_model.py --static-shape
```

TFLite conversion:

```powershell
.\.venv_voice_tflite\Scripts\onnx2tf.exe -i .\Analysis\Voice\converted\voice_emotion_static.onnx -o .\Analysis\Voice\converted\tflite_work_static2 -n
```

App model:

```text
Frontend/app/src/main/assets/models/voice_emotion.tflite
```

Copied from:

```text
Analysis/Voice/converted/tflite_work_static2/voice_emotion_static_float16.tflite
```

## Runtime Contract

Input:

```text
name: input_values
shape: [1, 192000]
dtype: float32
sample rate: 16000 Hz
duration: 12 seconds
```

The app pads shorter WAV recordings with zeros and truncates longer recordings to 12 seconds.

Output:

```text
shape: [1, 5]
labels: Happy, Sad, Angry, Fearful, Neutral
```

## Android Integration

Files:

```text
Frontend/app/src/main/java/com/example/counseling/voiceemotion/VoiceEmotionAnalyzer.kt
Frontend/app/src/main/java/com/example/counseling/ChatScreen.kt
Frontend/app/build.gradle.kts
Frontend/gradle/libs.versions.toml
```

The analyzer uses:

```kotlin
org.tensorflow.lite.Interpreter
```

The app dependency is:

```kotlin
implementation(libs.litert)
```

The PTL runtime dependency was removed.

## Build Result

```text
BUILD SUCCESSFUL
```

Measured sizes:

```text
voice_emotion.tflite: ~602.5 MB
debug APK: ~831.3 MB
```
