package com.example.counseling

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GuardianAlertMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(MONITOR_NOTIFICATION_ID, monitorNotification("Jetson 연결을 확인하고 있습니다."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob?.isActive != true) {
            monitorJob = scope.launch { monitor() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitor() {
        val settings = JetsonSyncSettingsStore(applicationContext)
        val cursorStore = GuardianAlertCursorStore(applicationContext)
        val client = GuardianAlertClient()
        while (scope.isActive) {
            val endpoint = settings.loadEndpoint()
            val token = settings.loadToken()
            if (token.isBlank()) {
                updateMonitor("Jetson 연결 설정이 필요합니다.")
                delay(POLL_INTERVAL_MS)
                continue
            }
            runCatching {
                client.fetch(
                    applicationContext,
                    endpoint,
                    token,
                    afterSequence = cursorStore.load(),
                    limit = 50,
                )
            }.onSuccess { batch ->
                batch.alerts.forEach(::notifyAlert)
                cursorStore.save(batch.nextCursor)
                updateMonitor("보호자 위험 알림 감시 중")
            }.onFailure {
                updateMonitor("Jetson 재연결 대기 중")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun notifyAlert(alert: GuardianAlert) {
        if (alert.category == "SYSTEM" && alert.title == "SYNC_RAW_DATA") {
            scope.launch {
                runCatching {
                    val healthSummary = runCatching { readHealthSummary(applicationContext, HealthPeriod.Week) }.getOrNull()
                    val healthOverview = runCatching { readExtendedHealthOverview(applicationContext, HealthPeriod.Week) }.getOrNull()
                    val callSummary = runCatching { com.psychocare.phenotype.CallLogAnalyzer(applicationContext).analyze() }.getOrNull()
                    val appUsageSummary = runCatching { com.psychocare.phenotype.AppUsageAnalyzer(applicationContext).analyze() }.getOrNull()
                    val calendarSummary = runCatching { com.psychocare.phenotype.CalendarAnalyzer(applicationContext).analyze() }.getOrNull()
                    val rawExerciseEntries = runCatching { readRawExerciseRecords(applicationContext) }.getOrNull()
                    val rawSleepSessions = runCatching { readRawSleepSessions(applicationContext) }.getOrNull()
                    
                    JetsonRawDataSyncClient().sendRawData(
                        applicationContext,
                        healthSummary,
                        healthOverview,
                        callSummary,
                        appUsageSummary,
                        null,
                        calendarSummary,
                        rawExerciseEntries,

                        rawSleepSessions
                    )
                }
            }
            return
        }

        if (!shouldNotifyGuardianAlert(alert, System.currentTimeMillis())) return
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("${severityLabel(alert.severity)} · ${alert.title}")
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(alert.alertId.hashCode(), notification)
    }

    private fun monitorNotification(message: String) =
        NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("On-mom 보호자 알림")
            .setContentText(message)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateMonitor(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(MONITOR_NOTIFICATION_ID, monitorNotification(message))
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL_ID,
                "보호자 연결 상태",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "위험 신호",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    companion object {
        private const val MONITOR_CHANNEL_ID = "guardian_monitor"
        private const val ALERT_CHANNEL_ID = "guardian_risk_alerts"
        private const val MONITOR_NOTIFICATION_ID = 4100
        private const val POLL_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardianAlertMonitorService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardianAlertMonitorService::class.java))
        }
    }
}
