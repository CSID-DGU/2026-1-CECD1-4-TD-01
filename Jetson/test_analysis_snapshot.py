import tempfile
import unittest
from pathlib import Path

from analysis_snapshot import AnalysisSnapshotError, validate_analysis_snapshot
from context_engine import ContextEngine, MILLIS_PER_DAY


FIXED_NOW = 2_000_000_000_000


def snapshot(snapshot_id: str = "snapshot-1", generated_at: int = FIXED_NOW):
    return {
        "schema_version": 1,
        "snapshot_id": snapshot_id,
        "generated_at": generated_at,
        "producer": "onmom-android",
        "privacy_level": "STRUCTURED_FEATURES",
        "contains_raw_data": False,
        "datasets": [
            {
                "category": "HEALTH",
                "collected_at": generated_at,
                "data": {
                    "period": "WEEK",
                    "period_values": {
                        "steps": 7234,
                        "distance_km": 5.1,
                    },
                    "daily_values": [
                        {
                            "date": "2033-05-18",
                            "values": {"steps": 7234},
                            "excluded": [],
                        }
                    ],
                },
            },
            {
                "category": "PHENOTYPE",
                "collected_at": generated_at,
                "data": {
                    "call_pattern": {
                        "total_calls_this_week": 4,
                        "missed_call_rate": 0.25,
                    },
                    "app_usage_pattern": {
                        "daily_average_screen_minutes": 142,
                        "top_apps": [
                            {
                                "app_name": "Browser",
                                "category": "OTHER",
                                "total_time_minutes": 55,
                            }
                        ],
                    },
                },
            },
        ],
    }


class AnalysisSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.database = Path(self.directory.name) / "context.db"
        self.engine = ContextEngine(self.database, clock=lambda: FIXED_NOW)

    def tearDown(self):
        self.directory.cleanup()

    def test_valid_structured_features_are_stored_and_listed(self):
        payload = validate_analysis_snapshot(snapshot())
        first = self.engine.ingest_analysis_snapshot(payload)
        duplicate = self.engine.ingest_analysis_snapshot(payload)

        self.assertTrue(first["inserted"])
        self.assertFalse(duplicate["inserted"])
        self.assertEqual(1, self.engine.health_status()["analysis_snapshots"])

        listed = self.engine.list_analysis_snapshots(category="health")
        self.assertEqual("analysis-snapshot-list-v1", listed["schema"])
        self.assertEqual(1, listed["count"])
        self.assertEqual(
            payload,
            listed["snapshots"][0]["snapshot"],
        )

    def test_rejects_raw_or_identifying_fields(self):
        payload = snapshot()
        payload["datasets"][0]["data"]["uri"] = "content://media/1"
        with self.assertRaises(AnalysisSnapshotError):
            validate_analysis_snapshot(payload)

        payload = snapshot()
        payload["datasets"][1]["data"]["phone_number"] = "01012345678"
        with self.assertRaises(AnalysisSnapshotError):
            validate_analysis_snapshot(payload)

        payload = snapshot()
        payload["datasets"][1]["data"]["top_apps"] = [
            {"package_name": "com.example.private"}
        ]
        with self.assertRaises(AnalysisSnapshotError):
            validate_analysis_snapshot(payload)

    def test_rejects_non_finite_or_raw_flag(self):
        payload = snapshot()
        payload["datasets"][0]["data"]["score"] = float("nan")
        with self.assertRaises(AnalysisSnapshotError):
            validate_analysis_snapshot(payload)

        payload = snapshot()
        payload["contains_raw_data"] = True
        with self.assertRaises(AnalysisSnapshotError):
            validate_analysis_snapshot(payload)

    def test_retention_removes_only_old_analysis_snapshots(self):
        old = snapshot(
            "snapshot-old",
            FIXED_NOW - 31 * MILLIS_PER_DAY,
        )
        recent = snapshot(
            "snapshot-recent",
            FIXED_NOW - MILLIS_PER_DAY,
        )
        self.engine.ingest_analysis_snapshot(validate_analysis_snapshot(old))
        self.engine.ingest_analysis_snapshot(validate_analysis_snapshot(recent))

        removed = self.engine.cleanup_expired(FIXED_NOW)

        self.assertEqual(1, removed["analysis_snapshots"])
        listed = self.engine.list_analysis_snapshots(limit=10)
        self.assertEqual(1, listed["count"])
        self.assertEqual(
            "snapshot-recent",
            listed["snapshots"][0]["snapshot"]["snapshot_id"],
        )


if __name__ == "__main__":
    unittest.main()
