package com.example.counseling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

suspend fun copyUriToInputFile(context: Context, uri: Uri, prefix: String): File = withContext(Dispatchers.IO) {
    val inputDir = File(context.filesDir, "chat_inputs").apply { mkdirs() }
    val displayName = queryOpenableDisplayName(context, uri)
    val extension = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() && it.length <= 8 }
        ?.let { ".$it" }
        ?: ""
    val target = File(inputDir, "${prefix}_${System.currentTimeMillis()}$extension")
    val partial = File(inputDir, "${target.name}.partial")

    if (partial.exists()) partial.delete()
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "첨부 파일을 열 수 없습니다." }
        partial.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    if (target.exists()) target.delete()
    check(partial.renameTo(target)) { "첨부 파일을 저장할 수 없습니다." }
    target
}

suspend fun copyUriToImageFile(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val inputDir = File(context.filesDir, "chat_inputs").apply { mkdirs() }
    val target = File(inputDir, "image_${System.currentTimeMillis()}.jpg")
    val maxSide = 768
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val width = info.size.width
            val height = info.size.height
            val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height).toFloat())
            if (scale < 1f) {
                decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            }
        }
    } else {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "이미지 파일을 열 수 없습니다." }
            BitmapFactory.decodeStream(input)
        }?.let { decoded ->
            val width = decoded.width
            val height = decoded.height
            val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height).toFloat())
            if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (width * scale).toInt(), (height * scale).toInt(), true).also {
                    decoded.recycle()
                }
            } else {
                decoded
            }
        }
    }
    requireNotNull(bitmap) { "이미지를 디코딩할 수 없습니다." }
    target.outputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) { "이미지 JPEG 변환에 실패했습니다." }
    }
    require(target.length() in 1L..1_500_000L) { "이미지 파일이 너무 큽니다." }
    bitmap.recycle()
    target
}

@Suppress("MissingPermission")
fun startAudioRecording(context: Context, scope: kotlinx.coroutines.CoroutineScope): ActiveAudioRecording {
    val sampleRate = 16_000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    require(minBufferSize > 0) { "마이크 버퍼를 초기화할 수 없습니다." }

    val inputDir = File(context.filesDir, "chat_inputs").apply { mkdirs() }
    val target = File(inputDir, "recording_${System.currentTimeMillis()}.wav")
    writeEmptyWavHeader(target, sampleRate, channels = 1, bitsPerSample = 16)

    val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        channelConfig,
        audioFormat,
        minBufferSize * 2,
    )
    require(recorder.state == AudioRecord.STATE_INITIALIZED) { "마이크 녹음기를 초기화할 수 없습니다." }
    recorder.startRecording()

    val job = scope.launch(Dispatchers.IO) {
        val buffer = ByteArray(minBufferSize)
        RandomAccessFile(target, "rw").use { output ->
            output.seek(44)
            while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) output.write(buffer, 0, read)
            }
        }
        updateWavHeader(target, sampleRate, channels = 1, bitsPerSample = 16)
    }
    return ActiveAudioRecording(recorder, target, job)
}

fun writeEmptyWavHeader(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
    file.outputStream().use { output ->
        output.write(ByteArray(44))
    }
    updateWavHeader(file, sampleRate, channels, bitsPerSample)
}

fun updateWavHeader(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
    val dataSize = (file.length() - 44L).coerceAtLeast(0L).toInt()
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    RandomAccessFile(file, "rw").use { wav ->
        wav.seek(0)
        wav.write("RIFF".toByteArray(Charsets.US_ASCII))
        wav.writeIntLe(36 + dataSize)
        wav.write("WAVE".toByteArray(Charsets.US_ASCII))
        wav.write("fmt ".toByteArray(Charsets.US_ASCII))
        wav.writeIntLe(16)
        wav.writeShortLe(1)
        wav.writeShortLe(channels)
        wav.writeIntLe(sampleRate)
        wav.writeIntLe(byteRate)
        wav.writeShortLe(blockAlign)
        wav.writeShortLe(bitsPerSample)
        wav.write("data".toByteArray(Charsets.US_ASCII))
        wav.writeIntLe(dataSize)
    }
}

fun RandomAccessFile.writeIntLe(value: Int) {
    write(byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    ))
}

fun RandomAccessFile.writeShortLe(value: Int) {
    write(byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
    ))
}

