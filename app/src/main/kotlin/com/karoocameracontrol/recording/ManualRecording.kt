package com.karoocameracontrol.recording

import android.content.Context
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.radar.RadarRecordingController

/** Main-thread entry points; both surfaces preserve radar/descent manual override. */
object ManualRecording {
    fun toggle(context: Context) {
        val camera = GoProManager.getInstance(context)
        val forceStop =
            RadarRecordingController.getInstance(context).manualOverride() &&
                camera.connectionState.value is ConnectionState.Connected
        camera.recordingCommands.toggleManually(forceStop = forceStop)
    }

    fun snapshot(
        context: Context,
        camera: GoProManager,
    ): RecordingFieldInput =
        RecordingFieldInput(
            connected = camera.connectionState.value is ConnectionState.Connected,
            recordingKnown = camera.recordingStateKnown.value,
            recording = camera.isRecording.value,
            videoModeKnown = camera.cameraModeKnown.value,
            videoMode = camera.cameraMode.value == 0 || camera.cameraMode.value == GoProManager.PRESET_MODE_VIDEO,
            command = camera.recordingCommands.state.value,
            seconds = camera.recordingDuration.value,
            radarStopRequired = RadarRecordingController.getInstance(context).manualStopRequired,
            canWake = camera.canWakeForRecording(),
        )

    fun perform(
        context: Context,
        action: RecordingFieldAction,
        expectedCamera: String? = null,
    ) {
        val camera = GoProManager.getInstance(context)
        if (expectedCamera != null && camera.wakeTargetAddress() != expectedCamera) return
        // Explicit actions reject stale Start taps and repeated Stop taps while stopping.
        performRecordingFieldAction(snapshot(context, camera), action, camera.recordingCommands) {
            RadarRecordingController.getInstance(context).manualOverride()
        }
    }
}
