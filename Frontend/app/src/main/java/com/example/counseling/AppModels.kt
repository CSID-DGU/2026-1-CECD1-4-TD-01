package com.example.counseling

import android.media.AudioRecord
import android.net.Uri
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import com.example.counseling.llm.ChatMessage
import kotlinx.coroutines.Job
import java.io.File
import java.time.LocalDate

enum class AppScreen(val label: String, val icon: String) {
    Chat("상담", "상"),
    Gallery("갤러리", "사"),
    Health("건강", "건"),
    Phenotype("패턴", "패"),
    Settings("설정", "설"),
}

enum class ThinkingMode(val label: String) {
    Auto("사고 자동"),
    On("사고 켬"),
    Off("사고 끔"),
}

enum class ChatFontSize(val label: String, val sizeSp: Int) {
    Small("작게", 15),
    Normal("보통", 17),
    Large("크게", 20),
}

data class GalleryImage(
    val uri: Uri,
    val name: String,
)

enum class GalleryAccess {
    None,
    Partial,
    Full,
}

data class HealthSummary(
    val period: HealthPeriod = HealthPeriod.Week,
    val steps: Long = 0,
    val caloriesKcal: Double = 0.0,
    val activeCaloriesKcal: Double = 0.0,
    val distanceKm: Double = 0.0,
    val heartRateBpm: Long? = null,
    val sleepHours: Double = 0.0,
    val daily: List<HealthDaySummary> = emptyList(),
    val message: String = "준비됨",
)

enum class HealthPeriod(val label: String) {
    Week("주"),
    Month("월"),
}

data class HealthDaySummary(
    val date: LocalDate,
    val steps: Long = 0,
    val caloriesKcal: Double = 0.0,
    val activeCaloriesKcal: Double = 0.0,
    val distanceKm: Double = 0.0,
    val heartRateBpm: Long? = null,
    val sleepHours: Double = 0.0,
)

data class ImportedSession(
    val messages: List<ChatMessage>,
    val systemPrompt: String,
    val importantMemories: List<String> = emptyList(),
)

data class MemoryItem(
    val text: String,
    val isDefault: Boolean,
)

data class ActiveAudioRecording(
    val recorder: AudioRecord,
    val file: File,
    val job: Job,
)

val defaultImportantMemories = listOf(
    "상담 응답은 진단이나 치료를 대신하지 않고, 감정 반영과 정리, 작은 다음 행동 선택을 돕는 방식으로 유지한다.",
    "불안, 긴장, 감정 과부하가 있을 때는 느린 호흡, 몸 감각 알아차리기, 5-4-3-2-1 감각 그라운딩, 짧은 산책, 점진적 근육 이완 같은 부담 낮은 안정화 방법을 선택지로 제안한다.",
    "자살, 자해, 타해, 학대, 폭력, 응급 위험 신호가 있으면 일반 조언보다 안전 확인과 긴급 지원 안내를 우선한다.",
)

val healthPermissions = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(DistanceRecord::class),
    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
)

