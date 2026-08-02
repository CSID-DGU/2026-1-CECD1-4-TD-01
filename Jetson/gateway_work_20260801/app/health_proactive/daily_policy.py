from __future__ import annotations

import json
from datetime import date, timedelta

from .analysis import LEVEL_RANK
from .models import AlertLevel, AnalysisResult, DailyPolicy
from .prompting import CATEGORY_GUIDANCE, METRIC_GUIDANCE


class DailyPolicyBuilder:
    """Create one stable, non-diagnostic LLM policy from the prior 21 days."""

    def __init__(self, analysis_days: int = 21):
        self.analysis_days = max(7, min(31, int(analysis_days)))

    def build(
        self,
        result: AnalysisResult,
        policy_day: date,
        *,
        generated_while_asleep: bool,
        generation_reason: str,
    ) -> DailyPolicy:
        window_end = policy_day - timedelta(days=1)
        window_start = policy_day - timedelta(days=self.analysis_days)
        clusters = [item for item in result.clusters if not item.gated][:4]
        concerning = [
            item
            for item in result.assessments
            if item.level != AlertLevel.NORMAL and not item.gated_categories
        ][:10]
        risk_level = (
            max(clusters, key=lambda item: LEVEL_RANK[item.level]).level
            if clusters
            else AlertLevel.NORMAL
        )
        risk_score = max((item.score for item in clusters), default=0.0)

        recommendations = [
            "사용자가 먼저 말하거나 능동 상담이 시작될 때 오늘의 생활 맥락을 일관되게 반영한다.",
            "한 번에 주제 하나만 다루고 관찰과 사용자의 실제 경험을 구분해 질문한다.",
            "제안 전에 통증·어지럼·피로 등 안전과 사용자의 동의를 먼저 확인한다.",
        ]
        prohibitions = [
            "센서·생활 지표만으로 질환, 우울, 치매, 중독, 고립을 진단하거나 확률을 말하지 않는다.",
            "내부 위험 점수·카테고리·센서명을 사용자에게 그대로 읽지 않는다.",
            "사용자 확인 없이 조명·일정·연락·약물·운동을 실행하거나 강요하지 않는다.",
        ]
        for cluster in clusters[:3]:
            guidance = CATEGORY_GUIDANCE.get(cluster.category, {})
            if guidance.get("flow"):
                recommendations.append(guidance["flow"])
            if guidance.get("forbidden"):
                prohibitions.append(guidance["forbidden"])
        for item in concerning[:4]:
            guidance = METRIC_GUIDANCE.get(item.metric, {})
            if guidance.get("ask"):
                recommendations.append(guidance["ask"])
            if guidance.get("never"):
                prohibitions.append(guidance["never"])

        recommendations = list(dict.fromkeys(recommendations))[:8]
        prohibitions = list(dict.fromkeys(prohibitions))[:8]
        analysis = {
            "risk_clusters": [item.to_dict() for item in clusters],
            "metric_assessments": [item.to_dict() for item in concerning],
            "source_status": result.source_status,
            "excluded_sources": [
                "voice_emotion",
                "rppg",
                "facial_expression",
                "gallery",
            ],
        }
        trusted = {
            "schema": "onmom.daily-counseling-policy.v1",
            "policy_day": policy_day.isoformat(),
            "analysis_window": {
                "start": window_start.isoformat(),
                "end": window_end.isoformat(),
                "days": self.analysis_days,
            },
            "risk_level": risk_level.value,
            "risk_score_internal_only": risk_score,
            "risk_clusters_internal_only": analysis["risk_clusters"],
            "recommendations": recommendations,
            "prohibitions": prohibitions,
        }
        context_json = json.dumps(trusted, ensure_ascii=False, separators=(",", ":"))
        prompt = f"""
<daily_counseling_policy>
{context_json}
</daily_counseling_policy>
<daily_policy_rules>
- 이 정책은 {policy_day.isoformat()} 하루 동안 사용자 선제 발화와 사용자 시작 대화 모두에 적용한다.
- 21일 관찰값은 대화 방향을 고르는 참고자료일 뿐 진단 근거가 아니다.
- recommendations를 우선하되 사용자의 현재 답변과 안전 상태가 항상 더 중요하다.
- prohibitions는 예외 없이 지킨다.
- 위험이 감지돼도 수치나 감시 사실부터 말하지 말고 중립적인 안부 질문으로 확인한다.
- 사용자가 거절하면 즉시 주제를 멈추고 재촉하지 않는다.
</daily_policy_rules>
""".strip()
        return DailyPolicy(
            policy_day=policy_day,
            generated_at=result.generated_at,
            window_start=window_start,
            window_end=window_end,
            generated_while_asleep=generated_while_asleep,
            generation_reason=generation_reason,
            risk_level=risk_level,
            risk_score=round(risk_score, 1),
            recommendations=tuple(recommendations),
            prohibitions=tuple(prohibitions),
            system_prompt=prompt,
            analysis=analysis,
        )
