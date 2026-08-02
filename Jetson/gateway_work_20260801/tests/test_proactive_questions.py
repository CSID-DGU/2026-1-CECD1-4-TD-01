from __future__ import annotations

import json
from datetime import datetime, timezone

from app.proactive_questions import (
    FALLBACK_QUESTION,
    ProactiveQuestionSelector,
)


def _write_payload(path, summary: str, *, generated_at: float | None = None) -> None:
    timestamp = generated_at
    if timestamp is None:
        timestamp = datetime.now(timezone.utc).timestamp() * 1000
    path.write_text(
        json.dumps(
            {
                "generated_at": timestamp,
                "summaries": [
                    {
                        "category": "GALLERY",
                        "summary": summary,
                    }
                ],
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )


def test_selects_active_focus_user_question(tmp_path):
    path = tmp_path / "latest.json"
    _write_payload(
        path,
        """
[ACTIVE FOCUS - ASK NOW]
- GENERAL_CHECK_IN: 일반 안부
  userQuestion=요즘 생활에서 달라진 점이 있다면 편하게 이야기해 줄래요?

[SUPPORTING FOCUS - DO NOT ASK YET]
- none
""",
    )

    selected = ProactiveQuestionSelector(path).select()

    assert selected.text == "요즘 생활에서 달라진 점이 있다면 편하게 이야기해 줄래요?"
    assert selected.source == "derived_insights"
    assert selected.topic == "GENERAL_CHECK_IN"


def test_sleep_question_is_not_hardcoded_and_only_used_when_selected(tmp_path):
    path = tmp_path / "latest.json"
    _write_payload(
        path,
        """
[ACTIVE FOCUS - ASK NOW]
- SLEEP_CONTINUITY: 최근 수면 신호
  userQuestion=최근 잠을 자다가 자주 깨는 날이 있었나요?
""",
    )

    selected = ProactiveQuestionSelector(path).select()

    assert selected.text == "최근 잠을 자다가 자주 깨는 날이 있었나요?"
    assert selected.topic == "SLEEP_CONTINUITY"


def test_stale_or_invalid_payload_uses_neutral_check_in(tmp_path):
    path = tmp_path / "latest.json"
    _write_payload(path, "userQuestion=어젯밤에는 몇 번 정도 깨셨나요?", generated_at=1)

    selected = ProactiveQuestionSelector(path).select()

    assert selected.text == FALLBACK_QUESTION
    assert selected.topic == "GENERAL_CHECK_IN"
