package com.karoocameracontrol.integrations.gopro

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** A BLE link can wake the camera before its command processor has finished booting. */
internal suspend fun awaitGoProBleReadiness(
    timeoutMs: Long = 30_000L,
    retryDelayMs: Long = 500L,
    probe: suspend () -> Unit,
) {
    withTimeout(timeoutMs) {
        while (true) {
            try {
                probe()
                return@withTimeout
            } catch (error: Exception) {
                // A probe's own timeout is retryable. Cancellation of this readiness
                // window or its caller must still stop all work immediately.
                currentCoroutineContext().ensureActive()
            }
            delay(retryDelayMs)
        }
    }
}
