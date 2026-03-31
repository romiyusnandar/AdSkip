package com.ryudev.adskip

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat

class AutoSkipService : AccessibilityService() {

    private val CHANNELID = "AdServiceChannel"
    private val skipKeywords: List<String>
        get() = resources.getStringArray(R.array.skip_keywords).toList()

    private var lastScanUptimeMs = 0L
    private var lastClickedNodeKey = ""
    private var lastClickUptimeMs = 0L
    private var isNotificationShown = false

    companion object {
        const val ACTION_DISABLE_FROM_NOTIFICATION = "com.ryudev.adskip.action.DISABLE_FROM_NOTIFICATION"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "adskip_prefs"
        private const val KEY_FEATURE_ENABLED = "feature_enabled"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val SCAN_THROTTLE_MS = 350L
        private const val CLICK_COOLDOWN_MS = 1200L
        private const val MAX_PARENT_DEPTH = 8
        var isFeatureEnabled = mutableStateOf(false)
        private var onFeatureToggle: ((Boolean) -> Unit)? = null

        fun syncFeatureEnabled(context: Context) {
            val enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FEATURE_ENABLED, false)
            isFeatureEnabled.value = enabled
        }

        fun setFeatureEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val previousEnabled = prefs.getBoolean(KEY_FEATURE_ENABLED, false)
            val hasChanged = previousEnabled != enabled

            prefs.edit().putBoolean(KEY_FEATURE_ENABLED, enabled).apply()
            isFeatureEnabled.value = enabled

            if (!enabled) {
                cancelStatusNotification(context)
            }

            if (hasChanged) {
                onFeatureToggle?.invoke(enabled)
            }
        }

        fun clearStaleNotificationIfNeeded(context: Context) {
            val enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FEATURE_ENABLED, false)
            if (!enabled) {
                cancelStatusNotification(context)
            }
        }

        private fun cancelStatusNotification(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.cancel(NOTIFICATION_ID)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createNotificationChannel()
        syncFeatureEnabled(this)
        onFeatureToggle = { enabled ->
            if (enabled) showStatusNotification() else hideStatusNotification()
        }

        if (isFeatureEnabled.value) {
            showStatusNotification()
        } else {
            hideStatusNotification()
        }
    }

    private fun showStatusNotification() {
        if (isNotificationShown) return

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = ACTION_DISABLE_FROM_NOTIFICATION
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            1001,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNELID)
            .setContentTitle(getString(R.string.notification_title_active))
            .setContentText(getString(R.string.notification_text_waiting))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_disable),
                stopPendingIntent
            )
            .build()

        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
            isNotificationShown = true
        } catch (e: Exception) {
            Log.e("AutoSkip", "Gagal menampilkan notifikasi service: ${e.message}")
            val reason = e.message ?: getString(R.string.generic_unknown_error)
            Toast.makeText(
                this,
                getString(R.string.service_start_failed_with_reason, reason),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun hideStatusNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID)
        isNotificationShown = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNELID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isFeatureEnabled.value || event == null) return

        try {
            if (event.packageName?.toString() != YOUTUBE_PACKAGE) return

            val eventType = event.eventType
            val isSupportedEvent = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            if (!isSupportedEvent) return

            val now = SystemClock.uptimeMillis()
            if (now - lastScanUptimeMs < SCAN_THROTTLE_MS) return
            lastScanUptimeMs = now

            val rootNode = rootInActiveWindow ?: return

            for (keyword in skipKeywords) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                for (node in nodes) {
                    if (tryClick(node, now)) {
                        val message = getString(R.string.ad_skipped, keyword)
                        Log.d("AutoSkip", message)
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AutoSkip", "onAccessibilityEvent error: ${e.message}", e)
        }
    }

    private fun tryClick(node: AccessibilityNodeInfo?, now: Long, depth: Int = 0): Boolean {
        if (node == null) return false
        if (depth > MAX_PARENT_DEPTH) return false

        if (node.isClickable && node.isEnabled) {
            val nodeKey = buildNodeKey(node)
            if (nodeKey == lastClickedNodeKey && now - lastClickUptimeMs < CLICK_COOLDOWN_MS) {
                return false
            }

            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                lastClickedNodeKey = nodeKey
                lastClickUptimeMs = now
            }
            return clicked
        }

        return tryClick(node.parent, now, depth + 1)
    }

    private fun buildNodeKey(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return "${node.viewIdResourceName}|${node.text}|${node.contentDescription}|${bounds.flattenToString()}"
    }

    override fun onUnbind(intent: Intent?): Boolean {
        onFeatureToggle = null
        hideStatusNotification()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        onFeatureToggle = null
        hideStatusNotification()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Toast.makeText(this, getString(R.string.service_interrupted), Toast.LENGTH_SHORT).show()
    }
}