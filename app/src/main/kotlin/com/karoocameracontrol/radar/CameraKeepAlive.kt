package com.karoocameracontrol.radar

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** Operation timeouts must not cancel the periodic task that keeps the camera ready. */
internal suspend fun keepAliveLoop(
    intervalMs: Long = 3_000,
    shouldSend: () -> Boolean,
    send: suspend () -> Unit,
    onResult: (Exception?) -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        if (shouldSend()) {
            try {
                send()
                onResult(null)
            } catch (error: Exception) {
                // An internal withTimeout leaves this loop's job active. Actual
                // service teardown cancels it and must propagate without retrying.
                currentCoroutineContext().ensureActive()
                onResult(error)
            }
        }
        delay(intervalMs)
    }
}
