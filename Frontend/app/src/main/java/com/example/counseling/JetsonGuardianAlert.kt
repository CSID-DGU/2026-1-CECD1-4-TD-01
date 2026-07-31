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
import java.net.URI
import java.net.URL

data class GuardianAlert(
    val sequence: Long,
    @SerializedName("alert_id") val alertId: String,
    @SerializedName("occurred_at") val occurredAt: Long,
    val severity: String,
    val category: String,
    val source: String,
    val title: String,
    val message: String,
    val acknowledged: Boolean,
    @SerializedName("acknowledged_at") val acknowledgedAt: Long?,
)

data class GuardianAlertBatch(
    val schema: String,
    val count: Int,
    @SerializedName("next_cursor") val nextCursor: Long,
    val alerts: List<GuardianAlert>,
)

internal fun jetsonApiUrl(
    rawEndpoint: String,
    path: String,
    allowPrivateLanHttp: Boolean,
    query: String? = null,
): URL {
    val validated = normalizeJetsonEndpoint(rawEndpoint, allowPrivateLanHttp)
    return URI(
        validated.protocol,
        null,
        validated.host,
        validated.port,
        path,
        query,
        null,
    ).toURL()
}

class GuardianAlertClient {
    suspend fun fetch(
        context: Context,
        rawEndpoint: String,
        token: String,
        afterSequence: Long = 0,
        limit: Int = 50,
    ): GuardianAlertBatch = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Jetson 인증 토큰이 필요합니다." }
        require(afterSequence >= 0) { "알림 커서는 0 이상이어야 합니다." }
        val endpoint = jetsonApiUrl(
            rawEndpoint,
            "/v1/guardian-alerts",
            isDebuggable(context),
            "after=$afterSequence&limit=${limit.coerceIn(1, 100)}",
        )
        val response = request(endpoint, "GET", token, null, null)
        Gson().fromJson(response, GuardianAlertBatch::class.java)
            ?: throw IOException("Jetson 알림 응답을 읽을 수 없습니다.")
    }

    suspend fun acknowledge(
        context: Context,
        rawEndpoint: String,
        token: String,
        alertId: String,
    ) = withContext(Dispatchers.IO) {
        val endpoint = jetsonApiUrl(
            rawEndpoint,
            "/v1/guardian-alerts/ack",
            isDebuggable(context),
        )
        request(
            endpoint,
            "POST",
            token,
            "guardian-alert-ack-v1",
            Gson().toJson(mapOf("alert_id" to alertId)).toByteArray(Charsets.UTF_8),
        )
    }

    private fun request(
        endpoint: URL,
        method: String,
        token: String,
        schema: String?,
        body: ByteArray?,
    ): String {
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
            if (status !in 200..299) {
                throw IOException("Jetson HTTP $status: ${response.take(300)}")
            }
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

    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

internal fun shouldNotifyGuardianAlert(alert: GuardianAlert, now: Long): Boolean {
    val age = now - alert.occurredAt
    return !alert.acknowledged &&
        age in -5L * 60L * 1000L..24L * 60L * 60L * 1000L
}

internal class GuardianAlertCursorStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "guardian_alert_monitor",
        Context.MODE_PRIVATE,
    )

    fun load(): Long = preferences.getLong("last_sequence", 0L).coerceAtLeast(0L)

    fun save(sequence: Long) {
        preferences.edit().putLong("last_sequence", sequence.coerceAtLeast(0L)).apply()
    }
}
