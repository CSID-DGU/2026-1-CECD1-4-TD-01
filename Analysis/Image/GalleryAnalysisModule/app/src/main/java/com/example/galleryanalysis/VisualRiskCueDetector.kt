package com.example.galleryanalysis

import kotlin.math.min

/**
 * v3.4.4 Safety-overlay visual cue detector.
 *
 * ML Kit 기본 라벨은 자해/상처를 직접 이해하지 못하므로, 단일 라벨 하나가 아니라
 * 1) 직접 상처/붕대/부상 라벨,
 * 2) 의료/처치 라벨 + 신체 부위 라벨,
 * 3) 반복되는 신체 클로즈업 맥락
 * 을 조합해서 "안전 확인이 필요한 보조 단서"를 만든다.
 *
 * 중요한 제한:
 * - 이미지 단독으로 자해/폭력/성폭력 피해를 확정하지 않는다.
 * - 이 detector는 SafetyDecisionEngine에서 최대 MEDIUM_CHECK_IN까지만 올리는 보조 입력이다.
 * - medical_decoy(약국/체온계/구급상자) 같은 일반 의료 이미지는 injury cue로 올리지 않도록 분리한다.
 */
class VisualRiskCueDetector {
    private data class CueAccumulator(
        val type: VisualRiskCueType,
        val imageIds: MutableSet<Long> = mutableSetOf(),
        val evidence: MutableList<String> = mutableListOf(),
        var weight: Double = 0.0
    )

    private val medicationKeywords = listOf(
        "pill", "pills", "medicine", "medication", "prescription", "pharmacy", "drugstore", "tablet bottle"
    )

    private val medicalKeywords = listOf(
        "hospital", "clinic", "doctor", "medical", "ambulance", "emergency room", "nurse",
        "stretcher", "first aid kit", "first aid", "thermometer", "band aid", "plaster"
    )

    private val directInjuryKeywords = listOf(
        "bandage", "wound", "injury", "injured", "blood", "bleeding", "bruise", "bruising",
        "cast", "splint", "sling", "gauze", "scar", "cut", "scratch", "scrape", "abrasion",
        "lesion", "rash", "stitches", "suture", "dressing", "plaster", "band aid", "pain"
    )

    private val sharpKeywords = listOf(
        "knife", "blade", "razor", "cutter", "box cutter", "utility knife"
    )

    private val bodyPartKeywords = listOf(
        "hand", "hands", "arm", "arms", "wrist", "finger", "fingers", "elbow", "leg", "knee",
        "skin", "human body", "body part", "person", "people", "face", "close-up", "closeup"
    )

    fun detect(classified: List<ClassifiedImage>): List<VisualRiskCue> {
        if (classified.isEmpty()) return emptyList()
        val acc = mutableMapOf<VisualRiskCueType, CueAccumulator>()
        fun bucket(type: VisualRiskCueType): CueAccumulator = acc.getOrPut(type) { CueAccumulator(type) }

        classified.forEach { image ->
            val text = evidenceText(image)
            val medicationHits = hits(text, medicationKeywords)
            val medicalHits = hits(text, medicalKeywords)
            val injuryHits = hits(text, directInjuryKeywords)
            val sharpHits = hits(text, sharpKeywords)
            val bodyHits = hits(text, bodyPartKeywords)

            if (medicationHits.isNotEmpty()) {
                add(bucket(VisualRiskCueType.POSSIBLE_MEDICATION), image, medicationHits, 0.56, "medication-label")
            }
            if (medicalHits.isNotEmpty()) {
                add(bucket(VisualRiskCueType.POSSIBLE_MEDICAL_CONTEXT), image, medicalHits, 0.48, "medical-context")
            }
            if (sharpHits.isNotEmpty()) {
                add(bucket(VisualRiskCueType.POSSIBLE_SHARP_OBJECT), image, sharpHits, 0.52, "sharp-object-label")
            }

            // 1) Direct injury labels: one image can be enough for LOW_OBSERVE; repeated evidence can become MEDIUM_CHECK_IN.
            if (injuryHits.isNotEmpty()) {
                val bodyBonus = if (bodyHits.isNotEmpty()) 0.22 else 0.0
                add(bucket(VisualRiskCueType.POSSIBLE_INJURY_CONTEXT), image, injuryHits + bodyHits.take(2), 0.86 + bodyBonus, "direct-injury-label")
            }

            // 2) Medical/care context on a body part: stronger than ordinary medical decoy, but still not a diagnosis.
            if (medicalHits.isNotEmpty() && bodyHits.isNotEmpty() && !isOrdinaryMedicalDecoyOnly(text)) {
                add(bucket(VisualRiskCueType.POSSIBLE_INJURY_CONTEXT), image, medicalHits.take(2) + bodyHits.take(2), 0.66, "body-care-combination")
            }

            // 3) Repeated body-part closeups without social context: weak cue only; requires multiple images downstream.
            val closeBodyOnly = bodyHits.size >= 2 && image.faceCount == 0 && image.category !in setOf(
                ActivityCategory.SOCIAL_ACTIVITY,
                ActivityCategory.PHYSICAL_ACTIVITY,
                ActivityCategory.FOOD_MEAL,
                ActivityCategory.PET_EMOTIONAL_RESOURCE
            )
            if (closeBodyOnly) {
                add(bucket(VisualRiskCueType.POSSIBLE_INJURY_CONTEXT), image, bodyHits.take(4), 0.28, "repeated-body-closeup-soft-cue")
            }
        }

        return acc.values.mapNotNull { b ->
            val count = b.imageIds.size
            if (count <= 0) return@mapNotNull null
            val minCount = when (b.type) {
                VisualRiskCueType.POSSIBLE_INJURY_CONTEXT -> 1
                VisualRiskCueType.POSSIBLE_SHARP_OBJECT -> 1
                else -> 2
            }
            if (count < minCount) return@mapNotNull null
            val confidence = cueConfidence(b.type, b.weight, count, classified.size)
            if (confidence < 0.22) return@mapNotNull null
            VisualRiskCue(
                cueType = b.type,
                cueNameKo = b.type.koName,
                imageCount = count,
                confidence = confidence,
                representativeEvidence = b.evidence.take(8),
                safetyInterpretation = when (b.type) {
                    VisualRiskCueType.POSSIBLE_INJURY_CONTEXT -> "상처/붕대/신체 클로즈업 등으로 안전 확인 질문이 필요할 수 있는 보조 단서입니다. 자해로 단정하지 않습니다."
                    VisualRiskCueType.POSSIBLE_SHARP_OBJECT -> "날카로운 물체 가능 단서입니다. 맥락 확인 전까지 위험으로 단정하지 않습니다."
                    else -> "위험 확정이 아니라 상담자가 부드럽게 확인할 수 있는 보조 단서입니다."
                }
            )
        }.sortedWith(compareByDescending<VisualRiskCue> { it.confidence }.thenByDescending { it.imageCount })
    }

    private fun evidenceText(image: ClassifiedImage): String {
        val labels = image.labels
            .filter { it.confidence >= 0.32f }
            .joinToString(" ") { it.text.lowercase() }
        val objects = image.objects
            .filter { it.confidence >= 0.30f }
            .joinToString(" ") { it.category.lowercase() }
        val sub = image.subActivities.joinToString(" ") { it.lowercase() }
        return "$labels $objects $sub".lowercase()
    }

    private fun hits(text: String, keywords: List<String>): List<String> = keywords
        .filter { matchesKeyword(text, it) }
        .distinct()

    private fun add(bucket: CueAccumulator, image: ClassifiedImage, hits: List<String>, weight: Double, reason: String) {
        bucket.imageIds.add(image.image.imageId)
        bucket.weight += weight * hits.size.coerceAtMost(3)
        bucket.evidence += "imageId=${image.image.imageId}, reason=$reason, hits=${hits.take(5).joinToString()}"
    }

    private fun cueConfidence(type: VisualRiskCueType, rawWeight: Double, imageCount: Int, totalImages: Int): Double {
        val countBoost = when {
            imageCount >= 6 -> 0.24
            imageCount >= 3 -> 0.16
            imageCount >= 2 -> 0.10
            else -> 0.04
        }
        val denominator = when (type) {
            VisualRiskCueType.POSSIBLE_INJURY_CONTEXT -> totalImages.coerceAtLeast(1) * 0.18 + 2.8
            VisualRiskCueType.POSSIBLE_SHARP_OBJECT -> totalImages.coerceAtLeast(1) * 0.28 + 4.0
            else -> totalImages.coerceAtLeast(1) * 0.36 + 6.0
        }
        val cap = when (type) {
            VisualRiskCueType.POSSIBLE_INJURY_CONTEXT -> 0.86
            VisualRiskCueType.POSSIBLE_SHARP_OBJECT -> 0.72
            else -> 0.62
        }
        return min(cap, 0.18 + countBoost + rawWeight / denominator).coerceIn(0.0, cap)
    }

    private fun isOrdinaryMedicalDecoyOnly(text: String): Boolean {
        val decoy = listOf("thermometer", "pharmacy", "drugstore", "medicine bottle", "first aid kit", "hospital", "clinic")
        val injury = directInjuryKeywords.any { matchesKeyword(text, it) }
        val body = bodyPartKeywords.any { matchesKeyword(text, it) }
        return !injury && !body && decoy.any { matchesKeyword(text, it) }
    }

    private fun matchesKeyword(text: String, keyword: String): Boolean {
        val escaped = Regex.escape(keyword.lowercase())
        return Regex("""(?<![a-z])$escaped(?![a-z])""").containsMatchIn(text.lowercase())
    }
}
