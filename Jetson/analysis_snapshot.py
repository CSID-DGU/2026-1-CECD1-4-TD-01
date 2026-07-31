#!/usr/bin/env python3
"""Strict validation for developer-only structured analysis snapshots.

The snapshot channel is intentionally separate from counseling context.  It
accepts useful numeric/categorical features for offline analysis, but never
raw photos, audio, identifiers, transcripts, file paths, or application
package names.
"""

from __future__ import annotations

import math
import re
from typing import Any


MAX_ANALYSIS_SNAPSHOT_BYTES = 512 * 1024
MAX_DATASETS = 4
MAX_CONTAINER_ITEMS = 5_000
MAX_TOTAL_NODES = 25_000
MAX_DEPTH = 10
MAX_STRING_CHARS = 500

ALLOWED_ANALYSIS_CATEGORIES = {
    "HEALTH",
    "PHENOTYPE",
    "GALLERY",
    "VOICE_EMOTION",
}
TOP_LEVEL_KEYS = {
    "schema_version",
    "snapshot_id",
    "generated_at",
    "producer",
    "privacy_level",
    "contains_raw_data",
    "datasets",
}
DATASET_KEYS = {"category", "collected_at", "data"}
FIELD_NAME_RE = re.compile(r"^[a-z][a-z0-9_]{0,79}$")
IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

FORBIDDEN_FIELD_NAMES = {
    "raw",
    "raw_data",
    "uri",
    "path",
    "file",
    "file_name",
    "relative_path",
    "phone",
    "phone_number",
    "number",
    "cached_name",
    "contact_name",
    "contact_id",
    "package_name",
    "transcript",
    "conversation_text",
    "user_message",
    "assistant_message",
    "image",
    "image_bytes",
    "audio",
    "audio_bytes",
    "rfid_uid",
    "card_uid",
}
FORBIDDEN_MARKERS = (
    "content://",
    "file://",
    "/storage/",
    "/sdcard/",
    "data:image",
    "data:audio",
    ";base64,",
    '"packagename"',
    '"cachedname"',
    "[현재 사용자 메시지]",
    "[사용자 입력]",
)


class AnalysisSnapshotError(ValueError):
    """Raised when a structured snapshot crosses the developer-data boundary."""


def _positive_millis(value: Any, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise AnalysisSnapshotError(f"{field} must be positive epoch milliseconds")
    return value


def _validate_value(
    value: Any,
    field: str,
    *,
    depth: int,
    node_count: list[int],
) -> None:
    node_count[0] += 1
    if node_count[0] > MAX_TOTAL_NODES:
        raise AnalysisSnapshotError("analysis snapshot contains too many values")
    if depth > MAX_DEPTH:
        raise AnalysisSnapshotError("analysis snapshot nesting is too deep")

    if value is None or isinstance(value, bool):
        return
    if isinstance(value, int):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise AnalysisSnapshotError(f"{field} must be a finite number")
        return
    if isinstance(value, str):
        if len(value) > MAX_STRING_CHARS:
            raise AnalysisSnapshotError(f"{field} string is too long")
        lowered = value.lower()
        if any(marker in lowered for marker in FORBIDDEN_MARKERS):
            raise AnalysisSnapshotError(f"{field} contains a raw-data marker")
        return
    if isinstance(value, list):
        if len(value) > MAX_CONTAINER_ITEMS:
            raise AnalysisSnapshotError(f"{field} contains too many list items")
        for index, item in enumerate(value):
            _validate_value(
                item,
                f"{field}[{index}]",
                depth=depth + 1,
                node_count=node_count,
            )
        return
    if isinstance(value, dict):
        if len(value) > MAX_CONTAINER_ITEMS:
            raise AnalysisSnapshotError(f"{field} contains too many fields")
        for key, item in value.items():
            if not isinstance(key, str) or not FIELD_NAME_RE.fullmatch(key):
                raise AnalysisSnapshotError(f"{field} has an invalid field name")
            if key in FORBIDDEN_FIELD_NAMES:
                raise AnalysisSnapshotError(f"{field}.{key} is not allowed")
            _validate_value(
                item,
                f"{field}.{key}",
                depth=depth + 1,
                node_count=node_count,
            )
        return
    raise AnalysisSnapshotError(f"{field} contains an unsupported value type")


def validate_analysis_snapshot(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict) or set(payload) != TOP_LEVEL_KEYS:
        raise AnalysisSnapshotError(
            "top-level fields do not match analysis-snapshot-v1"
        )
    if payload.get("schema_version") != 1:
        raise AnalysisSnapshotError("unsupported analysis snapshot schema_version")
    if payload.get("producer") != "onmom-android":
        raise AnalysisSnapshotError("unexpected analysis snapshot producer")
    if payload.get("privacy_level") != "STRUCTURED_FEATURES":
        raise AnalysisSnapshotError(
            "privacy_level must be STRUCTURED_FEATURES"
        )
    if payload.get("contains_raw_data") is not False:
        raise AnalysisSnapshotError("raw analysis data is not accepted")

    snapshot_id = payload.get("snapshot_id")
    if not isinstance(snapshot_id, str) or not IDENTIFIER_RE.fullmatch(snapshot_id):
        raise AnalysisSnapshotError("snapshot_id is invalid")
    _positive_millis(payload.get("generated_at"), "generated_at")

    datasets = payload.get("datasets")
    if not isinstance(datasets, list) or not 1 <= len(datasets) <= MAX_DATASETS:
        raise AnalysisSnapshotError("one to four analysis datasets are required")
    categories: set[str] = set()
    node_count = [0]
    for index, dataset in enumerate(datasets):
        if not isinstance(dataset, dict) or set(dataset) != DATASET_KEYS:
            raise AnalysisSnapshotError(
                f"datasets[{index}] fields do not match analysis-snapshot-v1"
            )
        category = dataset.get("category")
        if category not in ALLOWED_ANALYSIS_CATEGORIES or category in categories:
            raise AnalysisSnapshotError("invalid or duplicate analysis category")
        categories.add(category)
        _positive_millis(
            dataset.get("collected_at"),
            f"datasets[{index}].collected_at",
        )
        data = dataset.get("data")
        if not isinstance(data, dict) or not data:
            raise AnalysisSnapshotError(
                f"datasets[{index}].data must be a non-empty object"
            )
        _validate_value(
            data,
            f"datasets[{index}].data",
            depth=0,
            node_count=node_count,
        )
    return payload
