import json
import os
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from context_engine import ContextEngine
from derived_insight_server import DerivedInsightServer
from iot_control import HomeAssistantIotController


class GuardianIotHttpTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.engine = ContextEngine(root / "context.db")
        token_file = root / "ha_token"
        token_file.write_text("ha-secret", encoding="utf-8")
        if os.name == "posix":
            token_file.chmod(0o600)
        config = root / "iot_devices.json"
        config.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "home_assistant_url": "http://127.0.0.1:8123",
                    "token_file": str(token_file),
                    "devices": [
                        {
                            "id": "room_light",
                            "name": "방 조명",
                            "entity_id": "light.room",
                            "kind": "LIGHT",
                            "actions": ["TURN_ON", "TURN_OFF"],
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        self.ha_calls = []

        def requester(method, url, token, body):
            self.ha_calls.append((method, url, token, body))
            return {"state": "off"} if method == "GET" else []

        controller = HomeAssistantIotController(config, requester)
        self.server = DerivedInsightServer(
            ("127.0.0.1", 0),
            root / "latest.json",
            "test-token",
            context_engine=self.engine,
            iot_controller=controller,
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.directory.cleanup()

    def request(self, path, *, method="GET", body=None, schema=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {
            "Authorization": "Bearer test-token",
            "Accept": "application/json",
        }
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

    def guardian_alert(self):
        return {
            "schema_version": 1,
            "alert_id": "fall-1",
            "occurred_at": int(time.time() * 1000),
            "severity": "HIGH",
            "category": "SAFETY",
            "title": "낙상 의심",
            "message": "거실 확인이 필요합니다.",
            "source": "camera.fall",
            "contains_raw_data": False,
        }

    def test_guardian_alert_post_list_ack_and_privacy(self):
        status, accepted = self.request(
            "/v1/guardian-alerts",
            method="POST",
            schema="guardian-alert-v1",
            body=self.guardian_alert(),
        )
        self.assertEqual(202, status)
        self.assertTrue(accepted["inserted"])

        status, listed = self.request("/v1/guardian-alerts?after=0&limit=10")
        self.assertEqual(200, status)
        self.assertEqual(1, listed["count"])
        self.assertFalse(listed["alerts"][0]["acknowledged"])

        status, acknowledged = self.request(
            "/v1/guardian-alerts/ack",
            method="POST",
            schema="guardian-alert-ack-v1",
            body={"alert_id": "fall-1"},
        )
        self.assertEqual(202, status)
        self.assertTrue(acknowledged["acknowledged"])

        raw = self.guardian_alert()
        raw["alert_id"] = "fall-raw"
        raw["message"] = "content://media/1"
        with self.assertRaises(HTTPError) as raised:
            self.request(
                "/v1/guardian-alerts",
                method="POST",
                schema="guardian-alert-v1",
                body=raw,
            )
        self.assertEqual(400, raised.exception.code)

    def test_iot_list_and_allow_listed_command(self):
        status, listed = self.request("/v1/iot/devices")
        self.assertEqual(200, status)
        self.assertEqual("OFF", listed["devices"][0]["state"])
        self.assertNotIn("entity_id", listed["devices"][0])

        status, result = self.request(
            "/v1/iot/commands",
            method="POST",
            schema="iot-command-v1",
            body={"device_id": "room_light", "action": "TURN_ON"},
        )
        self.assertEqual(202, status)
        self.assertTrue(result["accepted"])
        self.assertEqual(
            ("POST", "http://127.0.0.1:8123/api/services/light/turn_on",
             "ha-secret", {"entity_id": "light.room"}),
            self.ha_calls[-1],
        )


if __name__ == "__main__":
    unittest.main()
