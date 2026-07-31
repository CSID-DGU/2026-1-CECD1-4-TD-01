package com.example.counseling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JetsonDerivedInsightSyncTest {
    @Test
    fun envelopeContainsOnlyWhitelistedDerivedFields() {
        val document = RagSlotDocument(
            slot = RagSlot.Health,
            updatedAt = 1234L,
            contextText = "주간 걸음 평균 7,200보, 수면 평균 7.1시간",
            source = "content://must-not-leave-device/private-source",
        )

        val envelope = buildDerivedInsightEnvelope(
            ragDocuments = listOf(document),
            now = 2000L,
            transferId = "test-transfer",
        )
        val json = serializeDerivedInsightEnvelope(envelope)

        assertTrue(json.contains("주간 걸음 평균"))
        assertFalse(json.contains("content://"))
        assertFalse(json.contains("source"))
        assertFalse(json.contains("device_id"))
        assertTrue(json.contains("\"expires_at\":null"))
        assertEquals(false, envelope.containsRawData)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawContentUriIsRejected() {
        DerivedInsightPrivacyGuard.requireDerivedOnly("분석 결과 content://media/external/images/1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawUserMessageBlockIsRejected() {
        DerivedInsightPrivacyGuard.requireDerivedOnly("[현재 사용자 메시지]\n오늘 너무 힘들어")
    }

    @Test
    fun localHttpDebugPrivateIpAndPublicHttpsEndpointsAreAllowed() {
        assertEquals(
            "http://iot.local:8765/v1/derived-insights",
            normalizeJetsonEndpoint("http://iot.local:8765").toString(),
        )
        assertEquals(
            "http://192.168.0.22:8765/v1/derived-insights",
            normalizeJetsonEndpoint("http://192.168.0.22:8765", allowPrivateLanHttp = true).toString(),
        )
        assertEquals(
            "http://100.93.236.68:8765/v1/derived-insights",
            normalizeJetsonEndpoint("http://100.93.236.68:8765", allowPrivateLanHttp = true).toString(),
        )
        assertEquals(
            "https://jetson.example.com/v1/derived-insights",
            normalizeJetsonEndpoint("https://jetson.example.com").toString(),
        )
        assertEquals(
            "http://192.168.0.22:8765/v1/derived-insights",
            normalizeJetsonEndpoint("http://192.168.0.22:8765/v1/derived-insight", allowPrivateLanHttp = true).toString(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun publicCleartextEndpointIsRejected() {
        normalizeJetsonEndpoint("http://example.com/v1/derived-insights")
    }

    @Test(expected = IllegalArgumentException::class)
    fun releasePolicyStillRejectsPrivateIpCleartext() {
        normalizeJetsonEndpoint("http://192.168.0.22:8765")
    }

    @Test
    fun onlyPrivateIpv4RangesAreRecognizedForDebugCleartext() {
        assertTrue(isPrivateLanIpv4("10.0.0.4"))
        assertTrue(isPrivateLanIpv4("172.16.1.2"))
        assertTrue(isPrivateLanIpv4("172.31.255.254"))
        assertTrue(isPrivateLanIpv4("192.168.100.5"))
        assertTrue(isPrivateLanIpv4("169.254.20.3"))
        assertTrue(isPrivateLanIpv4("100.64.0.1"))
        assertTrue(isPrivateLanIpv4("100.93.236.68"))
        assertTrue(isPrivateLanIpv4("100.127.255.254"))
        assertFalse(isPrivateLanIpv4("172.32.0.1"))
        assertFalse(isPrivateLanIpv4("100.63.255.255"))
        assertFalse(isPrivateLanIpv4("100.128.0.1"))
        assertFalse(isPrivateLanIpv4("8.8.8.8"))
        assertFalse(isPrivateLanIpv4("192.168.1"))
        assertFalse(isPrivateLanIpv4("not-an-ip"))
    }
}
