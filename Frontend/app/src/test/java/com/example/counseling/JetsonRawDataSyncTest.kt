package com.example.counseling

import com.google.gson.Gson
import org.junit.Assert.assertTrue
import org.junit.Test

class JetsonRawDataSyncTest {
    @Test
    fun rawExportIncludesSleepSessionTimes() {
        val json = Gson().toJson(
            RawDataPayload(
                generated_at = 1L,
                raw_sleep_sessions = listOf(RawSleepSession(2L, 3L)),
            ),
        )

        assertTrue(json.contains("\"raw_sleep_sessions\""))
        assertTrue(json.contains("\"startTimeMs\":2"))
        assertTrue(json.contains("\"endTimeMs\":3"))
    }
}