import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path

from context_engine import (
    ContextEngine,
    ContextEngineError,
    apply_source_quality_gate,
    MILLIS_PER_DAY,
    MILLIS_PER_MINUTE,
    outcome_reward,
    validate_analysis_event,
    validate_session_outcome,
)


FIXED_NOW = 2_000_000_000_000


def valid_event(
    event_id="event-1",
    *,
    occurred_at=FIXED_NOW,
    domain="PHYSICAL_ACTIVITY",
    event_type="daily_features",
    source="test.module",
    source_family="activity.motion",
    metrics=None,
    confidence=0.9,
    coverage=0.9,
    status="VALID",
    expires_at=None,
):
    return {
        "schema_version": 1,
        "event_id": event_id,
        "source": source,
        "source_family": source_family,
        "domain": domain,
        "event_type": event_type,
        "occurred_at": occurred_at,
        "expires_at": expires_at,
        "contains_raw_data": False,
        "metrics": metrics if metrics is not None else {"steps": 7200},
        "quality": {
            "status": status,
            "confidence": confidence,
            "coverage": coverage,
            "reasons": [],
        },
    }


def valid_outcome(session_id="session-1"):
    return {
        "schema_version": 1,
        "session_id": session_id,
        "started_at": FIXED_NOW - 20 * MILLIS_PER_MINUTE,
        "ended_at": FIXED_NOW - 5 * MILLIS_PER_MINUTE,
        "before": {
            "valence": -0.7,
            "arousal": 0.8,
            "confidence": 0.9,
        },
        "after": {
            "valence": -0.3,
            "arousal": 0.5,
            "confidence": 0.9,
        },
        "strategies": ["EMPATHIC_REFLECTION", "GROUNDING"],
        "explicit_feedback": 0.8,
        "contains_raw_data": False,
    }


def legacy_payload():
    return {
        "schema_version": 1,
        "transfer_id": "transfer-1",
        "generated_at": FIXED_NOW,
        "producer": "onmom-android",
        "contains_raw_data": False,
        "summaries": [
            {
                "category": "HEALTH",
                "updated_at": FIXED_NOW - MILLIS_PER_MINUTE,
                "expires_at": None,
                "summary": "최근 일주일의 유효 걸음 기록은 개인 기준선과 비슷합니다.",
            }
        ],
    }


class ContextEngineTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.database = Path(self.directory.name) / "onmom_context.db"
        self.engine = ContextEngine(self.database, clock=lambda: FIXED_NOW)

    def tearDown(self):
        self.directory.cleanup()

    def test_rejects_raw_fields_and_markers(self):
        event = valid_event()
        event["metrics"] = {"card_uid": "04-AA-BB"}
        with self.assertRaises(ContextEngineError):
            validate_analysis_event(event)

        event = valid_event()
        event["metrics"] = {"summary_ko": "content://media/external/image/1"}
        with self.assertRaises(ContextEngineError):
            validate_analysis_event(event)

    def test_missing_is_not_coerced_to_zero(self):
        event = valid_event(status="MISSING", metrics={"steps": None})
        validated = validate_analysis_event(event)
        self.assertIsNone(validated["metrics"]["steps"])
        self.engine.ingest_event(validated)
        bundle = self.engine.build_context_bundle(now_ms=FIXED_NOW, max_cards=6)
        self.assertFalse(any(card["code"] == "PERSONAL_BASELINE_CHANGE" for card in bundle["cards"]))

    def test_central_gate_ignores_zero_heart_rate_and_low_rppg_quality(self):
        event = valid_event(
            source="hub.camera",
            source_family="physiology.heart_rate",
            domain="PHYSIOLOGICAL_STATE",
            event_type="rppg_window",
            metrics={
                "heart_rate_bpm": 0,
                "signal_quality": 0.30,
                "measurement_seconds": 20,
            },
        )
        result = self.engine.ingest_event(event)

        self.assertEqual("SUSPECT", result["quality_status"])
        self.assertIn("heart_rate_bpm", result["excluded_metrics"])
        bundle = self.engine.build_context_bundle(now_ms=FIXED_NOW, max_cards=6)
        self.assertFalse(any(card["evidence_group"] == "physiology.heart_rate" for card in bundle["cards"]))

    def test_central_gate_preserves_steps_but_excludes_contradictory_distance(self):
        event = valid_event(
            metrics={
                "steps": 7200,
                "distance_km": 0.01,
            },
        )
        gated = apply_source_quality_gate(validate_analysis_event(event))

        self.assertEqual(7200, gated["metrics"]["steps"])
        self.assertIsNone(gated["metrics"]["distance_km"])
        self.assertIn("distance_km", gated["_excluded_metrics"])

    def test_denied_rfid_access_is_not_wellbeing_evidence(self):
        result = self.engine.ingest_event(
            valid_event(
                domain="DAILY_ROUTINE",
                event_type="access_transition",
                source="home_assistant.rfid",
                source_family="presence.access",
                metrics={"direction": "ENTER", "zone": "front_door", "authorized": False},
            )
        )

        self.assertEqual("EXCLUDED", result["quality_status"])

    def test_duplicate_event_is_idempotent(self):
        first = self.engine.ingest_event(valid_event())
        second = self.engine.ingest_event(valid_event())
        self.assertTrue(first["inserted"])
        self.assertFalse(second["inserted"])
        self.assertEqual(1, self.engine.health_status()["events"])

    def test_legacy_derived_summary_becomes_context_card(self):
        self.engine.ingest_derived_payload(legacy_payload())
        bundle = self.engine.build_context_bundle(
            topic_domains=["HEALTH"],
            max_cards=3,
            now_ms=FIXED_NOW,
        )
        self.assertEqual("adaptive-context-v1", self.engine.health_status()["schema"])
        health_cards = [card for card in bundle["cards"] if card["domain"] == "HEALTH"]
        self.assertEqual(1, len(health_cards))
        self.assertIn("개인 기준선", bundle["prompt_context"])
        self.assertFalse(bundle["contains_raw_data"])

    def test_numeric_feature_uses_personal_baseline(self):
        for index in range(8):
            self.engine.ingest_event(
                valid_event(
                    f"baseline-{index}",
                    occurred_at=FIXED_NOW - (index + 2) * MILLIS_PER_DAY,
                    metrics={"posture_slouch_minutes": 20 + index % 2},
                    domain="SEDENTARY_AND_POSTURE",
                    source_family="posture.camera",
                )
            )
        for index in range(2):
            self.engine.ingest_event(
                valid_event(
                    f"recent-{index}",
                    occurred_at=FIXED_NOW - (index + 1) * 2 * 60 * 60 * 1000,
                    metrics={"posture_slouch_minutes": 70 + index},
                    domain="SEDENTARY_AND_POSTURE",
                    source_family="posture.camera",
                )
            )

        bundle = self.engine.build_context_bundle(max_cards=6, now_ms=FIXED_NOW)
        cards = [card for card in bundle["cards"] if card["code"] == "PERSONAL_BASELINE_CHANGE"]
        self.assertEqual(1, len(cards))
        self.assertEqual("posture.camera", cards[0]["evidence_group"])
        self.assertGreaterEqual(cards[0]["quality"]["baseline_samples"], 5)

    def test_session_outcome_reward_and_validation(self):
        payload = valid_outcome()
        self.assertEqual(payload, validate_session_outcome(payload))
        self.assertGreater(outcome_reward(payload), 0.0)

        raw = valid_outcome()
        raw["conversation_text"] = "원문"
        with self.assertRaises(ContextEngineError):
            validate_session_outcome(raw)

        too_long = valid_outcome()
        too_long["started_at"] = FIXED_NOW - 7 * 60 * MILLIS_PER_MINUTE
        too_long["ended_at"] = FIXED_NOW
        with self.assertRaises(ContextEngineError):
            validate_session_outcome(too_long)

    def test_adaptation_waits_for_sleep_and_updates_only_slightly(self):
        recorded = self.engine.record_session_outcome(valid_outcome())
        self.assertTrue(recorded["inserted"])
        before = self.engine.policy_snapshot()["weights"]["NEGATIVE_HIGH_AROUSAL"]

        awake_result = self.engine.run_nightly_adaptation(now_ms=FIXED_NOW)
        self.assertFalse(awake_result["trained"])
        self.assertEqual("user_not_asleep", awake_result["reason"])

        self.engine.ingest_event(
            valid_event(
                "sleep-state-1",
                occurred_at=FIXED_NOW - MILLIS_PER_MINUTE,
                domain="SLEEP_AND_ROUTINE",
                event_type="sleep_state",
                source="home_assistant",
                source_family="sleep.presence",
                metrics={"state": "ASLEEP"},
                confidence=0.95,
                coverage=0.95,
                expires_at=FIXED_NOW + 30 * MILLIS_PER_MINUTE,
            )
        )
        trained = self.engine.run_nightly_adaptation(now_ms=FIXED_NOW)
        self.assertTrue(trained["trained"])
        self.assertEqual(1, trained["trained_sessions"])

        after = self.engine.policy_snapshot()["weights"]["NEGATIVE_HIGH_AROUSAL"]
        self.assertGreater(after["GROUNDING"], before["GROUNDING"])
        self.assertGreater(after["EMPATHIC_REFLECTION"], before["EMPATHIC_REFLECTION"])
        self.assertLess(abs(after["GROUNDING"] - before["GROUNDING"]), 0.01)
        self.assertEqual(0, self.engine.health_status()["pending_training_outcomes"])

    def test_large_backlog_cannot_cause_abrupt_policy_jump(self):
        before = self.engine.policy_snapshot()["weights"]["NEGATIVE_HIGH_AROUSAL"]
        for index in range(100):
            outcome = valid_outcome(f"backlog-{index}")
            outcome["strategies"] = ["GROUNDING"]
            outcome["explicit_feedback"] = 1.0
            self.engine.record_session_outcome(outcome)

        trained = self.engine.run_nightly_adaptation(
            force=True,
            now_ms=FIXED_NOW,
        )
        self.assertTrue(trained["trained"])
        self.assertEqual(100, trained["trained_sessions"])
        self.assertEqual(0.02, trained["max_policy_delta_per_run"])

        after = self.engine.policy_snapshot()["weights"]["NEGATIVE_HIGH_AROUSAL"]
        change = after["GROUNDING"] - before["GROUNDING"]
        self.assertGreater(change, 0.0)
        self.assertLessEqual(change, 0.021)

    def test_old_sleep_signal_does_not_trigger_training(self):
        self.engine.record_session_outcome(valid_outcome())
        self.engine.ingest_event(
            valid_event(
                "old-sleep",
                occurred_at=FIXED_NOW - 2 * 60 * MILLIS_PER_MINUTE,
                domain="SLEEP_AND_ROUTINE",
                event_type="sleep_state",
                source="home_assistant",
                source_family="sleep.presence",
                metrics={"state": "ASLEEP"},
                confidence=1.0,
                coverage=1.0,
            )
        )
        result = self.engine.run_nightly_adaptation(now_ms=FIXED_NOW)
        self.assertFalse(result["trained"])
        self.assertEqual("user_not_asleep", result["reason"])

    def test_rfid_card_never_claims_home_duration(self):
        local_now = datetime.now().astimezone().replace(hour=21, minute=0, second=0, microsecond=0)
        now_ms = int(local_now.timestamp() * 1000)

        def entry_millis(days_ago, hour):
            moment = local_now - timedelta(days=days_ago)
            moment = moment.replace(hour=hour, minute=0)
            return int(moment.timestamp() * 1000)

        for index, days_ago in enumerate(range(8, 15)):
            self.engine.ingest_event(
                valid_event(
                    f"rfid-baseline-{index}",
                    occurred_at=entry_millis(days_ago, 17),
                    domain="DAILY_ROUTINE",
                    event_type="access_transition",
                    source="home_assistant.rfid",
                    source_family="presence.access",
                    metrics={"direction": "ENTER", "zone": "front_door"},
                )
            )
        for index, days_ago in enumerate((1, 2, 3, 4)):
            self.engine.ingest_event(
                valid_event(
                    f"rfid-recent-{index}",
                    occurred_at=entry_millis(days_ago, 20),
                    domain="DAILY_ROUTINE",
                    event_type="access_transition",
                    source="home_assistant.rfid",
                    source_family="presence.access",
                    metrics={"direction": "ENTER", "zone": "front_door"},
                )
            )

        bundle = self.engine.build_context_bundle(max_cards=6, now_ms=now_ms)
        cards = [card for card in bundle["cards"] if card["code"] == "RFID_ENTRY_TIME_SHIFT"]
        self.assertEqual(1, len(cards))
        self.assertIn("home_duration", cards[0]["prompt_policy"]["forbidden_claims"])

    def test_prompt_never_contains_raw_payload_markers(self):
        self.engine.ingest_derived_payload(legacy_payload())
        prompt = self.engine.build_context_bundle(now_ms=FIXED_NOW)["prompt_context"].lower()
        self.assertNotIn("content://", prompt)
        self.assertNotIn("card_uid", prompt)
        self.assertNotIn("conversation_text", prompt)


if __name__ == "__main__":
    unittest.main()

