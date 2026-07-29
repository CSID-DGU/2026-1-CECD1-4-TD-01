package com.example.counseling

import com.example.counseling.voiceemotion.VoiceEmotion

/**
 * Android에서 Jetson 적응형 상담에 넘길 신호를 만드는 기준입니다.
 *
 * Jetson 서버의 개인 기준선·학습률·상황별 프롬프트는
 * `Jetson/adaptive_policy.ini`에서 수정합니다. 이 파일은 서버에 보내기 전
 * 휴대폰 안에서 수행하는 주제 분류, 낮은 신뢰도 제외, 텍스트 정서 fallback,
 * 상담 전후 연결 시간만 모아 둡니다.
 *
 * 이 값을 수정하면 APK를 다시 빌드하고 설치해야 합니다.
 */
internal object JetsonAdaptiveClientPolicy {
    /** Jetson 응답이 비정상적으로 커질 때 메모리 사용을 제한합니다. */
    const val MAX_CONTEXT_RESPONSE_BYTES = 128 * 1024

    /** 마지막 사용자 메시지에 삽입할 Jetson 맥락의 최대 문자 수입니다. */
    const val MAX_ADAPTIVE_PROMPT_CHARS = 12_000

    /** 한 번 요청할 맥락 카드 수입니다. Jetson 설정의 max_cards보다 클 수 없습니다. */
    const val REQUESTED_CONTEXT_CARDS = 3

    /** 휴대폰과 Jetson 시계가 약간 다른 경우 허용할 최대 오차입니다. */
    const val MAX_CLOCK_SKEW_MS = 5L * 60L * 1000L

    /**
     * 음성 또는 텍스트 정서를 적응 정책에 사용할 최소 신뢰도입니다.
     * 낮추면 더 많은 결과를 얻지만 잘못된 정서 연결도 늘어날 수 있습니다.
     */
    const val MIN_USABLE_EMOTION_CONFIDENCE = 0.45

    /** 사용자 메시지 길이를 원문 대신 SHORT/MEDIUM/LONG으로 나누는 기준입니다. */
    const val SHORT_MESSAGE_MAX_CHARS = 39
    const val MEDIUM_MESSAGE_MAX_CHARS = 159

    /** fallback 전략을 고르는 간단한 정서 경계입니다. */
    const val POSITIVE_FALLBACK_VALENCE = 0.25
    const val HIGH_AROUSAL_FALLBACK = 0.60

    /** 텍스트 정서 fallback의 점수 및 신뢰도 구성값입니다. */
    const val TEXT_POSITIVE_VALENCE_WEIGHT = 0.72
    const val TEXT_CALM_VALENCE_WEIGHT = 0.55
    const val TEXT_NEGATIVE_LOW_VALENCE_WEIGHT = 0.72
    const val TEXT_NEGATIVE_HIGH_VALENCE_WEIGHT = 0.82
    const val TEXT_POSITIVE_AROUSAL_WEIGHT = 0.52
    const val TEXT_CALM_AROUSAL_WEIGHT = 0.16
    const val TEXT_NEGATIVE_LOW_AROUSAL_WEIGHT = 0.34
    const val TEXT_NEGATIVE_HIGH_AROUSAL_WEIGHT = 0.86
    const val TEXT_INTENSIFIER_AROUSAL_STEP = 0.04
    const val TEXT_INTENSIFIER_AROUSAL_MAX = 0.12
    const val TEXT_CONFIDENCE_BASE = 0.38
    const val TEXT_CONFIDENCE_PER_CUE = 0.10
    const val TEXT_INTENSIFIER_CONFIDENCE_STEP = 0.02
    const val TEXT_INTENSIFIER_CONFIDENCE_MAX = 0.08
    const val TEXT_CONFIDENCE_MAX = 0.72

    /**
     * 키워드는 진단용이 아닙니다. 현재 대화와 관련 있는 Jetson 카드의
     * 도메인을 좁히기 위한 로컬 검색 규칙입니다.
     */
    val TOPIC_KEYWORDS: LinkedHashMap<String, List<String>> = linkedMapOf(
        "SLEEP_AND_ROUTINE" to listOf("잠", "수면", "새벽", "피곤", "꿈"),
        "PHYSICAL_ACTIVITY" to listOf("운동", "걸음", "걷", "활동", "달리", "헬스"),
        "SEDENTARY_AND_POSTURE" to listOf("자세", "허리", "목", "어깨", "앉아"),
        "PHYSIOLOGICAL_STATE" to listOf("심박", "심장", "호흡", "긴장", "두근"),
        "SOCIAL_CONNECTION" to listOf("친구", "가족", "사람", "외롭", "연락", "관계"),
        "SCHOOL_OR_WORK_LOAD" to listOf("학교", "수업", "시험", "과제", "공부", "일"),
        "MEAL_ROUTINE" to listOf("밥", "식사", "먹", "배고"),
        "DAILY_ROUTINE" to listOf("집", "외출", "귀가", "나갔", "들어왔"),
    )
    const val MAX_TOPIC_DOMAINS = 4
    const val DEFAULT_TOPIC_DOMAIN = "GENERAL_CHECK_IN"

    /**
     * 텍스트 정서는 음성 결과가 없을 때만 사용하는 제한적인 fallback입니다.
     * 부정형("불안하지 않아")은 JetsonAdaptiveContext.kt에서 제외합니다.
     */
    val POSITIVE_CUES = listOf(
        "좋아", "기뻐", "행복", "즐거", "고마", "뿌듯", "기대돼",
        "설레", "만족", "웃었", "잘됐", "괜찮아",
    )
    val CALM_CUES = listOf(
        "편안", "차분", "안심", "안정", "마음이 놓", "진정됐", "후련",
    )
    val NEGATIVE_LOW_CUES = listOf(
        "슬퍼", "우울", "힘들", "지쳐", "외로", "무기력", "괴로",
        "허무", "피곤", "속상", "눈물", "의욕이 없",
    )
    val NEGATIVE_HIGH_CUES = listOf(
        "화나", "짜증", "불안", "무서", "두려", "답답", "긴장",
        "스트레스", "초조", "두근", "미치겠", "폭발", "숨막",
    )
    val INTENSIFIERS = listOf("너무", "정말", "진짜", "엄청", "완전", "매우")

    /** 음성 감정 다섯 범주를 valence/arousal 좌표로 바꾸는 표입니다. */
    val VOICE_EMOTION_COORDINATES = mapOf(
        VoiceEmotion.Happy to (0.75 to 0.55),
        VoiceEmotion.Sad to (-0.70 to 0.35),
        VoiceEmotion.Angry to (-0.75 to 0.85),
        VoiceEmotion.Fearful to (-0.65 to 0.80),
        VoiceEmotion.Neutral to (0.0 to 0.25),
    )
}

/**
 * 답변 전 정서와 다음 발화 정서를 같은 상담 결과로 연결할 최대 시간입니다.
 * Jetson/adaptive_policy.ini의 max_session_outcome_interval_hours와 맞춰 주세요.
 */
internal const val MAX_JETSON_OUTCOME_INTERVAL_MS = 6L * 60L * 60L * 1000L
