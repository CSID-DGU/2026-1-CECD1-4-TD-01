#!/usr/bin/env python3
"""Validation for derived guardian alerts. Raw data and identifiers stay out."""

from __future__ import annotations

import json
import re
from typing import Any

MAX_ALERT_BYTES = 16 * 1024
ALERT_KEYS = {
    "schema_version",
    "alert_id",
    "occurred_at",
    "severity",
    "category",
    "title",
    "message",
    "source",
    "contains_raw_data",
}
SEVERITIES = {"INFO", "CAUTION", "HIGH", "CRITICAL"}
CATEGORIES = {"SAFETY", "HEALTH", "ACCESS", "ENVIRONMENT", "SYSTEM"}
IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
FORBIDDEN_MARKERS = (
    "content://",
    "file://",
    "/storage/",
    "/sdcard/",
    "data:image",
    "data:audio",
    ";base64,",
    "rfid_uid",
    "card_uid",
    "phone_number",
    "package_name",
    "transcript",
    "conversation_text",
)


class GuardianAlertError(ValueError):
    pass


def _text(value: Any, field: str, maximum: int) -> str:
    if not isinstance(value, str):
        raise GuardianAlertError(f"{field} must be text")
    result = value.strip()
    if not result or len(result) > maximum:
        raise GuardianAlertError(f"{field} is empty or too long")
    lowered = result.lower()
    if any(marker in lowered for marker in FORBIDDEN_MARKERS):
        raise GuardianAlertError(f"{field} contains a raw-data marker")
    return result


def validate_guardian_alert(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict) or set(payload) != ALERT_KEYS:
        raise GuardianAlertError("guardian alert fields do not match guardian-alert-v1")
    if payload.get("schema_version") != 1:
        raise GuardianAlertError("unsupported guardian alert schema_version")
    if payload.get("contains_raw_data") is not False:
        raise GuardianAlertError("raw data is not accepted")

    alert_id = payload.get("alert_id")
    source = payload.get("source")
    if not isinstance(alert_id, str) or not IDENTIFIER_RE.fullmatch(alert_id):
        raise GuardianAlertError("alert_id contains unsupported characters")
    if not isinstance(source, str) or not IDENTIFIER_RE.fullmatch(source):
        raise GuardianAlertError("source contains unsupported characters")
    occurred_at = payload.get("occurred_at")
    if (
        not isinstance(occurred_at, int)
        or isinstance(occurred_at, bool)
        or occurred_at <= 0
        or occurred_at > 9_999_999_999_999
    ):
        raise GuardianAlertError("occurred_at must be positive epoch milliseconds")
    if payload.get("severity") not in SEVERITIES:
        raise GuardianAlertError("unsupported severity")
    if payload.get("category") not in CATEGORIES:
        raise GuardianAlertError("unsupported category")

    validated = dict(payload)
    validated["title"] = _text(payload.get("title"), "title", 120)
    validated["message"] = _text(payload.get("message"), "message", 500)
    encoded = json.dumps(
        validated,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    if len(encoded) > MAX_ALERT_BYTES:
        raise GuardianAlertError("guardian alert is too large")
    return validated
