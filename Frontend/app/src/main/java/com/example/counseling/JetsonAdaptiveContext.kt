package com.example.counseling

import android.content.Context
import com.example.counseling.llm.ChatMessage
import com.example.counseling.llm.ChatRole
import com.example.counseling.voiceemotion.VoiceEmotionResult
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID



internal val ALLOWED_JETSON_STYLE_DIMENSIONS = setOf(
    "RESPONSE_LENGTH",
    "EMPATHY_RATIO",
    "QUESTION_FREQUENCY",
    "ADVICE_DIRECTNESS",
    "GROUNDING_INTENSITY",
    "EXPLANATION_DETAIL",
    "PROACTIVITY",
    "WARMTH",
    "ACTION_SIZE",
    "HEALTH_MENTION",
)

data class JetsonEmotionState(
    val valence: Double,
    val arousal: Double,
    val confidence: Double,
    val observedAt: Long,
)


data class JetsonResponseStyle(
    val values: Map<String, Double>,
    @SerializedName("baseline_values")
    val baselineValues: Map<String, Double>,
    @SerializedName("explored_dimensions")
    val exploredDimensions: List<String>,
)

data class JetsonAdaptiveContextResult(
    val promptContext: String?,
    val emotionBucket: String?,
    val recommendedStrategies: List<String>,
    val responseStyle: JetsonResponseStyle?,
)

data class JetsonLearningStatusSummary(
    val learningState: String,
    val sessionOutcomes: Int,
    val trainedOutcomes: Int,
    val pendingOutcomes: Int,
    val latestPolicyVersion: Int,
    val minimumWeight: Double,
    val maximumWeight: Double,
    val implicitLikes: Int,
    val implicitNeutral: Int,
    val implicitDislikes: Int,
    val styleDimensions: Int,
    val styleObservations: Int,
    val changedStyleDimensions: Int,
)

data class PendingJetsonOutcome(
    val outcomeId: String = UUID.randomUUID().toString(),
    val conversationSessionId: String,
    val startedAt: Long,
    val before: JetsonEmotionState,
    val strategies: List<String>,
    val responseStyle: JetsonResponseStyle? = null,
)

internal fun PendingJetsonOutcome.isWithinOutcomeWindow(now: Long): Boolean {
    return startedAt <= now + JetsonAdaptiveClientPolicy.MAX_CLOCK_SKEW_MS &&
        startedAt >= now - MAX_JETSON_OUTCOME_INTERVAL_MS &&
        before.observedAt <= now + JetsonAdaptiveClientPolicy.MAX_CLOCK_SKEW_MS
}

class PendingJetsonOutcomeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "jetson_pending_outcome",
        Context.MODE_PRIVATE,
    )
    private val gson = Gson()

    fun load(): PendingJetsonOutcome? {
        val encoded = preferences.getString(KEY_PENDING_OUTCOME, null) ?: return null
        return runCatching {
            gson.fromJson(encoded, PendingJetsonOutcome::class.java)
        }.getOrNull()?.takeIf { pending ->
            pending.conversationSessionId.isNotBlank() &&
                pending.startedAt > 0L &&
                pending.strategies.isNotEmpty() &&
                pending.isWithinOutcomeWindow(System.currentTimeMillis())
        }
    }

    fun save(pending: PendingJetsonOutcome?) {
        preferences.edit().apply {
            if (pending == null) {
                remove(KEY_PENDING_OUTCOME)
            } else {
                putString(KEY_PENDING_OUTCOME, gson.toJson(pending))
            }
        }.apply()
    }

    private companion object {
        const val KEY_PENDING_OUTCOME = "pending_outcome"
    }
}

private data class SessionOutcomeEnvelope(
    @SerializedName("schema_version")
    val schemaVersion: Int = 1,
    @SerializedName("session_id")
    val sessionId: String,
    @SerializedName("started_at")
    val startedAt: Long,
    @SerializedName("ended_at")
    val endedAt: Long,
    val before: EmotionPoint,
    val after: EmotionPoint,
    val strategies: List<String>,
    @SerializedName("response_style")
    val responseStyle: JetsonResponseStyle? = null,
    @SerializedName("explicit_feedback")
    val explicitFeedback: Double? = null,
    @SerializedName("contains_raw_data")
    val containsRawData: Boolean = false,
)

private data class EmotionPoint(
    val valence: Double,
    val arousal: Double,
    val confidence: Double,
)

private data class AnalysisEventEnvelope(
    @SerializedName("schema_version")
    val schemaVersion: Int = 1,
    @SerializedName("event_id")
    val eventId: String = UUID.randomUUID().toString(),
    val source: String = "onmom-android",
    @SerializedName("source_family")
    val sourceFamily: String,
    val domain: String,
    @SerializedName("event_type")
    val eventType: String,
    @SerializedName("occurred_at")
    val occurredAt: Long,
    @SerializedName("expires_at")
    val expiresAt: Long? = null,
    @SerializedName("contains_raw_data")
    val containsRawData: Boolean = false,
    val metrics: Map<String, Any>,
    val quality: AnalysisEventQuality,
)

private data class AnalysisEventQuality(
    val status: String = "VALID",
    val confidence: Double,
    val coverage: Double = 1.0,
    val reasons: List<String> = emptyList(),
)

fun inferJetsonTopicDomains(text: String): List<String> {
    val normalized = text.lowercase()
    val topics = linkedSetOf<String>()
    JetsonAdaptiveClientPolicy.TOPIC_KEYWORDS.forEach { (domain, keywords) ->
        if (keywords.any(normalized::contains)) topics += domain
    }
    if (topics.isEmpty()) {
        topics += JetsonAdaptiveClientPolicy.DEFAULT_TOPIC_DOMAIN
    }
    return topics.take(JetsonAdaptiveClientPolicy.MAX_TOPIC_DOMAINS)
}

/**
 * Produces a deliberately low-confidence emotional coordinate on-device.
 *
 * The original text never leaves this function. This is only a fallback for
 * typed turns when no usable voice-emotion result exists, and it is kept less
 * confident than a good acoustic observation.
 */
fun inferJetsonTextEmotionState(
    text: String,
    observedAt: Long = System.currentTimeMillis(),
): JetsonEmotionState? {
    val normalized = text.lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.isBlank()) return null

    fun isNegated(start: Int, endExclusive: Int): Boolean {
        val prefix = normalized.substring(maxOf(0, start - 4), start)
        val suffix = normalized.substring(endExclusive, minOf(normalized.length, endExclusive + 7))
        return prefix.endsWith("안 ") ||
            prefix.endsWith("못 ") ||
            suffix.contains("않") ||
            suffix.contains("아니") ||
            suffix.contains("없어") ||
            suffix.contains("없다")
    }

    fun cueCount(keywords: List<String>): Int = keywords.sumOf { keyword ->
        Regex(Regex.escape(keyword)).findAll(normalized).count { match ->
            !isNegated(match.range.first, match.range.last + 1)
        }
    }

    val positive = cueCount(JetsonAdaptiveClientPolicy.POSITIVE_CUES)
    val calm = cueCount(JetsonAdaptiveClientPolicy.CALM_CUES)
    val negativeLow = cueCount(JetsonAdaptiveClientPolicy.NEGATIVE_LOW_CUES)
    val negativeHigh = cueCount(JetsonAdaptiveClientPolicy.NEGATIVE_HIGH_CUES)
    val cueTotal = positive + calm + negativeLow + negativeHigh
    if (cueTotal == 0) return null

    val intensifiers = JetsonAdaptiveClientPolicy.INTENSIFIERS
        .count(normalized::contains)
    val denominator = cueTotal.toDouble()
    val valence = (
        positive * JetsonAdaptiveClientPolicy.TEXT_POSITIVE_VALENCE_WEIGHT +
            calm * JetsonAdaptiveClientPolicy.TEXT_CALM_VALENCE_WEIGHT -
            negativeLow * JetsonAdaptiveClientPolicy.TEXT_NEGATIVE_LOW_VALENCE_WEIGHT -
            negativeHigh * JetsonAdaptiveClientPolicy.TEXT_NEGATIVE_HIGH_VALENCE_WEIGHT
        ) / denominator
    val arousal = (
        positive * JetsonAdaptiveClientPolicy.TEXT_POSITIVE_AROUSAL_WEIGHT +
            calm * JetsonAdaptiveClientPolicy.TEXT_CALM_AROUSAL_WEIGHT +
            negativeLow * JetsonAdaptiveClientPolicy.TEXT_NEGATIVE_LOW_AROUSAL_WEIGHT +
            negativeHigh * JetsonAdaptiveClientPolicy.TEXT_NEGATIVE_HIGH_AROUSAL_WEIGHT
        ) / denominator + minOf(
        JetsonAdaptiveClientPolicy.TEXT_INTENSIFIER_AROUSAL_MAX,
        intensifiers * JetsonAdaptiveClientPolicy.TEXT_INTENSIFIER_AROUSAL_STEP,
    )
    val confidence = minOf(
        JetsonAdaptiveClientPolicy.TEXT_CONFIDENCE_MAX,
        JetsonAdaptiveClientPolicy.TEXT_CONFIDENCE_BASE +
            cueTotal * JetsonAdaptiveClientPolicy.TEXT_CONFIDENCE_PER_CUE +
            minOf(
                JetsonAdaptiveClientPolicy.TEXT_INTENSIFIER_CONFIDENCE_MAX,
                intensifiers * JetsonAdaptiveClientPolicy.TEXT_INTENSIFIER_CONFIDENCE_STEP,
            ),
    )
    return JetsonEmotionState(
        valence = valence.coerceIn(-1.0, 1.0),
        arousal = arousal.coerceIn(0.0, 1.0),
        confidence = confidence.coerceIn(0.0, 1.0),
        observedAt = observedAt,
    )
}

fun VoiceEmotionResult.toJetsonEmotionState(
    observedAt: Long = System.currentTimeMillis(),
): JetsonEmotionState {
    val coordinates = JetsonAdaptiveClientPolicy.VOICE_EMOTION_COORDINATES
    val valence = probabilities.entries.sumOf { (emotion, probability) ->
        coordinates.getValue(emotion).first * probability
    }.coerceIn(-1.0, 1.0)
    val arousal = probabilities.entries.sumOf { (emotion, probability) ->
        coordinates.getValue(emotion).second * probability
    }.coerceIn(0.0, 1.0)
    val sortedProbabilities = probabilities.values.sortedDescending()
    val margin = if (sortedProbabilities.size >= 2) {
        (sortedProbabilities[0] - sortedProbabilities[1]).coerceIn(0f, 1f)
    } else {
        confidence.coerceIn(0f, 1f)
    }
    val derivedConfidence = (confidence * (0.5f + 0.5f * margin)).coerceIn(0f, 1f)
    return JetsonEmotionState(
        valence = valence,
        arousal = arousal,
        confidence = derivedConfidence.toDouble(),
        observedAt = observedAt,
    )
}

fun List<ChatMessage>.withJetsonAdaptiveContext(
    adaptiveContext: String?,
): List<ChatMessage> {
    val normalized = adaptiveContext
        ?.trim()
        ?.take(JetsonAdaptiveClientPolicy.MAX_ADAPTIVE_PROMPT_CHARS)
    if (normalized.isNullOrBlank()) return this
    val lastUserIndex = indexOfLast { it.role == ChatRole.User }
    if (lastUserIndex < 0) return this
    return mapIndexed { index, message ->
        if (index != lastUserIndex) {
            message
        } else {
            message.copy(
                content = """
                    [Jetson 누적 분석 및 적응 정책]
                    아래 내용은 원본 센서·대화 기록이 아니라 Jetson이 품질, 최근성, 개인 기준선을 적용해 선택한 파생 맥락입니다.
                    사용자의 현재 말과 다르면 현재 말을 우선하고, 감정을 확정하거나 진단하지 마세요.

                    $normalized

                    [현재 사용자 메시지]
                    ${message.content}
                """.trimIndent(),
            )
        }
    }
}


internal fun parseJetsonResponseStyle(
    styleObject: com.google.gson.JsonObject?,
): JetsonResponseStyle? {
    if (styleObject == null) return null
    fun numberMap(name: String): Map<String, Double> {
        return styleObject.getAsJsonObject(name)
            ?.entrySet()
            ?.mapNotNull { (key, value) ->
                key.takeIf(ALLOWED_JETSON_STYLE_DIMENSIONS::contains)
                    ?.let { it to value.asDouble.coerceIn(0.0, 1.0) }
            }
            ?.toMap()
            .orEmpty()
    }
    val values = numberMap("values")
    val baselines = numberMap("baseline_values")
    val explored = styleObject.getAsJsonArray("explored_dimensions")
        ?.mapNotNull { item ->
            item.asString.takeIf(ALLOWED_JETSON_STYLE_DIMENSIONS::contains)
        }
        ?.distinct()
        .orEmpty()
    if (
        values.keys != ALLOWED_JETSON_STYLE_DIMENSIONS ||
        baselines.keys != ALLOWED_JETSON_STYLE_DIMENSIONS ||
        explored.size > 2
    ) {
        return null
    }
    return JetsonResponseStyle(
        values = values,
        baselineValues = baselines,
        exploredDimensions = explored,
    )
}

internal fun parseJetsonLearningStatus(response: String): JetsonLearningStatusSummary {
    val root = JsonParser.parseString(response).asJsonObject
    val versions = root.getAsJsonArray("policy_versions")
    val latestVersion = versions
        ?.firstOrNull()
        ?.asJsonObject
        ?.get("version")
        ?.asInt
        ?: 0
    val weightRange = root.getAsJsonObject("policy_weight_range")
    val feedbackCounts = root.getAsJsonObject("implicit_feedback_counts")
    val stylePolicy = root.getAsJsonObject("response_style_policy")
    return JetsonLearningStatusSummary(
        learningState = root.get("learning_state")?.asString ?: "UNKNOWN",
        sessionOutcomes = root.get("session_outcomes")?.asInt ?: 0,
        trainedOutcomes = root.get("trained_outcomes")?.asInt ?: 0,
        pendingOutcomes = root.get("pending_outcomes")?.asInt ?: 0,
        latestPolicyVersion = latestVersion,
        minimumWeight = weightRange
            ?.get("minimum")
            ?.asDouble
            ?: 1.0,
        maximumWeight = weightRange
            ?.get("maximum")
            ?.asDouble
            ?: 1.0,
        implicitLikes = feedbackCounts?.get("LIKE")?.asInt ?: 0,
        implicitNeutral = feedbackCounts?.get("NEUTRAL")?.asInt ?: 0,
        implicitDislikes = feedbackCounts?.get("DISLIKE")?.asInt ?: 0,
        styleDimensions = stylePolicy?.get("dimensions")?.asInt ?: 0,
        styleObservations = stylePolicy?.get("observations")?.asInt ?: 0,
        changedStyleDimensions = (
            stylePolicy?.get("changed_dimensions")?.asInt ?: 0
        ),
    )
}

class JetsonAdaptiveContextClient {
    suspend fun fetchLearningStatus(
        context: Context,
        rawEndpoint: String,
        token: String,
    ): JetsonLearningStatusSummary = withContext(Dispatchers.IO) {
        val endpoint = buildEndpoint(
            context = context,
            rawEndpoint = rawEndpoint,
            path = "/v1/learning-status",
            query = null,
        )
        parseJetsonLearningStatus(
            request(
                endpoint = endpoint,
                method = "GET",
                token = token,
                schema = null,
                body = null,
                connectTimeoutMillis = 1_500,
                readTimeoutMillis = 2_500,
            ),
        )
    }

    suspend fun fetch(
        context: Context,
        rawEndpoint: String,
        token: String,
        topicDomains: List<String>,
        currentEmotion: JetsonEmotionState? = null,
    ): JetsonAdaptiveContextResult = withContext(Dispatchers.IO) {
        val endpoint = buildEndpoint(
            context = context,
            rawEndpoint = rawEndpoint,
            path = "/v1/context",
            query = buildString {
                append("max_cards=${JetsonAdaptiveClientPolicy.REQUESTED_CONTEXT_CARDS}")
                if (topicDomains.isNotEmpty()) {
                    append("&topic=")
                    append(
                        URLEncoder.encode(
                            topicDomains.joinToString(","),
                            StandardCharsets.UTF_8.name(),
                        ),
                    )
                }
                currentEmotion?.let { emotion ->
                    append("&valence=")
                    append(emotion.valence.coerceIn(-1.0, 1.0))
                    append("&arousal=")
                    append(emotion.arousal.coerceIn(0.0, 1.0))
                }
            },
        )
        val response = request(
            endpoint = endpoint,
            method = "GET",
            token = token,
            schema = null,
            body = null,
            connectTimeoutMillis = 1_500,
            readTimeoutMillis = 2_500,
        )
        val root = JsonParser.parseString(response).asJsonObject
        val promptContext = root.get("prompt_context")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            ?.take(JetsonAdaptiveClientPolicy.MAX_ADAPTIVE_PROMPT_CHARS)
            ?.takeIf { it.isNotBlank() }
        val emotionBucket = root.getAsJsonObject("emotion_state")
            ?.get("bucket")
            ?.takeUnless { it.isJsonNull }
            ?.asString
        val strategies = root.getAsJsonArray("recommended_strategies")
            ?.mapNotNull { item ->
                item.asJsonObject.get("strategy")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
            }
            .orEmpty()
        val responseStyle = parseJetsonResponseStyle(
            root.getAsJsonObject("response_style"),
        )
        JetsonAdaptiveContextResult(
            promptContext = promptContext,
            emotionBucket = emotionBucket,
            recommendedStrategies = strategies,
            responseStyle = responseStyle,
        )
    }

    suspend fun sendOutcome(
        context: Context,
        rawEndpoint: String,
        token: String,
        pending: PendingJetsonOutcome,
        after: JetsonEmotionState,
        explicitFeedback: Double? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = buildEndpoint(
            context = context,
            rawEndpoint = rawEndpoint,
            path = "/v1/session-outcomes",
            query = null,
        )
        val envelope = SessionOutcomeEnvelope(
            sessionId = pending.outcomeId,
            startedAt = pending.startedAt,
            endedAt = after.observedAt.coerceAtLeast(pending.startedAt),
            before = EmotionPoint(
                valence = pending.before.valence,
                arousal = pending.before.arousal,
                confidence = pending.before.confidence,
            ),
            after = EmotionPoint(
                valence = after.valence,
                arousal = after.arousal,
                confidence = after.confidence,
            ),
            strategies = pending.strategies
                .filter(ALLOWED_JETSON_STRATEGIES::contains)
                .distinct()
                .take(4)
                .ifEmpty { listOf("EMPATHIC_REFLECTION") },
            responseStyle = pending.responseStyle?.takeIf { style ->
                style.values.keys == ALLOWED_JETSON_STYLE_DIMENSIONS &&
                    style.baselineValues.keys == ALLOWED_JETSON_STYLE_DIMENSIONS &&
                    style.exploredDimensions.all(
                        ALLOWED_JETSON_STYLE_DIMENSIONS::contains,
                    )
            },
            explicitFeedback = explicitFeedback?.coerceIn(-1.0, 1.0),
        )
        request(
            endpoint = endpoint,
            method = "POST",
            token = token,
            schema = "session-outcome-v1",
            body = Gson().toJson(envelope).toByteArray(Charsets.UTF_8),
            connectTimeoutMillis = 1_500,
            readTimeoutMillis = 2_500,
        )
        true
    }

    suspend fun sendConversationTurn(
        context: Context,
        rawEndpoint: String,
        token: String,
        topicDomains: List<String>,
        currentEmotion: JetsonEmotionState?,
        emotionModality: String?,
        strategies: List<String>,
        hasAudio: Boolean,
        userMessageLength: Int,
        assistantMessageLength: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = buildEndpoint(
            context = context,
            rawEndpoint = rawEndpoint,
            path = "/v1/analysis-events",
            query = null,
        )
        val metrics = linkedMapOf<String, Any>(
            "topic_domains" to topicDomains.distinct().take(4),
            "has_audio" to hasAudio,
            "user_length_band" to lengthBand(userMessageLength),
            "assistant_length_band" to lengthBand(assistantMessageLength),
            "response_strategies" to strategies
                .filter(ALLOWED_JETSON_STRATEGIES::contains)
                .distinct()
                .take(4),
        )
        currentEmotion?.let { emotion ->
            metrics["emotion_valence"] = emotion.valence.coerceIn(-1.0, 1.0)
            metrics["emotion_arousal"] = emotion.arousal.coerceIn(0.0, 1.0)
            metrics["emotion_confidence"] = emotion.confidence.coerceIn(0.0, 1.0)
            emotionModality
                ?.takeIf { it in setOf("VOICE", "TEXT") }
                ?.let { metrics["emotion_modality"] = it }
        }
        val event = AnalysisEventEnvelope(
            sourceFamily = "counseling.turn",
            domain = "CONVERSATION",
            eventType = "conversation_turn",
            occurredAt = System.currentTimeMillis(),
            metrics = metrics,
            quality = AnalysisEventQuality(
                confidence = currentEmotion?.confidence?.coerceIn(0.0, 1.0) ?: 1.0,
            ),
        )
        request(
            endpoint = endpoint,
            method = "POST",
            token = token,
            schema = "analysis-event-v1",
            body = Gson().toJson(event).toByteArray(Charsets.UTF_8),
            connectTimeoutMillis = 1_500,
            readTimeoutMillis = 2_500,
        )
        true
    }

    private fun lengthBand(length: Int): String = when {
        length <= 0 -> "EMPTY"
        length <= JetsonAdaptiveClientPolicy.SHORT_MESSAGE_MAX_CHARS -> "SHORT"
        length <= JetsonAdaptiveClientPolicy.MEDIUM_MESSAGE_MAX_CHARS -> "MEDIUM"
        else -> "LONG"
    }

    private fun buildEndpoint(
        context: Context,
        rawEndpoint: String,
        path: String,
        query: String?,
    ): URL {
        val isDebuggable =
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val normalized = normalizeJetsonEndpoint(
            rawEndpoint,
            allowPrivateLanHttp = isDebuggable,
        )
        return URI(
            normalized.protocol,
            null,
            normalized.host,
            normalized.port,
            path,
            query,
            null,
        ).toURL()
    }

    private fun request(
        endpoint: URL,
        method: String,
        token: String,
        schema: String?,
        body: ByteArray?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): String {
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            token.trim().takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
            schema?.let { setRequestProperty("X-OnMom-Schema", it) }
            body?.let {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(it.size)
            }
        }
        return try {
            body?.let { payload ->
                connection.outputStream.use { stream -> stream.write(payload) }
            }
            val statusCode = connection.responseCode
            val response = readLimited(
                if (statusCode in 200..299) connection.inputStream else connection.errorStream,
            )
            if (statusCode !in 200..299) {
                throw IOException(
                    "Jetson adaptive context HTTP $statusCode" +
                        response.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
                )
            }
            response
        } catch (error: java.net.UnknownHostException) {
            throw IOException("Jetson 호스트 이름을 해석하지 못했습니다.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream?): String {
        if (input == null) return ""
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1_024)
            while (output.size() < JetsonAdaptiveClientPolicy.MAX_CONTEXT_RESPONSE_BYTES) {
                val read = stream.read(
                    buffer,
                    0,
                    minOf(
                        buffer.size,
                        JetsonAdaptiveClientPolicy.MAX_CONTEXT_RESPONSE_BYTES - output.size(),
                    ),
                )
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name()).trim()
        }
    }

    private companion object {
        val ALLOWED_JETSON_STRATEGIES = setOf(
            "EMPATHIC_REFLECTION",
            "OPEN_QUESTION",
            "GROUNDING",
            "POSITIVE_REINFORCEMENT",
            "MICRO_ACTION",
            "QUIET_PRESENCE",
        )
    }
}

