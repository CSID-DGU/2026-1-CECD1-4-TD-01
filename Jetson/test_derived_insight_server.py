import os
import tempfile
import unittest
from pathlib import Path

from derived_insight_server import PayloadError, load_bearer_token, save_payload_atomically, validate_payload


def valid_payload():
    return {
        "schema_version": 1,
        "transfer_id": "test-transfer",
        "generated_at": 1000,
        "producer": "onmom-android",
        "contains_raw_data": False,
        "summaries": [
            {
                "category": "HEALTH",
                "updated_at": 900,
                "expires_at": None,
                "summary": "주간 걸음 평균 7,200보",
            }
        ],
    }


class DerivedInsightServerTest(unittest.TestCase):
    def test_accepts_fixed_derived_schema(self):
        self.assertEqual(valid_payload(), validate_payload(valid_payload()))

    def test_rejects_extra_raw_field(self):
        payload = valid_payload()
        payload["raw_audio"] = "bytes"
        with self.assertRaises(PayloadError):
            validate_payload(payload)

    def test_rejects_raw_uri_marker(self):
        payload = valid_payload()
        payload["summaries"][0]["summary"] = "content://media/external/images/1"
        with self.assertRaises(PayloadError):
            validate_payload(payload)

    def test_writes_snapshot_atomically(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "latest.json"
            save_payload_atomically(valid_payload(), output)
            text = output.read_text(encoding="utf-8")
            self.assertIn('"contains_raw_data": false', text)
            self.assertNotIn("raw_audio", text)

    def test_loads_persistent_token_file(self):
        with tempfile.TemporaryDirectory() as directory:
            token_file = Path(directory) / "jetson_sync_token"
            token_file.write_text("persistent-secret-token\n", encoding="utf-8")
            os.chmod(token_file, 0o600)

            self.assertEqual(
                "persistent-secret-token",
                load_bearer_token(token_file, environ={}),
            )

    def test_environment_token_takes_precedence(self):
        self.assertEqual(
            "environment-secret",
            load_bearer_token(Path("does-not-exist"), environ={"JETSON_SYNC_TOKEN": " environment-secret "}),
        )


if __name__ == "__main__":
    unittest.main()
