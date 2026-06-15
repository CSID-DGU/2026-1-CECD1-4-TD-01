package com.example.counseling.galleryanalysis

/** 사용자 직접 발화 기반 위기 신호 탐지. 이미지보다 이 결과가 우선한다. */
class CrisisKeywordDetector {
    data class TextRiskResult(
        val level: SafetyLevel,
        val trigger: String,
        val matchedKeywords: List<String>,
        val reasons: List<String>
    )

    private val immediateKeywords = listOf(
        "지금 죽", "오늘 죽", "곧 죽", "약을 먹었", "약 먹었", "칼 들고", "옥상", "뛰어내릴", "목을 맬", "목 매",
        "지금 위험", "가해자가 앞", "집에 가면 또", "지금 맞고", "방금 당했", "살려줘", "112", "119"
    )
    private val suicideKeywords = listOf(
        "죽고 싶", "자살", "사라지고 싶", "끝내고 싶", "살기 싫", "자해", "해치고 싶", "죽을까", "극단적 선택",
        "suicide", "kill myself", "self harm", "want to die"
    )
    private val sexualViolenceKeywords = listOf(
        "성폭행", "성폭력", "강제로 당", "원하지 않았는데", "싫다고 했는데", "만졌어", "추행", "강간", "협박받", "사진으로 협박",
        "sexual assault", "raped", "molested", "forced me"
    )
    private val violenceKeywords = listOf(
        "맞았", "폭행", "때렸", "감금", "협박", "스토킹", "집에 가기 무서", "가정폭력", "데이트폭력",
        "abused", "hit me", "threatened", "stalking"
    )

    fun detect(userText: String?): TextRiskResult {
        val text = userText.orEmpty().lowercase()
        if (text.isBlank()) {
            return TextRiskResult(SafetyLevel.NONE, "NO_TEXT", emptyList(), emptyList())
        }
        fun hits(keys: List<String>) = keys.filter { text.contains(it.lowercase()) }

        val immediate = hits(immediateKeywords)
        if (immediate.isNotEmpty()) {
            return TextRiskResult(
                level = SafetyLevel.IMMEDIATE_DANGER,
                trigger = "DIRECT_IMMEDIATE_DANGER_TEXT",
                matchedKeywords = immediate,
                reasons = listOf("구체적이고 즉각적인 위험 가능성이 있는 직접 발화가 감지되었습니다.")
            )
        }
        val suicide = hits(suicideKeywords)
        val sexual = hits(sexualViolenceKeywords)
        val violence = hits(violenceKeywords)
        val high = suicide + sexual + violence
        if (high.isNotEmpty()) {
            val domains = buildList {
                if (suicide.isNotEmpty()) add("자살/자해 직접 발화")
                if (sexual.isNotEmpty()) add("성폭력 피해 직접 발화")
                if (violence.isNotEmpty()) add("폭력/위협 직접 발화")
            }
            return TextRiskResult(
                level = SafetyLevel.HIGH_SUPPORT,
                trigger = "DIRECT_HIGH_RISK_TEXT",
                matchedKeywords = high,
                reasons = domains
            )
        }
        return TextRiskResult(SafetyLevel.NONE, "NO_HIGH_RISK_TEXT", emptyList(), emptyList())
    }
}
