package com.karoocameracontrol.radar

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.recording.AutomaticRecordingOwner
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owned by the extension service; the activity only observes it and sends overrides. */
class RadarRecordingController private constructor(context: Context) {
    private val camera = GoProManager.getInstance(context)
    private val settings = RadarRecordingSettings.getInstance(context)
    private var policy = RadarRecordingPolicy()
    private var scope: CoroutineScope? = null
    private var commandJob: Job? = null
    private var rideActive = false
    private var systemConnected = false
    private var commandError: String? = null
    private var keepAliveFailed = false
    private val mutableStatus = MutableStateFlow("Waiting for Karoo extension")
    val status = mutableStatus.asStateFlow()
    private val mutableOperationInFlight = MutableStateFlow(false)
    val operationInFlight = mutableOperationInFlight.asStateFlow()
    val isRunning: Boolean get() = scope != null
    val manualStopRequired: Boolean get() = policy.manualStopRequired

    fun start(system: KarooSystemService) {
        if (scope != null) return
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = controllerScope
        controllerScope.launch {
            while (isActive) {
                eventFlow<OnStreamState>(system, OnStreamState.StartStreaming(DataType.Type.RADAR)).collect { event ->
                    policy.onRadar(RadarData.presence(event.state), SystemClock.elapsedRealtime())
                }
                policy.onRadar(RadarPresence.UNKNOWN, SystemClock.elapsedRealtime())
                delay(5_000)
            }
        }
        controllerScope.launch {
            while (isActive) {
                eventFlow<RideState>(system, RideState.Params).collect { rideActive = it is RideState.Recording }
                rideActive = false
                delay(5_000)
            }
        }
        controllerScope.launch {
            while (true) {
                evaluate()
                delay(250)
            }
        }
        controllerScope.launch {
            keepAliveLoop(
                shouldSend = {
                    settings.settings.value.enabled && systemConnected && rideActive &&
                        camera.connectionState.value is ConnectionState.Connected &&
                        !camera.recordingCommands.state.value.busy && policy.pendingAction == null
                },
                send = camera::keepAlive,
                onResult = { error ->
                    keepAliveFailed = error != null
                    if (error != null) Log.w(TAG, "GoPro keep-alive failed", error)
                },
            )
        }
    }

    fun onSystemConnection(connected: Boolean) {
        scope?.launch {
            systemConnected = connected
            if (!connected) {
                rideActive = false
                policy.onRadar(RadarPresence.UNKNOWN, SystemClock.elapsedRealtime())
            }
        }
    }

    private inline fun <reified T : KarooEvent> eventFlow(
        system: KarooSystemService,
        params: KarooEventParams,
    ) =
        callbackFlow {
            val consumer =
                system.addConsumer<T>(
                    params,
                    onError = {
                        Log.w(TAG, "Karoo stream failed: $it")
                        close()
                    },
                    onComplete = { close() },
                ) { trySend(it) }
            awaitClose { system.removeConsumer(consumer) }
        }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val config = settings.settings.value
        val connected = camera.connectionState.value is ConnectionState.Connected
        val cameraReady = camera.recordingStateKnown.value && camera.cameraModeKnown.value
        val video = camera.cameraMode.value == 0 || camera.cameraMode.value == 1000
        if (!connected) {
            keepAliveFailed = false
            commandJob?.cancel()
            mutableOperationInFlight.value = false
        }
        if (policy.ownsRecording && policy.pendingAction == null &&
            !camera.recordingCommands.ownsRecording(AutomaticRecordingOwner.RADAR)
        ) {
            policy.manualOverride()
        }
        val action =
            policy.nextAction(
                now = now,
                enabled = config.enabled && cameraReady && !camera.recordingCommands.state.value.busy,
                rideActive = systemConnected && rideActive,
                cameraConnected = connected,
                cameraRecording = camera.isRecording.value,
                videoMode = video,
                stopDelayMs = config.stopDelaySeconds * 1000L,
            )
        if (!policy.ownsRecording && policy.pendingAction == null) commandError = null
        if (action != null) {
            mutableOperationInFlight.value = true
            commandJob =
                scope?.launch {
                    var success = false
                    try {
                        camera.recordingCommands.recordForRadar(start = action == RecordingAction.START)
                        success = true
                        commandError = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        commandError = "Camera command failed. Check camera; retrying stop."
                        Log.e(TAG, "Radar recording $action failed", error)
                    } finally {
                        policy.complete(action, success, SystemClock.elapsedRealtime())
                        mutableOperationInFlight.value = false
                    }
                }
        }
        mutableStatus.value =
            when {
                commandError != null -> commandError!!
                policy.pendingAction == RecordingAction.START -> "Starting radar recording"
                policy.pendingAction == RecordingAction.STOP -> "Stopping radar recording"
                !config.enabled -> "Radar recording off"
                !systemConnected -> "Waiting for Karoo system"
                !connected -> "Connect a GoPro to use radar recording"
                !cameraReady -> "Waiting for camera recording state and mode"
                !video -> "Select Video mode on the camera"
                !rideActive -> "Waiting for an active ride"
                keepAliveFailed -> "Camera keep-alive failed — check camera"
                policy.suppressed -> "Manual override — waiting for radar clear"
                policy.ownsRecording && policy.currentPresence(now) == RadarPresence.UNKNOWN -> "Radar unavailable — stopping after delay"
                policy.ownsRecording && policy.currentPresence(now) == RadarPresence.CLEAR -> "Radar clear — stopping after ${config.stopDelaySeconds}s"
                policy.ownsRecording -> "Recording detected traffic"
                camera.isRecording.value -> "Manual recording — radar will leave it running"
                policy.currentPresence(now) == RadarPresence.UNKNOWN -> "Waiting for radar data"
                else -> "Ready — waiting for a vehicle"
            }
    }

    /** Called on the main thread before a manual camera operation. */
    fun manualOverride(): Boolean {
        val starting = policy.manualOverride()
        commandJob?.cancel()
        commandJob = null
        mutableOperationInFlight.value = false
        commandError = null
        return starting
    }

    fun stop() {
        scope?.cancel()
        scope = null
        commandJob = null
        policy = RadarRecordingPolicy()
        rideActive = false
        systemConnected = false
        commandError = null
        mutableOperationInFlight.value = false
        keepAliveFailed = false
        mutableStatus.value = "Waiting for Karoo extension"
    }

    companion object {
        private const val TAG = "RadarRecording"

        @Volatile private var instance: RadarRecordingController? = null

        fun getInstance(context: Context): RadarRecordingController =
            instance ?: synchronized(this) {
                instance ?: RadarRecordingController(context.applicationContext).also { instance = it }
            }
    }
}
