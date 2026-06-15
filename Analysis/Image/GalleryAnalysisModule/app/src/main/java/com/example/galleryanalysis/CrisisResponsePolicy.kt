package com.example.galleryanalysis

class CrisisResponsePolicy {
    fun responseGuide(level: SafetyLevel): String = when (level) {
        SafetyLevel.NONE -> "일반 상담 모드입니다. 변화 신호를 단정하지 말고 사용자의 경험을 확인하는 질문으로 시작하세요."
        SafetyLevel.LOW_OBSERVE -> "위험을 언급하지 말고 몸/마음의 피로, 생활 변화, 최근 부담을 부드럽게 확인하세요."
        SafetyLevel.MEDIUM_CHECK_IN -> "최근 혼자 감당하기 힘든 순간이 있었는지 직접적이지만 부드럽게 확인하세요. 이미지 단서만으로 위기라고 말하지 마세요."
        SafetyLevel.HIGH_SUPPORT -> "LLM 자유응답보다 고정 위기 응답을 우선하세요. 사용자의 말을 믿고, 현재 안전 여부와 곁에 있는 사람/전문기관 연결을 확인하세요."
        SafetyLevel.IMMEDIATE_DANGER -> "즉각적인 위험 가능성이 있습니다. 현재 위치의 긴급 구조/경찰/자살예방상담전화 등 즉시 도움 연결을 안내하세요."
    }

    fun emergencyResourcesKo(level: SafetyLevel): List<String> = when (level) {
        SafetyLevel.HIGH_SUPPORT, SafetyLevel.IMMEDIATE_DANGER -> listOf(
            "자살예방상담전화 109: 자살·자해 위기 상담",
            "119: 생명 또는 신체가 즉시 위험한 응급 상황",
            "112: 폭력·위협·성폭력 등 범죄 위험 상황",
            "여성긴급전화 1366: 성폭력·가정폭력·데이트폭력 등 폭력 피해 상담",
            "해바라기센터: 성폭력 피해 상담·의료·수사·법률 연계"
        )
        else -> emptyList()
    }
}
