package com.example.counseling

import android.content.Context
import com.example.counseling.galleryanalysis.GalleryAnalysisResult
import com.google.gson.GsonBuilder
import com.psychocare.data.AppUsageSummary
import com.psychocare.data.CallLogSummary
import com.psychocare.data.CalendarSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class RawDataPayload(
    val generated_at: Long,
    val producer: String = "onmom-android",
    val contains_raw_data: Boolean = true,
    val health_summary: HealthSummary? = null,
    val health_overview: ExtendedHealthOverview? = null,
    val call_summary: CallLogSummary? = null,
    val app_usage_summary: AppUsageSummary? = null,
    val gallery_result: GalleryAnalysisResult? = null,
    val calendar_summary: CalendarSummary? = null,
    val raw_exercise_entries: List<RawExerciseEntry>? = null
)

class JetsonRawDataSyncClient {
    suspend fun sendRawData(
        context: Context,
        healthSummary: HealthSummary?,
        healthOverview: ExtendedHealthOverview?,
        callSummary: CallLogSummary?,
        appUsageSummary: AppUsageSummary?,
        galleryResult: GalleryAnalysisResult?,
        calendarSummary: CalendarSummary?,
        rawExerciseEntries: List<RawExerciseEntry>?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val settings = JetsonSyncSettingsStore(context)
        val endpointUrl = settings.loadEndpoint()
        val token = settings.loadToken()
        
        if (endpointUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("젯슨 주소가 설정되지 않았습니다."))
        }

        val normalized = normalizeJetsonEndpoint(endpointUrl, allowPrivateLanHttp = true).toString()
        val targetUrl = normalized.replace("/v1/derived-insights", "/v1/raw-exports")
            .replace("/v1/analysis-snapshots", "/v1/raw-exports")

        val payload = RawDataPayload(
            generated_at = System.currentTimeMillis(),
            health_summary = healthSummary,
            health_overview = healthOverview,
            call_summary = callSummary,
            app_usage_summary = appUsageSummary,
            gallery_result = galleryResult,
            calendar_summary = calendarSummary,
            raw_exercise_entries = rawExerciseEntries
        )

        val json = GsonBuilder().serializeNulls().create().toJson(payload)

        try {
            val url = URL(targetUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-OnMom-Schema", "raw-export-v1")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            val bytes = json.toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Content-Length", bytes.size.toString())

            connection.outputStream.use { out ->
                out.write(bytes)
                out.flush()
            }

            val code = connection.responseCode
            if (code in 200..299) {
                Result.success(Unit)
            } else {
                val err = connection.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: "Unknown error"
                Result.failure(IOException("Server error $code: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
