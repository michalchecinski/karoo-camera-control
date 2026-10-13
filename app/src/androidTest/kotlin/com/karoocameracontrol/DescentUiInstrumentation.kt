package com.karoocameracontrol

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.karoocameracontrol.integrations.gopro.Preset
import com.karoocameracontrol.integrations.gopro.PresetGroup
import com.karoocameracontrol.recording.CameraCommandController
import com.karoocameracontrol.recording.DescentStatus
import com.karoocameracontrol.screens.ConnectedScreen
import com.karoocameracontrol.theme.AppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/** Runs the production connected composable with an isolated test camera on an AVD. */
class DescentUiInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onStart() {
        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val recording = MutableStateFlow(false)
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
                    val command by controller.state.collectAsState()
                    val isRecording by recording.collectAsState()
                    AppTheme {
                        Scaffold(
                            topBar = {
                                CenterAlignedTopAppBar(
                                    title = {
                                        Text("Connected to GoPro Test Camera", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    },
                                    actions = {
                                        IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                                    },
                                    colors =
                                        TopAppBarDefaults.centerAlignedTopAppBarColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            titleContentColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                )
                            },
                        ) { padding ->
                            Box(Modifier.padding(padding)) {
                                ConnectedScreen(
                                    deviceName = "GoPro Test Camera",
                                    isRecording = isRecording,
                                    isProcessing = command.busy,
                                    isRecordingOperationInFlight = command.recordingInFlight,
                                    recordingDuration = if (isRecording) 12 else 0,
                                    batteryLevel = 87,
                                    remainingTime = 3720,
                                    cameraMode = 0,
                                    availablePresets = listOf(PresetGroup(id = 1000, presets = listOf(Preset(id = 1)))),
                                    activePresetName = "Standard",
                                    activePresetIcon = null,
                                    descentRecordingEnabled = true,
                                    descentStatus = if (isRecording || command.recordingInFlight) DescentStatus.CONSUMED else DescentStatus.WAITING_FOR_RIDE,
                                    onToggleRecording = { controller.toggleManually() },
                                    onSetMode = {},
                                    onOpenPresetSelection = {},
                                    onDisconnect = {},
                                    onForget = {},
                                    onFinish = {},
                                )
                            }
                        }
                    }
                }
            }
            val readyRecord = clickableParent(waitForText("Record"))
            val presetBounds = android.graphics.Rect()
            clickableParent(waitForText("Preset: Standard")).getBoundsInScreen(presetBounds)
            val recordBounds = android.graphics.Rect()
            readyRecord.getBoundsInScreen(recordBounds)
            capture("connected-layout-ready")
            check(presetBounds.height() >= 96) { "Preset button clipped: $presetBounds" }
            check(presetBounds.bottom <= recordBounds.top - 16) { "Preset touches recording control: $presetBounds / $recordBounds" }
            runOnMainSync { check(controller.startAutomatically()) }
            val pendingStop = waitForText("Stop")
            check(clickableParent(pendingStop).isEnabled) { "Stop unavailable during automatic start" }
            check(!clickableParent(waitForText("Photo")).isEnabled) { "Mode change available during start" }
            capture("descent-start-pending")
            clickableParent(pendingStop).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            waitForText("Record")
            runOnMainSync { check(stops == 1 && controller.manualStopCount.value == 1L) }
            runOnMainSync {
                startGate = CompletableDeferred(Unit)
                check(controller.startAutomatically())
            }
            waitForText("00:12")
            val recordingStop = clickableParent(waitForText("Stop"))
            check(recordingStop.isEnabled)
            val bounds = android.graphics.Rect()
            recordingStop.getBoundsInScreen(bounds)
            check(bounds.bottom <= 704 && bounds.top >= 48) { "Stop clipped by system UI: $bounds" }
            capture("descent-recording-active")
            // Details can scroll, while the recording timer and Stop remain fixed.
            findScrollable(uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            check(!clickableParent(waitForText("Photo")).isEnabled)
            recordingStop.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            waitForText("Record")
            runOnMainSync { check(stops == 2 && !recording.value) }
            capture("descent-recording-ready")
            runOnMainSync {
                check(controller.startAutomatically())
            }
            waitForText("00:12")
            runOnMainSync {
                stopGate = CompletableDeferred()
                check(controller.stopAutomatically())
            }
            waitForIdleSync()
            val automaticStop = clickableParent(waitForText("Stop"))
            check(automaticStop.isEnabled)
            runOnMainSync { check(controller.state.value.busy && controller.state.value.recordingInFlight) }
            automaticStop.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            waitForManualStops(controller, 3L)
            runOnMainSync { stopGate.complete(Unit) }
            waitForText("Record")
            runOnMainSync { check(!recording.value && controller.manualStopCount.value == 3L) }
            runOnMainSync { check(controller.startAutomatically()) }
            waitForText("00:12")
            runOnMainSync { check(controller.stopAutomatically()) }
            waitForText("Record")
            runOnMainSync { check(!recording.value && controller.manualStopCount.value == 3L) }
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

    private fun waitForText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            findText(uiAutomation.rootInActiveWindow, text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("UI text did not appear: $text")
    }

    private fun findText(
        node: AccessibilityNodeInfo?,
        text: String,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable) return node
        return clickableParent(checkNotNull(node.parent))
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (index in 0 until node.childCount) findScrollable(node.getChild(index))?.let { return it }
        return null
    }
}
