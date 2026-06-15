package com.example.counseling

import com.example.counseling.llm.ChatMessage
import com.example.counseling.llm.ChatRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val fixedCounselingPolicy = """
    당신은 한국어로 응답하는 상담 보조자입니다. 진단이나 치료를 대신하지 않고, 사용자가 스스로 상황을 정리하고 다음 행동을 고를 수 있도록 돕습니다.

    [핵심 상담 원칙]
    1. 먼저 사용자의 감정과 상황을 짧게 반영하고, 판단하거나 단정하지 않습니다.
    2. 필요한 경우 한 번에 하나씩 명확한 질문을 합니다.
    3. 사용자의 말에서 사실, 감정, 욕구, 위험 신호를 구분합니다.
    4. 가능한 선택지를 작고 실행 가능한 단계로 제안합니다.
    5. 개인정보, 의료, 법률, 재정 문제는 전문 기관 상담을 권합니다.

    [위기 대응]
    사용자가 자살, 자해, 타해, 학대, 폭력, 긴급한 의료 위험, 극심한 절망감, 구체적인 계획이나 수단을 언급하면 일반 상담보다 안전 확인을 우선합니다.
    즉시 위험이 있거나 혼자 안전을 유지하기 어렵다면 지금 바로 112 또는 119에 연락하거나 가까운 응급실로 가도록 안내합니다.
    한국에서는 자살예방상담전화 109, 정신건강상담전화 1577-0199를 안내합니다.
    미국에 있는 사용자라면 988 Suicide & Crisis Lifeline을 안내합니다.
    위기 상황에서는 사용자가 믿을 수 있는 사람 곁으로 이동하고, 위험한 물건이나 수단에서 거리를 두도록 권합니다.

    [동적 분석 맥락 사용 원칙]
    Health Connect, 생활 패턴, 갤러리 분석, 음성 감정 분석, 과거 대화 검색 결과는 사용자가 허용한 보조 맥락입니다.
    이 자료만으로 사용자의 상태를 진단하거나 감시하듯 단정하지 말고, 현재 사용자 메시지와 일치할 때만 조심스럽게 참고합니다.
    분석 결과가 현재 사용자 말과 다르면 현재 사용자 말을 우선합니다.
    민감한 추정은 "그럴 수 있습니다"보다 "혹시 이런 점도 관련이 있을까요?"처럼 확인 질문으로 다룹니다.

    [청소년 신체활동 및 좌식행동 기준]
    청소년 생활 패턴을 다룰 때는 WHO 5-17세 신체활동 권고를 참고합니다.
    일주일 평균 하루 60분 이상 중등도-고강도 신체활동, 주 3일 이상 고강도 유산소 활동, 주 3일 이상 근육과 뼈를 강화하는 활동을 기본 기준으로 삼습니다.
    좌식행동과 여가 목적의 스크린 시간은 줄이는 방향을 권합니다.
    활동량 부족, 무기력, 흥미 저하, 수면 악화, 장시간 앉아 있음, 과도한 스크린 사용이 보일 때만 자연스럽게 제안합니다.
    권장량을 채우지 못한 사용자를 비난하지 말고, 조금이라도 움직이는 것이 도움이 된다는 원칙에 따라 안전하고 즐겁고 다양한 활동을 적은 양부터 시작해 빈도, 강도, 시간을 점진적으로 늘리도록 돕습니다.
    신체활동은 체력, 심혈관 건강, 뼈 건강뿐 아니라 실행기능, 학업 수행, 우울 증상 감소와도 관련될 수 있으나, 치료나 진단처럼 말하지 않습니다.

    [마음챙김과 안정화]
    사용자가 불안, 압박감, 긴장, 감정 과부하를 말하면 진단이나 치료처럼 말하지 말고, 현재 순간에 주의를 돌리는 짧은 연습을 선택지로 제안합니다.
    예시는 느린 호흡, 몸 감각 알아차리기, 5-4-3-2-1 감각 그라운딩, 짧은 산책, 점진적 근육 이완, 잠시 물 마시기처럼 안전하고 부담이 낮은 방법으로 제한합니다.
    사용자가 트라우마, 공황, 해리, 극심한 불안, 자해 충동을 말하면 눈을 감거나 내면에 오래 머무는 연습을 강요하지 말고, 주변 사물 이름 대기, 발바닥 감각 느끼기, 신뢰할 수 있는 사람에게 연락하기처럼 외부 지향적인 안정화부터 제안합니다.
    증상이 지속되거나 일상 기능을 크게 방해하면 정신건강 전문가나 의료기관 상담을 권합니다.
""".trimIndent()

val defaultUserCustomInstruction = """
    따뜻하지만 과장하지 않고, 간결하게 답하세요.
    필요할 때 **굵게** 강조를 사용하세요.
    사용자가 음성이나 오디오를 보냈다면 들은 내용에 근거해 답하되, 불확실하면 확인 질문을 하세요.
""".trimIndent()

fun buildSystemPromptWithMemories(systemPrompt: String, importantMemories: List<String>): String {
    val customInstruction = normalizeEditableSystemPrompt(systemPrompt)
    return """
        [고정 정책 지침]
        $fixedCounselingPolicy

        [사용자 커스텀 응답 지침]
        ${customInstruction.trim()}

        [기본 중요 기억]
        ${defaultImportantMemories.joinToString(separator = "\n") { "- $it" }}

        ${if (importantMemories.isNotEmpty()) "[사용자가 중요하다고 저장한 개인 맥락]\n${importantMemories.takeLast(20).joinToString(separator = "\n") { "- $it" }}" else ""}

        기본 중요 기억은 앱의 안전한 상담 방식입니다. 사용자가 저장한 개인 맥락은 답변에 필요할 때만 조심스럽게 참고하고, 사용자의 현재 말과 다르면 현재 말을 우선하세요.
    """.trimIndent()
}

fun normalizeEditableSystemPrompt(systemPrompt: String): String {
    val trimmed = systemPrompt.trim()
    if (trimmed.isBlank()) return defaultUserCustomInstruction
    val looksLikeLegacyFullPrompt = trimmed.contains("당신은 한국어로 응답하는 상담 보조자") &&
        trimmed.contains("위기 대응") &&
        trimmed.contains("상담 흐름")
    return if (looksLikeLegacyFullPrompt) defaultUserCustomInstruction else trimmed
}

fun ThinkingMode.next(): ThinkingMode {
    return when (this) {
        ThinkingMode.Auto -> ThinkingMode.On
        ThinkingMode.On -> ThinkingMode.Off
        ThinkingMode.Off -> ThinkingMode.Auto
    }
}

fun HealthPeriod.next(): HealthPeriod {
    return when (this) {
        HealthPeriod.Week -> HealthPeriod.Month
        HealthPeriod.Month -> HealthPeriod.Week
    }
}

fun formatSessionTime(updatedAt: Long): String {
    if (updatedAt <= 0L) return "시간 없음"
    return DateTimeFormatter.ofPattern("MM.dd HH:mm")
        .format(Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault()))
}

fun List<ChatMessage>.withMemoryContext(
    importantMemories: List<String>,
    relevantMemories: List<RelevantMemory>,
): List<ChatMessage> {
    if (importantMemories.isEmpty() && relevantMemories.isEmpty()) return this
    val lastUserIndex = indexOfLast { it.role == ChatRole.User }
    if (lastUserIndex < 0) return this
    val importantContext = importantMemories.takeLast(20).joinToString(separator = "\n") { "- $it" }
    val relevantContext = relevantMemories
        .filter { it.content.isNotBlank() }
        .distinctBy { "${it.role.name}:${it.content}" }
        .take(4)
        .joinToString(separator = "\n") { memory ->
            val role = if (memory.role == ChatRole.User) "사용자" else "상담 보조자"
            val attachment = memory.attachmentLabel?.let { " [$it]" }.orEmpty()
            "- $role$attachment: ${memory.content.take(220)}"
        }
    return mapIndexed { index, message ->
        if (index != lastUserIndex) {
            message
        } else {
            message.copy(
                content = """
                    [동적 검색 맥락 - Memory/RAG]
                    아래 자료는 사용자가 중요하다고 저장한 기억과 현재 메시지에 관련 있을 수 있는 과거 대화 검색 결과입니다.
                    현재 사용자 메시지와 연결될 때만 참고하고, 오래된 내용이 현재 말과 다르면 현재 말을 우선하세요.

                    ${if (importantContext.isNotBlank()) "[사용자가 중요하다고 저장한 맥락]\n$importantContext\n" else ""}
                    ${if (relevantContext.isNotBlank()) "[현재 메시지와 관련 있을 수 있는 과거 대화]\n$relevantContext\n" else ""}

                    [현재 사용자 메시지]
                    ${message.content}
                """.trimIndent(),
            )
        }
    }
}

fun List<ChatMessage>.withHealthContext(healthContext: String?): List<ChatMessage> {
    if (healthContext.isNullOrBlank()) return this
    val lastUserIndex = indexOfLast { it.role == ChatRole.User }
    if (lastUserIndex < 0) return this
    return mapIndexed { index, message ->
        if (index != lastUserIndex) {
            message
        } else {
            message.copy(
                content = """
                    [동적 분석 맥락 - Health Connect]
                    아래 자료는 사용자가 허용한 건강 데이터 요약입니다. 고정 정책의 WHO 청소년 신체활동 기준과 함께 보조 맥락으로만 사용하세요.
                    생활 패턴 개선이 필요해 보이면 하루 60분을 강요하지 말고, 짧고 안전한 활동부터 점진적으로 늘리는 방향으로 제안하세요.

                    $healthContext

                    [현재 사용자 메시지]
                    ${message.content}
                """.trimIndent(),
            )
        }
    }
}

fun List<ChatMessage>.withThinkingInstruction(enabled: Boolean): List<ChatMessage> {
    if (!enabled) return this
    val lastUserIndex = indexOfLast { it.role == ChatRole.User }
    if (lastUserIndex < 0) return this
    return mapIndexed { index, message ->
        if (index != lastUserIndex) {
            message
        } else {
            message.copy(
                content = """
                    [사고 모드]
                    답변하기 전에 내부적으로만 상황을 차분히 검토하세요.
                    내부 사고, 숨은 추론, 단계별 사고 과정, <think>...</think> 형식의 내용은 절대 출력하지 마세요.
                    화면에 보이는 답변은 상담자가 사용자에게 바로 말해도 되는 정제된 최종 답변만 쓰세요.
                    필요한 경우에만 짧은 핵심 근거 1-3개와 실행 가능한 답변을 제시하세요.
                    상담 안전성, 위험 신호, 사용자의 감정, 사실/추정 구분, 이전 맥락과 충돌 여부를 우선 확인하세요.

                    [사용자 메시지]
                    ${message.content}
                """.trimIndent(),
            )
        }
    }
}

fun stripInternalThinking(text: String): String {
    if (text.isBlank()) return text
    var cleaned = text
        .replace(Regex("(?is)<think>.*?</think>"), "")
        .replace(Regex("(?is)<thinking>.*?</thinking>"), "")
    cleaned = cleaned.replace(Regex("(?is)<think>.*$"), "")
    cleaned = cleaned.replace(Regex("(?is)<thinking>.*$"), "")
    cleaned = cleaned
        .replace(Regex("(?im)^\\s*(내부\\s*)?사고\\s*과정\\s*[:：].*$"), "")
        .replace(Regex("(?im)^\\s*숨은\\s*추론\\s*[:：].*$"), "")
        .replace(Regex("(?im)^\\s*최종\\s*답변\\s*[:：]\\s*"), "")
    return cleaned.trim()
}

fun HealthSummary.toPromptContext(): String? {
    if (daily.isEmpty()) return null
    val recentDays = daily.take(7).joinToString(separator = "\n") { day ->
        val heartRate = day.heartRateBpm?.let { "$it bpm" } ?: "데이터 없음"
        "- ${day.date.format(healthPromptDateFormatter)}: 걸음 ${"%,d".format(day.steps)}, 수면 ${"%.1f".format(day.sleepHours)}시간, 평균 심박 $heartRate, 활동 칼로리 ${"%.1f".format(day.activeCaloriesKcal)}kcal, 거리 ${"%.2f".format(day.distanceKm)}km"
    }
    val heartRate = heartRateBpm?.let { "$it bpm" } ?: "데이터 없음"
    return """
        [Health Connect ${period.label} 요약]
        이 자료는 사용자가 상담에 참고하도록 허용한 생활 리듬 요약입니다. 진단이나 단정의 근거로 쓰지 말고, 수면/활동/긴장 패턴을 조심스럽게 확인하는 보조 맥락으로만 사용하세요.
        기간 합계: 걸음 ${"%,d".format(steps)}, 총 소모 칼로리 ${"%.1f".format(caloriesKcal)}kcal, 활동 칼로리 ${"%.1f".format(activeCaloriesKcal)}kcal, 이동 거리 ${"%.2f".format(distanceKm)}km, 수면 ${"%.1f".format(sleepHours)}시간, 평균 심박 $heartRate
        최근 날짜별 기록:
        $recentDays
    """.trimIndent()
}

val healthPromptDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

fun shouldUseThinkingMode(
    mode: ThinkingMode,
    text: String,
    hasAttachment: Boolean,
    importantMemories: List<String>,
): Boolean {
    return when (mode) {
        ThinkingMode.On -> true
        ThinkingMode.Off -> false
        ThinkingMode.Auto -> {
            val normalized = text.lowercase()
            val riskSignals = listOf(
                "죽고", "자살", "자해", "해치", "폭력", "학대", "응급", "위험", "무서워", "살기 싫",
            )
            val complexSignals = listOf(
                "어떻게 해야", "왜", "판단", "결정", "고민", "갈등", "분석", "정리", "비교", "계획", "도와줘",
                "불안", "우울", "화가", "관계", "수면", "운동", "건강", "기억", "전에", "패턴",
            )
            hasAttachment ||
                importantMemories.isNotEmpty() ||
                text.length >= 80 ||
                riskSignals.any { normalized.contains(it) } ||
                complexSignals.any { normalized.contains(it) }
        }
    }
}

fun extractImportantMemory(text: String): String? {
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    if (normalized.length < 6) return null
    val wantsSaved = listOf(
        "기억해",
        "기억해줘",
        "기억해 줘",
        "저장해",
        "저장해줘",
        "저장해 줘",
        "중요해",
        "중요한",
        "잊지마",
        "잊지 마",
    ).any { normalized.contains(it) }
    if (!wantsSaved) return null
    return normalized
        .removePrefix("이거")
        .trim()
        .take(240)
}

