package com.example.counseling.voiceemotion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

enum class VoiceEmotion(val displayName: String) {
    Happy("기쁨"),
    Sad("슬픔"),
    Angry("분노"),
    Fearful("불안"),
    Neutral("중립"),
}

data class VoiceEmotionResult(
    val emotion: VoiceEmotion,
    val confidence: Float,
    val probabilities: Map<VoiceEmotion, Float>,
) {
    val confidencePercent: Float get() = confidence * 100f

    fun displaySummary(): String {
        return "감정: ${emotion.displayName}(${confidencePercent.formatPercent()}%)"
    }

    fun probabilitySummary(): String {
        return probabilities.entries.joinToString(", ") { (emotion, probability) ->
            "${emotion.displayName} ${(probability * 100f).formatPercent()}%"
        }
    }
}

class LiteRtVoiceEmotionAnalyzer(
    private val context: Context,
    private val modelFileName: String = "voice_emotion.tflite",
    private val maxSamples: Int = 16000 * 12,
) : AutoCloseable {
    private var interpreter: Interpreter? = null

    suspend fun analyze(audioFile: File): VoiceEmotionResult? = withContext(Dispatchers.Default) {
        val model = loadInterpreterOrNull() ?: return@withContext null
        val waveform = readWavAsFixedMonoFloat32(audioFile, maxSamples)
        if (waveform.isEmpty()) return@withContext null

        val input = arrayOf(waveform)
        val output = Array(1) { FloatArray(VoiceEmotion.entries.size) }
        model.run(input, output)

        val probabilities = softmax(output[0])
        val bestIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: return@withContext null
        VoiceEmotionResult(
            emotion = VoiceEmotion.entries[bestIndex],
            confidence = probabilities[bestIndex],
            probabilities = VoiceEmotion.entries.associateWith { probabilities[it.ordinal] },
        )
    }

    fun buildPromptContext(result: VoiceEmotionResult): String {
        val guideline = when (result.emotion) {
            VoiceEmotion.Sad -> """
                말투: 섣부른 조언보다 감정 반영을 먼저 하고, 낮고 안정적인 톤을 유지한다.
                행동: 문제를 빨리 해결하려 들지 말고, 사용자가 지금 감당 가능한 작은 다음 행동을 고르도록 돕는다.
            """.trimIndent()
            VoiceEmotion.Angry -> """
                말투: 방어적으로 반응하지 말고, 짧고 차분하며 단정적이지 않은 문장으로 답한다.
                행동: 감정을 반박하거나 교정하지 말고, 사용자가 느낀 불편함과 경계선을 정리하도록 돕는다.
            """.trimIndent()
            VoiceEmotion.Fearful -> """
                말투: 불안을 키우지 않는 부드럽고 안정적인 톤을 유지한다.
                행동: 확신 없는 안심을 남발하지 말고, 현재 안전 확인과 즉시 할 수 있는 안정화 행동을 우선한다.
            """.trimIndent()
            VoiceEmotion.Happy -> """
                말투: 과하게 들뜨지 않되 사용자의 긍정 정서를 자연스럽게 함께한다.
                행동: 좋은 감정을 인정하고, 그 상태를 유지하거나 기록할 수 있는 작은 행동을 제안한다.
            """.trimIndent()
            VoiceEmotion.Neutral -> """
                말투: 담백하고 일상적인 톤을 유지한다.
                행동: 감정을 과잉 해석하지 말고, 사용자가 말한 내용과 필요한 확인 질문에 집중한다.
            """.trimIndent()
        }

        return """
            [음성 감정 분석]
            녹음된 사용자 음성의 주요 감정은 ${result.emotion.displayName}(${result.confidencePercent.formatPercent()}%)로 추정됩니다.
            전체 분포: ${result.probabilitySummary()}

            [응답 조정 지침]
            $guideline

            주의: 이 감정 분석은 상담 보조 신호일 뿐이며 진단으로 말하지 마세요. 음성의 실제 발화 내용과 사용자의 텍스트를 더 우선하세요.
        """.trimIndent()
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun loadInterpreterOrNull(): Interpreter? {
        interpreter?.let { return it }
        val buffer = runCatching { mapModelFile() }.getOrNull() ?: return null
        return Interpreter(buffer).also { interpreter = it }
    }

    private fun mapModelFile(): MappedByteBuffer {
        val externalModel = File(File(context.getExternalFilesDir(null), "models"), modelFileName)
        if (externalModel.exists() && externalModel.isFile && externalModel.canRead()) {
            return FileInputStream(externalModel).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
            }
        }

        context.assets.openFd("models/$modelFileName").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }
    }
}

private fun readWavAsFixedMonoFloat32(file: File, maxSamples: Int): FloatArray {
    RandomAccessFile(file, "r").use { wav ->
        require(wav.length() > 44L) { "WAV 파일이 비어 있습니다." }
        val riff = ByteArray(4)
        wav.readFully(riff)
        require(String(riff, Charsets.US_ASCII) == "RIFF") { "RIFF WAV 파일이 아닙니다." }
        wav.seek(22)
        val channels = wav.readShortLe().coerceAtLeast(1)
        val sampleRate = wav.readIntLe()
        require(sampleRate == 16000) { "음성 감정 모델은 16kHz WAV 입력을 기대합니다." }
        wav.seek(34)
        val bitsPerSample = wav.readShortLe()
        require(bitsPerSample == 16) { "음성 감정 모델은 16-bit PCM WAV 입력을 기대합니다." }

        wav.seek(12)
        var dataOffset = -1L
        var dataSize = 0
        while (wav.filePointer + 8L <= wav.length()) {
            val chunkIdBytes = ByteArray(4)
            wav.readFully(chunkIdBytes)
            val chunkId = String(chunkIdBytes, Charsets.US_ASCII)
            val chunkSize = wav.readIntLe()
            if (chunkId == "data") {
                dataOffset = wav.filePointer
                dataSize = chunkSize
                break
            }
            wav.seek(wav.filePointer + chunkSize)
        }
        require(dataOffset >= 0L && dataSize > 0) { "WAV data 청크를 찾지 못했습니다." }

        wav.seek(dataOffset)
        val frameCount = dataSize / (2 * channels)
        val sampleCount = min(frameCount, maxSamples)
        val samples = FloatArray(maxSamples)
        for (i in 0 until sampleCount) {
            var mono = 0
            repeat(channels) {
                mono += wav.readShortLe()
            }
            samples[i] = (mono / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) / 32768f
        }
        return samples
    }
}

private fun RandomAccessFile.readShortLe(): Int {
    val b0 = read()
    val b1 = read()
    if (b0 < 0 || b1 < 0) return 0
    return ((b1 shl 8) or b0).toShort().toInt()
}

private fun RandomAccessFile.readIntLe(): Int {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return 0
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}

private fun softmax(logits: FloatArray): FloatArray {
    val maxLogit = logits.maxOrNull() ?: 0f
    val exps = logits.map { kotlin.math.exp((it - maxLogit).toDouble()).toFloat() }
    val sum = exps.sum().takeIf { it > 0f } ?: 1f
    return FloatArray(logits.size) { index -> exps[index] / sum }
}

private fun Float.formatPercent(): String = "%.1f".format(this)
