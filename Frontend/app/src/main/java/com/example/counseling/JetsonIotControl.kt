package com.example.counseling

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection

data class IotDevice(
    val id: String,
    val name: String,
    val kind: String,
    val state: String,
    val available: Boolean,
    val actions: List<String>,
)

data class IotDeviceList(
    val schema: String,
    val configured: Boolean,
    @SerializedName("device_count") val deviceCount: Int,
    @SerializedName("config_file") val configFile: String,
    val devices: List<IotDevice>,
)

data class IotCommandResult(
    val accepted: Boolean,
    @SerializedName("device_id") val deviceId: String,
    val action: String,
)

class JetsonIotClient {
    suspend fun listDevices(
        context: Context,
        rawEndpoint: String,
        token: String,
    ): IotDeviceList = withContext(Dispatchers.IO) {
        val response = request(
            context,
            rawEndpoint,
            token,
            "/v1/iot/devices",
            "GET",
            null,
            null,
        )
        Gson().fromJson(response, IotDeviceList::class.java)
            ?: throw IOException("IoT 장치 응답을 읽을 수 없습니다.")
    }

    suspend fun command(
        context: Context,
        rawEndpoint: String,
        token: String,
        deviceId: String,
        action: String,
    ): IotCommandResult = withContext(Dispatchers.IO) {
        val response = request(
            context,
            rawEndpoint,
            token,
            "/v1/iot/commands",
            "POST",
            "iot-command-v1",
            Gson().toJson(mapOf("device_id" to deviceId, "action" to action))
                .toByteArray(Charsets.UTF_8),
        )
        Gson().fromJson(response, IotCommandResult::class.java)
            ?: throw IOException("IoT 제어 응답을 읽을 수 없습니다.")
    }

    private fun request(
        context: Context,
        rawEndpoint: String,
        token: String,
        path: String,
        method: String,
        schema: String?,
        body: ByteArray?,
    ): String {
        require(token.isNotBlank()) { "Jetson 인증 토큰이 필요합니다." }
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val endpoint = jetsonApiUrl(rawEndpoint, path, debug)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${token.trim()}")
            schema?.let { setRequestProperty("X-OnMom-Schema", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            body?.let { connection.outputStream.use { stream -> stream.write(it) } }
            val status = connection.responseCode
            val response = readLimited(
                if (status in 200..299) connection.inputStream else connection.errorStream,
            )
            if (status !in 200..299) throw IOException("Jetson HTTP $status: ${response.take(300)}")
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream?): String {
        if (input == null) return ""
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (output.size() < 256 * 1024) {
                val read = stream.read(buffer, 0, minOf(buffer.size, 256 * 1024 - output.size()))
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
}

internal fun iotActionLabel(action: String): String = when (action) {
    "TURN_ON" -> "켜기"
    "TURN_OFF" -> "끄기"
    "TOGGLE" -> "전환"
    "LOCK" -> "잠그기"
    "UNLOCK" -> "열기"
    "OPEN" -> "열기"
    "CLOSE" -> "닫기"
    else -> action
}
