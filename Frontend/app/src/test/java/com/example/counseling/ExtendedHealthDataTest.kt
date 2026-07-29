package com.example.counseling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedHealthDataTest {
    @Test
    fun promptContextIncludesOnlyReceivedDerivedSummaries() {
        val overview = ExtendedHealthOverview(
            period = HealthPeriod.Week,
            snapshots = listOf(
                HealthDataSnapshot(
                    key = "oxygen",
                    label = "산소포화도",
                    category = HealthDataCategory.Vitals,
                    availability = HealthDataAvailability.Received,
                    recordCount = 5,
                    summary = "평균 97.2 % · 범위 95.0~99.0 %",
                    latest = "07.28 08:10",
                    sources = listOf("Samsung Health · 워치 · Galaxy Watch"),
                ),
                HealthDataSnapshot(
                    key = "blood_pressure",
                    label = "혈압",
                    category = HealthDataCategory.Vitals,
                    availability = HealthDataAvailability.PermissionRequired,
                    summary = "Health Connect 읽기 권한이 필요합니다.",
                ),
            ),
        )

        val prompt = requireNotNull(overview.toPromptContext())

        assertTrue(prompt.contains("산소포화도"))
        assertTrue(prompt.contains("평균 97.2"))
        assertFalse(prompt.contains("혈압"))
        assertFalse(prompt.contains("Galaxy Watch"))
        assertFalse(prompt.contains("Samsung Health"))
        assertFalse(prompt.contains("07.28 08:10"))
    }

    @Test
    fun promptContextIsNullWhenNoValidRecordExists() {
        val overview = ExtendedHealthOverview(
            period = HealthPeriod.Month,
            snapshots = listOf(
                HealthDataSnapshot(
                    key = "exercise",
                    label = "운동 세션",
                    category = HealthDataCategory.Activity,
                    availability = HealthDataAvailability.NoData,
                    summary = "기록 없음",
                ),
            ),
        )

        assertNull(overview.toPromptContext())
    }
}
