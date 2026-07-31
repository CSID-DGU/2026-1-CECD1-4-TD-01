import tempfile
import unittest
from pathlib import Path

from adaptive_config import (
    DEFAULT_ADAPTIVE_CONFIG_PATH,
    STRATEGY_NAMES,
    AdaptiveConfigError,
    load_adaptive_policy_config,
)
from context_engine import ContextEngine, apply_source_quality_gate


class AdaptiveConfigTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.default_text = DEFAULT_ADAPTIVE_CONFIG_PATH.read_text(encoding="utf-8")

    def tearDown(self):
        self.directory.cleanup()

    def write_config(self, text: str) -> Path:
        path = self.root / "adaptive_policy.ini"
        path.write_text(text, encoding="utf-8")
        return path

    def test_default_config_has_all_thresholds_and_strategy_prompts(self):
        config = load_adaptive_policy_config()

        self.assertEqual(3, config.config_version)
        self.assertEqual(30, config.retention.analysis_snapshots_days)
        self.assertEqual(0.01, config.learning.learning_rate)
        self.assertEqual(3, config.context.recommended_strategies)
        self.assertEqual(set(STRATEGY_NAMES), set(config.prompts.strategy_instructions))
        self.assertIn(
            "감정과 상황을 짧게 반영",
            config.prompts.strategy_instructions["EMPATHIC_REFLECTION"],
        )

    def test_invalid_edit_fails_loudly(self):
        invalid = self.default_text.replace(
            "learning_rate = 0.01",
            "learning_rate = -0.01",
        )

        with self.assertRaises(AdaptiveConfigError):
            load_adaptive_policy_config(self.write_config(invalid))

    def test_quality_gate_uses_edited_distance_threshold(self):
        edited = self.default_text.replace(
            "distance_km_missing_at_or_below = 0.02",
            "distance_km_missing_at_or_below = 0.50",
        )
        config = load_adaptive_policy_config(self.write_config(edited))
        event = {
            "source_family": "activity.motion",
            "domain": "PHYSICAL_ACTIVITY",
            "event_type": "daily_features",
            "metrics": {"distance_km": 0.4},
            "quality": {
                "status": "VALID",
                "confidence": 1.0,
                "coverage": 1.0,
                "reasons": [],
            },
        }

        gated = apply_source_quality_gate(event, config)

        self.assertIsNone(gated["metrics"]["distance_km"])

    def test_edited_bucket_prompt_is_injected_into_context(self):
        edited = self.default_text.replace(
            "사용자가 압도되거나 긴장했을 가능성을 고려해 짧고 차분하게 답합니다.",
            "테스트용 고각성 응답 지침입니다.",
        )
        config_path = self.write_config(edited)
        engine = ContextEngine(
            self.root / "context.db",
            config_path=config_path,
        )

        bundle = engine.build_context_bundle(
            current_valence=-0.8,
            current_arousal=0.8,
        )

        self.assertEqual(
            "NEGATIVE_HIGH_AROUSAL",
            bundle["emotion_state"]["bucket"],
        )
        self.assertIn("테스트용 고각성 응답 지침", bundle["prompt_context"])
        self.assertIn("EMPATHIC_REFLECTION", bundle["prompt_context"])


if __name__ == "__main__":
    unittest.main()
