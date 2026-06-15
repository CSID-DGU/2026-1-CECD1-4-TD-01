package com.example.galleryanalysis

import kotlin.math.abs

/**
 * 한 번의 ML Kit 결과를 그대로 믿지 않고, 낮은 신뢰도/UNKNOWN/사회활동 과검출을 반복 검토한다.
 * 시시포스 메커니즘: classify → review → correct → re-audit을 최대 N회 반복한다.
 */
class SisyphusReviewPipeline(
    private val iterations: Int = 3,
    private val timeWindowMillis: Long = 10 * 60 * 1000L
) {
    fun review(input: List<ClassifiedImage>): Pair<List<ClassifiedImage>, SisyphusReviewReport> {
        var current = input.sortedBy { it.image.bestTakenAtMillis ?: 0L }
        val reports = mutableListOf<ReviewIterationReport>()
        var totalCorrected = 0

        repeat(iterations) { index ->
            val beforeUnknown = unknownRatio(current)
            val corrected = mutableListOf<ClassifiedImage>()
            var changed = 0
            val notes = mutableListOf<String>()

            current.forEachIndexed { i, item ->
                var reviewed = item
                val categoryFromScore = correctionFromScoreConflict(reviewed)
                if (categoryFromScore != null && categoryFromScore != reviewed.category) {
                    reviewed = reassign(
                        reviewed,
                        categoryFromScore,
                        0.52f.coerceAtLeast(reviewed.confidence),
                        "score_conflict_correction:${categoryFromScore.name}"
                    )
                    changed++
                }

                // 시간 이웃 보정은 UNKNOWN 또는 카테고리 충돌이 뚜렷한 경우에만 쓴다.
                // 낮은 신뢰도라는 이유만으로 매 반복마다 같은 카테고리를 재보정하지 않는다.
                if (reviewed.category == ActivityCategory.UNKNOWN) {
                    val neighbor = inferFromTemporalNeighbors(current, i)
                    if (neighbor != null && neighbor != reviewed.category) {
                        reviewed = reassign(
                            reviewed,
                            neighbor,
                            0.46f.coerceAtLeast(reviewed.confidence),
                            "temporal_neighbor_support:${neighbor.name}"
                        )
                        changed++
                    }
                }

                corrected += reviewed
            }

            if (changed > 0) {
                notes += "corrected=$changed"
                totalCorrected += changed
            } else {
                notes += "no correction"
            }
            val afterUnknown = unknownRatio(corrected)
            reports += ReviewIterationReport(
                iteration = index + 1,
                beforeUnknownRatio = beforeUnknown,
                afterUnknownRatio = afterUnknown,
                correctedImages = changed,
                notes = notes
            )
            current = corrected
        }

        return current to SisyphusReviewReport(reports, totalCorrected)
    }

    private fun unknownRatio(items: List<ClassifiedImage>): Double =
        if (items.isEmpty()) 1.0 else items.count { it.category == ActivityCategory.UNKNOWN }.toDouble() / items.size

    private fun correctionFromScoreConflict(item: ClassifiedImage): ActivityCategory? {
        val top = item.scoreBreakdown.sortedByDescending { it.rawScore }
        val currentScore = top.firstOrNull { it.category == item.category }?.rawScore ?: 0.0
        fun score(category: ActivityCategory): Double = top.firstOrNull { it.category == category }?.rawScore ?: 0.0

        // 얼굴 0~1개인데 restaurant/cafe 때문에 사회활동으로 과검출된 경우 음식 쪽 우선.
        if (item.category == ActivityCategory.SOCIAL_ACTIVITY && item.faceCount < 2) {
            if (score(ActivityCategory.FOOD_MEAL) >= currentScore * 0.80 && score(ActivityCategory.FOOD_MEAL) >= 0.55) {
                return ActivityCategory.FOOD_MEAL
            }
            if (score(ActivityCategory.NATURE_OUTDOOR) >= currentScore * 0.95 && score(ActivityCategory.NATURE_OUTDOOR) >= 0.70) {
                return ActivityCategory.NATURE_OUTDOOR
            }
        }

        // 실내/정적과 학업이 겹칠 때 책/노트북/문서 근거가 있으면 학업 우선.
        if (item.category == ActivityCategory.INDOOR_STATIC && score(ActivityCategory.STUDY_TASK) >= currentScore * 0.72) {
            val hasStudyEvidence = item.scoreBreakdown
                .firstOrNull { it.category == ActivityCategory.STUDY_TASK }
                ?.evidence
                ?.any { e -> listOf("book", "notebook", "laptop", "paper", "document", "desk").any { e.value.lowercase().contains(it) } }
                ?: false
            if (hasStudyEvidence) return ActivityCategory.STUDY_TASK
        }

        // UNKNOWN인데 상위 점수가 낮지만 의미 있는 근거가 있으면 살려낸다.
        if (item.category == ActivityCategory.UNKNOWN) {
            val candidate = top.firstOrNull { it.rawScore >= 0.55 }
            if (candidate != null) return candidate.category
        }

        return null
    }

    private fun inferFromTemporalNeighbors(items: List<ClassifiedImage>, index: Int): ActivityCategory? {
        val targetTime = items[index].image.bestTakenAtMillis ?: return null
        val neighbors = listOfNotNull(items.getOrNull(index - 2), items.getOrNull(index - 1), items.getOrNull(index + 1), items.getOrNull(index + 2))
            .filter { it.category != ActivityCategory.UNKNOWN }
            .filter { neighbor ->
                val t = neighbor.image.bestTakenAtMillis ?: return@filter false
                abs(t - targetTime) <= timeWindowMillis
            }
        if (neighbors.isEmpty()) return null
        val category = neighbors.groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key ?: return null
        return if (neighbors.count { it.category == category } >= 2 || neighbors.size == 1 && neighbors.first().confidence >= 0.70f) category else null
    }

    private fun reassign(item: ClassifiedImage, category: ActivityCategory, confidence: Float, note: String): ClassifiedImage {
        val addedEvidence = CategoryEvidence(EvidenceSource.REVIEW_CORRECTION, note, 0.35)
        val updatedBreakdown = item.scoreBreakdown.map { score ->
            if (score.category == category) {
                score.copy(
                    rawScore = score.rawScore + 0.35,
                    evidence = (listOf(addedEvidence) + score.evidence).take(8)
                )
            } else score
        }
        return item.copy(
            category = category,
            confidence = confidence,
            scoreBreakdown = updatedBreakdown,
            reviewNotes = item.reviewNotes + note
        )
    }
}
