from __future__ import annotations

from datetime import timedelta, timezone, tzinfo
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


def load_timezone(name: str) -> tzinfo:
    """Load an IANA zone, with a portable fallback for the deployed KST zone."""

    try:
        return ZoneInfo(name)
    except ZoneInfoNotFoundError:
        if name == "Asia/Seoul":
            return timezone(timedelta(hours=9), name)
        raise
