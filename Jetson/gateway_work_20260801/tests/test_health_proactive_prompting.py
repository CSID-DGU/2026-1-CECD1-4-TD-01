from __future__ import annotations

from datetime import datetime, timezone

from app.health_proactive.analysis import HealthRiskAnalyzer
from app.health_proactive.models import DataBundle, ExternalComparison
from app.health_proactive.prompting import Gemma4HealthPromptBuilder
from app.health_proactive.storage import HealthMonitorStore


UTC = timezone.utc


def test_gemma4_prompt_contains_metric_rules_and_explicit_exclusions(tmp_path) -> None:
    store = HealthMonitorStore(tmp_path / "health.sqlite3")
    store.initialize()
    now = datetime(2026, 7, 31, 10, tzinfo=UTC)
    result = HealthRiskAnalyzer(store).analyze(
        DataBundle(
            comparisons=[
                ExternalComparison(
                    "phone_screen_daily_minutes", 600, 300, 0.9,
                    "android_phenotype_summary", now,
                )
            ],
            context={"now": now.isoformat()},
        ),
        now,
    )

    prompt = Gemma4HealthPromptBuilder().build(result)

    assert "<trusted_health_context>" in prompt
    assert "phone_screen_daily_minutes" in prompt
    assert "주로 어떤 일을 했는지 먼저 묻는다" in prompt
    assert "voice_emotion" in prompt
    assert "rppg" in prompt
    assert "facial_expression" in prompt
    assert "gallery" in prompt
    assert "진단하거나 확률을 말하지 않는다" in prompt


def test_bedtime_prompt_includes_sleep_context(tmp_path) -> None:
    store = HealthMonitorStore(tmp_path / "health.sqlite3")
    store.initialize()
    now = datetime(2026, 7, 31, 13, 20, tzinfo=UTC)
    result = HealthRiskAnalyzer(store).analyze(
        DataBundle(
            context={
                "bedroom_light_on": True,
                "target_present": True,
                "expected_sleep_time": "22:30",
            }
        ),
        now,
    )

    prompt = Gemma4HealthPromptBuilder().build(result)

    assert '"expected_sleep_time":"22:30"' in prompt
    assert "bedtime_light" in prompt