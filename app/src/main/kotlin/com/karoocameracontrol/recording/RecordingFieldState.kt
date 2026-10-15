package com.karoocameracontrol.recording

/** Pure policy shared by rendering and live tap validation. */
data class RecordingFieldInput(
    val connected: Boolean,
    val recordingKnown: Boolean,
    val recording: Boolean,
    val videoModeKnown: Boolean,
    val videoMode: Boolean,
    val command: CameraCommandState = CameraCommandState(),
    val seconds: Int = 0,
    val radarStopRequired: Boolean = false,
)

enum class RecordingFieldStatus { DISCONNECTED, UNKNOWN, VIDEO_REQUIRED, READY, STARTING, RECORDING, STOPPING, BUSY, FAILED }

enum class RecordingFieldAction { START, STOP }

data class RecordingFieldState(
    val status: RecordingFieldStatus,
    val action: RecordingFieldAction? = null,
    val error: String? = null,
    val seconds: Int = 0,
)

fun recordingFieldState(input: RecordingFieldInput): RecordingFieldState {
    val command = input.command
    if (!input.connected) return RecordingFieldState(RecordingFieldStatus.DISCONNECTED)
    if (command.recordingInFlight) {
        return if (command.targetRecording == false) {
            RecordingFieldState(RecordingFieldStatus.STOPPING)
        } else {
            RecordingFieldState(RecordingFieldStatus.STARTING, RecordingFieldAction.STOP)
        }
    }
    if (command.busy) return RecordingFieldState(RecordingFieldStatus.BUSY)
    val action =
        when {
            input.radarStopRequired -> RecordingFieldAction.STOP
            !input.recordingKnown -> null
            input.recording -> RecordingFieldAction.STOP
            input.videoModeKnown && input.videoMode -> RecordingFieldAction.START
            else -> null
        }
    if (command.error != null) return RecordingFieldState(RecordingFieldStatus.FAILED, action, command.error)
    if (input.radarStopRequired) return RecordingFieldState(RecordingFieldStatus.STARTING, action)
    if (!input.recordingKnown) return RecordingFieldState(RecordingFieldStatus.UNKNOWN)
    if (input.recording) return RecordingFieldState(RecordingFieldStatus.RECORDING, action, seconds = input.seconds)
    if (!input.videoModeKnown) return RecordingFieldState(RecordingFieldStatus.UNKNOWN)
    if (!input.videoMode) return RecordingFieldState(RecordingFieldStatus.VIDEO_REQUIRED)
    return RecordingFieldState(RecordingFieldStatus.READY, action)
}

fun recordingFieldTimer(seconds: Int): String {
    val value = seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(value / 3600, value / 60 % 60, value % 60)
}

/** Validate the intent against live state before changing automation ownership. */
fun performRecordingFieldAction(
    input: RecordingFieldInput,
    action: RecordingFieldAction,
    controller: CameraCommandController,
    manualOverride: () -> Boolean,
): Boolean {
    if (recordingFieldState(input).action != action) return false
    val forceStop = manualOverride()
    controller.toggleManually(forceStop = forceStop || action == RecordingFieldAction.STOP)
    return true
}
