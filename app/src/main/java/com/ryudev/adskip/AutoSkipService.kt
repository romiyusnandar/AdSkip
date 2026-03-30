package com.ryudev.adskip

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat

class AutoSkipService : AccessibilityService() {

    private val CHANNELID = "AdServiceChannel"

    override fun onServiceConnected() {
        super.onServiceConnected()
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNELID)
            .setContentTitle("AdSkip Service Aktif")
            .setContentText("Menunggu iklan YouTube muncul...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            Log.e("AutoSkip", "Gagal memulai foreground service: ${e.message}")
            Toast.makeText(this, "Gagal memulai layanan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return

        val skipKeywords = listOf("Lewati Iklan", "Skip Ad", "Lewati", "Skip")

        for (keyword in skipKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (tryClick(node)) {
                    Log.d("AutoSkip", "Berhasil menekan tombol: $keyword")
                }
            }
        }
    }

    private fun tryClick(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        if (node.isClickable && node.isEnabled) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        val parent = node.parent
        val clicked = tryClick(parent)
        return clicked
    }

    override fun onInterrupt() {
        Toast.makeText(this, "AdSkip service terputus", Toast.LENGTH_SHORT).show()
    }
}