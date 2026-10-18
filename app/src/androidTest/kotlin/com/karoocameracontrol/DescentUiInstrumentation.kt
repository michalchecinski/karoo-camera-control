package com.karoocameracontrol

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import com.karoocameracontrol.components.CompactTopAppBar
import com.karoocameracontrol.integrations.gopro.Preset
import com.karoocameracontrol.recording.CameraCommandController
import com.karoocameracontrol.screens.ConnectedScreen
import com.karoocameracontrol.screens.ModeSelectionScreen
import com.karoocameracontrol.screens.PresetSelectionScreen
import com.karoocameracontrol.screens.ScreenDestination
import com.karoocameracontrol.theme.AppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicReference

/** Runs the production connected composable with an isolated test camera on an AVD. */
class DescentUiInstrumentation : Instrumentation() {
    private lateinit var ui: UiTestSupport

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onStart() {
        ui = UiTestSupport(this)
        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val recording = MutableStateFlow(false)
            val title = MutableStateFlow("GoPro Test Camera")
            val page = MutableStateFlow(ScreenDestination.MAIN)
            val mode = MutableStateFlow(0)
            val preset = MutableStateFlow("Standard")
            val titleLayout = AtomicReference<TextLayoutResult>()
            var startGate = CompletableDeferred<Unit>()
            var stopGate = CompletableDeferred(Unit)
            var stops = 0
            val controller =
                CameraCommandController(
                    scope,
                    connected = { true },
                    recording = { recording.value },
                    videoMode = { true },
                    startRecording = {
                        startGate.await()
                        recording.value = true
                    },
                    stopRecording = {
                        stops += 1
                        stopGate.await()
                        recording.value = false
                    },
                )
            runOnMainSync {
                activity.setContent {
                    val cameraTitle by title.collectAsState()
                    val destination by page.collectAsState()
                    val currentMode by mode.collectAsState()
                    val currentPreset by preset.collectAsState()
                    val command by controller.state.collectAsState()
                    val isRecording by recording.collectAsState()
                    AppTheme {
                        Scaffold(
                            topBar = {
                                CompactTopAppBar(
                                    title =
                                        when (destination) {
                                            ScreenDestination.MODES -> "Camera mode"
                                            ScreenDestination.PRESETS -> "Camera presets"
                                            else -> cameraTitle
                                        },
                                    onTitleLayout = { titleLayout.set(it) },
                                    actions = {
                                        if (destination == ScreenDestination.MAIN) {
                                            IconButton(onClick = {}) { Icon(Icons.Default.Settings, contentDescription = "Settings and features") }
                                        }
                                    },
                                )
                            },
                        ) { padding ->
                            Box(Modifier.padding(padding)) {
                                when (destination) {
                                    ScreenDestination.MODES ->
                                        ModeSelectionScreen(currentMode, !command.busy && !isRecording, {
                                            mode.value = it
                                            page.value = ScreenDestination.MAIN
                                        }, { page.value = ScreenDestination.MAIN })
                                    ScreenDestination.PRESETS ->
                                        PresetSelectionScreen(listOf(Preset(id = 1, customName = "Standard"), Preset(id = 2, customName = "Activity")), if (currentPreset == "Standard") 1 else 2, {
                                            preset.value = it.customName ?: "Standard"
                                            page.value = ScreenDestination.MAIN
                                        }, { page.value = ScreenDestination.MAIN }, !command.busy && !isRecording)
                                    else ->
                                        ConnectedScreen(
                                            deviceName = "GoPro Test Camera",
                                            isRecording = isRecording,
                                            isProcessing = command.busy,
                                            isRecordingOperationInFlight = command.recordingInFlight,
                                            recordingDuration = if (isRecording) 12 else 0,
                                            batteryLevel = 87,
                                            remainingTime = 3720,
                                            cameraMode = currentMode,
                                            activePresetName = currentPreset,
                                            onToggleRecording = { controller.toggleManually() },
                                            onOpenModeSelection = { page.value = ScreenDestination.MODES },
                                            onOpenPresetSelection = { page.value = ScreenDestination.PRESETS },
                                            onFinish = {},
                                            recordingTarget = command.targetRecording,
                                            radarStatus = "Waiting for an active ride",
                                            descentStatus = "Off",
                                        )
                                }
                            }
                        }
                    }
                }
            }
            val readyRecord = ui.clickableParent(ui.waitForText("Record"))
            val measuredTitle = checkNotNull(titleLayout.get())
            check(measuredTitle.lineCount == 1 && !measuredTitle.hasVisualOverflow) { "Camera title must fit one line" }
            check(measuredTitle.layoutInput.style.fontSize.value <= 12f) { "Camera title too large" }
            val presetBounds = android.graphics.Rect()
            ui.clickableParent(ui.waitForDescription("Preset")).getBoundsInScreen(presetBounds)
            val recordBounds = android.graphics.Rect()
            readyRecord.getBoundsInScreen(recordBounds)
            capture("connected-layout-ready")
            check(presetBounds.height() >= 96) { "Preset button clipped: $presetBounds" }
            check(presetBounds.bottom <= recordBounds.top - 16) { "Preset touches recording control: $presetBounds / $recordBounds" }
            runOnMainSync { check(controller.startAutomatically()) }
            val pendingStop = ui.waitForText("Stop")
            check(ui.clickableParent(pendingStop).isEnabled) { "Stop unavailable during automatic start" }
            check(!ui.clickableParent(ui.waitForDescription("Mode")).isEnabled) { "Mode change available during start" }
            capture("descent-start-pending")
            ui.clickText("Stop")
            ui.waitForText("Record")
            runOnMainSync { check(stops == 1 && controller.manualStopCount.value == 1L) }
            runOnMainSync {
                startGate = CompletableDeferred(Unit)
                check(controller.startAutomatically())
            }
            ui.waitForText("00:12")
            val recordingStop = ui.clickableParent(ui.waitForText("Stop"))
            check(recordingStop.isEnabled)
            val bounds = android.graphics.Rect()
            recordingStop.getBoundsInScreen(bounds)
            check(bounds.bottom <= 704 && bounds.top >= 48) { "Stop clipped by system UI: $bounds" }
            val activePresetBounds = android.graphics.Rect()
            ui.clickableParent(ui.waitForDescription("Preset")).getBoundsInScreen(activePresetBounds)
            val timerBounds = android.graphics.Rect()
            ui.waitForText("00:12").getBoundsInScreen(timerBounds)
            check(activePresetBounds.height() >= 96 && timerBounds.bottom <= activePresetBounds.top) {
                "Preset clipped by fixed recording controls: $activePresetBounds / $timerBounds"
            }
            capture("descent-recording-active")
            // Details can scroll, while the recording timer and Stop remain fixed.
            ui.scrollForward()
            check(!ui.clickableParent(ui.waitForDescription("Mode")).isEnabled)
            ui.clickText("Stop")
            ui.waitForText("Record")
            runOnMainSync { check(stops == 2 && !recording.value) }
            capture("descent-recording-ready")
            runOnMainSync {
                check(controller.startAutomatically())
            }
            ui.waitForText("00:12")
            runOnMainSync {
                stopGate = CompletableDeferred()
                check(controller.stopAutomatically())
            }
            waitForIdleSync()
            val automaticStop = ui.clickableParent(ui.waitForText("Stop"))
            check(automaticStop.isEnabled)
            runOnMainSync { check(controller.state.value.busy && controller.state.value.recordingInFlight) }
            ui.clickText("Stop")
            waitForManualStops(controller, 3L)
            runOnMainSync { stopGate.complete(Unit) }
            ui.waitForText("Record")
            runOnMainSync { check(!recording.value && controller.manualStopCount.value == 3L) }
            runOnMainSync { check(controller.startAutomatically()) }
            ui.waitForText("00:12")
            runOnMainSync { check(controller.stopAutomatically()) }
            ui.waitForText("Record")
            runOnMainSync { check(!recording.value && controller.manualStopCount.value == 3L) }
            ui.clickDescription("Mode")
            ui.waitForText("Camera mode")
            capture("camera-mode-selection")
            ui.clickText("Photo")
            ui.waitForText("Record")
            runOnMainSync { check(mode.value == 1 && page.value == ScreenDestination.MAIN) }
            ui.clickDescription("Mode")
            ui.clickText("Video")
            ui.waitForText("Record")
            ui.clickDescription("Preset")
            ui.waitForText("Activity")
            capture("camera-preset-selection")
            ui.clickText("Activity")
            ui.waitForText("Record")
            runOnMainSync { check(mode.value == 0 && preset.value == "Activity") }
            // The Android NAVIGATE_IN route uses the same recording controller.
            runOnMainSync { activity.recordHardwareAction = { controller.toggleManually() } }
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_NAVIGATE_IN)
            ui.waitForText("00:12")
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_NAVIGATE_IN)
            ui.waitForText("Record")
            val countBeforeHeldPress = controller.manualStopCount.value
            runOnMainSync {
                val now = SystemClock.uptimeMillis()
                activity.onKeyDown(android.view.KeyEvent.KEYCODE_NAVIGATE_IN, android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_NAVIGATE_IN, 0))
                activity.onKeyDown(android.view.KeyEvent.KEYCODE_NAVIGATE_IN, android.view.KeyEvent(now, now + 100, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_NAVIGATE_IN, 1))
                activity.onKeyUp(android.view.KeyEvent.KEYCODE_NAVIGATE_IN, android.view.KeyEvent(now, now + 200, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_NAVIGATE_IN, 0))
                check(!recording.value && controller.manualStopCount.value == countBeforeHeldPress)
                var actions = 0
                activity.recordHardwareAction = { actions++ }
                val recordKey = android.view.KeyEvent.KEYCODE_NAVIGATE_IN
                val otherKey = android.view.KeyEvent.KEYCODE_DPAD_UP

                fun key(
                    code: Int,
                    down: Boolean,
                ) {
                    val action = if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP
                    val event = android.view.KeyEvent(now, now + 250, action, code, 0)
                    if (down) activity.onKeyDown(code, event) else activity.onKeyUp(code, event)
                }
                // Both press orders, and both release orders, must suppress recording.
                for (recordFirst in listOf(false, true)) {
                    for (recordReleasedFirst in listOf(false, true)) {
                        val first = if (recordFirst) recordKey else otherKey
                        val second = if (recordFirst) otherKey else recordKey
                        val releasedFirst = if (recordReleasedFirst) recordKey else otherKey
                        val releasedSecond = if (recordReleasedFirst) otherKey else recordKey
                        key(first, true)
                        key(second, true)
                        key(releasedFirst, false)
                        key(releasedSecond, false)
                        check(actions == 0) { "Key combination triggered recording" }
                    }
                }
                key(recordKey, true)
                key(recordKey, false)
                check(actions == 1) { "Standalone press must work after releasing a combination" }
                activity.recordHardwareAction = null
            }
            runOnMainSync { title.value = "GoPro " + "Long camera name ".repeat(12) }
            waitForIdleSync()
            SystemClock.sleep(200)
            val longTitle = checkNotNull(titleLayout.get())
            check(longTitle.lineCount == 1 && longTitle.isLineEllipsized(0)) { "Long title must remain one line" }
            check(longTitle.layoutInput.style.fontSize.value == 10f) { "Long title shrank below readable minimum" }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: automatic start/stop, pending Stop, mode lock, visible recording Stop and manual override\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${e.stackTraceToString()}\n") })
        } finally {
            scope.cancel()
        }
    }

    private fun capture(name: String) {
        waitForIdleSync()
        // Take a screenshot of the actual emulator surface, never a Compose preview.
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        targetContext.cacheDir.resolve("$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun waitForManualStops(
        controller: CameraCommandController,
        count: Long,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            var observed = 0L
            runOnMainSync { observed = controller.manualStopCount.value }
            if (observed == count) return
            SystemClock.sleep(100)
        }
        error("Manual override did not arrive")
    }
}
