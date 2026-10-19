package com.karoocameracontrol.integrations.gopro

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-thread requests share readiness, but a withdrawing ride policy cannot cancel a manual wake. */
internal class WakeCoordinator(private val onManualRequestsDrained: () -> Unit = {}) {
    private val mutex = Mutex()
    private var manualRequests = 0

    suspend fun run(
        forRide: Boolean,
        operation: suspend () -> Unit,
    ) {
        if (!forRide) manualRequests++
        try {
            mutex.withLock { operation() }
        } finally {
            if (!forRide) {
                manualRequests--
                if (manualRequests == 0) onManualRequestsDrained()
            }
        }
    }

    fun shouldCancelOwnedAttempt(forRide: Boolean): Boolean = !forRide || manualRequests == 0
}
