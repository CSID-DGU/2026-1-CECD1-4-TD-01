package com.example.counseling.galleryanalysis

import kotlin.math.min

/**
 * ML Kit 라벨/객체 + 파일/폴더 힌트 + 얼굴 수를 점수화한다.
 * 한 장을 단정하지 않고 카테고리별 점수와 근거를 함께 남긴다.
 */
class ActivityClassifier(private val minConfidence: Double = 0.38) {

    private data class KeywordRule(
        val category: ActivityCategory,
        val keyword: String,
        val weight: Double,
        val subActivity: String? = null
    )

    private val rules = listOf(
        // 음식/식사
        rule(ActivityCategory.FOOD_MEAL, 1.35, "food", "음식"),
        rule(ActivityCategory.FOOD_MEAL, 1.25, "meal", "식사"),
        rule(ActivityCategory.FOOD_MEAL, 1.20, "dish", "음식"),
        rule(ActivityCategory.FOOD_MEAL, 1.15, "restaurant", "외식"),
        rule(ActivityCategory.FOOD_MEAL, 1.10, "cuisine", "음식"),
        rule(ActivityCategory.FOOD_MEAL, 1.05, "coffee", "카페/음료"),
        rule(ActivityCategory.FOOD_MEAL, 1.00, "drink", "카페/음료"),
        rule(ActivityCategory.FOOD_MEAL, 0.95, "dessert", "디저트"),
        rule(ActivityCategory.FOOD_MEAL, 0.95, "cake", "디저트"),
        rule(ActivityCategory.FOOD_MEAL, 0.90, "pizza", "음식"),
        rule(ActivityCategory.FOOD_MEAL, 0.90, "bread", "베이커리"),
        rule(ActivityCategory.FOOD_MEAL, 0.70, "tableware", "식사"),
        rule(ActivityCategory.FOOD_MEAL, 0.70, "bowl", "식사"),
        rule(ActivityCategory.FOOD_MEAL, 0.65, "plate", "식사"),

        // 운동/신체활동
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.45, "sports", "스포츠"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.35, "sport", "스포츠"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.30, "soccer", "축구"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.25, "football", "축구/풋볼"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.20, "basketball", "농구"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.20, "baseball", "야구"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.15, "tennis", "테니스"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.10, "ball", "구기운동"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.20, "running", "러닝/조깅"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.20, "jogging", "러닝/조깅"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.15, "bicycle", "자전거"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.15, "cycling", "자전거"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.15, "fitness", "헬스/피트니스"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.15, "gym", "헬스/피트니스"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.10, "exercise", "운동"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.05, "swimming", "수영"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 1.05, "hiking", "등산/하이킹"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 0.95, "court", "운동장/코트"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 0.95, "stadium", "운동장/경기장"),
        rule(ActivityCategory.PHYSICAL_ACTIVITY, 0.90, "field", "운동장/필드"),

        // 학업/과제
        rule(ActivityCategory.STUDY_TASK, 1.45, "book", "독서/교재"),
        rule(ActivityCategory.STUDY_TASK, 1.55, "notebook", "노트/필기"),
        rule(ActivityCategory.STUDY_TASK, 1.55, "laptop", "노트북 작업"),
        rule(ActivityCategory.STUDY_TASK, 1.35, "computer", "컴퓨터 작업"),
        rule(ActivityCategory.STUDY_TASK, 1.25, "keyboard", "컴퓨터 작업"),
        rule(ActivityCategory.STUDY_TASK, 1.40, "desk", "책상/공부"),
        rule(ActivityCategory.STUDY_TASK, 1.35, "paper", "과제/문서"),
        rule(ActivityCategory.STUDY_TASK, 1.30, "document", "과제/문서"),
        rule(ActivityCategory.STUDY_TASK, 1.25, "homework", "과제/문서"),
        rule(ActivityCategory.STUDY_TASK, 1.25, "worksheet", "과제/문서"),
        rule(ActivityCategory.STUDY_TASK, 1.20, "textbook", "독서/교재"),
        rule(ActivityCategory.STUDY_TASK, 1.15, "reading", "독서/교재"),
        rule(ActivityCategory.STUDY_TASK, 1.05, "pen", "필기"),
        rule(ActivityCategory.STUDY_TASK, 1.05, "pencil", "필기"),
        rule(ActivityCategory.STUDY_TASK, 1.10, "classroom", "수업/학교"),
        rule(ActivityCategory.STUDY_TASK, 1.10, "school", "학교"),
        rule(ActivityCategory.STUDY_TASK, 0.95, "whiteboard", "수업/학교"),
        rule(ActivityCategory.STUDY_TASK, 1.05, "monitor", "컴퓨터 작업"),
        rule(ActivityCategory.STUDY_TASK, 0.70, "tablet", "태블릿/학습보조"),

        // 사회활동
        rule(ActivityCategory.SOCIAL_ACTIVITY, 1.30, "people", "여럿이 있는 장면"),
        rule(ActivityCategory.SOCIAL_ACTIVITY, 0.45, "person", "사람 포함"),
        rule(ActivityCategory.SOCIAL_ACTIVITY, 1.40, "crowd", "단체/군중"),
        rule(ActivityCategory.SOCIAL_ACTIVITY, 1.35, "group", "단체"),
        rule(ActivityCategory.SOCIAL_ACTIVITY, 1.05, "party", "모임/행사"),
        rule(ActivityCategory.SOCIAL_ACTIVITY, 0.85, "cafe", "카페/사회적 장소"),
        rule(ActivityCategory.SOCIAL_ACTIVITY, 0.45, "restaurant", "식당/모임 장소"),
        rule(ActivityCategory.SOCIAL_ACTIVITY, 0.85, "festival", "행사/축제"),
        rule(ActivityCategory.SOCIAL_ACTIVITY, 0.80, "audience", "행사/관람"),
        rule(ActivityCategory.SOCIAL_ACTIVITY, 0.75, "selfie", "셀피"),

        // 취미/창작
        rule(ActivityCategory.HOBBY_CREATIVE, 1.25, "piano", "음악"),
        rule(ActivityCategory.HOBBY_CREATIVE, 1.25, "guitar", "음악"),
        rule(ActivityCategory.HOBBY_CREATIVE, 1.20, "music", "음악"),
        rule(ActivityCategory.HOBBY_CREATIVE, 1.15, "instrument", "음악"),
        rule(ActivityCategory.HOBBY_CREATIVE, 1.15, "drawing", "그림/드로잉"),
        rule(ActivityCategory.HOBBY_CREATIVE, 1.15, "painting", "그림/페인팅"),
        rule(ActivityCategory.HOBBY_CREATIVE, 1.10, "art", "예술/창작"),
        rule(ActivityCategory.HOBBY_CREATIVE, 1.05, "craft", "공예"),
        rule(ActivityCategory.HOBBY_CREATIVE, 1.00, "dance", "댄스"),
        rule(ActivityCategory.HOBBY_CREATIVE, 0.90, "design", "디자인"),

        // 반려동물/정서자원
        rule(ActivityCategory.PET_EMOTIONAL_RESOURCE, 1.05, "dog", "강아지"),
        rule(ActivityCategory.PET_EMOTIONAL_RESOURCE, 1.05, "cat", "고양이"),
        rule(ActivityCategory.PET_EMOTIONAL_RESOURCE, 1.35, "pet", "반려동물"),
        rule(ActivityCategory.PET_EMOTIONAL_RESOURCE, 0.55, "animal", "동물"),
        rule(ActivityCategory.PET_EMOTIONAL_RESOURCE, 1.10, "puppy", "강아지"),
        rule(ActivityCategory.PET_EMOTIONAL_RESOURCE, 1.10, "kitten", "고양이"),
        rule(ActivityCategory.PET_EMOTIONAL_RESOURCE, 0.45, "bird", "새"),
        rule(ActivityCategory.PET_EMOTIONAL_RESOURCE, 0.75, "rabbit", "토끼"),

        // 자연/외출
        rule(ActivityCategory.NATURE_OUTDOOR, 1.20, "park", "공원"),
        rule(ActivityCategory.NATURE_OUTDOOR, 1.20, "beach", "해변"),
        rule(ActivityCategory.NATURE_OUTDOOR, 1.20, "mountain", "산/등산"),
        rule(ActivityCategory.NATURE_OUTDOOR, 0.65, "sky", "하늘/풍경"),
        rule(ActivityCategory.NATURE_OUTDOOR, 1.15, "tree", "나무/숲"),
        rule(ActivityCategory.NATURE_OUTDOOR, 1.15, "outdoor", "외출/야외"),
        rule(ActivityCategory.NATURE_OUTDOOR, 1.15, "nature", "자연"),
        rule(ActivityCategory.NATURE_OUTDOOR, 1.10, "forest", "숲"),
        rule(ActivityCategory.NATURE_OUTDOOR, 1.10, "sea", "바다"),
        rule(ActivityCategory.NATURE_OUTDOOR, 1.05, "river", "강/하천"),
        rule(ActivityCategory.NATURE_OUTDOOR, 1.05, "lake", "호수"),
        rule(ActivityCategory.NATURE_OUTDOOR, 0.70, "flower", "꽃/식물"),
        rule(ActivityCategory.NATURE_OUTDOOR, 1.05, "sunset", "노을/풍경"),
        rule(ActivityCategory.NATURE_OUTDOOR, 0.85, "landscape", "풍경"),
        rule(ActivityCategory.NATURE_OUTDOOR, 0.75, "garden", "정원/식물"),
        rule(ActivityCategory.NATURE_OUTDOOR, 0.90, "travel", "여행/외출"),

        // 실내/정적활동
        rule(ActivityCategory.INDOOR_STATIC, 1.10, "bed", "침대/휴식"),
        rule(ActivityCategory.INDOOR_STATIC, 1.05, "room", "방/실내"),
        rule(ActivityCategory.INDOOR_STATIC, 1.05, "home", "집"),
        rule(ActivityCategory.INDOOR_STATIC, 1.05, "sofa", "휴식"),
        rule(ActivityCategory.INDOOR_STATIC, 0.95, "television", "TV/시청"),
        rule(ActivityCategory.INDOOR_STATIC, 0.95, "tv", "TV/시청"),
        rule(ActivityCategory.INDOOR_STATIC, 0.95, "indoor", "실내"),
        rule(ActivityCategory.INDOOR_STATIC, 0.90, "furniture", "실내"),
        rule(ActivityCategory.INDOOR_STATIC, 0.45, "monitor", "모니터/정적활동")
    )

    fun classify(image: GalleryImage, analysis: ImageAnalysisOutput, averageHash: Long?): ClassifiedImage {
        val scoreMap = ActivityCategory.values()
            .filter { it != ActivityCategory.UNKNOWN }
            .associateWith { mutableListOf<CategoryEvidence>() }
            .toMutableMap()
        val subActivityVotes = mutableMapOf<String, Int>()

        val mlTexts = analysis.labels.map { Triple(EvidenceSource.ML_LABEL, it.text, it.confidence.toDouble()) } +
            analysis.objects.map { Triple(EvidenceSource.ML_OBJECT, it.category, it.confidence.toDouble().coerceAtLeast(0.30)) }

        mlTexts.forEach { (source, text, confidence) ->
            applyKeywordRules(text, confidence, source, scoreMap, subActivityVotes)
        }

        // v3.2: 파일명/폴더명은 활동 분류 점수에 사용하지 않는다.
        // 테스트 데이터셋과 실제 갤러리 모두 DCIM/Camera, IMG_... 같은 경로가 많아
        // "camera/photo" 일반 단서가 취미/창작으로 과잉 매핑되는 문제가 있었다.

        when {
            analysis.faceCount >= 3 -> addEvidence(scoreMap, ActivityCategory.SOCIAL_ACTIVITY, EvidenceSource.FACE_COUNT, "faceCount>=3", 1.55)
            analysis.faceCount == 2 -> addEvidence(scoreMap, ActivityCategory.SOCIAL_ACTIVITY, EvidenceSource.FACE_COUNT, "faceCount=2", 1.20)
            analysis.faceCount == 1 -> addEvidence(scoreMap, ActivityCategory.SOCIAL_ACTIVITY, EvidenceSource.FACE_COUNT, "faceCount=1", 0.30)
        }

        if (analysis.socialContextType == SocialContextType.PUBLIC_PLACE_LABEL) {
            addEvidence(scoreMap, ActivityCategory.SOCIAL_ACTIVITY, EvidenceSource.ML_LABEL, "public place label", 0.35)
        }

        calibrateCategoryEvidence(scoreMap, analysis)

        val rawScores = scoreMap.mapValues { (_, evidence) -> evidence.sumOf { it.weight } }
        val best = rawScores.maxByOrNull { it.value }
        val second = rawScores.filterKeys { it != best?.key }.maxByOrNull { it.value }
        val total = rawScores.values.sum().coerceAtLeast(0.0001)
        val normalizedBreakdown = rawScores.map { (category, raw) ->
            CategoryScore(
                category = category,
                rawScore = raw,
                normalizedScore = raw / total,
                evidence = scoreMap[category].orEmpty().sortedByDescending { it.weight }.take(8)
            )
        }.sortedByDescending { it.rawScore }

        val bestCategory = best?.key
        val bestScore = best?.value ?: 0.0
        val secondScore = second?.value ?: 0.0
        val margin = bestScore - secondScore
        val category = if (bestCategory == null || bestScore < minConfidence) {
            ActivityCategory.UNKNOWN
        } else {
            bestCategory
        }
        val confidence = if (category == ActivityCategory.UNKNOWN) {
            0f
        } else {
            val marginBoost = (margin / 2.0).coerceIn(0.0, 0.25)
            min(1.0, (bestScore / 3.0) + marginBoost).toFloat()
        }

        return ClassifiedImage(
            image = image,
            category = category,
            confidence = confidence,
            labels = analysis.labels,
            objects = analysis.objects,
            faceCount = analysis.faceCount,
            socialContextType = analysis.socialContextType,
            averageHash = averageHash,
            scoreBreakdown = normalizedBreakdown,
            subActivities = subActivityVotes.entries.sortedByDescending { it.value }.map { it.key }.take(5),
            mlKitSucceeded = analysis.mlKitSucceeded
        )
    }

    private fun rule(category: ActivityCategory, weight: Double, keyword: String, subActivity: String): KeywordRule =
        KeywordRule(category, keyword, weight, subActivity)

    private fun applyKeywordRules(
        text: String,
        confidence: Double,
        source: EvidenceSource,
        scoreMap: MutableMap<ActivityCategory, MutableList<CategoryEvidence>>,
        subActivityVotes: MutableMap<String, Int>
    ) {
        val normalized = text.lowercase()
        rules.forEach { rule ->
            if (matchesKeyword(normalized, rule.keyword)) {
                val weight = rule.weight * confidence.coerceIn(0.2, 1.0)
                addEvidence(scoreMap, rule.category, source, text.take(48), weight)
                rule.subActivity?.let { subActivityVotes[it] = (subActivityVotes[it] ?: 0) + 1 }
            }
        }
    }

    private fun calibrateCategoryEvidence(
        scoreMap: MutableMap<ActivityCategory, MutableList<CategoryEvidence>>,
        analysis: ImageAnalysisOutput
    ) {
        val directStudy = hasAny(analysis, listOf("book", "notebook", "laptop", "desk", "paper", "document", "homework", "worksheet", "textbook", "keyboard"))
        val directHobby = hasAny(analysis, listOf("guitar", "piano", "music", "instrument", "drawing", "painting", "craft", "dance"))
        val directPet = hasAny(analysis, listOf("pet", "dog", "cat", "puppy", "kitten"))
        val directOutdoor = hasAny(analysis, listOf("park", "forest", "mountain", "beach", "trail", "hiking", "outdoor"))

        // 취미/창작은 "음악/그림/공예"처럼 직접 단서가 있을 때만 강하게 인정한다.
        // 방/실내/사진/카메라 같은 일반 단서가 취미를 이기는 문제를 방지한다.
        if (!directHobby) {
            dampen(scoreMap, ActivityCategory.HOBBY_CREATIVE, 0.55)
        }

        // 반려동물은 동물/새 같은 넓은 라벨 하나만으로 과대표현하지 않는다.
        if (!directPet) {
            dampen(scoreMap, ActivityCategory.PET_EMOTIONAL_RESOURCE, 0.45)
        }

        // 하늘/꽃 같은 일반 풍경 라벨만으로 자연/외출을 강하게 잡지 않는다.
        if (!directOutdoor) {
            dampen(scoreMap, ActivityCategory.NATURE_OUTDOOR, 0.65)
        }

        // 책상/노트북/문서 단서가 있으면 실내보다 학업/과제를 우선한다.
        if (directStudy) {
            dampen(scoreMap, ActivityCategory.INDOOR_STATIC, 0.70)
        }
    }

    private fun hasAny(analysis: ImageAnalysisOutput, keywords: List<String>): Boolean {
        val texts = analysis.labels.filter { it.confidence >= 0.35f }.map { it.text.lowercase() } +
            analysis.objects.filter { it.confidence >= 0.35f }.map { it.category.lowercase() }
        return texts.any { text -> keywords.any { keyword -> matchesKeyword(text, keyword) } }
    }

    private fun matchesKeyword(text: String, keyword: String): Boolean {
        val escaped = Regex.escape(keyword.lowercase())
        return Regex("""(?<![a-z0-9])$escaped(?![a-z0-9])""").containsMatchIn(text.lowercase())
    }

    private fun dampen(
        scoreMap: MutableMap<ActivityCategory, MutableList<CategoryEvidence>>,
        category: ActivityCategory,
        factor: Double
    ) {
        val current = scoreMap[category].orEmpty()
        if (current.isEmpty()) return
        scoreMap[category] = current.map { it.copy(weight = it.weight * factor) }.toMutableList()
    }

    private fun addEvidence(
        scoreMap: MutableMap<ActivityCategory, MutableList<CategoryEvidence>>,
        category: ActivityCategory,
        source: EvidenceSource,
        value: String,
        weight: Double
    ) {
        scoreMap.getOrPut(category) { mutableListOf() } += CategoryEvidence(source, value, weight)
    }
}
