import json
import os
import tempfile
import unittest
from pathlib import Path

from iot_control import HomeAssistantIotController, IotControlError


class IotControlTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.token = root / "ha_token"
        self.token.write_text("secret", encoding="utf-8")
        if os.name == "posix":
            self.token.chmod(0o600)
        self.config = root / "iot_devices.json"
        self.config.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "home_assistant_url": "http://127.0.0.1:8123",
                    "token_file": str(self.token),
                    "devices": [
                        {
                            "id": "light",
                            "name": "조명",
                            "entity_id": "light.room",
                            "kind": "LIGHT",
                            "actions": ["TURN_ON", "TURN_OFF"],
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        self.calls = []

        def requester(method, url, token, body):
            self.calls.append((method, url, token, body))
            return {"state": "on"} if method == "GET" else []

        self.controller = HomeAssistantIotController(self.config, requester)

    def tearDown(self):
        self.directory.cleanup()

    def test_lists_without_leaking_entity_id(self):
        result = self.controller.list_devices()
        self.assertTrue(result["configured"])
        self.assertEqual("ON", result["devices"][0]["state"])
        self.assertNotIn("entity_id", result["devices"][0])

    def test_executes_allow_listed_action(self):
        result = self.controller.execute("light", "turn_off")
        self.assertTrue(result["accepted"])
        self.assertEqual(
            ("POST", "http://127.0.0.1:8123/api/services/light/turn_off", "secret",
             {"entity_id": "light.room"}),
            self.calls[-1],
        )

    def test_rejects_unlisted_device_or_action(self):
        with self.assertRaises(IotControlError):
            self.controller.execute("unknown", "TURN_OFF")
        with self.assertRaises(IotControlError):
            self.controller.execute("light", "UNLOCK")


if __name__ == "__main__":
    unittest.main()
