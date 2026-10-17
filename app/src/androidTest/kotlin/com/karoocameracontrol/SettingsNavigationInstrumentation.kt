package com.karoocameracontrol

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.screens.MainViewModel
import com.karoocameracontrol.screens.ScreenDestination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** Runs the production activity on an isolated AVD, with no GATT or physical camera. */
class SettingsNavigationInstrumentation : Instrumentation() {
    private lateinit var ui: UiTestSupport

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        ui = UiTestSupport(this)
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
            ui.waitForDescription("Settings and features")
            ui.clickDescription("Settings and features")
            checkSummaries(model)
            ui.clickDescription("Automatic recording")
            ui.clickDescription("Radar recording")
            ui.waitForText("Record detected traffic")
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitForDescription("Descent recording")
            ui.clickDescription("Descent recording")
            ui.waitForDescription("Start on descents")
            ui.clickDescription("Back")
            ui.waitForDescription("Radar recording")
            ui.clickDescription("Back")
            ui.waitForDescription("Alerts & sound")
            ui.clickDescription("Alerts & sound")
            ui.waitForDescription("Sound")

            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            runOnMainSync { activity.recreate() }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10000)) as MainActivity
            removeMonitor(monitor)
            ui.waitForDescription("Sound")
            runOnMainSync {
                check(ViewModelProvider(activity)[MainViewModel::class.java] === model)
                check(model.uiState.value.navigation.destination == ScreenDestination.ALERTS)
            }
            ui.clickDescription("Back")
            ui.waitForDescription("Alerts & sound")
            capture("settings-hub")
            ui.scrollForward()
            capture("settings-hub-support")
            ui.clickDescription("Feedback & diagnostics")
            ui.waitForText("Having a problem?")
            ui.clickDescription("Back")
            ui.waitForDescription("Feedback & diagnostics")
            ui.clickDescription("Camera connection")
            ui.waitForText("No camera connected")
            check(!ui.clickableParent(ui.waitForDescription("Disconnect")).isEnabled) { "Disconnect should be disabled without a camera" }
            check(!ui.clickableParent(ui.waitForDescription("Forget camera…")).isEnabled)
            capture("camera-connection-disconnected")

            val first = ConnectionState.Connected("GoPro Test Camera")
            runOnMainSync { connection.value = first }
            ui.waitForText("Connected to GoPro Test Camera")
            check(ui.clickableParent(ui.waitForDescription("Disconnect")).isEnabled)
            capture("camera-connection-connected")
            ui.clickDescription("Forget camera…")
            ui.waitForText("Forget camera?")
            ui.clickText("Cancel")
            ui.waitForDescription("Disconnect")
            check(connection.value === first)
            ui.clickDescription("Forget camera…")
            ui.waitForText("Forget camera?")
            runOnMainSync { connection.value = ConnectionState.Disconnected }
            ui.waitForText("No camera connected")
            val second = ConnectionState.Connected("GoPro Test Camera")
            runOnMainSync {
                connection.value = second
                model.forgetDevice(first)
                check(connection.value === second) { "Stale confirmation forgot a replacement connection" }
            }
            ui.waitForText("Connected to GoPro Test Camera")

            // Navigating while a command is pending must not cancel it or obscure Stop on return.
            val gate = CompletableDeferred<Unit>()
            runOnMainSync {
                recording.value = false
                manager.recordingCommands.configure { gate.await() }
            }
            ui.clickDescription("Back")
            ui.waitForDescription("Alerts & sound")
            ui.clickDescription("Back")
            ui.waitForDescription("Settings and features")
            runOnMainSync { check(manager.recordingCommands.state.value.busy) }
            ui.clickDescription("Settings and features")
            runOnMainSync {
                check(manager.recordingCommands.state.value.busy)
                gate.complete(Unit)
            }
            ui.clickDescription("Automatic recording")
            ui.waitForDescription("Radar recording")
            capture("automatic-recording")
            ui.clickDescription("Back")
            ui.clickDescription("Back")
            runOnMainSync { recording.value = true }
            ui.waitForText("Stop")
            ui.clickDescription("Settings and features")
            ui.clickDescription("Alerts & sound")
            ui.clickDescription("Back")
            ui.clickDescription("Back")
            check(ui.clickableParent(ui.waitForText("Stop")).isEnabled)
            runOnMainSync {
                check(recording.value)
                recording.value = false
            }
            ui.clickDescription("Settings and features")
            ui.scrollForward()
            ui.clickDescription("Camera connection")
            ui.clickDescription("Disconnect")
            ui.waitForDescription("Settings and features")
            check(connection.value == ConnectionState.Disconnected)

            // Scanning remains active beneath settings and Back restores its screen.
            ui.clickDescription("Add Device")
            ui.waitForDescription("Retry scanning")
            val scanState = connection.value
            ui.clickDescription("Settings and features")
            ui.clickDescription("Alerts & sound")
            ui.clickDescription("Back")
            ui.clickDescription("Back")
            ui.waitForDescription("Retry scanning")
            check(connection.value == scanState) { "Settings interrupted scanning" }
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitForDescription("Add Device")
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
                ui.waitForText("Radar ${if (radarOn) "On" else "Off"} · Descent ${if (descentOn) "On" else "Off"}")
            }
            for (choice in listOf(
                alerts.copy(confirmations = true, warnings = true, sound = false),
                alerts.copy(confirmations = false, warnings = false, sound = true),
                alerts.copy(confirmations = false, warnings = false, sound = false),
            )) {
                runOnMainSync { model.cameraAlertSettings.update(choice) }
                ui.waitForText("Confirmations ${if (choice.confirmations) "On" else "Off"} · Warnings ${if (choice.warnings) "On" else "Off"} · Sound ${if (choice.sound) "On" else "Off"}")
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
