# 2026-06-02 Voice Emotion PTL Integration

## Summary

The voice emotion model was integrated as a PyTorch Lite `.ptl` asset and included in the debug APK.

Status update:

This PTL path was later replaced by a static float16 TFLite/LiteRT artifact because the PTL model was too large and could crash after analysis on-device. Keep this document as the PTL trial record, not the current production path.

Model source:

```text
Analysis/Voice/wav2vec2_korean_emotion.ptl
```

APK asset destination:

```text
Frontend/app/src/main/assets/models/wav2vec2_korean_emotion.ptl
```

Both paths are ignored by git because the model is a large binary artifact.

## Runtime Flow

1. The user records a voice message in the chat screen.
2. The app saves the recording as 16kHz mono 16-bit PCM WAV.
3. `PyTorchVoiceEmotionAnalyzer` loads `wav2vec2_korean_emotion.ptl`.
4. The WAV waveform is converted to a float tensor shaped `[1, samples]`.
5. The model output is interpreted as logits for:

```text
Happy, Sad, Angry, Fearful, Neutral
```

6. The logits are converted to probabilities.
7. The top emotion and confidence are converted into current-turn prompt context.
8. The original audio file is still passed to Gemma/LiteRT-LM when direct attachment mode is enabled.

Voice emotion remains current-turn context only. It is not stored as a RAG slot because it can change every utterance and can become stale quickly.

## Code Changes

### Voice Emotion Analyzer

File:

```text
Frontend/app/src/main/java/com/example/counseling/voiceemotion/VoiceEmotionAnalyzer.kt
```

Main class:

```kotlin
PyTorchVoiceEmotionAnalyzer
```

Key runtime API:

```kotlin
LiteModuleLoader.load(path)
Tensor.fromBlob(waveform, longArrayOf(1L, waveform.size.toLong()))
module.forward(IValue.from(input)).toTensor()
```

The analyzer first checks:

```text
context.getExternalFilesDir(null)/models/wav2vec2_korean_emotion.ptl
```

If not found, it copies the asset model from:

```text
assets/models/wav2vec2_korean_emotion.ptl
```

to:

```text
context.filesDir/wav2vec2_korean_emotion.ptl
```

PyTorch Lite requires a normal file path, so the asset must be copied out before loading.

### ChatScreen Integration

File:

```text
Frontend/app/src/main/java/com/example/counseling/ChatScreen.kt
```

The chat screen now creates:

```kotlin
PyTorchVoiceEmotionAnalyzer(context.applicationContext)
```

Before sending a voice-attached user message, it calls `withVoiceEmotionContext()`.

If emotion analysis succeeds, this block is prepended to the current user prompt:

```text
[음성 감정 분석]
녹음된 사용자 음성의 주요 감정은 ...

[응답 조정 지침]
...
```

If emotion analysis fails or the model cannot be loaded, the app keeps the original audio attachment flow and sends the voice message without emotion context.

### Gradle Dependencies

Files:

```text
Frontend/gradle/libs.versions.toml
Frontend/app/build.gradle.kts
```

Added:

```toml
pytorchAndroid = "2.1.0"
pytorch-android-lite = { group = "org.pytorch", name = "pytorch_android_lite", version.ref = "pytorchAndroid" }
```

App dependency:

```kotlin
implementation(libs.pytorch.android.lite)
```

Asset packaging:

```kotlin
noCompress += listOf("gguf", "litertlm", "task", "tflite", "ptl")
```

The previous standalone LiteRT dependency for the voice emotion `.tflite` path was removed. LiteRT-LM remains in use for Gemma.

## Git Ignore

File:

```text
.gitignore
```

Added:

```gitignore
Analysis/Voice/*.ptl
Frontend/app/src/main/assets/models/*.ptl
```

The model is included in local APK builds but is not tracked by git.

## Build Result

Build command used:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```

Result:

```text
BUILD SUCCESSFUL
```

Output APKs:

```text
Frontend/app/build/outputs/apk/debug/app-debug.apk
Frontend/app/build/outputs/apk/debug/Counseling_06_01_v1.0.5_debug.apk
```

Measured sizes:

```text
wav2vec2_korean_emotion.ptl: 1,204.5 MB
debug APK: 1,657.7 MB
```

## Operational Notes

- The APK is very large because the `.ptl` file is about 1.2GB.
- On first use, the model is copied from assets to app-private storage because PyTorch Lite needs a file path.
- This means device storage usage can be roughly APK size plus another model copy.
- The model label order is assumed to be:

```text
Happy, Sad, Angry, Fearful, Neutral
```

- The model input is assumed to accept a float waveform tensor shaped `[1, samples]`.
- If the exported `.ptl` expects a different input shape, padding length, attention mask, or label order, only `VoiceEmotionAnalyzer.kt` should need adjustment.

## Recommended Next Steps

- Use the TFLite/LiteRT path documented in `Analysis/Voice/README_conversion.md`.
- Verify inference on a device with a short recorded WAV.
- Rename the dated debug APK task from `06_01` to `06_02` if the filename should match the current build date.
