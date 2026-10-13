package com.karoocameracontrol.recording

import android.os.SystemClock
import android.util.Log
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.GoProManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The extension owns subscriptions; all sensor and command handling runs on main. */
class DescentRecordingMonitor(
    private val scope: CoroutineScope,
    private val karooSystem: KarooSystemService,
    private val settingsStore: DescentSettingsStore,
    private val camera: GoProManager,
) {
    private val detector = DescentDetector()
    private val consumerIds = mutableListOf<String>()
    private var rideActive = false
    private var systemConnected = false
    private var gradient: TimedRideValue? = null
    private var speed: TimedRideValue? = null
    private var settingsJob: Job? = null
    private var manualStopJob: Job? = null
    private var subscriptionGeneration = 0L
    private var retryAtMs: Long? = null

    fun start() {
        manualStopJob =
            scope.launch {
                var lastCount = camera.recordingCommands.manualStopCount.value
                camera.recordingCommands.manualStopCount.collect { count ->
                    if (count != lastCount) {
                        detector.suppressUntilNextDescent()
                        lastCount = count
                    }
                }
            }
        settingsJob =
            scope.launch {
                settingsStore.settings.collectLatest { settings ->
                    unsubscribe()
                    subscribe(settings.enabled)
                    try {
                        while (isActive) {
                            retryAtMs?.let { retryMs ->
                                if (SystemClock.elapsedRealtime() >= retryMs) {
                                    unsubscribe()
                                    subscribe(settings.enabled)
                                }
                            }
                            evaluate()
                            delay(if (settings.enabled) 250 else 1000)
                        }
                    } finally {
                        unsubscribe()
                    }
                }
            }
    }

    fun systemConnectionChanged(connected: Boolean) {
        systemConnected = connected
        if (!connected) clearTelemetry()
    }

    fun stop() {
        settingsJob?.cancel()
        manualStopJob?.cancel()
        unsubscribe()
        settingsStore.setStatus(if (settingsStore.settings.value.enabled) DescentStatus.WAITING_FOR_KAROO else DescentStatus.OFF)
    }

    private fun subscribe(enabled: Boolean) {
        val generation = subscriptionGeneration
        consumerIds +=
            karooSystem.addConsumer<RideState>(
                onError = { message -> scope.launch { if (generation == subscriptionGeneration) streamFailed(message) } },
                onComplete = { scope.launch { if (generation == subscriptionGeneration) streamFailed("Ride stream ended") } },
            ) { state ->
                scope.launch {
                    if (generation != subscriptionGeneration) return@launch
                    rideActive = state is RideState.Recording
                    if (!rideActive) detector.interrupt()
                    if (state is RideState.Idle) {
                        val settings = settingsStore.settings.value
                        if (settings.enabled && settings.stopAtDescentEnd) camera.recordingCommands.stopAutomatically()
                        detector.endRide()
                    }
                }
            }
        // Keep observing ride end while disabled, without streaming sensor data.
        if (enabled) {
            subscribeData(DataType.Type.ELEVATION_GRADE, DataType.Field.ELEVATION_GRADE) { gradient = it }
            subscribeData(DataType.Type.SPEED, DataType.Field.SPEED) { speed = it }
        }
    }

    private fun subscribeData(
        type: String,
        field: String,
        update: (TimedRideValue?) -> Unit,
    ) {
        val generation = subscriptionGeneration
        consumerIds +=
            karooSystem.addConsumer<OnStreamState>(
                OnStreamState.StartStreaming(type),
                onError = { message -> scope.launch { if (generation == subscriptionGeneration) streamFailed(message) } },
                onComplete = { scope.launch { if (generation == subscriptionGeneration) streamFailed("Data stream ended") } },
            ) { event ->
                // Timestamp at receipt, before handing off from the Binder thread.
                val receivedMs = SystemClock.elapsedRealtime()
                val value = (event.state as? StreamState.Streaming)?.dataPoint?.values?.get(field)
                scope.launch {
                    if (generation != subscriptionGeneration) return@launch
                    update(value?.takeIf { it.isFinite() }?.let { TimedRideValue(it, receivedMs) })
                    if (value == null || !value.isFinite()) detector.interrupt()
                }
            }
    }

    private fun streamFailed(message: String) {
        Log.w("DescentRecording", "Karoo stream failed: $message")
        clearTelemetry()
        retryAtMs = SystemClock.elapsedRealtime() + 3000L
    }

    private fun unsubscribe() {
        subscriptionGeneration += 1
        retryAtMs = null
        consumerIds.forEach(karooSystem::removeConsumer)
        consumerIds.clear()
        clearTelemetry()
    }

    private fun clearTelemetry() {
        rideActive = false
        gradient = null
        speed = null
        detector.interrupt()
    }

    private fun evaluate() {
        val settings = settingsStore.settings.value
        val nowMs = SystemClock.elapsedRealtime()
        val freshGradient = gradient?.freshValue(nowMs)
        val freshSpeed = speed?.freshValue(nowMs)
        when (detector.onSample(nowMs, freshGradient, freshSpeed, systemConnected && rideActive, settings)) {
            DescentEvent.STARTED -> camera.recordingCommands.startAutomatically()
            DescentEvent.ENDED -> if (settings.stopAtDescentEnd) camera.recordingCommands.stopAutomatically()
            DescentEvent.NONE -> {}
        }
        val status =
            when {
                !settings.enabled -> DescentStatus.OFF
                !systemConnected -> DescentStatus.WAITING_FOR_KAROO
                !rideActive -> DescentStatus.WAITING_FOR_RIDE
                camera.connectionState.value !is ConnectionState.Connected -> DescentStatus.WAITING_FOR_CAMERA
                camera.cameraMode.value != 0 && camera.cameraMode.value != 1000 -> DescentStatus.VIDEO_REQUIRED
                detector.descentConsumed -> DescentStatus.CONSUMED
                freshGradient == null || freshSpeed == null -> DescentStatus.WAITING_FOR_DATA
                detector.confirming -> DescentStatus.CONFIRMING
                else -> DescentStatus.READY
            }
        settingsStore.setStatus(status)
    }
}
