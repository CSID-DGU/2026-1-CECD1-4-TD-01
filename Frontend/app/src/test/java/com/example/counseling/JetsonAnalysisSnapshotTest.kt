package com.example.counseling

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.psychocare.data.AppCategory
import com.psychocare.data.AppUsageEntry
import com.psychocare.data.AppUsageSummary
import com.psychocare.data.CallLogSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JetsonAnalysisSnapshotTest {
    @Test
    fun `health dataset contains valid daily values and exclusion reasons`() {
        val summary = HealthSummary(
            period = HealthPeriod.Week,
            steps = 7_200,
            distanceKm = 5.4,
            heartRateBpm = 71,
            daily = listOf(
                HealthDaySummary(
                    date = LocalDate.of(2026, 7, 29),
                    steps = 7_200,
                    distanceKm = 5.4,
                    heartRateBpm = 71,
                    validMetrics = setOf(
                        HealthMetricKind.Steps,
                        HealthMetricKind.Distance,
                        HealthMetricKind.HeartRate,
                    ),
                    exclusions = listOf(
                        HealthDataExclusion(
                            HealthMetricKind.Sleep,
                            "기록을 받지 못함",
                        ),
                    ),
                ),
            ),
            validMetrics = setOf(
                HealthMetricKind.Steps,
                HealthMetricKind.Distance,
                HealthMetricKind.HeartRate,
            ),
            excludedMetricCount = 1,
        )
        val overview = ExtendedHealthOverview(
            period = HealthPeriod.Week,
            snapshots = listOf(
                HealthDataSnapshot(
                    key = "exercise",
                    label = "운동 세션",
                    category = HealthDataCategory.Activity,
                    availability = HealthDataAvailability.Received,
                    recordCount = 2,
                    summary = "2회 · 총 65분",
                    sources = listOf("Samsung Health · 워치"),
                    analysisValues = mapOf(
                        "session_count" to 2.0,
                        "total_minutes" to 65.0,
                    ),
                ),
            ),
        )

        val dataset = buildHealthAnalysisDataset(summary, overview, 1234L)
        val daily = dataset.data.getAsJsonArray("daily_values")[0].asJsonObject
        val values = daily.getAsJsonObject("values")

        assertEquals("HEALTH", dataset.category)
        assertEquals(7_200L, values.get("steps").asLong)
        assertEquals(5.4, values.get("distance_km").asDouble, 0.0001)
        assertFalse(values.has("sleep_hours"))
        assertEquals(
            "기록을 받지 못함",
            daily.getAsJsonArray("excluded")[0]
                .asJsonObject.get("reason").asString,
        )
        val exercise = dataset.data
            .getAsJsonArray("extended_values")[0].asJsonObject
            .getAsJsonObject("values")
        assertEquals(2.0, exercise.get("session_count").asDouble, 0.0001)
        assertEquals(65.0, exercise.get("total_minutes").asDouble, 0.0001)
    }

    @Test
    fun `phenotype sends aggregate values and app name but not package name`() {
        val calls = CallLogSummary(
            totalCallsThisWeek = 4,
            totalCallsPrevWeek = 7,
            uniqueContactsThisWeek = 3,
            avgDurationSec = 92f,
            missedCallCount = 1,
            missedCallRate = 0.25f,
            zeroCommunicationStreak = 1,
            weeklyChangePct = -42.8f,
            isolationAlert = false,
            namedContactsRate = 0.75f,
        )
        val usage = AppUsageSummary(
            topApps = listOf(
                AppUsageEntry(
                    packageName = "com.private.should.not.leave",
                    appName = "브라우저",
                    totalTimeMin = 81,
                    category = AppCategory.OTHER,
                ),
            ),
            dailyAvgScreenTimeMin = 140,
            dailyAvgLateNightMin = 18,
            longestSingleSessionMin = 44,
            weeklyChangePct = 3.5f,
            hasPermission = true,
        )

        val dataset = buildPhenotypeAnalysisDataset(calls, usage, 1234L)
        val json = Gson().toJson(dataset)

        assertTrue(json.contains("\"app_name\":\"브라우저\""))
        assertTrue(json.contains("\"total_calls_this_week\":4"))
        assertFalse(json.contains("com.private.should.not.leave"))
        assertFalse(json.contains("package_name"))
    }

    @Test
    fun `gallery fallback sends counts and scores without image identifiers`() {
        val snapshot = GalleryAnalysisCacheSnapshot(
            fingerprint = "local-only-fingerprint",
            imageCount = 20,
            updatedAt = 1234L,
            contextText = "상담용 요약",
            interestItems = listOf(
                GalleryInterestItem(
                    label = "운동/활동",
                    photoCount = 6,
                    eventCount = 3,
                    ratio = 0.3,
                    confidence = 0.8,
                    level = "보통",
                ),
            ),
        )

        val dataset = buildGalleryAnalysisDataset(snapshot)
        val json = Gson().toJson(dataset)

        assertTrue(json.contains("\"image_count\":20"))
        assertTrue(json.contains("\"photo_count\":6"))
        assertFalse(json.contains("fingerprint"))
        assertFalse(json.contains("contextText"))
    }

    @Test
    fun `serializer rejects a raw uri field before network transfer`() {
        val data = JsonObject().apply {
            addProperty("uri", "content://media/external/images/1")
        }
        val envelope = AnalysisSnapshotEnvelope(
            snapshotId = "snapshot-test",
            generatedAt = 1234L,
            datasets = listOf(
                AnalysisSnapshotDataset(
                    category = "GALLERY",
                    collectedAt = 1234L,
                    data = data,
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            serializeAnalysisSnapshot(envelope)
        }
    }
}
