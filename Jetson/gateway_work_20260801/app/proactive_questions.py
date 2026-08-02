from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


FALLBACK_QUESTION = "오늘은 어떻게 지내고 계세요?"
_ACTIVE_BLOCK = re.compile(
    r"\[ACTIVE FOCUS - ASK NOW\](.*?)(?=\n\s*\n\[|\Z)",
    re.DOTALL,
)
_USER_QUESTION = re.compile(r"^\s*userQuestion=(.+?)\s*$", re.MULTILINE)
_ACTIVE_TOPIC = re.compile(r"^\s*-\s*([A-Z][A-Z0-9_]*)\s*:", re.MULTILINE)
_SUGGESTED_MESSAGE = re.compile(
    r"\[SUGGESTED USER-FACING MESSAGE\]\s*(.+?)(?=\n\s*\n\[|\Z)",
    re.DOTALL,
)


@dataclass(frozen=True)
class OpeningQuestion:
    text: str
    source: str
    topic: str


class ProactiveQuestionSelector:
    """Select the opening question produced by the derived-insights engine."""

    def __init__(self, path: Path, max_age_hours: float = 168.0):
        self.path = path
        self.max_age_hours = max(1.0, max_age_hours)

    def select(self) -> OpeningQuestion:
        payload = self._load_payload()
        if payload is None or not self._is_fresh(payload):
            return self._fallback()

        summaries = payload.get("summaries")
        if not isinstance(summaries, list):
            return self._fallback()

        ordered = sorted(
            (item for item in summaries if isinstance(item, dict)),
            key=lambda item: 0 if item.get("category") == "GALLERY" else 1,
        )
        for item in ordered:
            summary = item.get("summary")
            if not isinstance(summary, str):
                continue
            selected = self._from_summary(summary)
            if selected is not None:
                return selected
        return self._fallback()

    def health(self) -> dict[str, Any]:
        selected = self.select()
        return {
            "available": self.path.is_file(),
            "source": selected.source,
            "topic": selected.topic,
        }

    def _load_payload(self) -> dict[str, Any] | None:
        try:
            if not self.path.is_file() or self.path.stat().st_size > 2_000_000:
                return None
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            return None
        return payload if isinstance(payload, dict) else None

    def _is_fresh(self, payload: dict[str, Any]) -> bool:
        generated_at = payload.get("generated_at")
        if not isinstance(generated_at, (int, float)):
            return False
        timestamp = float(generated_at)
        if timestamp > 10_000_000_000:
            timestamp /= 1000.0
        now = datetime.now(timezone.utc).timestamp()
        age_hours = (now - timestamp) / 3600.0
        return -24.0 <= age_hours <= self.max_age_hours

    def _from_summary(self, summary: str) -> OpeningQuestion | None:
        block_match = _ACTIVE_BLOCK.search(summary)
        if block_match:
            block = block_match.group(1)
            question_match = _USER_QUESTION.search(block)
            if question_match:
                question = self._clean_question(question_match.group(1))
                if question:
                    topic_match = _ACTIVE_TOPIC.search(block)
                    topic = topic_match.group(1) if topic_match else "ACTIVE_FOCUS"
                    return OpeningQuestion(question, "derived_insights", topic)

        suggested_match = _SUGGESTED_MESSAGE.search(summary)
        if suggested_match:
            question = self._clean_question(suggested_match.group(1))
            if question:
                return OpeningQuestion(
                    question,
                    "derived_insights",
                    "SUGGESTED_MESSAGE",
                )
        return None

    @staticmethod
    def _clean_question(value: str) -> str | None:
        question = " ".join(value.split()).strip("\"' ")
        if not 8 <= len(question) <= 240:
            return None
        if question.count("?") > 1:
            return None
        if not question.endswith("?"):
            question = question.rstrip(".!") + "?"
        return question

    @staticmethod
    def _fallback() -> OpeningQuestion:
        return OpeningQuestion(
            FALLBACK_QUESTION,
            "fallback",
            "GENERAL_CHECK_IN",
        )
