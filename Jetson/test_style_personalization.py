import tempfile
import unittest
from pathlib import Path

from adaptive_config import STYLE_DIMENSION_NAMES
from context_engine import (
    ContextEngine,
    ContextEngineError,
    validate_session_outcome,
)


FIXED_NOW = 2_000_000_000_000


def outcome(session_id: str, response_style: dict | None = None) -> dict:
    payload = {
        "schema_version": 1,
        "session_id": session_id,
        "started_at": FIXED_NOW - 20 * 60 * 1000,
        "ended_at": FIXED_NOW - 5 * 60 * 1000,
        "before": {
            "valence": -0.7,
            "arousal": 0.8,
            "confidence": 1.0,
        },
        "after": {
            "valence": -0.2,
            "arousal": 0.3,
            "confidence": 1.0,
        },
        "strategies": ["EMPATHIC_REFLECTION", "GROUNDING"],
        "explicit_feedback": None,
        "contains_raw_data": False,
    }
    if response_style is not None:
        payload["response_style"] = response_style
    return payload


class StylePersonalizationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.engine = ContextEngine(
            Path(self.directory.name) / "style.db",
            clock=lambda: FIXED_NOW,
        )

    def tearDown(self):
        self.directory.cleanup()

    def test_context_contains_bounded_exploration_and_concrete_style_prompt(self):
        bundle = self.engine.build_context_bundle(
            current_valence=-0.7,
            current_arousal=0.8,
        )
        style = bundle["response_style"]

        self.assertEqual(set(STYLE_DIMENSION_NAMES), set(style["values"]))
        self.assertEqual(set(STYLE_DIMENSION_NAMES), set(style["baseline_values"]))
        self.assertEqual(2, len(style["explored_dimensions"]))
        self.assertIn("[개인화 응답 스타일]", bundle["prompt_context"])
        self.assertIn("응답 길이 수준", bundle["prompt_context"])
        self.assertFalse(bundle["contains_raw_data"])

        explored = set(style["explored_dimensions"])
        for dimension in STYLE_DIMENSION_NAMES:
            difference = abs(
                style["values"][dimension]
                - style["baseline_values"][dimension]
            )
            if dimension in explored:
                self.assertGreater(difference, 0.0)
                self.assertLessEqual(difference, 0.120001)
            else:
                self.assertEqual(0.0, difference)

    def test_style_payload_is_strict_and_backward_compatible(self):
        self.assertEqual(outcome("old-app"), validate_session_outcome(outcome("old-app")))
        style = self.engine.build_context_bundle(
            current_valence=-0.7,
            current_arousal=0.8,
        )["response_style"]
        self.assertEqual(
            outcome("new-app", style),
            validate_session_outcome(outcome("new-app", style)),
        )

        invalid = outcome("invalid-style", style)
        untested = next(
            dimension
            for dimension in STYLE_DIMENSION_NAMES
            if dimension not in style["explored_dimensions"]
        )
        invalid["response_style"]["values"][untested] += 0.01
        with self.assertRaises(ContextEngineError):
            validate_session_outcome(invalid)

    def test_positive_reward_reinforces_only_explored_style_directions(self):
        bundle = self.engine.build_context_bundle(
            current_valence=-0.7,
            current_arousal=0.8,
        )
        style = bundle["response_style"]
        before = self.engine.policy_snapshot()["response_styles"][
            "NEGATIVE_HIGH_AROUSAL"
        ]
        self.engine.record_session_outcome(outcome("style-positive", style))

        trained = self.engine.run_nightly_adaptation(
            force=True,
            now_ms=FIXED_NOW,
        )
        after = self.engine.policy_snapshot()["response_styles"][
            "NEGATIVE_HIGH_AROUSAL"
        ]

        self.assertTrue(trained["trained"])
        self.assertEqual(
            2,
            len(trained["affected_style_dimensions"]),
        )
        for dimension in STYLE_DIMENSION_NAMES:
            if dimension not in style["explored_dimensions"]:
                self.assertEqual(before[dimension], after[dimension])
                continue
            applied_direction = (
                style["values"][dimension]
                - style["baseline_values"][dimension]
            )
            learned_direction = after[dimension] - before[dimension]
            self.assertGreater(applied_direction * learned_direction, 0.0)
            self.assertLessEqual(abs(learned_direction), 0.030001)

        status = self.engine.learning_status()["response_style_policy"]
        self.assertEqual(10, status["dimensions"])
        self.assertEqual(2, status["observations"])
        self.assertEqual(2, status["changed_dimensions"])

    def test_rollback_restores_style_values_with_strategy_policy(self):
        style = self.engine.build_context_bundle(
            current_valence=-0.7,
            current_arousal=0.8,
        )["response_style"]
        initial = self.engine.policy_snapshot()["response_styles"]
        self.engine.record_session_outcome(outcome("style-rollback", style))
        self.engine.run_nightly_adaptation(force=True, now_ms=FIXED_NOW)

        rollback = self.engine.rollback_policy(1)
        restored = self.engine.policy_snapshot()["response_styles"]

        self.assertTrue(rollback["rolled_back"])
        self.assertEqual(initial, restored)


if __name__ == "__main__":
    unittest.main()
