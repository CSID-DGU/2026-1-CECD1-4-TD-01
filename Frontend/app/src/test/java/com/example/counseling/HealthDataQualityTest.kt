package com.example.counseling

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthDataQualityTest {
    private val date = LocalDate.of(2026, 7, 28)

    @Test
    fun zeroAndMissingValuesAreExcluded() {
        val day = sanitizeHealthDay(
            date,
            RawHealthDayMetrics(
                steps = 0,
                caloriesKcal = null,
                activeCaloriesKcal = 0.0,
                distanceKm = 0.0,
                heartRateBpm = 0,
                sleepMinutes = 0,
            ),
        )

        assertTrue(day.validMetrics.isEmpty())
        assertEquals(6, day.exclusions.size)
    }

    @Test
    fun positiveStepsWithZeroDistanceKeepsStepsButDropsDistance() {
        val day = sanitizeHealthDay(
            date,
            RawHealthDayMetrics(
                steps = 5_000,
                caloriesKcal = 1_800.0,
                activeCaloriesKcal = 280.0,
                distanceKm = 0.0,
                heartRateBpm = 72,
                sleepMinutes = 420,
            ),
        )

        assertTrue(day.hasValidMetric(HealthMetricKind.Steps))
        assertFalse(day.hasValidMetric(HealthMetricKind.Distance))
    }

    @Test
    fun implausiblyShortDistanceForStepsIsExcluded() {
        val day = sanitizeHealthDay(
            date,
            RawHealthDayMetrics(
                steps = 10_000,
                caloriesKcal = 2_000.0,
                activeCaloriesKcal = 400.0,
                distanceKm = 0.1,
                heartRateBpm = 68,
                sleepMinutes = 450,
            ),
        )

        assertTrue(day.hasValidMetric(HealthMetricKind.Steps))
        assertFalse(day.hasValidMetric(HealthMetricKind.Distance))
        assertTrue(day.exclusions.any { it.reason.contains("비율이 모순") })
    }

    @Test
    fun plausibleMetricsRemainInSummary() {
        val day = sanitizeHealthDay(
            date,
            RawHealthDayMetrics(
                steps = 6_000,
                caloriesKcal = 1_900.0,
                activeCaloriesKcal = 320.0,
                distanceKm = 4.2,
                heartRateBpm = 71,
                sleepMinutes = 450,
            ),
        )
        val summary = summarizeHealthDays(HealthPeriod.Week, listOf(day))

        assertEquals(6_000, summary.steps)
        assertEquals(4.2, summary.distanceKm, 0.001)
        assertEquals(7.5, summary.sleepHours, 0.001)
        assertEquals(0, summary.excludedMetricCount)
        assertNotNull(summary.toPromptContext())
    }

    @Test
    fun invalidDayDoesNotCreateAnAvailableAggregate() {
        val day = sanitizeHealthDay(
            date,
            RawHealthDayMetrics(
                steps = 0,
                caloriesKcal = 0.0,
                activeCaloriesKcal = 0.0,
                distanceKm = 0.0,
                heartRateBpm = 10,
                sleepMinutes = 2,
            ),
        )
        val summary = summarizeHealthDays(HealthPeriod.Week, listOf(day))

        assertFalse(summary.hasValidMetric(HealthMetricKind.Steps))
        assertFalse(summary.hasValidMetric(HealthMetricKind.Distance))
        assertFalse(summary.hasValidMetric(HealthMetricKind.Sleep))
        assertNull(summary.toPromptContext())
    }
}
