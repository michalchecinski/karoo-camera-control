package com.karoocameracontrol.recording

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CameraCommandState(
    val busy: Boolean = false,
    val recordingInFlight: Boolean = false,
    val error: String? = null,
    val targetRecording: Boolean? = null,
)

enum class AutomaticRecordingOwner { DESCENT, RADAR }

/** All entry points are called on the main thread, shared by UI and extension. */
class CameraCommandController(
    private val scope: CoroutineScope,
    private val connected: () -> Boolean,
    private val recording: () -> Boolean,
    private val videoMode: () -> Boolean,
    private val startRecording: suspend () -> Unit,
    private val stopRecording: suspend () -> Unit,
) {
    private val _state = MutableStateFlow(CameraCommandState())
    val state = _state.asStateFlow()
    private val _manualStopCount = MutableStateFlow(0L)
    val manualStopCount = _manualStopCount.asStateFlow()
    private var commandJob: Job? = null
    private var generation = 0L
    private var automaticOwner: AutomaticRecordingOwner? = null
    private var automaticStartPending = false

    fun startAutomatically(owner: AutomaticRecordingOwner = AutomaticRecordingOwner.DESCENT): Boolean {
        if (!connected() || !videoMode() || recording() || _state.value.busy || automaticOwner != null) return false
        automaticOwner = owner
        automaticStartPending = true
        launchCommand(
            recordingCommand = true,
            targetRecording = true,
            onFinished = { succeeded ->
                // Radar cleans up ambiguous starts even when encoding status is
                // still false. Keep its ownership until that cleanup Stop.
                automaticStartPending = owner == AutomaticRecordingOwner.RADAR && !succeeded && automaticOwner == owner
                if (!succeeded && !recording() && owner == AutomaticRecordingOwner.DESCENT) automaticOwner = null
            },
        ) { startRecording() }
        return true
    }

    /** Never stops a manual recording or a recording from a previous connection. */
    fun stopAutomatically(owner: AutomaticRecordingOwner = AutomaticRecordingOwner.DESCENT): Boolean {
        if (!connected() || automaticOwner != owner || (_state.value.busy && !automaticStartPending)) return false
        if (!recording() && !automaticStartPending) {
            automaticOwner = null
            return false
        }
        automaticStartPending = false
        launchCommand(
            recordingCommand = true,
            targetRecording = false,
            onFinished = { succeeded ->
                automaticStartPending = !succeeded && owner == AutomaticRecordingOwner.RADAR
                if (succeeded || (!recording() && owner == AutomaticRecordingOwner.DESCENT)) automaticOwner = null
            },
        ) { stopRecording() }
        return true
    }

    fun recordingStopped() {
        automaticOwner = null
    }

    fun ownsRecording(owner: AutomaticRecordingOwner): Boolean = automaticOwner == owner

    /** Radar uses the same command job and ownership checks as descent and manual controls. */
    suspend fun recordForRadar(start: Boolean) {
        val accepted = if (start) startAutomatically(AutomaticRecordingOwner.RADAR) else stopAutomatically(AutomaticRecordingOwner.RADAR)
        check(accepted) { "Camera is busy or radar does not own this recording" }
        val job = commandJob
        job?.join()
        if (job?.isCancelled == true) throw CancellationException("Radar command was overridden")
        _state.value.error?.let { throw IllegalStateException(it) }
        check(recording() == start) { "Camera recording state was not confirmed" }
        if (start) check(automaticOwner == AutomaticRecordingOwner.RADAR) { "Radar recording was overridden" }
    }

    fun toggleManually(forceStop: Boolean = false) {
        val stop = forceStop || recording() || _state.value.recordingInFlight
        if (!connected() || (_state.value.busy && !stop)) return
        automaticOwner = null
        automaticStartPending = false
        if (stop) {
            _manualStopCount.value += 1
            launchCommand(recordingCommand = true, targetRecording = false) { stopRecording() }
        } else {
            launchCommand(recordingCommand = true, targetRecording = true) { startRecording() }
        }
    }

    fun configure(operation: suspend () -> Unit) {
        if (!connected() || recording() || _state.value.busy) return
        launchCommand(recordingCommand = false, operation = operation)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun disconnected() {
        generation += 1
        commandJob?.cancel()
        commandJob = null
        automaticOwner = null
        automaticStartPending = false
        _state.value = CameraCommandState()
    }

    private fun launchCommand(
        recordingCommand: Boolean,
        targetRecording: Boolean? = null,
        onFinished: (Boolean) -> Unit = {},
        operation: suspend () -> Unit,
    ) {
        // Increment first so cancellation of the old command cannot clear new state.
        val currentGeneration = ++generation
        val previousJob = commandJob
        previousJob?.cancel()
        _state.value = CameraCommandState(busy = true, recordingInFlight = recordingCommand, targetRecording = targetRecording)
        commandJob =
            scope.launch(start = CoroutineStart.LAZY) {
                var succeeded = false
                try {
                    // Wait for cancelled BLE operations to release their locks.
                    previousJob?.join()
                    operation()
                    succeeded = true
                } catch (e: TimeoutCancellationException) {
                    // An operation's timeout is a failure; cancellation of this job still wins.
                    currentCoroutineContext().ensureActive()
                    if (generation == currentGeneration) {
                        _state.value = _state.value.copy(error = "Camera command timed out")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == currentGeneration) {
                        _state.value = _state.value.copy(error = e.message ?: "Camera command failed")
                    }
                } finally {
                    if (generation == currentGeneration) {
                        onFinished(succeeded)
                        _state.value = _state.value.copy(busy = false, recordingInFlight = false, targetRecording = null)
                        commandJob = null
                    }
                }
            }.also { it.start() }
    }
}
