package com.example.counseling

import com.example.counseling.llm.ChatMessage
import com.example.counseling.llm.ChatRole
import com.example.counseling.voiceemotion.VoiceEmotion
import com.example.counseling.voiceemotion.VoiceEmotionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JetsonAdaptiveContextTest {
    @Test
    fun `infers relevant context domains without sending message text`() {
        val topics = inferJetsonTopicDomains("어제 잠도 못 자고 심장이 두근거렸어")

        assertTrue(topics.contains("SLEEP_AND_ROUTINE"))
        assertTrue(topics.contains("PHYSIOLOGICAL_STATE"))
        assertFalse(topics.contains("GALLERY"))
    }

    @Test
    fun `uses general check in when no domain keyword is found`() {
        assertEquals(
            listOf("GENERAL_CHECK_IN"),
            inferJetsonTopicDomains("그냥 오늘 이야기를 하고 싶어"),
        )
    }

    @Test
    fun `converts full emotion distribution into bounded valence and arousal`() {
        val result = VoiceEmotionResult(
            emotion = VoiceEmotion.Fearful,
            confidence = 0.70f,
            probabilities = mapOf(
                VoiceEmotion.Happy to 0.05f,
                VoiceEmotion.Sad to 0.10f,
                VoiceEmotion.Angry to 0.10f,
                VoiceEmotion.Fearful to 0.70f,
                VoiceEmotion.Neutral to 0.05f,
            ),
        )

        val state = result.toJetsonEmotionState(observedAt = 1234L)

        assertTrue(state.valence in -1.0..1.0)
        assertTrue(state.arousal in 0.0..1.0)
        assertTrue(state.valence < 0.0)
        assertTrue(state.arousal > 0.5)
        assertTrue(state.confidence in 0.0..1.0)
        assertEquals(1234L, state.observedAt)
    }

    @Test
    fun `text emotion fallback produces only derived coordinates`() {
        val result = inferJetsonTextEmotionState(
            "오늘은 너무 불안하고 답답해서 긴장이 돼",
            observedAt = 1234L,
        )

        requireNotNull(result)
        assertTrue(result.valence < -0.4)
        assertTrue(result.arousal > 0.7)
        assertTrue(result.confidence in 0.45..0.72)
        assertEquals(1234L, result.observedAt)
    }

    @Test
    fun `calm positive text produces positive low arousal coordinate`() {
        val result = inferJetsonTextEmotionState("이제 마음이 놓이고 편안해")

        requireNotNull(result)
        assertTrue(result.valence > 0.3)
        assertTrue(result.arousal < 0.4)
    }

    @Test
    fun `negated emotion and neutral text are not invented as signals`() {
        assertEquals(
            null,
            inferJetsonTextEmotionState("오늘은 불안하지 않아"),
        )
        assertEquals(
            null,
            inferJetsonTextEmotionState("오늘 점심 메뉴를 정리했어"),
        )
    }

    @Test
    fun `pending outcome expires before an unrelated later conversation`() {
        val now = 10L * MAX_JETSON_OUTCOME_INTERVAL_MS
        val pending = PendingJetsonOutcome(
            conversationSessionId = "session",
            startedAt = now - MAX_JETSON_OUTCOME_INTERVAL_MS - 1L,
            before = JetsonEmotionState(
                valence = -0.5,
                arousal = 0.7,
                confidence = 0.6,
                observedAt = now - MAX_JETSON_OUTCOME_INTERVAL_MS - 1L,
            ),
            strategies = listOf("GROUNDING"),
        )

        assertFalse(pending.isWithinOutcomeWindow(now))
        assertTrue(
            pending.copy(
                startedAt = now - 1_000L,
                before = pending.before.copy(observedAt = now - 1_000L),
            ).isWithinOutcomeWindow(now),
        )
    }

    @Test
    fun `parses inspectable learning status without raw records`() {
        val result = parseJetsonLearningStatus(
            """
            {
              "learning_state":"EARLY_SIGNAL",
              "session_outcomes":7,
              "trained_outcomes":5,
              "pending_outcomes":2,
              "policy_weight_range":{"minimum":0.98,"maximum":1.02},
              "policy_versions":[{"version":3}]
            }
            """.trimIndent(),
        )

        assertEquals("EARLY_SIGNAL", result.learningState)
        assertEquals(7, result.sessionOutcomes)
        assertEquals(5, result.trainedOutcomes)
        assertEquals(2, result.pendingOutcomes)
        assertEquals(3, result.latestPolicyVersion)
        assertEquals(0.98, result.minimumWeight, 0.0)
        assertEquals(1.02, result.maximumWeight, 0.0)
    }

    @Test
    fun `injects adaptive context only into latest user message`() {
        val messages = listOf(
            ChatMessage(ChatRole.User, "이전 질문"),
            ChatMessage(ChatRole.Assistant, "이전 답변"),
            ChatMessage(ChatRole.User, "현재 질문"),
        )

        val result = messages.withJetsonAdaptiveContext("누적 생활 패턴 변화")

        assertEquals("이전 질문", result.first().content)
        assertTrue(result.last().content.contains("누적 생활 패턴 변화"))
        assertTrue(result.last().content.contains("현재 질문"))
    }
}

