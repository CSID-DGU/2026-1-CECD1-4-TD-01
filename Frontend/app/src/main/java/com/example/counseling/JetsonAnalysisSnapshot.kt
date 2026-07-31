package com.example.counseling

import android.content.Context
import com.example.counseling.galleryanalysis.GalleryAnalysisResult
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.psychocare.data.AppUsageSummary
import com.psychocare.data.CallLogSummary
import com.psychocare.phenotype.AppUsageAnalyzer
import com.psychocare.phenotype.CallLogAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

private const val ANALYSIS_SNAPSHOT_PATH = "/v1/analysis-snapshots"
private const val MAX_ANALYSIS_SNAPSHOT_BYTES = 512 * 1024

data class AnalysisSnapshotEnvelope(
    @SerializedName("schema_version")
    val schemaVersion: Int = 1,
    @SerializedName("snapshot_id")
    val snapshotId: String,
    @SerializedName("generated_at")
    val generatedAt: Long,
    val producer: String = "onmom-android",
    @SerializedName("privacy_level")
    val privacyLevel: String = "STRUCTURED_FEATURES",
    @SerializedName("contains_raw_data")
    val containsRawData: Boolean = false,
    val datasets: List<AnalysisSnapshotDataset>,
)

data class AnalysisSnapshotDataset(
    val category: String,
    @SerializedName("collected_at")
    val collectedAt: Long,
    val data: JsonObject,
)

data class AnalysisSnapshotSyncResult(
    val statusCode: Int,
    val snapshotId: String,
    val categories: List<String>,
    val payloadBytes: Int,
)

internal fun buildHealthAnalysisDataset(
    summary: HealthSummary,
    overview: ExtendedHealthOverview,
    collectedAt: Long,
): AnalysisSnapshotDataset {
    val data = JsonObject().apply {
        addProperty("period", summary.period.name.uppercase())
        addProperty("excluded_metric_count", summary.excludedMetricCount)
        add(
            "valid_metrics",
            JsonArray().apply {
                summary.validMetrics.sortedBy { it.name }.forEach {
                    add(it.name.uppercase())
                }
            },
        )
        add(
            "period_values",
            JsonObject().apply {
                if (summary.hasValidMetric(HealthMetricKind.Steps)) {
                    addProperty("steps", summary.steps)
                }
                if (summary.hasValidMetric(HealthMetricKind.Distance)) {
                    addProperty("distance_km", summary.distanceKm)
                }
                if (summary.hasValidMetric(HealthMetricKind.Calories)) {
                    addProperty("total_calories_kcal", summary.caloriesKcal)
                }
                if (summary.hasValidMetric(HealthMetricKind.ActiveCalories)) {
                    addProperty("active_calories_kcal", summary.activeCaloriesKcal)
                }
                if (summary.hasValidMetric(HealthMetricKind.HeartRate)) {
                    summary.heartRateBpm?.let { addProperty("mean_heart_rate_bpm", it) }
                }
                if (summary.hasValidMetric(HealthMetricKind.Sleep)) {
                    addProperty("sleep_hours", summary.sleepHours)
                }
            },
        )
        add(
            "daily_values",
            JsonArray().apply {
                summary.daily.forEach { day ->
                    add(
                        JsonObject().apply {
                            addProperty("date", day.date.toString())
                            add(
                                "values",
                                JsonObject().apply {
                                    if (day.hasValidMetric(HealthMetricKind.Steps)) {
                                        addProperty("steps", day.steps)
                                    }
                                    if (day.hasValidMetric(HealthMetricKind.Distance)) {
                                        addProperty("distance_km", day.distanceKm)
                                    }
                                    if (day.hasValidMetric(HealthMetricKind.Calories)) {
                                        addProperty("total_calories_kcal", day.caloriesKcal)
                                    }
                                    if (day.hasValidMetric(HealthMetricKind.ActiveCalories)) {
                                        addProperty("active_calories_kcal", day.activeCaloriesKcal)
                                    }
                                    if (day.hasValidMetric(HealthMetricKind.HeartRate)) {
                                        day.heartRateBpm?.let {
                                            addProperty("mean_heart_rate_bpm", it)
                                        }
                                    }
                                    if (day.hasValidMetric(HealthMetricKind.Sleep)) {
                                        addProperty("sleep_hours", day.sleepHours)
                                    }
                                },
                            )
                            add(
                                "excluded",
                                JsonArray().apply {
                                    day.exclusions.forEach { exclusion ->
                                        add(
                                            JsonObject().apply {
                                                addProperty(
                                                    "metric",
                                                    exclusion.metric.name.uppercase(),
                                                )
                                                addProperty("reason", exclusion.reason)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        add(
            "extended_values",
            JsonArray().apply {
                overview.snapshots.forEach { snapshot ->
                    add(
                        JsonObject().apply {
                            addProperty("key", snapshot.key)
                            addProperty("label", snapshot.label)
                            addProperty("availability", snapshot.availability.name.uppercase())
                            addProperty("record_count", snapshot.recordCount)
                            addProperty("summary", snapshot.summary)
                            snapshot.latest?.let { addProperty("latest", it) }
                            add(
                                "values",
                                JsonObject().apply {
                                    snapshot.analysisValues.entries
                                        .sortedBy { it.key }
                                        .forEach { (key, value) ->
                                            addProperty(key, value)
                                        }
                                },
                            )
                            add(
                                "sources",
                                JsonArray().apply {
                                    snapshot.sources.forEach(::add)
                                },
                            )
                        },
                    )
                }
            },
        )
    }
    return AnalysisSnapshotDataset(
        category = "HEALTH",
        collectedAt = collectedAt,
        data = data,
    )
}

internal fun buildPhenotypeAnalysisDataset(
    callSummary: CallLogSummary?,
    appUsageSummary: AppUsageSummary,
    collectedAt: Long,
): AnalysisSnapshotDataset {
    val data = JsonObject()
    callSummary?.let { call ->
        data.add(
            "call_pattern",
            JsonObject().apply {
                addProperty("total_calls_this_week", call.totalCallsThisWeek)
                addProperty("total_calls_previous_week", call.totalCallsPrevWeek)
                addProperty("unique_contacts_this_week", call.uniqueContactsThisWeek)
                addProperty("average_duration_seconds", call.avgDurationSec)
                addProperty("missed_call_count", call.missedCallCount)
                addProperty("missed_call_rate", call.missedCallRate)
                addProperty("zero_communication_streak_days", call.zeroCommunicationStreak)
                addProperty("weekly_change_percent", call.weeklyChangePct)
                addProperty("isolation_alert", call.isolationAlert)
                addProperty("named_contacts_rate", call.namedContactsRate)
            },
        )
    }
    data.add(
        "app_usage_pattern",
        JsonObject().apply {
            addProperty("permission_granted", appUsageSummary.hasPermission)
            addProperty("daily_average_screen_minutes", appUsageSummary.dailyAvgScreenTimeMin)
            addProperty("daily_average_late_night_minutes", appUsageSummary.dailyAvgLateNightMin)
            addProperty("longest_session_minutes", appUsageSummary.longestSingleSessionMin)
            addProperty("weekly_change_percent", appUsageSummary.weeklyChangePct)
            add(
                "top_apps",
                JsonArray().apply {
                    appUsageSummary.topApps.take(20).forEach { app ->
                        add(
                            JsonObject().apply {
                                addProperty("app_name", app.appName.take(120))
                                addProperty("category", app.category.name)
                                addProperty("total_time_minutes", app.totalTimeMin)
                            },
                        )
                    }
                },
            )
        },
    )
    return AnalysisSnapshotDataset(
        category = "PHENOTYPE",
        collectedAt = collectedAt,
        data = data,
    )
}

internal fun buildGalleryStructuredAnalysisJson(
    result: GalleryAnalysisResult,
): String {
    val report = result.counselingReport
    val wellbeing = report.wellbeingReport
    val data = JsonObject().apply {
        add(
            "analysis_period",
            JsonObject().apply {
                addProperty("long_term_months", result.analysisPeriod.longTermMonths)
                addProperty("recent_days", result.analysisPeriod.recentDays)
                addProperty("baseline_days", result.analysisPeriod.baselineDays)
            },
        )
        add(
            "summary",
            JsonObject().apply {
                addProperty("total_images", result.summary.totalImages)
                addProperty("analyzable_images", result.summary.analyzableImages)
                addProperty("excluded_images", result.summary.excludedImages)
                addProperty("analyzed_images", result.summary.analyzedImages)
                addProperty("event_count", result.summary.eventCount)
                addProperty("elapsed_milliseconds", result.summary.elapsedMs)
            },
        )
        add(
            "quality",
            JsonObject().apply {
                addProperty("mlkit_success_rate", result.qualityReport.mlKitSuccessRate)
                addProperty(
                    "classification_coverage",
                    result.qualityReport.classificationCoverage,
                )
                addProperty("unknown_ratio", result.qualityReport.unknownRatio)
                addProperty(
                    "low_confidence_ratio",
                    result.qualityReport.lowConfidenceRatio,
                )
                addProperty(
                    "average_confidence",
                    result.qualityReport.averageConfidence,
                )
                addProperty(
                    "event_reduction_ratio",
                    result.qualityReport.eventReductionRatio,
                )
                addProperty("images_per_second", result.qualityReport.imagesPerSecond)
                add(
                    "excluded_counts",
                    JsonArray().apply {
                        result.qualityReport.excludedByReason.entries
                            .sortedBy { it.key.name }
                            .forEach { (reason, count) ->
                                add(
                                    JsonObject().apply {
                                        addProperty("reason", reason.name)
                                        addProperty("count", count)
                                    },
                                )
                            }
                    },
                )
            },
        )
        add(
            "preferences",
            JsonArray().apply {
                result.preferences.forEach { preference ->
                    add(
                        JsonObject().apply {
                            addProperty("category", preference.category.name)
                            addProperty("event_count", preference.eventCount)
                            addProperty("photo_count", preference.photoCount)
                            addProperty("ratio", preference.ratio)
                            addProperty("confidence", preference.confidence)
                            addProperty("recurrence_score", preference.recurrenceScore)
                            addProperty("active_week_count", preference.activeWeekCount)
                            addProperty("active_month_count", preference.activeMonthCount)
                            addProperty(
                                "observation_span_days",
                                preference.observationSpanDays,
                            )
                            addProperty(
                                "average_photos_per_event",
                                preference.averagePhotosPerEvent,
                            )
                        },
                    )
                }
            },
        )
        add(
            "period_changes",
            JsonArray().apply {
                result.periodChanges.forEach { change ->
                    add(
                        JsonObject().apply {
                            addProperty("category", change.category.name)
                            addProperty("recent_count", change.recentCount)
                            addProperty("baseline_count", change.baselineCount)
                            addProperty("recent_ratio", change.recentRatio)
                            addProperty("baseline_ratio", change.baselineRatio)
                            addProperty(
                                "delta_percent_point",
                                change.deltaPercentPoint,
                            )
                            addProperty("direction", change.direction.take(80))
                        },
                    )
                }
            },
        )
        add(
            "night_change",
            JsonObject().apply {
                addProperty("recent_count", result.nightChange.recentCount)
                addProperty("baseline_count", result.nightChange.baselineCount)
                addProperty("recent_ratio", result.nightChange.recentRatio)
                addProperty("baseline_ratio", result.nightChange.baselineRatio)
                addProperty("delta_percent_point", result.nightChange.deltaPercentPoint)
            },
        )
        add(
            "time_distribution",
            JsonArray().apply {
                result.timeDistribution.forEach { bucket ->
                    add(
                        JsonObject().apply {
                            addProperty("day_of_week", bucket.dayOfWeek.take(40))
                            addProperty("time_bucket", bucket.bucket.take(40))
                            addProperty("count", bucket.count)
                            addProperty("ratio", bucket.ratio)
                        },
                    )
                }
            },
        )
        add(
            "monthly_trends",
            JsonArray().apply {
                result.monthlyTrends.forEach { trend ->
                    add(
                        JsonObject().apply {
                            addProperty("month", trend.month.take(20))
                            addProperty("total_events", trend.totalEvents)
                            trend.topCategory?.let {
                                addProperty("top_category", it.name)
                            }
                            add(
                                "category_counts",
                                JsonArray().apply {
                                    trend.categoryCounts.entries
                                        .sortedBy { it.key.name }
                                        .forEach { (category, count) ->
                                            add(
                                                JsonObject().apply {
                                                    addProperty("category", category.name)
                                                    addProperty("count", count)
                                                },
                                            )
                                        }
                                },
                            )
                        },
                    )
                }
            },
        )
        add(
            "signals",
            JsonArray().apply {
                result.signals.forEach { signal ->
                    add(
                        JsonObject().apply {
                            addProperty("signal_type", signal.signalType.name)
                            addProperty("strength", signal.strength.name)
                            addProperty("confidence", signal.confidence)
                        },
                    )
                }
            },
        )
        add(
            "behavior_scores",
            JsonObject().apply {
                addProperty(
                    "activity_activation",
                    report.behaviorScores.activityActivationScore,
                )
                addProperty(
                    "life_rhythm_instability",
                    report.behaviorScores.lifeRhythmInstabilityScore,
                )
                addProperty(
                    "social_withdrawal_signal",
                    report.behaviorScores.socialWithdrawalSignalScore,
                )
                addProperty(
                    "academic_load_signal",
                    report.behaviorScores.academicLoadSignalScore,
                )
                addProperty(
                    "interest_continuity_drop",
                    report.behaviorScores.interestContinuityDropScore,
                )
                addProperty(
                    "meal_pattern_change",
                    report.behaviorScores.mealPatternChangeScore,
                )
                addProperty("visual_risk_cue", report.behaviorScores.visualRiskCueScore)
                addProperty(
                    "overall_counseling_priority",
                    report.behaviorScores.overallCounselingPriorityScore,
                )
            },
        )
        add(
            "safety",
            JsonObject().apply {
                addProperty("level", report.safetyAssessment.level.name)
                addProperty("action", report.safetyAssessment.action.name)
                addProperty(
                    "rule_based_response",
                    report.safetyAssessment.mustUseRuleBasedResponse,
                )
            },
        )
        wellbeing?.let { wellbeingReport ->
            addProperty("conversation_phase", wellbeingReport.conversationPhase.name)
            add(
                "wellbeing_domains",
                JsonArray().apply {
                    wellbeingReport.domainScores.forEach { domain ->
                        add(
                            JsonObject().apply {
                                addProperty("domain", domain.domain.name)
                                addProperty("level", domain.level.name)
                                addProperty("score", domain.score)
                                addProperty("confidence", domain.confidence)
                                addProperty("recent_events", domain.recentEvents)
                                addProperty("baseline_events", domain.baselineEvents)
                                addProperty("recent_ratio", domain.recentRatio)
                                addProperty("baseline_ratio", domain.baselineRatio)
                                addProperty(
                                    "delta_percent_point",
                                    domain.deltaPercentPoint,
                                )
                            },
                        )
                    }
                },
            )
            add(
                "protective_resources",
                JsonArray().apply {
                    wellbeingReport.protectiveResources.forEach { resource ->
                        add(
                            JsonObject().apply {
                                addProperty("category", resource.sourceCategory.name)
                                addProperty("recurrence_score", resource.recurrenceScore)
                                addProperty("active_week_count", resource.activeWeekCount)
                                addProperty("active_month_count", resource.activeMonthCount)
                                addProperty(
                                    "observation_span_days",
                                    resource.observationSpanDays,
                                )
                                addProperty("status", resource.status.take(80))
                            },
                        )
                    }
                },
            )
        }
    }
    return Gson().toJson(data)
}

internal fun buildGalleryAnalysisDataset(
    snapshot: GalleryAnalysisCacheSnapshot,
): AnalysisSnapshotDataset {
    val structured = snapshot.structuredAnalysisJson
        ?.takeIf { it.isNotBlank() }
        ?.let { JsonParser.parseString(it).asJsonObject }
        ?: JsonObject().apply {
            addProperty("image_count", snapshot.imageCount)
            add(
                "interest_items",
                JsonArray().apply {
                    snapshot.interestItems.forEach { item ->
                        add(
                            JsonObject().apply {
                                addProperty("label", item.label)
                                addProperty("photo_count", item.photoCount)
                                addProperty("event_count", item.eventCount)
                                addProperty("ratio", item.ratio)
                                addProperty("confidence", item.confidence)
                                addProperty("level", item.level)
                            },
                        )
                    }
                },
            )
        }
    return AnalysisSnapshotDataset(
        category = "GALLERY",
        collectedAt = snapshot.updatedAt,
        data = structured,
    )
}

suspend fun collectLatestAnalysisSnapshot(
    context: Context,
    now: Long = System.currentTimeMillis(),
): AnalysisSnapshotEnvelope {
    val datasets = mutableListOf<AnalysisSnapshotDataset>()

    runCatching {
        val health = readHealthSummary(context, HealthPeriod.Week)
        val extended = readExtendedHealthOverview(context, HealthPeriod.Week)
        buildHealthAnalysisDataset(health, extended, now)
    }.getOrNull()?.let(datasets::add)

    runCatching {
        val calls = CallLogAnalyzer(context.applicationContext).analyze()
        val usage = AppUsageAnalyzer(context.applicationContext).analyze()
        buildPhenotypeAnalysisDataset(calls, usage, now)
    }.getOrNull()?.let(datasets::add)

    readGalleryAnalysisCache(context)
        ?.let(::buildGalleryAnalysisDataset)
        ?.let(datasets::add)

    require(datasets.isNotEmpty()) {
        "전송할 구조화 분석 수치가 없습니다. 건강 또는 Gallery 분석을 먼저 실행해 주세요."
    }
    return AnalysisSnapshotEnvelope(
        snapshotId = UUID.randomUUID().toString(),
        generatedAt = now,
        datasets = datasets,
    )
}

internal fun serializeAnalysisSnapshot(
    envelope: AnalysisSnapshotEnvelope,
): String {
    require(envelope.containsRawData.not())
    require(envelope.privacyLevel == "STRUCTURED_FEATURES")
    val json = GsonBuilder().serializeNulls().create().toJson(envelope)
    val lowered = json.lowercase()
    val forbidden = listOf(
        "content://",
        "file://",
        "/storage/",
        "/sdcard/",
        "data:image",
        "data:audio",
        ";base64,",
        "\"package_name\"",
        "\"phone_number\"",
        "\"cached_name\"",
        "\"transcript\"",
        "\"conversation_text\"",
        "\"user_message\"",
        "\"uri\"",
        "\"image_bytes\"",
        "\"audio_bytes\"",
        "\"rfid_uid\"",
    ).firstOrNull(lowered::contains)
    require(forbidden == null) {
        "원본 또는 식별자 필드가 분석 스냅샷에 포함되어 전송을 중단했습니다: $forbidden"
    }
    return json
}

class JetsonAnalysisSnapshotClient {
    suspend fun sendLatest(
        context: Context,
        rawEndpoint: String,
        token: String,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 30_000,
    ): AnalysisSnapshotSyncResult = withContext(Dispatchers.IO) {
        val isDebuggable =
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val normalized = normalizeJetsonEndpoint(
            rawEndpoint,
            allowPrivateLanHttp = isDebuggable,
        )
        val endpoint = URI(normalized.toString())
            .resolve(ANALYSIS_SNAPSHOT_PATH)
            .toURL()
        val envelope = collectLatestAnalysisSnapshot(context)
        val body = serializeAnalysisSnapshot(envelope).toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_ANALYSIS_SNAPSHOT_BYTES) {
            "분석 스냅샷이 512KB를 초과해 전송을 중단했습니다."
        }

        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMillis.coerceIn(500, 30_000)
            readTimeout = readTimeoutMillis.coerceIn(500, 60_000)
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-OnMom-Schema", "analysis-snapshot-v1")
            token.trim().takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
            setFixedLengthStreamingMode(body.size)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val detail = readLimited(
                    connection.errorStream ?: connection.inputStream,
                    2_048,
                )
                throw IOException(
                    "Jetson이 HTTP $statusCode 응답을 보냈습니다." +
                        detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(),
                )
            }
            AnalysisSnapshotSyncResult(
                statusCode = statusCode,
                snapshotId = envelope.snapshotId,
                categories = envelope.datasets.map { it.category },
                payloadBytes = body.size,
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
                val read = stream.read(
                    buffer,
                    0,
                    minOf(buffer.size, maxBytes - output.size()),
                )
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name()).trim()
        }
    }
}
