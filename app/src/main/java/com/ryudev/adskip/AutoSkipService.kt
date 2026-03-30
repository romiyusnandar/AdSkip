package com.ryudev.adskip

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoSkipService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return
        val skipKeywords = listOf<String>("Skip Ad", "Lewati Iklan", "Skip", "Lewati")

        for (keyword in skipKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (node.isClickable && node.isEnabled) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    println("AdSkip: Berhasil skip iklan, keyword: $keyword")
                }
                node.recycle()
            }
        }
    }

    override fun onInterrupt() {

    }
}