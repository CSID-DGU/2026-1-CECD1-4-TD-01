import json
import socket
import tempfile
import time
import unittest
from pathlib import Path

from camera_analysis_bridge import (
    CameraSampleError,
    CameraUdpWorker,
    CameraWindowAggregator,
    send_camera_sample,
    validate_camera_sample,
)
from context_engine import ContextEngine


class CameraAnalysisBridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp_directory = tempfile.TemporaryDirectory()
        self.now = 2_000_000_000_000
        self.engine = ContextEngine(
            Path(self.temp_directory.name) / "context.db",
            clock=lambda: self.now,
        )

    def tearDown(self):
        self.temp_directory.cleanup()

    def sample(self, sample_id, index=0):
        return {
            "schema_version": 1,
            "sample_id": sample_id,
            "observed_at": self.now + index * 1_000,
            "observation_seconds": 15.0,
            "contains_raw_data": False,
            "activity": {
                "presence": True,
                "motion_score": 0.25 if index < 2 else 0.05,
                "confidence": 0.9,
            },
            "posture": {
                "label": "UPRIGHT" if index < 2 else "SLOUCH",
                "confidence": 0.85,
            },
            "face_emotion": {
                "valence": -0.4 + index * 0.1,
                "arousal": 0.7 - index * 0.05,
                "confidence": 0.8,
                "face_count": 1,
            },
            "rppg": {
                "heart_rate_bpm": 70.0 + index,
                "signal_quality": 0.9,
                "measurement_seconds": 15.0,
                "confidence": 0.88,
            },
        }

    def test_raw_camera_material_and_extra_fields_are_rejected(self):
        raw = self.sample("camera-raw")
        raw["frame"] = "base64-image"
        with self.assertRaises(CameraSampleError):
            validate_camera_sample(raw, now_ms=self.now)

        declared_raw = self.sample("camera-declared-raw")
        declared_raw["contains_raw_data"] = True
        with self.assertRaises(CameraSampleError):
            validate_camera_sample(declared_raw, now_ms=self.now)

    def test_one_window_emits_four_derived_events(self):
        aggregator = CameraWindowAggregator(
            self.engine,
            window_seconds=60.0,
            clock=lambda: self.now,
        )
        for index in range(4):
            result = aggregator.ingest(self.sample(f"camera-{index}", index))
            self.assertTrue(result["accepted"])

        duplicate = aggregator.ingest(self.sample("camera-0", 0))
        self.assertTrue(duplicate["duplicate"])
        emitted = aggregator.flush(force=True, now_ms=self.now + 60_000)

        self.assertEqual(4, len(emitted))
        self.assertEqual(4, self.engine.health_status()["events"])
        status = aggregator.status()
        self.assertEqual(4, status["received_samples"])
        self.assertEqual(1, status["duplicate_samples"])
        self.assertEqual(4, status["events_emitted"])

        with self.engine._connect() as connection:
            rows = connection.execute(
                "SELECT source_family, quality_status, payload_json "
                "FROM analysis_events ORDER BY source_family"
            ).fetchall()
        payloads = {
            row["source_family"]: json.loads(row["payload_json"])
            for row in rows
        }
        self.assertEqual(
            0.5,
            payloads["activity.camera"]["metrics"]["active_ratio"],
        )
        self.assertEqual(
            0.5,
            payloads["posture.camera"]["metrics"]["slouch_ratio"],
        )
        self.assertAlmostEqual(
            -0.25,
            payloads["emotion.face"]["metrics"]["valence"],
            places=3,
        )
        self.assertEqual(
            71.5,
            payloads["physiology.heart_rate"]["metrics"]["heart_rate_bpm"],
        )
        self.assertTrue(
            all(payload["contains_raw_data"] is False for payload in payloads.values())
        )
        self.assertTrue(all(row["quality_status"] == "VALID" for row in rows))

    def test_multiple_faces_are_excluded_from_emotion_but_not_other_modalities(self):
        sample = self.sample("camera-multiple")
        sample["face_emotion"]["face_count"] = 2
        aggregator = CameraWindowAggregator(
            self.engine,
            window_seconds=60.0,
            clock=lambda: self.now,
        )
        aggregator.ingest(sample)
        aggregator.flush(force=True)

        with self.engine._connect() as connection:
            families = {
                row[0]
                for row in connection.execute(
                    "SELECT source_family FROM analysis_events"
                ).fetchall()
            }
        self.assertNotIn("emotion.face", families)
        self.assertIn("activity.camera", families)
        self.assertIn("posture.camera", families)
        self.assertIn("physiology.heart_rate", families)

    def test_loopback_udp_worker_accepts_a_valid_sample(self):
        worker = CameraUdpWorker(
            self.engine,
            host="127.0.0.1",
            port=0,
            window_seconds=1.0,
        )
        worker.start()
        payload = {
            "schema_version": 1,
            "sample_id": "udp-activity-1",
            "observed_at": int(time.time() * 1000),
            "observation_seconds": 1.0,
            "contains_raw_data": False,
            "activity": {
                "presence": True,
                "motion_score": 0.3,
                "confidence": 0.9,
            },
        }
        try:
            send_camera_sample(payload, port=worker.port)
            deadline = time.time() + 2.0
            while (
                worker.status()["received_samples"] < 1
                and time.time() < deadline
            ):
                time.sleep(0.02)
        finally:
            worker.stop()

        self.assertEqual(1, worker.status()["received_samples"])
        self.assertEqual(1, worker.status()["events_emitted"])
        self.assertEqual(1, self.engine.health_status()["events"])

    def test_non_loopback_bind_is_refused(self):
        with self.assertRaises(ValueError):
            CameraUdpWorker(self.engine, host="0.0.0.0")


if __name__ == "__main__":
    unittest.main()
