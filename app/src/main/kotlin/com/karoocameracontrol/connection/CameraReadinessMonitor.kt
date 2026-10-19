package com.karoocameracontrol.connection

import android.content.Context
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.radar.RadarRecordingSettings
import com.karoocameracontrol.recording.DescentSettingsStore
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.karoocameracontrol.diagnostics.DiagnosticLog as Log

internal fun shouldKeepCameraReady(
    systemConnected: Boolean,
    rideActive: Boolean,
    radar: Boolean,
    descent: Boolean,
    wholeRide: Boolean,
): Boolean = systemConnected && rideActive && (radar || descent || wholeRide)

/** Extension-owned ride subscription. Only this policy can enable the manager's shared loop. */
class CameraReadinessMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val system: KarooSystemService,
    private val camera: GoProManager,
) {
    private val radar = RadarRecordingSettings.getInstance(context)
    private val descent = DescentSettingsStore.getInstance(context)
    private val settings = CameraReadinessSettings.getInstance(context)
    private val connected = MutableStateFlow(false)
    private val active = MutableStateFlow(false)
    private val jobs = mutableListOf<Job>()

    fun start() {
        jobs +=
            scope.launch {
                while (isActive) {
                    callbackFlow {
                        val consumer =
                            system.addConsumer<RideState>(
                                RideState.Params,
                                onError = { close() },
                                onComplete = { close() },
                            ) { trySend(it is RideState.Recording) }
                        awaitClose { system.removeConsumer(consumer) }
                    }.collect { active.value = it }
                    active.value = false
                    delay(3_000)
                }
            }
        jobs +=
            scope.launch {
                combine(connected, active, radar.settings, descent.settings, settings.wholeRide) { systemReady, riding, radarSettings, descentSettings, wholeRide ->
                    shouldKeepCameraReady(systemReady, riding, radarSettings.enabled, descentSettings.enabled, wholeRide)
                }.distinctUntilChanged().collectLatest { enabled ->
                    camera.setRideKeepAliveEnabled(enabled)
                    if (enabled) {
                        var initialReadiness = true
                        while (isActive) {
                            camera.recordingCommands.state.first { !it.busy }
                            val state = camera.connectionState.value
                            if (camera.pairedDevices.value.isNotEmpty() &&
                                (
                                    state is ConnectionState.Disconnected || state is ConnectionState.Error ||
                                        (initialReadiness && state is ConnectionState.Connected)
                                )
                            ) {
                                try {
                                    camera.ensureCameraReady(forRide = true)
                                    initialReadiness = false
                                } catch (error: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    Log.e("CameraReadiness", "Active ride camera wake failed", error)
                                }
                            }
                            delay(5_000)
                        }
                    }
                }
            }
    }

    fun systemConnectionChanged(isConnected: Boolean) {
        if (!isConnected) active.value = false
        connected.value = isConnected
    }

    fun stop() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        active.value = false
        connected.value = false
        camera.setRideKeepAliveEnabled(false)
    }
}
