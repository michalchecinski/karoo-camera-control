package com.karoocameracontrol.radar

enum class RadarPresence { UNKNOWN, CLEAR, VEHICLE }

enum class RecordingAction { START, STOP }

/** A monotonic-clock state machine. Only recordings started here may be stopped here. */
class RadarRecordingPolicy {
    var ownsRecording = false
        private set
    var pendingAction: RecordingAction? = null
        private set
    var suppressed = false
        private set
    var presence = RadarPresence.UNKNOWN
        private set
    private var lastSampleAt: Long? = null
    private var clearSince: Long? = null
    private var retryAfter = 0L
    private var startFailed = false
    private var lastCameraRecording: Boolean? = null

    fun onRadar(
        presence: RadarPresence,
        now: Long,
    ) {
        this.presence = presence
        lastSampleAt = now
        if (presence == RadarPresence.VEHICLE) {
            clearSince = null
        } else if (clearSince == null) {
            clearSince = now
        }
        if (presence == RadarPresence.CLEAR) suppressed = false
    }

    fun currentPresence(now: Long): RadarPresence =
        if (lastSampleAt?.let { now - it < STALE_AFTER_MS } == true) presence else RadarPresence.UNKNOWN

    fun nextAction(
        now: Long,
        enabled: Boolean,
        rideActive: Boolean,
        cameraConnected: Boolean,
        cameraRecording: Boolean,
        videoMode: Boolean,
        stopDelayMs: Long,
    ): RecordingAction? {
        if (!cameraConnected) {
            if (ownsRecording) suppressed = true
            ownsRecording = false
            pendingAction = null
            startFailed = false
            lastCameraRecording = null
            return null
        }

        val current = currentPresence(now)
        if (lastCameraRecording == true && !cameraRecording && !ownsRecording && current != RadarPresence.CLEAR) {
            // A manual recording stopped on the camera must also override radar.
            suppressed = true
        }
        lastCameraRecording = cameraRecording
        if (pendingAction != null || now < retryAfter) return null

        if (current == RadarPresence.UNKNOWN && clearSince == null) {
            clearSince = lastSampleAt?.plus(STALE_AFTER_MS) ?: now
        }
        if (ownsRecording) {
            // A physical camera stop also overrides automation for this encounter.
            if (!cameraRecording && !startFailed) {
                ownsRecording = false
                suppressed = true
                return null
            }
            if (startFailed || !enabled || !rideActive || !videoMode ||
                (current != RadarPresence.VEHICLE && now - (clearSince ?: now) >= stopDelayMs)
            ) {
                pendingAction = RecordingAction.STOP
            }
        } else if (enabled && rideActive && videoMode && !cameraRecording &&
            !suppressed && current == RadarPresence.VEHICLE
        ) {
            ownsRecording = true
            pendingAction = RecordingAction.START
        }
        return pendingAction
    }

    fun complete(
        action: RecordingAction,
        success: Boolean,
        now: Long,
    ) {
        if (pendingAction != action) return
        pendingAction = null
        retryAfter = if (success) 0L else now + RETRY_AFTER_MS
        if (action == RecordingAction.START && !success) {
            // A timeout may follow a successful shutter write. Attempt a confirmed stop.
            startFailed = true
            suppressed = true
        }
        if (action == RecordingAction.STOP && success) {
            ownsRecording = false
            startFailed = false
            // This confirmed stop was automatic, not an external shutter override.
            lastCameraRecording = false
        }
    }

    /** Returns true if a start may already have reached the camera. */
    fun manualOverride(): Boolean {
        val starting = pendingAction == RecordingAction.START || startFailed
        ownsRecording = false
        pendingAction = null
        startFailed = false
        suppressed = true
        retryAfter = 0L
        return starting
    }

    companion object {
        const val STALE_AFTER_MS = 5_000L
        const val RETRY_AFTER_MS = 5_000L
    }
}
