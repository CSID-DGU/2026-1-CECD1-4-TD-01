package com.example.counseling

import android.content.Context
import com.example.counseling.llm.ChatMessage
import com.example.counseling.llm.ChatRole
import com.psychocare.data.AppUsageSummary
import com.psychocare.data.CallLogSummary
import com.psychocare.phenotype.AppUsageAnalyzer
import com.psychocare.phenotype.CallLogAnalyzer

data class PhenotypePromptResult(
    val contextText: String?,
    val message: String,
)

suspend fun readPhenotypePromptContext(context: Context): PhenotypePromptResult {
    val callSummary = CallLogAnalyzer(context.applicationContext).analyze()
    val appUsageSummary = AppUsageAnalyzer(context.applicationContext).analyze()
    val contextText = buildPhenotypePromptContext(callSummary, appUsageSummary)
    val message = when {
        contextText != null -> "생활 패턴 요약을 상담 맥락에 포함합니다."
        callSummary == null && appUsageSummary.hasPermission.not() -> "생활 패턴 권한이 없어 상담 맥락에 포함하지 못했습니다."
        callSummary == null -> "통화 기록 권한이 없어 앱 사용 패턴만 확인했습니다."
        appUsageSummary.hasPermission.not() -> "앱 사용 정보 접근 권한이 없어 통화 패턴만 확인했습니다."
        else -> "생활 패턴 데이터가 비어 있습니다."
    }
    return PhenotypePromptResult(contextText, message)
}

suspend fun refreshPhenotypeRagSlot(context: Context): PhenotypePromptResult {
    val result = readPhenotypePromptContext(context)
    if (result.contextText != null) {
        replaceRagSlot(
            context = context,
            slot = RagSlot.Phenotype,
            contextText = result.contextText,
            source = "통화 기록 및 앱 사용 패턴 요약",
        )
    }
    return result
}

fun List<ChatMessage>.withPhenotypeContext(phenotypeContext: String?): List<ChatMessage> {
    if (phenotypeContext.isNullOrBlank()) return this
    val lastUserIndex = indexOfLast { it.role == ChatRole.User }
    if (lastUserIndex < 0) return this
    return mapIndexed { index, message ->
        if (index != lastUserIndex) {
            message
        } else {
            message.copy(
                content = """
                    [동적 분석 맥락 - 생활 패턴]
                    아래 자료는 사용자가 허용한 휴대폰 사용 패턴 요약입니다. 고정 정책의 좌식행동/여가 스크린 시간 기준과 함께 보조 맥락으로만 사용하세요.
                    고립, 회피, 야간 사용, 과도한 스크린 시간이 의심되어도 단정하지 말고 현재 사용자 메시지와 연결될 때만 확인 질문이나 작은 행동 제안으로 다루세요.

                    $phenotypeContext

                    [현재 사용자 메시지]
                    ${message.content}
                """.trimIndent(),
            )
        }
    }
}

private fun buildPhenotypePromptContext(
    callSummary: CallLogSummary?,
    appUsageSummary: AppUsageSummary,
): String? {
    if (callSummary == null && !appUsageSummary.hasPermission) return null
    val callBlock = callSummary?.let {
        """
        통화 패턴:
        - 이번 주 통화 ${it.totalCallsThisWeek}회, 지난 주 통화 ${it.totalCallsPrevWeek}회
        - 이번 주 고유 연락처 ${it.uniqueContactsThisWeek}명
        - 평균 통화 시간 ${it.avgDurationSec.toInt()}초, 부재중 비율 ${"%.0f".format(it.missedCallRate * 100)}%
        - 연속 무연락 ${it.zeroCommunicationStreak}일, 주간 변화 ${"%.1f".format(it.weeklyChangePct)}%
        - 대인교류 급감 경보 ${if (it.isolationAlert) "있음" else "없음"}
        """.trimIndent()
    }
    val usageBlock = appUsageSummary.takeIf { it.hasPermission }?.let {
        val apps = it.topApps.take(6).joinToString(separator = "\n") { app ->
            "- ${app.appName}: ${app.totalTimeMin}분 (${app.category.korean})"
        }.ifBlank { "- 표시할 앱별 사용 시간이 없습니다." }
        """
        앱 사용 패턴:
        - 최근 7일 일평균 스크린타임 ${it.dailyAvgScreenTimeMin}분
        - 일평균 야간 사용 ${it.dailyAvgLateNightMin}분
        - 최장 연속 세션 ${it.longestSingleSessionMin}분
        - 주간 스크린타임 변화 ${"%.1f".format(it.weeklyChangePct)}%
        - 사용시간이 높은 앱:
        $apps
        """.trimIndent()
    }
    return """
        [생활 패턴 요약]
        이 자료는 사용자가 상담에 참고하도록 허용한 휴대폰 사용 패턴입니다. 진단이나 감시처럼 단정하지 말고, 수면/회피/고립/과사용 가능성을 조심스럽게 확인하는 보조 맥락으로만 사용하세요.
        ${callBlock.orEmpty()}
        ${usageBlock.orEmpty()}
    """.trimIndent()
}
