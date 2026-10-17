package com.karoocameracontrol

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** Shared accessibility synchronization for emulator instrumentation, never shipped in the app. */
internal class UiTestSupport(private val instrumentation: Instrumentation) {
    fun waitForText(text: String): AccessibilityNodeInfo = waitFor("text '$text'") { it.text?.toString() == text }

    fun waitForDescription(description: String): AccessibilityNodeInfo =
        waitFor("description '$description'") { it.contentDescription?.toString() == description }

    fun clickText(text: String) = click("text '$text'") { it.text?.toString() == text }

    fun clickDescription(description: String) = click("description '$description'") { it.contentDescription?.toString() == description }

    fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable) current = checkNotNull(current.parent) { "No clickable parent for ${node.text ?: node.contentDescription}" }
        return current
    }

    fun scrollForward() {
        find(freshRoot()) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        SystemClock.sleep(300)
    }

    private fun click(
        label: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        var attempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = find(freshRoot(), predicate)
            val target = node?.let { clickableParent(it) }
            if (target != null && visibleBounds(target) != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(250)
                return
            }
            find(freshRoot()) { it.isScrollable }?.performAction(
                if (attempts++ % 4 < 2) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            SystemClock.sleep(600)
        }
        error("Cannot click $label within ${WAIT_MS}ms")
    }

    private fun waitFor(
        label: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            find(freshRoot(), predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("UI $label did not appear within ${WAIT_MS}ms")
    }

    private fun freshRoot(): AccessibilityNodeInfo? {
        // Compose may otherwise reuse stale enabled/visible state after a recompose or scroll.
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }

    private fun visibleBounds(node: AccessibilityNodeInfo): Rect? {
        if (!node.isVisibleToUser) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable) {
                val viewport = Rect()
                parent.getBoundsInScreen(viewport)
                if (!bounds.intersect(viewport)) return null
            }
            parent = parent.parent
        }
        return bounds.takeUnless { it.isEmpty }
    }

    private fun find(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
