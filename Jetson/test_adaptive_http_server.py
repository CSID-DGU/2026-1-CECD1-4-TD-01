import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from camera_analysis_bridge import CameraUdpWorker, send_camera_sample
from context_engine import ContextEngine
from derived_insight_server import DerivedInsightServer


class AdaptiveHttpServerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        directory = Path(self.directory.name)
        self.engine = ContextEngine(directory / "context.db")
        self.camera_worker = CameraUdpWorker(
            self.engine,
            port=0,
            window_seconds=1.0,
        )
        self.camera_worker.start()
        self.server = DerivedInsightServer(
            ("127.0.0.1", 0),
            directory / "latest.json",
            "test-token",
            context_engine=self.engine,
            camera_worker=self.camera_worker,
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.camera_worker.stop()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.directory.cleanup()

    def request(self, path, *, method="GET", body=None, schema=None, authorized=True):
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"Accept": "application/json"}
        if authorized:
            headers["Authorization"] = "Bearer test-token"
        if schema:
            headers["X-OnMom-Schema"] = schema
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = Request(
            self.base_url + path,
            method=method,
            headers=headers,
            data=data,
        )
        with urlopen(request, timeout=3) as response:
            return response.status, json.loads(response.read().decode("utf-8"))

    def analysis_event(self, event_id, **overrides):
        timestamp = int(time.time() * 1000)
        event = {
            "schema_version": 1,
            "event_id": event_id,
            "source": "test.sensor",
            "source_family": "physiology.heart_rate",
            "domain": "PHYSIOLOGICAL_STATE",
            "event_type": "rppg_window",
            "occurred_at": timestamp,
            "expires_at": timestamp + 30 * 60 * 1000,
            "contains_raw_data": False,
            "metrics": {"heart_rate_bpm": 72.0},
            "quality": {
                "status": "VALID",
                "confidence": 0.9,
                "coverage": 0.9,
                "reasons": [],
            },
        }
        event.update(overrides)
        return event

    def test_health_reports_context_engine(self):
        status, body = self.request("/health", authorized=False)
        self.assertEqual(200, status)
        self.assertEqual("derived-only-v1", body["schema"])
        self.assertEqual("adaptive-context-v1", body["context_engine"]["schema"])
        self.assertTrue(body["camera_bridge"]["running"])
        self.assertFalse(body["camera_bridge"]["accepts_raw_data"])

    def test_protected_api_rejects_missing_token(self):
        with self.assertRaises(HTTPError) as raised:
            self.request("/v1/context", authorized=False)
        self.assertEqual(401, raised.exception.code)

    def test_camera_udp_sample_is_visible_in_http_health(self):
        timestamp = int(time.time() * 1000)
        send_camera_sample(
            {
                "schema_version": 1,
                "sample_id": "http-camera-1",
                "observed_at": timestamp,
                "observation_seconds": 1.0,
                "contains_raw_data": False,
                "activity": {
                    "presence": True,
                    "motion_score": 0.4,
                    "confidence": 0.9,
                },
            },
            port=self.camera_worker.port,
        )
        deadline = time.time() + 2.0
        while (
            self.camera_worker.status()["received_samples"] < 1
            and time.time() < deadline
        ):
            time.sleep(0.02)
        self.camera_worker.aggregator.flush(force=True)

        status, body = self.request("/health", authorized=False)
        self.assertEqual(200, status)
        self.assertEqual(1, body["camera_bridge"]["received_samples"])
        self.assertEqual(1, body["camera_bridge"]["events_emitted"])
        self.assertGreaterEqual(body["context_engine"]["events"], 1)

    def test_event_context_session_and_sleep_adaptation_flow(self):
        status, event_result = self.request(
            "/v1/analysis-events",
            method="POST",
            schema="analysis-event-v1",
            body=self.analysis_event("rppg-1"),
        )
        self.assertEqual(202, status)
        self.assertTrue(event_result["inserted"])

        status, context = self.request(
            "/v1/context?topic=PHYSIOLOGICAL_STATE&valence=-0.6&arousal=0.8"
        )
        self.assertEqual(200, status)
        self.assertEqual("NEGATIVE_HIGH_AROUSAL", context["emotion_state"]["bucket"])
        self.assertIn("[JETSON 통합 파생 맥락 v1]", context["prompt_context"])

        now_ms = int(time.time() * 1000)
        session = {
            "schema_version": 1,
            "session_id": "http-session-1",
            "started_at": now_ms - 10 * 60 * 1000,
            "ended_at": now_ms - 60 * 1000,
            "before": {"valence": -0.7, "arousal": 0.8, "confidence": 0.9},
            "after": {"valence": -0.2, "arousal": 0.4, "confidence": 0.9},
            "strategies": ["EMPATHIC_REFLECTION", "GROUNDING"],
            "explicit_feedback": 1.0,
            "contains_raw_data": False,
        }
        status, outcome = self.request(
            "/v1/session-outcomes",
            method="POST",
            schema="session-outcome-v1",
            body=session,
        )
        self.assertEqual(202, status)
        self.assertGreater(outcome["reward"], 0)

        sleep_event = self.analysis_event(
            "sleep-http-1",
            source="home_assistant",
            source_family="sleep.presence",
            domain="SLEEP_AND_ROUTINE",
            event_type="sleep_state",
            metrics={"state": "ASLEEP"},
        )
        self.request(
            "/v1/analysis-events",
            method="POST",
            schema="analysis-event-v1",
            body=sleep_event,
        )
        status, adapted = self.request(
            "/v1/nightly-adapt",
            method="POST",
            schema="nightly-adapt-v1",
            body={"force": False},
        )
        self.assertEqual(202, status)
        self.assertTrue(adapted["trained"])
        self.assertEqual(1, adapted["trained_sessions"])

        status, policy = self.request("/v1/policy")
        self.assertEqual(200, status)
        self.assertGreater(policy["version"], 1)

        status, learning = self.request("/v1/learning-status")
        self.assertEqual(200, status)
        self.assertEqual("learning-status-v1", learning["schema"])
        self.assertEqual(1, learning["trained_outcomes"])
        self.assertIn(learning["learning_state"], {"COLLECTING", "EARLY_SIGNAL", "TREND_AVAILABLE"})

        adapted_version = policy["version"]
        status, rollback = self.request(
            "/v1/policy/rollback",
            method="POST",
            schema="policy-rollback-v1",
            body={"version": 1},
        )
        self.assertEqual(202, status)
        self.assertTrue(rollback["rolled_back"])
        self.assertGreater(rollback["new_policy_version"], adapted_version)

        _, restored_policy = self.request("/v1/policy")
        all_weights = [
            weight
            for bucket in restored_policy["weights"].values()
            for weight in bucket.values()
        ]
        self.assertTrue(all(weight == 1.0 for weight in all_weights))


if __name__ == "__main__":
    unittest.main()

