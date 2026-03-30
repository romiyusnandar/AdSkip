package com.ryudev.adskip

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AutoSkipService : AccessibilityService() {

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
        node.recycle()
        return clicked
    }

    override fun onInterrupt() {
        Toast.makeText(this, "AdSkip service terputus", Toast.LENGTH_SHORT).show()
    }
}