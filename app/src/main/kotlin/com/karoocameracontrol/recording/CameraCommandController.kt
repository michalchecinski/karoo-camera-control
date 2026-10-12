package com.karoocameracontrol.recording

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CameraCommandState(
    val busy: Boolean = false,
    val recordingInFlight: Boolean = false,
    val error: String? = null,
)

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

    fun startAutomatically(): Boolean {
        if (!connected() || !videoMode() || recording() || _state.value.busy) return false
        launchCommand(recordingCommand = true) { startRecording() }
        return true
    }

    fun toggleManually() {
        val stop = recording() || _state.value.recordingInFlight
        if (!connected() || (_state.value.busy && !stop)) return
        if (stop) {
            _manualStopCount.value += 1
            launchCommand(recordingCommand = true) { stopRecording() }
        } else {
            launchCommand(recordingCommand = true) { startRecording() }
        }
    }

    fun configure(operation: suspend () -> Unit) {
        if (!connected() || recording() || _state.value.busy) return
        launchCommand(recordingCommand = false, operation)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun disconnected() {
        generation += 1
        commandJob?.cancel()
        commandJob = null
        _state.value = CameraCommandState()
    }

    private fun launchCommand(
        recordingCommand: Boolean,
        operation: suspend () -> Unit,
    ) {
        // Increment first so cancellation of the old command cannot clear new state.
        val currentGeneration = ++generation
        val previousJob = commandJob
        previousJob?.cancel()
        _state.value = CameraCommandState(busy = true, recordingInFlight = recordingCommand)
        commandJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Wait for cancelled BLE operations to release their locks.
                    previousJob?.join()
                    operation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == currentGeneration) {
                        _state.value = _state.value.copy(error = e.message ?: "Camera command failed")
                    }
                } finally {
                    if (generation == currentGeneration) {
                        _state.value = _state.value.copy(busy = false, recordingInFlight = false)
                        commandJob = null
                    }
                }
            }.also { it.start() }
    }
}
