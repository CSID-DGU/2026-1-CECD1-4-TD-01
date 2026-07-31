package com.example.counseling

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JetsonGuardianIotTest {
    @Test
    fun buildsGuardianEndpointFromSavedDerivedEndpoint() {
        val url = jetsonApiUrl(
            "https://example.com:8765/v1/derived-insights",
            "/v1/guardian-alerts",
            allowPrivateLanHttp = false,
            query = "after=3&limit=10",
        )
        assertEquals(
            "https://example.com:8765/v1/guardian-alerts?after=3&limit=10",
            url.toString(),
        )
    }

    @Test
    fun parsesGuardianAlertCursor() {
        val batch = Gson().fromJson(
            """
            {
              "schema":"guardian-alert-list-v1",
              "count":1,
              "next_cursor":9,
              "alerts":[{
                "sequence":9,
                "alert_id":"fall-9",
                "occurred_at":2000000000000,
                "received_at":2000000000100,
                "severity":"HIGH",
                "category":"SAFETY",
                "source":"camera.fall",
                "title":"낙상 의심",
                "message":"확인이 필요합니다.",
                "acknowledged":false,
                "acknowledged_at":null
              }]
            }
            """.trimIndent(),
            GuardianAlertBatch::class.java,
        )
        assertEquals(9, batch.nextCursor)
        assertFalse(batch.alerts.single().acknowledged)
    }

    @Test
    fun notifiesOnlyFreshUnacknowledgedAlert() {
        val now = 2_000_000_000_000
        val fresh = GuardianAlert(
            sequence = 1,
            alertId = "fresh",
            occurredAt = now - 60_000,
            severity = "HIGH",
            category = "SAFETY",
            source = "test",
            title = "확인",
            message = "확인이 필요합니다.",
            acknowledged = false,
            acknowledgedAt = null,
        )
        assertEquals(true, shouldNotifyGuardianAlert(fresh, now))
        assertEquals(
            false,
            shouldNotifyGuardianAlert(
                fresh.copy(occurredAt = now - 25L * 60L * 60L * 1000L),
                now,
            ),
        )
        assertEquals(false, shouldNotifyGuardianAlert(fresh.copy(acknowledged = true), now))
    }

    @Test
    fun mapsOnlySupportedIotLabels() {
        assertEquals("켜기", iotActionLabel("TURN_ON"))
        assertEquals("UNKNOWN", iotActionLabel("UNKNOWN"))
    }
}
