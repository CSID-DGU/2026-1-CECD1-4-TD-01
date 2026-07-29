package com.example.counseling

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

private const val DEFAULT_JETSON_PATH = "/v1/derived-insights"
internal const val DEFAULT_JETSON_ENDPOINT = "http://iot.local:8765/v1/derived-insights"
private const val MAX_SUMMARY_CHARS = 64_000
private const val MAX_PAYLOAD_BYTES = 256 * 1024
private const val VOICE_INSIGHT_TTL_MS = 30L * 60L * 1000L

data class DerivedInsightEnvelope(
    @SerializedName("schema_version")
    val schemaVersion: Int = 1,
    @SerializedName("transfer_id")
    val transferId: String,
    @SerializedName("generated_at")
    val generatedAt: Long,
    @SerializedName("producer")
    val producer: String = "onmom-android",
    @SerializedName("contains_raw_data")
    val containsRawData: Boolean = false,
    val summaries: List<DerivedInsightSummary>,
)

data class DerivedInsightSummary(
    val category: String,
    @SerializedName("updated_at")
    val updatedAt: Long,
    @SerializedName("expires_at")
    val expiresAt: Long? = null,
    val summary: String,
)

data class JetsonSyncResult(
    val statusCode: Int,
    val sentCategories: List<String>,
)

class JetsonSyncSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "jetson_sync_settings",
        Context.MODE_PRIVATE,
    )

    private val tokenStore = JetsonSecureTokenStore(preferences)
    fun loadEndpoint(): String {
        val saved = preferences.getString(KEY_ENDPOINT, "").orEmpty().trim()
        if (saved.isBlank()) return DEFAULT_JETSON_ENDPOINT
        return if (saved.startsWith("http://jetson.local", ignoreCase = true)) {
            saved.replaceFirst("jetson.local", "iot.local", ignoreCase = true)
        } else {
            saved
        }
    }

    fun loadToken(): String = tokenStore.load()

    fun save(endpoint: String, token: String) {
        preferences.edit()
            .putString(KEY_ENDPOINT, endpoint.trim())
            .apply()
        tokenStore.save(token)
    }

    private companion object {
        const val KEY_ENDPOINT = "endpoint"
    }
}

internal object DerivedInsightPrivacyGuard {
    private val forbiddenMarkers = listOf(
        "content://",
        "file://",
        "/storage/",
        "/sdcard/",
        "data:image",
        "data:audio",
        ";base64,",
        "\"cachedname\"",
        "\"packagename\"",
        "\"imagepath\"",
        "\"audiopath\"",
        "\"uri\"",
        "[현재 사용자 메시지]",
        "[사용자 입력]",
    )

    fun requireDerivedOnly(summary: String) {
        require(summary.isNotBlank()) { "빈 분석 요약은 전송할 수 없습니다." }
        require(summary.length <= MAX_SUMMARY_CHARS) { "분석 요약이 허용 크기를 초과했습니다." }
        val normalized = summary.lowercase()
        val marker = forbiddenMarkers.firstOrNull { normalized.contains(it) }
        require(marker == null) { "원본 데이터 표식이 포함된 요약은 전송하지 않습니다: $marker" }
    }
}

internal fun buildDerivedInsightEnvelope(
    ragDocuments: List<RagSlotDocument>,
    voiceInsight: DerivedInsightSummary? = null,
    now: Long = System.currentTimeMillis(),
    transferId: String = UUID.randomUUID().toString(),
): DerivedInsightEnvelope {
    val summaries = ragDocuments
        .distinctBy { it.slot }
        .map { document ->
            DerivedInsightPrivacyGuard.requireDerivedOnly(document.contextText)
            DerivedInsightSummary(
                category = document.slot.name.uppercase(),
                updatedAt = document.updatedAt,
                summary = document.contextText.trim(),
            )
        }
        .plus(
            voiceInsight
                ?.takeIf { it.expiresAt == null || it.expiresAt > now }
                ?.also { DerivedInsightPrivacyGuard.requireDerivedOnly(it.summary) },
        )
        .filterNotNull()
        .sortedBy { it.category }

    require(summaries.isNotEmpty()) {
        "전송할 파생 정보가 없습니다. 건강·생활 패턴·Gallery 분석을 먼저 실행해 주세요."
    }
    return DerivedInsightEnvelope(
        transferId = transferId,
        generatedAt = now,
        summaries = summaries,
    )
}

internal fun serializeDerivedInsightEnvelope(envelope: DerivedInsightEnvelope): String {
    // The receiver uses a fixed schema, so nullable fields must remain explicit.
    return GsonBuilder().serializeNulls().create().toJson(envelope)
}

internal fun isPrivateLanIpv4(host: String): Boolean {
    val octets = host.split('.')
    if (octets.size != 4) return false
    val values = octets.map { it.toIntOrNull() ?: return false }
    if (values.any { it !in 0..255 }) return false
    return when {
        values[0] == 10 -> true
        values[0] == 172 && values[1] in 16..31 -> true
        values[0] == 192 && values[1] == 168 -> true
        values[0] == 169 && values[1] == 254 -> true
        else -> false
    }
}

internal fun normalizeJetsonEndpoint(
    rawEndpoint: String,
    allowPrivateLanHttp: Boolean = false,
): URL {
    val input = rawEndpoint.trim()
    require(input.isNotBlank()) { "Jetson 주소를 입력해 주세요." }
    val parsed = runCatching { URI(input) }
        .getOrElse { throw IllegalArgumentException("올바른 Jetson URL이 아닙니다.") }
    val scheme = parsed.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "Jetson 주소는 http 또는 https여야 합니다." }
    require(!parsed.host.isNullOrBlank()) { "Jetson 호스트 주소가 필요합니다." }
    require(parsed.userInfo == null && parsed.query == null && parsed.fragment == null) {
        "주소에는 사용자 정보, 쿼리 또는 fragment를 넣을 수 없습니다."
    }
    if (scheme == "http") {
        val host = parsed.host
        val debugPrivateIp = allowPrivateLanHttp && isPrivateLanIpv4(host)
        require(host.equals("iot.local", ignoreCase = true) || debugPrivateIp) {
            "암호화되지 않은 http는 iot.local에서만 허용됩니다. 개발자 빌드에서는 " +
                "사설 LAN IPv4 주소(10.x, 172.16~31.x, 192.168.x, 169.254.x)도 사용할 수 있습니다. " +
                "그 외 주소는 https를 사용해 주세요."
        }
    }
    val path = when {
        parsed.path.isNullOrBlank() || parsed.path == "/" -> DEFAULT_JETSON_PATH
        parsed.path == "/v1/derived-insight" -> DEFAULT_JETSON_PATH
        else -> parsed.path
    }
    return URI(
        scheme,
        null,
        parsed.host,
        parsed.port,
        path,
        null,
        null,
    ).toURL()
}

suspend fun collectLatestDerivedInsights(
    context: Context,
    refreshLocalSummaries: Boolean,
): DerivedInsightEnvelope {
    if (refreshLocalSummaries) {
        runCatching { refreshHealthRagSlot(context, HealthPeriod.Week) }
        runCatching { refreshPhenotypeRagSlot(context) }
    }
    val ragDocuments = RagSlot.entries.mapNotNull { readRagSlot(context, it) }
    return buildDerivedInsightEnvelope(
        ragDocuments = ragDocuments,
        voiceInsight = readLatestVoiceEmotionInsight(context),
    )
}

suspend fun storeLatestVoiceEmotionInsight(context: Context, summary: String) = withContext(Dispatchers.IO) {
    DerivedInsightPrivacyGuard.requireDerivedOnly(summary)
    val now = System.currentTimeMillis()
    val record = DerivedInsightSummary(
        category = "VOICE_EMOTION",
        updatedAt = now,
        expiresAt = now + VOICE_INSIGHT_TTL_MS,
        summary = summary.trim(),
    )
    val target = latestVoiceEmotionFile(context)
    val partial = File(target.parentFile, "${target.name}.partial")
    partial.writeText(Gson().toJson(record), Charsets.UTF_8)
    if (target.exists()) target.delete()
    check(partial.renameTo(target)) { "음성 감정 파생 정보를 저장할 수 없습니다." }
}

private suspend fun readLatestVoiceEmotionInsight(context: Context): DerivedInsightSummary? =
    withContext(Dispatchers.IO) {
        runCatching {
            val file = latestVoiceEmotionFile(context)
            if (!file.exists()) return@withContext null
            Gson().fromJson(file.readText(Charsets.UTF_8), DerivedInsightSummary::class.java)
        }.getOrNull()
    }

private fun latestVoiceEmotionFile(context: Context): File {
    val directory = File(context.filesDir, "derived_insights").apply { mkdirs() }
    return File(directory, "latest_voice_emotion.json")
}

class JetsonDerivedInsightClient {
    suspend fun sendLatest(
        context: Context,
        rawEndpoint: String,
        token: String,
        refreshLocalSummaries: Boolean = true,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 20_000,
    ): JetsonSyncResult = withContext(Dispatchers.IO) {
        val isDebuggable =
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val endpoint = normalizeJetsonEndpoint(rawEndpoint, allowPrivateLanHttp = isDebuggable)
        val envelope = collectLatestDerivedInsights(context, refreshLocalSummaries = refreshLocalSummaries)
        val body = serializeDerivedInsightEnvelope(envelope).toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_PAYLOAD_BYTES) { "파생 정보 묶음이 256KB를 초과해 전송을 중단했습니다." }

        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMillis.coerceIn(500, 30_000)
            readTimeout = readTimeoutMillis.coerceIn(500, 60_000)
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-OnMom-Schema", "derived-only-v1")
            token.trim().takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
            setFixedLengthStreamingMode(body.size)
        }

        try {
            connection.outputStream.use { it.write(body) }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val detail = readLimited(connection.errorStream ?: connection.inputStream, 2_048)
                throw IOException(
                    "Jetson이 HTTP $statusCode 응답을 보냈습니다." +
                        detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(),
                )
            }
            JetsonSyncResult(
                statusCode = statusCode,
                sentCategories = envelope.summaries.map { it.category },
            )
        } catch (error: java.net.UnknownHostException) {
            throw IOException(
                "Android가 ${endpoint.host} 이름을 해석하지 못했습니다. Jetson의 사설 IPv4 주소로 다시 시도해 주세요.",
                error,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream?, maxBytes: Int): String {
        if (input == null) return ""
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (output.size() < maxBytes) {
                val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name()).trim()
        }
    }
}
