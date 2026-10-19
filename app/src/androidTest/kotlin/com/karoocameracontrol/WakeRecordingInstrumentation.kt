package com.karoocameracontrol

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import com.karoocameracontrol.connection.CameraReadinessSettings
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.integrations.gopro.PairedDevice
import com.karoocameracontrol.recording.CameraCommandState
import kotlinx.coroutines.flow.MutableStateFlow

/** Exercises production UI using in-memory sample states only, never opens GATT. */
class WakeRecordingInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val ui = UiTestSupport(this)
        try {
            check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Use an isolated emulator" }
            val camera = GoProManager.getInstance(targetContext)
            check(camera.pairedDevices.value.isEmpty()) { "Use app data without personal cameras" }
            check(field<Any?>(camera, "connectedGatt") == null)
            val prefs = CameraReadinessSettings.getInstance(targetContext)
            check(!prefs.wholeRide.value) { "Use the default off preference" }
            val paired = field<MutableStateFlow<List<PairedDevice>>>(camera, "_pairedDevices")
            val connection = field<MutableStateFlow<ConnectionState>>(camera, "_connectionState")
            val command = field<MutableStateFlow<CameraCommandState>>(camera.recordingCommands, "_state")
            var activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runOnMainSync { paired.value = listOf(PairedDevice("00:00:00:00:00:00", "GoPro Test Camera")) }
            ui.waitForText("Wake & record")
            // Opening the app must not initiate a connection or any command.
            SystemClock.sleep(500)
            check(connection.value == ConnectionState.Disconnected)
            check(!camera.recordingCommands.state.value.busy)
            capture("camera-wake-ready")
            runOnMainSync { command.value = CameraCommandState(busy = true, recordingInFlight = true, targetRecording = true, waking = true) }
            ui.waitForText("Waking camera…")
            capture("camera-waking")
            ui.clickText("Cancel")
            ui.waitForText("Wake & record")
            check(!camera.recordingCommands.state.value.busy)
            check(field<Any?>(camera, "connectedGatt") == null)
            // Saved fallback remains editable while disconnected and survives recreation.
            ui.clickDescription("Settings and features")
            ui.clickDescription("Camera connection")
            ui.waitForDescription("Keep camera ready during rides")
            ui.clickDescription("Keep camera ready during rides")
            check(prefs.wholeRide.value)
            check(targetContext.getSharedPreferences("CameraReadiness", 0).getBoolean("whole_ride", false))
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            runOnMainSync { activity.recreate() }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10000)) as MainActivity
            removeMonitor(monitor)
            ui.waitForDescription("Keep camera ready during rides")
            check(prefs.wholeRide.value)
            capture("camera-connection-readiness")
            ui.clickDescription("Keep camera ready during rides")
            check(!prefs.wholeRide.value)
            ui.clickDescription("Back")
            ui.clickDescription("Back")
            ui.waitForText("Wake & record")
            runOnMainSync {
                check(activity.recordHardwareAction != null)
                // No real command is launched: simulate only the pending UI state.
                command.value = CameraCommandState(busy = true, recordingInFlight = true, targetRecording = true, waking = true)
            }
            ui.waitForText("Waking camera…")
            sendKeyDownUpSync(KeyEvent.KEYCODE_NAVIGATE_IN)
            ui.waitForText("Wake & record")
            check(!command.value.waking)
            runOnMainSync {
                paired.value = emptyList()
                activity.finish()
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: explicit wake UI, no startup connection, touch/hardware wake cancellation, offline fallback preference persistence, recreation and navigation") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.stackTraceToString()}") })
        } finally {
            runOnMainSync { CameraReadinessSettings.getInstance(targetContext).setWholeRide(false) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(
        owner: Any,
        name: String,
    ): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T

    private fun capture(name: String) {
        // The wake screen deliberately animates; waiting for global UI idle can hang.
        SystemClock.sleep(300)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        targetContext.cacheDir.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
