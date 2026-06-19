package com.example.counseling

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.psychocare.data.AppCategory
import com.psychocare.data.AppUsageEntry
import com.psychocare.data.AppUsageSummary
import com.psychocare.data.CallLogSummary
import com.psychocare.phenotype.AppUsageAnalyzer
import com.psychocare.phenotype.CallLogAnalyzer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PhenotypeScreen(presentationMode: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callAnalyzer = remember { CallLogAnalyzer(context.applicationContext) }
    val appUsageAnalyzer = remember { AppUsageAnalyzer(context.applicationContext) }
    var callSummary by remember { mutableStateOf<CallLogSummary?>(null) }
    var appUsageSummary by remember { mutableStateOf<AppUsageSummary?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("사용자가 허용한 통화 기록과 앱 사용 통계만 분석합니다.") }

    fun refresh() {
        scope.launch {
            isLoading = true
            callSummary = callAnalyzer.analyze()
            appUsageSummary = appUsageAnalyzer.analyze()
            message = when {
                callSummary == null && appUsageSummary?.hasPermission != true -> "통화 기록 권한과 앱 사용 정보 접근 권한이 필요합니다."
                callSummary == null -> "통화 기록 권한이 없어 앱 사용 패턴만 표시합니다."
                appUsageSummary?.hasPermission != true -> "앱 사용 정보 접근 권한이 없어 통화 패턴만 표시합니다."
                else -> "최근 생활 패턴 보조 지표를 표시합니다."
            }
            isLoading = false
        }
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            message = if (it) "통화 기록 권한을 허용했습니다." else "통화 기록 권한을 허용하지 않았습니다."
            refresh()
        },
    )

    LaunchedEffect(Unit) {
        refresh()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (presentationMode) "생활 리듬" else "생활 패턴", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (presentationMode) "허용한 기록을 바탕으로 최근 리듬을 점수로만 간단히 보여줍니다." else message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                            refresh()
                        } else {
                            callPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
                        }
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(if (presentationMode) "연락 리듬 연결" else "통화 권한")
                }
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(if (presentationMode) "생활 리듬 연결" else "앱 사용 설정")
                }
                OutlinedButton(
                    onClick = { refresh() },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("새로고침")
                }
            }
        }
        if (presentationMode) {
            item {
                UserPatternSummary(callSummary, appUsageSummary)
            }
        } else {
            item {
                IndicatorSummary(callSummary, appUsageSummary)
            }
            item {
                CallPatternSection(callSummary)
            }
            item {
                AppUsageSection(appUsageSummary)
            }
            appUsageSummary?.topApps.orEmpty().takeIf { it.isNotEmpty() }?.let { apps ->
                item {
                    Text("상위 앱 사용 시간", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(apps.take(12)) { app ->
                    AppUsageRow(app, apps.maxOfOrNull { it.totalTimeMin } ?: 1L)
                }
            }
        }
    }
}

@Composable
private fun UserPatternSummary(callLog: CallLogSummary?, appUsage: AppUsageSummary?) {
    val contactScore = contactRhythmScore(callLog)
    val routineScore = routineBalanceScore(appUsage)
    val overallScore = listOfNotNull(contactScore, routineScore).takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("최근 리듬 참고 점수", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "건강/정신상태 판정이 아니라, 지난주와 달라진 흐름을 부드럽게 요약합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (overallScore == null) {
                    Text("아직 연결된 생활 리듬 데이터가 없습니다.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("${overallScore}점", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(
                        progress = { (overallScore / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        UserPatternScoreCard(
            title = "연락 리듬",
            score = contactScore,
            detail = contactRhythmText(callLog),
        )
        UserPatternScoreCard(
            title = "일상 균형",
            score = routineScore,
            detail = routineBalanceText(appUsage),
        )
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Text(
                "이 점수는 진단이 아니라 상담 전 대화 주제를 잡기 위한 참고용입니다.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UserPatternScoreCard(title: String, score: Int?, detail: String) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(score?.let { "${it}점" } ?: "연결 필요", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(
                progress = { ((score ?: 0) / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private enum class IndicatorLevel(val label: String, val color: Color) {
    Normal("정상", Color(0xFF2E7D32)),
    Caution("주의", Color(0xFFF57F17)),
    Risk("위험", Color(0xFFC62828)),
}

private fun contactRhythmScore(callLog: CallLogSummary?): Int? {
    if (callLog == null) return null
    if (callLog.totalCallsThisWeek == 0 && callLog.totalCallsPrevWeek == 0) return null
    var score = 82
    val changePenalty = when {
        callLog.totalCallsPrevWeek < 3 -> 0
        callLog.weeklyChangePct <= -50f -> 28
        callLog.weeklyChangePct <= -30f -> 18
        callLog.weeklyChangePct <= -10f -> 8
        else -> 0
    }
    val quietPenalty = when {
        callLog.zeroCommunicationStreak >= 5 -> 18
        callLog.zeroCommunicationStreak >= 3 -> 10
        else -> 0
    }
    score -= changePenalty + quietPenalty
    return score.coerceIn(0, 100)
}

private fun routineBalanceScore(appUsage: AppUsageSummary?): Int? {
    if (appUsage == null || !appUsage.hasPermission) return null
    val totalPenalty = when {
        appUsage.dailyAvgScreenTimeMin >= 360 -> 30
        appUsage.dailyAvgScreenTimeMin >= 240 -> 20
        appUsage.dailyAvgScreenTimeMin >= 180 -> 10
        else -> 0
    }
    val nightPenalty = when {
        appUsage.dailyAvgLateNightMin >= 60 -> 26
        appUsage.dailyAvgLateNightMin >= 30 -> 16
        appUsage.dailyAvgLateNightMin >= 15 -> 8
        else -> 0
    }
    return (100 - totalPenalty - nightPenalty).coerceIn(0, 100)
}

private fun contactRhythmText(callLog: CallLogSummary?): String {
    if (callLog == null) return "연락 리듬을 보려면 권한 연결이 필요합니다."
    val direction = when {
        callLog.weeklyChangePct <= -30f -> "지난주보다 연락 흐름이 꽤 줄어든 편입니다."
        callLog.weeklyChangePct <= -10f -> "지난주보다 연락 흐름이 조금 줄어든 편입니다."
        callLog.weeklyChangePct >= 20f -> "지난주보다 연락 흐름이 늘어난 편입니다."
        else -> "지난주와 비슷한 연락 흐름입니다."
    }
    val known = when {
        callLog.namedContactsRate >= 0.7f -> "알던 사람과의 연락 비중이 높은 편입니다."
        callLog.namedContactsRate >= 0.35f -> "알던 사람과의 연락이 일부 확인됩니다."
        else -> "알던 사람과의 연락 비중은 낮게 보입니다."
    }
    return "$direction $known"
}

private fun routineBalanceText(appUsage: AppUsageSummary?): String {
    if (appUsage == null || !appUsage.hasPermission) return "일상 균형을 보려면 생활 리듬 연결이 필요합니다."
    return when {
        appUsage.dailyAvgLateNightMin >= 60 -> "늦은 시간대 리듬이 흔들린 날이 있는 편입니다."
        appUsage.weeklyChangePct >= 35f -> "지난주보다 디지털 생활 리듬이 늘어난 편입니다."
        appUsage.weeklyChangePct <= -20f -> "지난주보다 디지털 생활 리듬이 줄어든 편입니다."
        else -> "최근 일상 리듬은 비교적 비슷하게 유지되고 있습니다."
    }
}

@Composable
private fun IndicatorSummary(callLog: CallLogSummary?, appUsage: AppUsageSummary?) {
    val social = when {
        callLog == null -> null
        callLog.isolationAlert || callLog.totalCallsThisWeek < 3 -> IndicatorLevel.Risk
        callLog.weeklyChangePct <= -30f -> IndicatorLevel.Caution
        else -> IndicatorLevel.Normal
    }
    val isolation = when {
        callLog == null -> null
        callLog.zeroCommunicationStreak >= 4 -> IndicatorLevel.Risk
        callLog.zeroCommunicationStreak >= 2 -> IndicatorLevel.Caution
        else -> IndicatorLevel.Normal
    }
    val digital = when {
        appUsage == null || !appUsage.hasPermission -> null
        appUsage.dailyAvgScreenTimeMin >= 360 -> IndicatorLevel.Risk
        appUsage.dailyAvgScreenTimeMin >= 240 -> IndicatorLevel.Caution
        else -> IndicatorLevel.Normal
    }
    val night = when {
        appUsage == null || !appUsage.hasPermission -> null
        appUsage.dailyAvgLateNightMin >= 60 -> IndicatorLevel.Risk
        appUsage.dailyAvgLateNightMin >= 30 -> IndicatorLevel.Caution
        else -> IndicatorLevel.Normal
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("핵심 지표 요약", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "통화 기록과 앱 사용 통계 기반 보조 신호입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IndicatorTile("사회적 연결", social, callLog?.let { "주 ${it.totalCallsThisWeek}건" } ?: "권한 없음", Modifier.weight(1f))
                IndicatorTile("고립 신호", isolation, callLog?.let { "무연락 ${it.zeroCommunicationStreak}일" } ?: "권한 없음", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IndicatorTile("디지털 과사용", digital, appUsage?.takeIf { it.hasPermission }?.let { "${it.dailyAvgScreenTimeMin}분/일" } ?: "권한 없음", Modifier.weight(1f))
                IndicatorTile("야간 리듬", night, appUsage?.takeIf { it.hasPermission }?.let { "${it.dailyAvgLateNightMin}분/일" } ?: "권한 없음", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IndicatorTile(title: String, level: IndicatorLevel?, detail: String, modifier: Modifier = Modifier) {
    val color = level?.color ?: Color(0xFF9E9E9E)
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(6.dp), color = color) {
            Text(
                text = level?.label ?: "없음",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CallPatternSection(summary: CallLogSummary?) {
    SectionCard(
        title = "통화 패턴",
        alertMessage = summary?.takeIf { it.isolationAlert }?.let { "대인 교류 급감 신호가 있습니다." },
    ) {
        if (summary == null) {
            Text("통화 기록 권한이 없거나 분석할 기록이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        WeeklyComparisonBar(
            label = "주간 통화",
            thisWeek = summary.totalCallsThisWeek,
            prevWeek = summary.totalCallsPrevWeek,
            unit = "건",
            changePct = summary.weeklyChangePct,
        )
        HorizontalDivider()
        StatRow("고유 연락처", "${summary.uniqueContactsThisWeek}명")
        StatRow("평균 통화 시간", "${summary.avgDurationSec.toInt()}초")
        StatRow("부재중 비율", "${"%.0f".format(summary.missedCallRate * 100)}%")
        StatRow("연속 무연락", "${summary.zeroCommunicationStreak}일")
        StatRow("저장된 연락처 비율", "${"%.0f".format(summary.namedContactsRate * 100)}%")
    }
}

@Composable
private fun AppUsageSection(summary: AppUsageSummary?) {
    SectionCard(
        title = "앱 사용 패턴",
        alertMessage = summary?.takeIf { it.hasPermission && it.dailyAvgLateNightMin >= 30 }?.let { "야간 앱 사용 시간이 높은 편입니다." },
    ) {
        if (summary == null || !summary.hasPermission) {
            Text("앱 사용 정보 접근 권한이 필요합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        ScreenTimeDisplay(summary.dailyAvgScreenTimeMin, summary.weeklyChangePct)
        HorizontalDivider()
        StatRow("일평균 야간 사용", "${summary.dailyAvgLateNightMin}분")
        StatRow("최장 연속 세션", "${summary.longestSingleSessionMin}분")
        StatRow("주간 변화", "${"%.1f".format(summary.weeklyChangePct)}%")
    }
}

@Composable
private fun SectionCard(
    title: String,
    alertMessage: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            alertMessage?.let {
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFFCDD2)) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color(0xFFB71C1C),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun WeeklyComparisonBar(label: String, thisWeek: Int, prevWeek: Int, unit: String, changePct: Float) {
    val max = maxOf(thisWeek, prevWeek, 1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${if (changePct >= 0) "+" else ""}${"%.0f".format(changePct)}%",
                style = MaterialTheme.typography.labelMedium,
                color = if (changePct >= 0) Color(0xFF2E7D32) else Color(0xFFC62828),
                fontWeight = FontWeight.SemiBold,
            )
        }
        BarWithLabel("이번 주", thisWeek, max, unit, MaterialTheme.colorScheme.primary)
        BarWithLabel("지난 주", prevWeek, max, unit, Color(0xFF9E9E9E))
    }
}

@Composable
private fun BarWithLabel(label: String, value: Int, max: Int, unit: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((value.toFloat() / max).coerceIn(0f, 1f))
                    .background(color, RoundedCornerShape(5.dp)),
            )
        }
        Text("$value$unit", modifier = Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ScreenTimeDisplay(dailyMin: Long, weeklyChangePct: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatMinutes(dailyMin), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("일평균 스크린타임", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${if (weeklyChangePct >= 0) "+" else ""}${"%.0f".format(weeklyChangePct)}%",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (weeklyChangePct >= 0) Color(0xFFC62828) else Color(0xFF2E7D32),
            )
            Text("주간 변화", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppUsageRow(app: AppUsageEntry, maxMinutes: Long) {
    val fraction = if (maxMinutes > 0) app.totalTimeMin.toFloat() / maxMinutes else 0f
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.appName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${categoryLabel(app.category)} · ${app.packageName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatMinutes(app.totalTimeMin), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .background(categoryColor(app.category), RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatMinutes(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}시간 ${mins}분" else "${mins}분"
}

private fun categoryLabel(category: AppCategory): String {
    return when (category) {
        AppCategory.GAME -> "게임"
        AppCategory.VIDEO -> "영상"
        AppCategory.SNS -> "SNS"
        AppCategory.PRODUCTIVITY -> "생산성"
        AppCategory.OTHER -> "기타"
    }
}

private fun categoryColor(category: AppCategory): Color {
    return when (category) {
        AppCategory.GAME -> Color(0xFFE91E63)
        AppCategory.VIDEO -> Color(0xFF2196F3)
        AppCategory.SNS -> Color(0xFF9C27B0)
        AppCategory.PRODUCTIVITY -> Color(0xFF4CAF50)
        AppCategory.OTHER -> Color(0xFF9E9E9E)
    }
}
