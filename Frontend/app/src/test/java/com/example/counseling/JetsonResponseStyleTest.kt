package com.example.counseling

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JetsonResponseStyleTest {
    private val values = linkedMapOf(
        "RESPONSE_LENGTH" to 0.25,
        "EMPATHY_RATIO" to 0.85,
        "QUESTION_FREQUENCY" to 0.20,
        "ADVICE_DIRECTNESS" to 0.15,
        "GROUNDING_INTENSITY" to 0.80,
        "EXPLANATION_DETAIL" to 0.30,
        "PROACTIVITY" to 0.35,
        "WARMTH" to 0.80,
        "ACTION_SIZE" to 0.15,
        "HEALTH_MENTION" to 0.10,
    )

    @Test
    fun `parses all ten response style dimensions`() {
        val baselines = values.toMutableMap()
        val applied = values.toMutableMap().apply {
            this["RESPONSE_LENGTH"] = 0.31
            this["WARMTH"] = 0.74
        }
        val root = JsonParser.parseString(
            Gson().toJson(
                mapOf(
                    "values" to applied,
                    "baseline_values" to baselines,
                    "explored_dimensions" to listOf("RESPONSE_LENGTH", "WARMTH"),
                ),
            ),
        ).asJsonObject

        val result = parseJetsonResponseStyle(root)

        assertNotNull(result)
        assertEquals(10, result!!.values.size)
        assertEquals(0.31, result.values.getValue("RESPONSE_LENGTH"), 0.0)
        assertEquals(
            listOf("RESPONSE_LENGTH", "WARMTH"),
            result.exploredDimensions,
        )
    }

    @Test
    fun `serializes server field names for the next outcome`() {
        val style = JetsonResponseStyle(
            values = values,
            baselineValues = values,
            exploredDimensions = listOf("WARMTH"),
        )

        val json = Gson().toJson(style)

        assertTrue(json.contains("\"baseline_values\""))
        assertTrue(json.contains("\"explored_dimensions\""))
    }

    @Test
    fun `learning status exposes continuous style progress`() {
        val result = parseJetsonLearningStatus(
            """
            {
              "learning_state":"EARLY_SIGNAL",
              "session_outcomes":7,
              "trained_outcomes":5,
              "pending_outcomes":2,
              "implicit_feedback_counts":{"LIKE":4,"NEUTRAL":2,"DISLIKE":1},
              "response_style_policy":{
                "dimensions":10,
                "observations":18,
                "changed_dimensions":6
              },
              "policy_weight_range":{"minimum":0.98,"maximum":1.02},
              "policy_versions":[{"version":3}]
            }
            """.trimIndent(),
        )

        assertEquals(10, result.styleDimensions)
        assertEquals(18, result.styleObservations)
        assertEquals(6, result.changedStyleDimensions)
    }
}
