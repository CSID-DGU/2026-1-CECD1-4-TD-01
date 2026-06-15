package com.example.galleryanalysis

/** 분석 품질/성능 지표를 계산한다. 결과가 믿을 만한지 UI와 Logcat에서 판단할 수 있게 한다. */
class QualityEvaluator {
    fun evaluate(
        totalImages: Int,
        filtered: List<Pair<GalleryImage, FilterResult>>,
        classified: List<ClassifiedImage>,
        events: List<ActivityEvent>,
        elapsedMs: Long
    ): AnalysisQualityReport {
        val analyzable = filtered.count { it.second.isAnalyzable }
        val analyzed = classified.size.coerceAtLeast(1)
        val known = classified.count { it.category != ActivityCategory.UNKNOWN }
        val lowConfidence = classified.count { it.isLowConfidence || it.category == ActivityCategory.UNKNOWN }
        val mlKitSuccess = classified.count { it.mlKitSucceeded }
        val avgConfidence = classified.map { it.confidence }.average().takeIf { !it.isNaN() } ?: 0.0
        val excluded = filtered.filter { !it.second.isAnalyzable }.groupingBy { it.second.status }.eachCount()
        val eventReduction = if (known == 0) 0.0 else 1.0 - (events.size.toDouble() / known.toDouble())
        val ips = if (elapsedMs <= 0) 0.0 else classified.size * 1000.0 / elapsedMs

        val warnings = mutableListOf<String>()
        val coverage = known.toDouble() / analyzed
        val unknownRatio = 1.0 - coverage
        if (classified.size < 30) warnings += "분석된 사진 수가 적어 장기 선호/변화 추정 신뢰도가 낮습니다."
        if (coverage < 0.55) warnings += "UNKNOWN 비율이 높습니다. ML Kit 기본 라벨만으로 구분이 어려운 사진이 많을 수 있습니다."
        if (avgConfidence < 0.45) warnings += "평균 분류 신뢰도가 낮습니다. 결과를 상담 질문 후보로만 사용하세요."
        if (analyzable > 0 && classified.size < analyzable) warnings += "일부 분석 가능 이미지가 디코딩/ML Kit 오류 또는 maxImages 제한으로 분석되지 않았습니다."

        return AnalysisQualityReport(
            mlKitSuccessRate = mlKitSuccess.toDouble() / analyzed,
            classificationCoverage = coverage,
            unknownRatio = unknownRatio,
            lowConfidenceRatio = lowConfidence.toDouble() / analyzed,
            averageConfidence = avgConfidence,
            eventReductionRatio = eventReduction.coerceIn(0.0, 1.0),
            imagesPerSecond = ips,
            excludedByReason = excluded,
            warnings = warnings
        )
    }
}
