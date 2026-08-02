from __future__ import annotations

from datetime import date, datetime, timezone

from app.health_proactive.analysis import HealthRiskAnalyzer, robust_modified_z
from app.health_proactive.models import (
    AlertLevel,
    DataBundle,
    DailyMetric,
    ExternalComparison,
    RiskCategory,
    TriggerKind,
)
from app.health_proactive.storage import HealthMonitorStore


UTC = timezone.utc


def test_robust_z_uses_personal_baseline() -> None:
    assert robust_modified_z(100, [10, 10, 10, 10]) == 9.0


def test_multiple_digital_signals_select_one_proactive_topic(tmp_path) -> None:
    store = HealthMonitorStore(tmp_path / "health.sqlite3")
    store.initialize()
    analyzer = HealthRiskAnalyzer(store)
    now = datetime(2026, 7, 31, 10, tzinfo=UTC)
    bundle = DataBundle(
        comparisons=[
            ExternalComparison(
                "phone_screen_daily_minutes", 600, 300, 0.9,
                "android_phenotype_summary", now,
            ),
            ExternalComparison(
                "phone_longest_session_minutes", 130, 50, 0.9,
                "android_phenotype_summary", now,
            ),
        ],
        context={"now": now.isoformat()},
    )

    result = analyzer.analyze(bundle, now)

    digital = next(
        item for item in result.clusters
        if item.category == RiskCategory.DIGITAL_WELLBEING
    )
    assert digital.level == AlertLevel.CONCERN
    assert len(digital.metrics) == 2
    assert result.decision.should_speak
    assert result.decision.trigger == TriggerKind.MULTI_SIGNAL
    assert result.decision.category == RiskCategory.DIGITAL_WELLBEING
    assert result.decision.fallback_opening.count("?") == 1

    store.record_outreach("s1", result.decision, result.decision.fallback_opening, created_at=now)
    cooled_down = analyzer.analyze(bundle, now)
    assert not cooled_down.decision.should_speak


def test_absolute_only_summary_does_not_claim_a_period_comparison(tmp_path) -> None:
    store = HealthMonitorStore(tmp_path / "health.sqlite3")
    store.initialize()
    analyzer = HealthRiskAnalyzer(store)
    now = datetime(2026, 7, 31, 10, tzinfo=UTC)
    bundle = DataBundle(
        comparisons=[
            ExternalComparison(
                "phone_longest_session_minutes",
                57,
                57,
                0.8,
                "android_phenotype_summary",
                now,
                {"baseline_available": False},
            )
        ]
    )

    result = analyzer.analyze(bundle, now)
    assessment = result.assessments[0]

    assert assessment.level == AlertLevel.WATCH
    assert assessment.baseline_median is None
    assert "주간 요약의 현재 기간과 이전 기간 비교" not in assessment.reasons


def test_bedtime_light_uses_expected_sleep_time(tmp_path) -> None:
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

    assert result.decision.should_speak
    assert result.decision.trigger == TriggerKind.BEDTIME_LIGHT

def test_bedtime_deviation_uses_existing_robust_anomaly_policy(tmp_path) -> None:
    store = HealthMonitorStore(tmp_path / "health.sqlite3")
    store.initialize()
    bundle = DataBundle(
        daily_metrics=[
            DailyMetric("sleep_bedtime_deviation_minutes", date(2026, 7, day), value, 0.9, "android_sleep_sessions")
            for day, value in [(20, 0), (21, 5), (22, 5), (23, 3), (24, 2), (25, 480)]
        ]
    )

    result = HealthRiskAnalyzer(store).analyze(bundle, datetime(2026, 7, 27, 10, tzinfo=UTC))

    assessment = next(item for item in result.assessments if item.metric == "sleep_bedtime_deviation_minutes")
    assert assessment.level == AlertLevel.CONCERN
    assert assessment.modified_z is not None and assessment.modified_z >= 3.5
    assert result.decision.category == RiskCategory.SLEEP_CIRCADIAN