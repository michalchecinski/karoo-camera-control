package com.karoocameracontrol

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.screens.MainViewModel
import com.karoocameracontrol.screens.ScreenDestination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** Runs the production activity on an isolated AVD, with no GATT or physical camera. */
class SettingsNavigationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_OK
        try {
            check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Use an isolated emulator" }
            val manager = GoProManager.getInstance(targetContext)
            check(field<Any?>(manager, "connectedGatt") == null) { "A real camera must not be connected" }
            check(manager.pairedDevices.value.isEmpty()) { "Use isolated app data without saved cameras" }
            val connection = field<MutableStateFlow<ConnectionState>>(manager, "_connectionState")
            val recording = field<MutableStateFlow<Boolean>>(manager, "_isRecording")
            var activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var model: MainViewModel
            runOnMainSync { model = ViewModelProvider(activity)[MainViewModel::class.java] }
            waitForDescription("Settings and features")
            clickDescription("Settings and features")
            checkSummaries(model)
            clickDescription("Automatic recording")
            clickDescription("Radar recording")
            waitForText("Record detected traffic")
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitForDescription("Descent recording")
            clickDescription("Descent recording")
            waitForDescription("Start on descents")
            clickDescription("Back")
            waitForDescription("Radar recording")
            clickDescription("Back")
            waitForDescription("Alerts & sound")
            clickDescription("Alerts & sound")
            waitForDescription("Sound")

            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            runOnMainSync { activity.recreate() }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10000)) as MainActivity
            removeMonitor(monitor)
            waitForDescription("Sound")
            runOnMainSync {
                check(ViewModelProvider(activity)[MainViewModel::class.java] === model)
                check(model.uiState.value.navigation.destination == ScreenDestination.ALERTS)
            }
            clickDescription("Back")
            waitForDescription("Alerts & sound")
            capture("settings-hub")
            scrollForward()
            capture("settings-hub-support")
            clickDescription("Feedback & diagnostics")
            waitForText("Having a problem?")
            clickDescription("Back")
            waitForDescription("Feedback & diagnostics")
            clickDescription("Camera connection")
            waitForText("No camera connected")
            check(!clickableParent(waitForDescription("Disconnect")).isEnabled) { "Disconnect should be disabled without a camera" }
            check(!clickableParent(waitForDescription("Forget camera…")).isEnabled)
            capture("camera-connection-disconnected")

            val first = ConnectionState.Connected("GoPro Test Camera")
            runOnMainSync {
                field<MutableStateFlow<Boolean>>(manager, "_recordingStateKnown").value = true
                field<MutableStateFlow<Boolean>>(manager, "_cameraModeKnown").value = true
                field<MutableStateFlow<Int>>(manager, "_cameraMode").value = 0
                connection.value = first
            }
            waitForText("Connected to GoPro Test Camera")
            check(clickableParent(waitForDescription("Disconnect")).isEnabled)
            capture("camera-connection-connected")
            clickDescription("Forget camera…")
            waitForText("Forget camera?")
            clickText("Cancel")
            waitForDescription("Disconnect")
            check(connection.value === first)
            clickDescription("Forget camera…")
            waitForText("Forget camera?")
            runOnMainSync { connection.value = ConnectionState.Disconnected }
            waitForText("No camera connected")
            val second = ConnectionState.Connected("GoPro Test Camera")
            runOnMainSync {
                connection.value = second
                model.forgetDevice(first)
                check(connection.value === second) { "Stale confirmation forgot a replacement connection" }
            }
            waitForText("Connected to GoPro Test Camera")

            // Navigating while a command is pending must not cancel it or obscure Stop on return.
            val gate = CompletableDeferred<Unit>()
            runOnMainSync {
                recording.value = false
                manager.recordingCommands.configure { gate.await() }
            }
            clickDescription("Back")
            waitForDescription("Alerts & sound")
            clickDescription("Back")
            waitForDescription("Settings and features")
            runOnMainSync { check(manager.recordingCommands.state.value.busy) }
            clickDescription("Settings and features")
            runOnMainSync {
                check(manager.recordingCommands.state.value.busy)
                gate.complete(Unit)
            }
            clickDescription("Automatic recording")
            waitForDescription("Radar recording")
            capture("automatic-recording")
            clickDescription("Back")
            clickDescription("Back")
            runOnMainSync { recording.value = true }
            waitForText("Stop")
            clickDescription("Settings and features")
            clickDescription("Alerts & sound")
            clickDescription("Back")
            clickDescription("Back")
            check(clickableParent(waitForText("Stop")).isEnabled)
            runOnMainSync {
                check(recording.value)
                recording.value = false
                // The earlier simulated disconnection reset the camera mode.
                field<MutableStateFlow<Int>>(manager, "_cameraMode").value = 0
            }
            waitForText("Record")
            runOnMainSync { check(activity.recordHardwareAction != null) }
            clickDescription("Mode")
            waitForText("Select a mode to return to the remote.")
            runOnMainSync { check(activity.recordHardwareAction == null) }
            clickText("Video")
            waitForText("Record")
            runOnMainSync { check(model.uiState.value.navigation.destination == ScreenDestination.MAIN) }
            clickDescription("Settings and features")
            runOnMainSync { check(activity.recordHardwareAction == null) }
            scrollForward()
            clickDescription("Camera connection")
            // Exercise a real touch as well as the accessibility actions used above.
            touchText("Disconnect")
            waitForDescription("Settings and features")
            check(connection.value == ConnectionState.Disconnected)

            // Scanning remains active beneath settings and Back restores its screen.
            clickDescription("Add Device")
            waitForDescription("Retry scanning")
            val scanState = connection.value
            clickDescription("Settings and features")
            clickDescription("Alerts & sound")
            clickDescription("Back")
            clickDescription("Back")
            waitForDescription("Retry scanning")
            check(connection.value == scanState) { "Settings interrupted scanning" }
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitForDescription("Add Device")
            result.putString("stream", "PASS: parent navigation, activity recreation, connection loss, stale Forget, pending commands, recording Stop, disconnect and scanning origin\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            capture("settings-navigation-failure")
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
        }
        finish(code, result)
    }

    private fun checkSummaries(model: MainViewModel) {
        val radar = model.radarSettings.settings.value
        val descent = model.descentSettings.value
        val alerts = model.cameraAlertSettings.settings.value
        try {
            for ((radarOn, descentOn) in listOf(false to false, true to false, true to true, false to true)) {
                runOnMainSync {
                    model.radarSettings.setEnabled(radarOn)
                    model.updateDescentSettings(descent.copy(enabled = descentOn))
                }
                waitForText("Radar ${if (radarOn) "On" else "Off"} · Descent ${if (descentOn) "On" else "Off"}")
            }
            for (choice in listOf(
                alerts.copy(confirmations = true, warnings = true, sound = false),
                alerts.copy(confirmations = false, warnings = false, sound = true),
                alerts.copy(confirmations = false, warnings = false, sound = false),
            )) {
                runOnMainSync { model.cameraAlertSettings.update(choice) }
                waitForText("Confirmations ${if (choice.confirmations) "On" else "Off"} · Warnings ${if (choice.warnings) "On" else "Off"} · Sound ${if (choice.sound) "On" else "Off"}")
            }
        } finally {
            runOnMainSync {
                model.radarSettings.setEnabled(radar.enabled)
                model.updateDescentSettings(descent)
                model.cameraAlertSettings.update(alerts)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(
        manager: GoProManager,
        name: String,
    ): T =
        GoProManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager) as T

    private fun clickDescription(description: String) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        var attempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = find(freshRoot()) { it.contentDescription?.toString() == description }
            val target = node?.let { clickableParent(it) }
            if (target != null && visibleBounds(target) != null) {
                tap(target)
                return
            }
            find(freshRoot()) { it.isScrollable }?.performAction(
                if (attempts++ % 4 < 2) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            SystemClock.sleep(600)
        }
        error("Cannot click $description")
    }

    private fun clickText(text: String) {
        tap(clickableParent(waitForText(text)))
    }

    private fun touchText(text: String) {
        val bounds = Rect()
        waitForText(text).getBoundsInScreen(bounds)
        check(!bounds.isEmpty && bounds.top >= 48 && bounds.bottom <= 704) { "Touch target is outside the app: $bounds" }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        waitForIdleSync()
    }

    private fun tap(node: AccessibilityNodeInfo) {
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitForIdleSync()
        SystemClock.sleep(250)
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

    private fun waitForDescription(description: String) = waitFor { it.contentDescription?.toString() == description }

    private fun waitForText(text: String) = waitFor { it.text?.toString() == text }

    private fun waitFor(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(freshRoot(), predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected UI element did not appear")
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

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        if (node.isClickable) node else clickableParent(checkNotNull(node.parent))

    private fun freshRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        return uiAutomation.rootInActiveWindow
    }

    private fun scrollForward() {
        find(freshRoot()) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        SystemClock.sleep(300)
    }

    private fun capture(name: String) {
        waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        targetContext.cacheDir.resolve("$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
