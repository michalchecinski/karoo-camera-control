package com.karoocameracontrol.recording

import com.karoocameracontrol.alerts.CameraAlertInput
import com.karoocameracontrol.alerts.RecordingOrigin
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
    val waking: Boolean = false,
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
    private val alertEvent: (CameraAlertInput) -> Unit = {},
    private val canWake: () -> Boolean = { false },
    private val prepareRecording: (suspend () -> Unit)? = null,
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
            target = true,
            origin = owner.alertOrigin(),
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
            target = false,
            origin = owner.alertOrigin(),
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
        if (_state.value.waking) {
            cancelWake()
            return
        }
        val stop = forceStop || recording() || _state.value.recordingInFlight
        if ((!connected() && !canWake()) || (_state.value.busy && !stop)) return
        automaticOwner = null
        automaticStartPending = false
        if (stop) {
            _manualStopCount.value += 1
            launchCommand(recordingCommand = true, target = false) { stopRecording() }
        } else {
            startManually()
        }
    }

    /** Explicit Start never becomes Stop because a stale field action arrived late. */
    fun startManually() {
        if (_state.value.busy || recording() || (!connected() && !canWake())) return
        automaticOwner = null
        automaticStartPending = false
        launchCommand(recordingCommand = true, target = true, waking = prepareRecording != null) {
            prepareRecording?.invoke()
            currentCoroutineContext().ensureActive()
            check(connected()) { "Camera disconnected before recording" }
            // Reconnecting may reveal a clip started using the camera's own shutter.
            if (!recording()) {
                if (prepareRecording != null) check(videoMode()) { "Select Video mode on the camera" }
                _state.value = _state.value.copy(waking = false)
                // Connecting selects/rebaselines the alert feed; bind the shutter request
                // to the now-ready camera so its confirmation retains manual origin.
                if (prepareRecording != null) alertEvent(CameraAlertInput.CommandStarted(true, RecordingOrigin.MANUAL))
                startRecording()
            }
        }
    }

    fun cancelWake() {
        if (!_state.value.waking) return
        _manualStopCount.value += 1
        disconnected()
        alertEvent(CameraAlertInput.CommandFinished(false, true))
    }

    fun cancelForServiceShutdown() {
        when {
            _state.value.waking -> cancelWake()
            _state.value.recordingInFlight && _state.value.targetRecording == true -> toggleManually(forceStop = true)
            _state.value.recordingInFlight && _state.value.targetRecording == false -> Unit // Let bounded Stop finish.
            else -> disconnected() // Preserve an already-running clip.
        }
    }

    fun configure(operation: suspend () -> Unit) {
        if (!connected() || recording() || _state.value.busy) return
        launchCommand(recordingCommand = false, operation = operation)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun disconnected(preserveWake: Boolean = false) {
        if (preserveWake && _state.value.waking) return
        generation += 1
        commandJob?.cancel()
        commandJob = null
        automaticOwner = null
        automaticStartPending = false
        _state.value = CameraCommandState()
    }

    private fun AutomaticRecordingOwner.alertOrigin(): RecordingOrigin =
        if (this == AutomaticRecordingOwner.DESCENT) RecordingOrigin.DESCENT else RecordingOrigin.RADAR

    private fun launchCommand(
        recordingCommand: Boolean,
        target: Boolean = false,
        origin: RecordingOrigin = RecordingOrigin.MANUAL,
        onFinished: (Boolean) -> Unit = {},
        waking: Boolean = false,
        operation: suspend () -> Unit,
    ) {
        // Increment first so cancellation of the old command cannot clear new state.
        val currentGeneration = ++generation
        val previousJob = commandJob
        previousJob?.cancel()
        if (recordingCommand) alertEvent(CameraAlertInput.CommandStarted(target, origin))
        _state.value = CameraCommandState(busy = true, recordingInFlight = recordingCommand, targetRecording = target.takeIf { recordingCommand }, waking = waking)
        commandJob =
            scope.launch(start = CoroutineStart.LAZY) {
                var succeeded = false
                var cancelled = false
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
                    cancelled = true
                    throw e
                } catch (e: Exception) {
                    if (generation == currentGeneration) {
                        _state.value = _state.value.copy(error = e.message ?: "Camera command failed")
                    }
                } finally {
                    if (generation == currentGeneration) {
                        onFinished(succeeded)
                        if (recordingCommand) alertEvent(CameraAlertInput.CommandFinished(succeeded, cancelled))
                        _state.value = _state.value.copy(busy = false, recordingInFlight = false, targetRecording = null, waking = false)
                        commandJob = null
                    }
                }
            }.also { it.start() }
    }
}
