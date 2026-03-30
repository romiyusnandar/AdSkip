package com.ryudev.adskip

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
    private val skipKeywords = listOf("Lewati Iklan", "Skip Ad")

    private var lastScanUptimeMs = 0L
    private var lastClickedNodeKey = ""
    private var lastClickUptimeMs = 0L
    private var isForegroundShown = false

    companion object {
        const val ACTION_DISABLE_FROM_NOTIFICATION = "com.ryudev.adskip.action.DISABLE_FROM_NOTIFICATION"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val SCAN_THROTTLE_MS = 350L
        private const val CLICK_COOLDOWN_MS = 1200L
        private const val MAX_PARENT_DEPTH = 8
        var isFeatureEnabled = mutableStateOf(false)
        private var onFeatureToggle: ((Boolean) -> Unit)? = null

        fun setFeatureEnabled(enabled: Boolean) {
            if (isFeatureEnabled.value == enabled) return
            isFeatureEnabled.value = enabled
            onFeatureToggle?.invoke(enabled)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createNotificationChannel()
        onFeatureToggle = { enabled ->
            if (enabled) showForegroundNotification() else hideForegroundNotification()
        }

        if (isFeatureEnabled.value) {
            showForegroundNotification()
        } else {
            hideForegroundNotification()
        }
    }

    private fun showForegroundNotification() {
        if (isForegroundShown) return

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
            .setContentTitle("AdSkip Service Aktif")
            .setContentText("Menunggu iklan YouTube muncul...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Matikan",
                stopPendingIntent
            )
            .build()

        try {
            startForeground(1, notification)
            isForegroundShown = true
        } catch (e: Exception) {
            Log.e("AutoSkip", "Gagal memulai foreground service: ${e.message}")
            Toast.makeText(this, "Gagal memulai layanan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideForegroundNotification() {
        if (!isForegroundShown) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForegroundShown = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNELID,
                "Ad Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isFeatureEnabled.value) return
        if (event == null) return
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
                    Log.d("AutoSkip", "Berhasil menekan tombol: $keyword")
                    Toast.makeText(this, "Berhasil melewati iklan!", Toast.LENGTH_SHORT).show()
                    return
                }
            }
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
        setFeatureEnabled(false)
        hideForegroundNotification()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        onFeatureToggle = null
        setFeatureEnabled(false)
        hideForegroundNotification()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Toast.makeText(this, "AdSkip service terputus", Toast.LENGTH_SHORT).show()
    }
}