package com.ryudev.adskip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == AutoSkipService.ACTION_DISABLE_FROM_NOTIFICATION) {
            AutoSkipService.setFeatureEnabled(context, false)
            AutoSkipService.clearStaleNotificationIfNeeded(context)
        }
    }
}

