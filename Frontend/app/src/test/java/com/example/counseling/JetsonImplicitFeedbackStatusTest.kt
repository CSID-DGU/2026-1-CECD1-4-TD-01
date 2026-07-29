package com.example.counseling

import org.junit.Assert.assertEquals
import org.junit.Test

class JetsonImplicitFeedbackStatusTest {
    @Test
    fun `parses implicit emotion feedback counts`() {
        val result = parseJetsonLearningStatus(
            """
            {
              "learning_state":"EARLY_SIGNAL",
              "session_outcomes":9,
              "trained_outcomes":6,
              "pending_outcomes":3,
              "implicit_feedback_counts":{
                "LIKE":5,
                "NEUTRAL":3,
                "DISLIKE":1
              },
              "policy_weight_range":{"minimum":0.97,"maximum":1.04},
              "policy_versions":[{"version":4}]
            }
            """.trimIndent(),
        )

        assertEquals(5, result.implicitLikes)
        assertEquals(3, result.implicitNeutral)
        assertEquals(1, result.implicitDislikes)
    }

    @Test
    fun `old server response defaults implicit counts to zero`() {
        val result = parseJetsonLearningStatus(
            """
            {
              "learning_state":"COLLECTING",
              "session_outcomes":0,
              "trained_outcomes":0,
              "pending_outcomes":0,
              "policy_weight_range":{"minimum":1.0,"maximum":1.0},
              "policy_versions":[]
            }
            """.trimIndent(),
        )

        assertEquals(0, result.implicitLikes)
        assertEquals(0, result.implicitNeutral)
        assertEquals(0, result.implicitDislikes)
    }
}
