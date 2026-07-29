import tempfile
import unittest
from pathlib import Path

from context_engine import ContextEngine, implicit_feedback_label


FIXED_NOW = 2_000_000_000_000


def outcome(
    session_id: str,
    *,
    before_valence: float,
    before_arousal: float,
    after_valence: float,
    after_arousal: float,
) -> dict:
    return {
        "schema_version": 1,
        "session_id": session_id,
        "started_at": FIXED_NOW - 20 * 60 * 1000,
        "ended_at": FIXED_NOW - 5 * 60 * 1000,
        "before": {
            "valence": before_valence,
            "arousal": before_arousal,
            "confidence": 1.0,
        },
        "after": {
            "valence": after_valence,
            "arousal": after_arousal,
            "confidence": 1.0,
        },
        "strategies": ["EMPATHIC_REFLECTION", "GROUNDING"],
        "explicit_feedback": None,
        "contains_raw_data": False,
    }


class ImplicitFeedbackTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.engine = ContextEngine(
            Path(self.directory.name) / "feedback.db",
            clock=lambda: FIXED_NOW,
        )

    def tearDown(self):
        self.directory.cleanup()

    def test_thresholds_map_reward_to_like_neutral_and_dislike(self):
        self.assertEqual("LIKE", implicit_feedback_label(0.05))
        self.assertEqual("NEUTRAL", implicit_feedback_label(0.049))
        self.assertEqual("NEUTRAL", implicit_feedback_label(-0.049))
        self.assertEqual("DISLIKE", implicit_feedback_label(-0.05))

    def test_recorded_emotion_direction_is_visible_in_learning_status(self):
        calmer = self.engine.record_session_outcome(
            outcome(
                "calmer",
                before_valence=-0.8,
                before_arousal=0.9,
                after_valence=-0.2,
                after_arousal=0.3,
            )
        )
        unchanged = self.engine.record_session_outcome(
            outcome(
                "unchanged",
                before_valence=-0.4,
                before_arousal=0.5,
                after_valence=-0.4,
                after_arousal=0.5,
            )
        )
        escalated = self.engine.record_session_outcome(
            outcome(
                "escalated",
                before_valence=-0.2,
                before_arousal=0.3,
                after_valence=-0.8,
                after_arousal=0.9,
            )
        )

        self.assertEqual("LIKE", calmer["implicit_feedback"])
        self.assertEqual("NEUTRAL", unchanged["implicit_feedback"])
        self.assertEqual("DISLIKE", escalated["implicit_feedback"])
        self.assertEqual(
            {"LIKE": 1, "NEUTRAL": 1, "DISLIKE": 1},
            self.engine.learning_status()["implicit_feedback_counts"],
        )


if __name__ == "__main__":
    unittest.main()
