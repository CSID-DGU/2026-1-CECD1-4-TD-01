package com.example.counseling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class GuardianAlertBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action == Intent.ACTION_BOOT_COMPLETED &&
            AppSettingsStore(context).loadRole() == AppRole.Guardian
        ) {
            GuardianAlertMonitorService.start(context)
        }
    }
}
