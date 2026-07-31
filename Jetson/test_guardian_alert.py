import unittest

from guardian_alert import GuardianAlertError, validate_guardian_alert


def alert():
    return {
        "schema_version": 1,
        "alert_id": "alert-1",
        "occurred_at": 2_000_000_000_000,
        "severity": "HIGH",
        "category": "SAFETY",
        "title": "낙상 의심",
        "message": "거실에서 확인이 필요합니다.",
        "source": "camera.fall",
        "contains_raw_data": False,
    }


class GuardianAlertTest(unittest.TestCase):
    def test_accepts_derived_alert(self):
        self.assertEqual("HIGH", validate_guardian_alert(alert())["severity"])

    def test_rejects_raw_marker(self):
        payload = alert()
        payload["message"] = "content://media/1"
        with self.assertRaises(GuardianAlertError):
            validate_guardian_alert(payload)

    def test_rejects_unknown_severity(self):
        payload = alert()
        payload["severity"] = "PANIC"
        with self.assertRaises(GuardianAlertError):
            validate_guardian_alert(payload)


if __name__ == "__main__":
    unittest.main()
