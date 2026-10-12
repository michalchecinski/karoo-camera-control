package com.karoocameracontrol.recording

data class DescentSettings(
    val enabled: Boolean = false,
    val gradientPercent: Int = 3,
    val confirmationSeconds: Int = 3,
) {
    init {
        require(gradientPercent in 1..10)
        require(confirmationSeconds in 1..10)
    }
}

/** Consumes one opportunity per descent; missing data never releases the latch. */
class DescentDetector {
    var descentConsumed: Boolean = false
        private set

    val confirming: Boolean
        get() = descentSinceMs != null

    private var descentSinceMs: Long? = null
    private var flatSinceMs: Long? = null
    private var lastSampleMs: Long? = null
    private var lastSettings: DescentSettings? = null

    fun onSample(
        nowMs: Long,
        gradientPercent: Double?,
        speedMetersPerSecond: Double?,
        rideActive: Boolean,
        settings: DescentSettings,
    ): Boolean {
        if (lastSettings != settings) {
            interrupt()
            lastSettings = settings
        }
        val previousMs = lastSampleMs
        if (previousMs != null && (nowMs < previousMs || nowMs - previousMs > MAX_SAMPLE_AGE_MS)) {
            interrupt()
        }
        lastSampleMs = nowMs

        if (!settings.enabled || !rideActive ||
            gradientPercent == null || !gradientPercent.isFinite() ||
            speedMetersPerSecond == null || !speedMetersPerSecond.isFinite() ||
            speedMetersPerSecond < MIN_SPEED_METERS_PER_SECOND
        ) {
            interrupt()
            return false
        }

        if (descentConsumed) {
            if (gradientPercent >= REARM_GRADIENT_PERCENT) {
                val since = flatSinceMs ?: nowMs.also { flatSinceMs = it }
                if (nowMs - since >= REARM_DURATION_MS) {
                    descentConsumed = false
                    interrupt()
                }
            } else {
                flatSinceMs = null
            }
            return false
        }

        if (gradientPercent > -settings.gradientPercent) {
            descentSinceMs = null
            return false
        }
        val since = descentSinceMs ?: nowMs.also { descentSinceMs = it }
        if (nowMs - since < settings.confirmationSeconds * 1000L) return false

        descentConsumed = true
        interrupt()
        return true
    }

    fun suppressUntilNextDescent() {
        descentConsumed = true
        interrupt()
    }

    fun interrupt() {
        descentSinceMs = null
        flatSinceMs = null
        lastSampleMs = null
    }

    fun endRide() {
        descentConsumed = false
        interrupt()
    }

    companion object {
        const val MAX_SAMPLE_AGE_MS = 2500L
        const val MIN_SPEED_METERS_PER_SECOND = 5.0 / 3.6
        private const val REARM_GRADIENT_PERCENT = -1.0
        private const val REARM_DURATION_MS = 3000L
    }
}
