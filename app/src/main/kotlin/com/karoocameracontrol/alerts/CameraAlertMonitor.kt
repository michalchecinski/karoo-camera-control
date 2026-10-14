package com.karoocameracontrol.alerts

import android.content.Context
import com.karoocameracontrol.R
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns the ride subscription and live feed only for the extension lifetime. */
class CameraAlertMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val system: KarooSystemService,
    private val feed: CameraAlertFeed,
    private val settings: CameraAlertSettingsStore,
) {
    private val policy = CameraAlertPolicy()
    private var consumerId: String? = null
    private var connected = false
    private var generation = 0L
    private var retryJob: Job? = null
    private var warningJob: Job? = null
    private var warning: CameraAlert? = null

    fun start() {
        policy.seed(feed.snapshot)
        feed.listener = { input ->
            if (input is CameraAlertInput.Snapshot && !input.value.connected) clearWarnings()
            policy.settings = settings.settings.value
            policy.accept(input).forEach(::notify)
        }
        feed.pendingCommand?.let { policy.accept(it) }
    }

    fun systemConnectionChanged(value: Boolean) {
        connected = value
        unsubscribe()
        if (value) subscribe()
    }

    fun stop() {
        connected = false
        feed.listener = null
        unsubscribe()
    }

    private fun subscribe() {
        val current = generation
        consumerId =
            system.addConsumer<RideState>(
                onError = { scope.launch { if (current == generation) retry() } },
                onComplete = { scope.launch { if (current == generation) retry() } },
            ) { ride ->
                scope.launch {
                    if (current != generation) return@launch
                    policy.rideActive = connected && (ride is RideState.Recording || ride is RideState.Paused)
                    if (!policy.rideActive) clearWarnings()
                }
            }
    }

    private fun retry() {
        unsubscribe()
        if (connected) {
            retryJob =
                scope.launch {
                    delay(3000)
                    subscribe()
                }
        }
    }

    private fun unsubscribe() {
        generation += 1
        consumerId?.let(system::removeConsumer)
        consumerId = null
        retryJob?.cancel()
        retryJob = null
        policy.rideActive = false
        clearWarnings()
    }

    private fun clearWarnings() {
        warningJob?.cancel()
        warningJob = null
        warning = null
    }

    private fun notify(alert: CameraAlert) {
        if (alert.kind != CameraAlertKind.LOW_RESOURCES) {
            dispatch(alert)
            return
        }
        val previous = warning
        warning = alert.copy(battery = alert.battery ?: previous?.battery, capacitySeconds = alert.capacitySeconds ?: previous?.capacitySeconds)
        if (warningJob == null) {
            warningJob =
                scope.launch {
                    // GoPro can report battery and capacity in separate adjacent packets.
                    delay(300)
                    val grouped = warning
                    warning = null
                    warningJob = null
                    if (grouped != null && connected && policy.rideActive) dispatch(grouped)
                }
        }
    }

    private fun dispatch(alert: CameraAlert) {
        // Preferences can change during warning grouping; never play an old enabled setting.
        val current = settings.settings.value
        val confirmation = alert.kind == CameraAlertKind.STARTED || alert.kind == CameraAlertKind.STOPPED
        val visual = alert.visual && if (confirmation) current.confirmations else current.warnings
        val audible = alert.audible && current.sound
        CameraAlertEffects.create(context, alert.copy(visual = visual, audible = audible)).forEach(system::dispatch)
    }
}

/** Uses only public SDK effects, with Android resource IDs supplied by this extension. */
object CameraAlertEffects {
    fun create(
        context: Context,
        alert: CameraAlert,
    ): List<KarooEffect> {
        val confirmation = alert.kind == CameraAlertKind.STARTED || alert.kind == CameraAlertKind.STOPPED
        val title =
            when (alert.kind) {
                CameraAlertKind.STARTED -> R.string.camera_alert_started
                CameraAlertKind.STOPPED -> R.string.camera_alert_stopped
                CameraAlertKind.FAILED -> R.string.camera_alert_failed
                CameraAlertKind.DISCONNECTED -> R.string.camera_alert_disconnected
                CameraAlertKind.LOW_RESOURCES -> R.string.camera_alert_low_resources
            }
        val detail =
            when (alert.kind) {
                CameraAlertKind.STARTED, CameraAlertKind.STOPPED ->
                    context.getString(
                        when (alert.origin) {
                            RecordingOrigin.MANUAL -> R.string.camera_alert_origin_manual
                            RecordingOrigin.DESCENT -> R.string.camera_alert_origin_descent
                            RecordingOrigin.RADAR -> R.string.camera_alert_origin_radar
                            null -> R.string.camera_alert_origin_camera
                        },
                    )
                CameraAlertKind.FAILED -> context.getString(R.string.camera_alert_check_camera)
                CameraAlertKind.DISCONNECTED -> context.getString(R.string.camera_alert_unknown_recording)
                CameraAlertKind.LOW_RESOURCES ->
                    listOfNotNull(
                        alert.battery?.let { context.getString(R.string.camera_alert_battery_detail, it) },
                        alert.capacitySeconds?.let { context.getString(R.string.camera_alert_capacity_detail, it / 60, it % 60) },
                    ).joinToString(" • ")
            }
        val tones =
            when (alert.kind) {
                CameraAlertKind.STARTED -> listOf(PlayBeepPattern.Tone(1800, 100), PlayBeepPattern.Tone(2400, 100))
                CameraAlertKind.STOPPED -> listOf(PlayBeepPattern.Tone(2400, 100), PlayBeepPattern.Tone(1800, 100))
                else -> listOf(PlayBeepPattern.Tone(900, 150), PlayBeepPattern.Tone(null, 100), PlayBeepPattern.Tone(900, 150))
            }
        return buildList {
            if (alert.visual) {
                add(
                    InRideAlert(
                        id = "camera-control-alert",
                        icon = R.drawable.camera_alert,
                        title = context.getString(title),
                        detail = detail,
                        autoDismissMs = if (confirmation) 3000L else 6000L,
                        backgroundColor = if (confirmation) R.color.camera_alert_confirmation else R.color.camera_alert_warning,
                        textColor = R.color.camera_alert_text,
                    ),
                )
            }
            if (alert.audible) add(PlayBeepPattern(tones))
        }
    }
}
