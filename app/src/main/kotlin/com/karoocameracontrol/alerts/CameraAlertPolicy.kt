package com.karoocameracontrol.alerts

data class CameraAlertSettings(
    val confirmations: Boolean = false,
    val warnings: Boolean = true,
    val sound: Boolean = false,
)

data class CameraAlertSnapshot(
    val connected: Boolean = false,
    val recording: Boolean? = null,
    val battery: Int? = null,
    val capacitySeconds: Int? = null,
    val cameraId: Long = 0,
)

enum class RecordingOrigin { MANUAL, DESCENT, RADAR }

enum class CameraAlertKind { STARTED, STOPPED, FAILED, DISCONNECTED, LOW_RESOURCES }

data class CameraAlert(
    val kind: CameraAlertKind,
    val origin: RecordingOrigin? = null,
    val battery: Int? = null,
    val capacitySeconds: Int? = null,
    val visual: Boolean,
    val audible: Boolean,
)

sealed interface CameraAlertInput {
    data class Snapshot(val value: CameraAlertSnapshot, val recordingReported: Boolean = true) : CameraAlertInput

    data class CommandStarted(val target: Boolean, val origin: RecordingOrigin) : CameraAlertInput

    data class CommandFinished(val succeeded: Boolean, val cancelled: Boolean = false) : CameraAlertInput
}

/** No Android dependencies; called in order on main by the extension's live event feed. */
class CameraAlertPolicy(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var settings = CameraAlertSettings()
    var rideActive = false
    private var snapshot = CameraAlertSnapshot()

    private data class Pending(val target: Boolean, val origin: RecordingOrigin, val before: Boolean?, val eligible: Boolean)

    private var pending: Pending? = null
    private var suppressedTarget: Boolean? = null
    private var suppressionUntilMs = 0L

    private data class ResourceWarnings(var battery: Boolean = false, var capacity: Boolean = false)

    private val warningsByCamera = mutableMapOf<Long, ResourceWarnings>()
    private var failureWarned = false

    /** Seed a new monitor without replaying transitions or warnings from the old session. */
    fun seed(value: CameraAlertSnapshot) {
        snapshot = value
        pending = null
        suppressedTarget = null
        warningsByCamera[value.cameraId] =
            ResourceWarnings(
                battery = value.battery?.let { it in 0..20 } == true,
                capacity = value.capacitySeconds?.let { it in 0..300 } == true,
            )
        failureWarned = false
    }

    /** Rebaseline at ride/transport boundaries without rearming resource warning latches. */
    fun resetTransient(value: CameraAlertSnapshot) {
        snapshot = value
        // Retain only an ineligible request until its terminal event. Clearing it
        // outright could misclassify its late status as a physical shutter change.
        pending = pending?.copy(eligible = false)
        // Bounded ambiguity suppression still protects against a late status
        // from a request that already finished while ride alerts were unavailable.
        failureWarned = false
    }

    fun accept(input: CameraAlertInput): List<CameraAlert> =
        when (input) {
            is CameraAlertInput.Snapshot -> update(input.value, input.recordingReported)
            is CameraAlertInput.CommandStarted -> {
                // Replacement cancels the old request; its eventual status is not success.
                pending?.let { suppress(it.target) }
                pending = Pending(input.target, input.origin, snapshot.recording, eligible = rideActive)
                emptyList()
            }
            is CameraAlertInput.CommandFinished -> {
                val command = pending
                pending = null
                if (command == null) {
                    emptyList()
                } else if (!command.eligible) {
                    suppress(command.target)
                    emptyList()
                } else if (input.succeeded) {
                    failureWarned = false
                    if (suppressedTarget == command.target) suppressedTarget = null
                    if (command.before != null && command.before != command.target && snapshot.recording == command.target && snapshot.connected) {
                        listOfNotNull(alert(if (command.target) CameraAlertKind.STARTED else CameraAlertKind.STOPPED, command.origin))
                    } else {
                        emptyList()
                    }
                } else {
                    suppress(command.target)
                    if (!input.cancelled && !failureWarned) {
                        failureWarned = true
                        listOfNotNull(alert(CameraAlertKind.FAILED))
                    } else {
                        emptyList()
                    }
                }
            }
        }

    private fun suppress(target: Boolean) {
        suppressedTarget = target
        // Match the existing recording-state confirmation window. Without shutter
        // provenance, an immediate matching update after failure is ambiguous.
        suppressionUntilMs = nowMs() + 5000L
    }

    private fun update(
        value: CameraAlertSnapshot,
        recordingReported: Boolean,
    ): List<CameraAlert> {
        val previous = snapshot
        snapshot = value
        if (!value.connected) {
            val lost = previous.connected && (previous.recording == true || pending != null)
            pending = null
            suppressedTarget = null
            failureWarned = false
            return if (lost) listOfNotNull(alert(CameraAlertKind.DISCONNECTED)) else emptyList()
        }
        if (nowMs() >= suppressionUntilMs ||
            (pending == null && recordingReported && value.recording != null && value.recording != suppressedTarget)
        ) {
            suppressedTarget = null
        }
        val warned = warningsByCamera.getOrPut(value.cameraId) { ResourceWarnings() }
        val result = mutableListOf<CameraAlert>()
        if (previous.connected && previous.recording != null && value.recording != null && previous.recording != value.recording && pending == null) {
            if (suppressedTarget == value.recording) {
                suppressedTarget = null
            } else {
                failureWarned = false
                alert(if (value.recording) CameraAlertKind.STARTED else CameraAlertKind.STOPPED)?.let(result::add)
            }
        }
        // Hysteresis prevents 20/21% or 300/301s chatter from flooding the ride screen.
        if (value.battery != null && value.battery in 25..100) warned.battery = false
        if (value.capacitySeconds != null && value.capacitySeconds > 360) warned.capacity = false
        val lowBattery = value.battery?.takeIf { it in 0..20 && !warned.battery }
        val lowCapacity = value.capacitySeconds?.takeIf { it in 0..300 && !warned.capacity }
        if (lowBattery != null) warned.battery = true
        if (lowCapacity != null) warned.capacity = true
        if (lowBattery != null || lowCapacity != null) {
            alert(CameraAlertKind.LOW_RESOURCES, battery = lowBattery, capacity = lowCapacity)?.let(result::add)
        }
        return result
    }

    private fun alert(
        kind: CameraAlertKind,
        origin: RecordingOrigin? = null,
        battery: Int? = null,
        capacity: Int? = null,
    ): CameraAlert? {
        if (!rideActive) return null
        val visual = if (kind == CameraAlertKind.STARTED || kind == CameraAlertKind.STOPPED) settings.confirmations else settings.warnings
        if (!visual && !settings.sound) return null
        return CameraAlert(kind, origin, battery, capacity, visual, settings.sound)
    }
}

/** A live, unbuffered feed. Detaching the extension drops events rather than queuing old alerts. */
class CameraAlertFeed {
    var snapshot = CameraAlertSnapshot()
        private set
    var listener: ((CameraAlertInput) -> Unit)? = null
    var pendingCommand: CameraAlertInput.CommandStarted? = null
        private set
    private var selectedCamera: String? = null

    // Keys remain private/in-memory; only opaque IDs reach policy snapshots.
    private val cameraIds = mutableMapOf<String, Long>()

    fun selectCamera(key: String) {
        if (selectedCamera == key) return
        selectedCamera = key
        snapshot = CameraAlertSnapshot(cameraId = cameraIds.getOrPut(key) { cameraIds.size.toLong() + 1 })
        pendingCommand = null
        listener?.invoke(CameraAlertInput.Snapshot(snapshot))
    }

    fun status(
        recording: Boolean?,
        battery: Int?,
        capacitySeconds: Int?,
    ) {
        snapshot =
            snapshot.copy(
                recording = recording ?: snapshot.recording,
                battery = battery?.takeIf { it in 0..100 } ?: snapshot.battery,
                capacitySeconds = capacitySeconds?.takeIf { it >= 0 } ?: snapshot.capacitySeconds,
            )
        listener?.invoke(CameraAlertInput.Snapshot(snapshot, recordingReported = recording != null))
    }

    fun connection(connected: Boolean) {
        snapshot = if (connected) snapshot.copy(connected = true) else CameraAlertSnapshot(cameraId = snapshot.cameraId)
        if (!connected) pendingCommand = null
        listener?.invoke(CameraAlertInput.Snapshot(snapshot))
    }

    fun command(input: CameraAlertInput) {
        pendingCommand = input as? CameraAlertInput.CommandStarted
        listener?.invoke(input)
    }
}
