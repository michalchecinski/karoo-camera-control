package com.karoocameracontrol.integrations.gopro

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal const val RECORDING_START_CONFIRMATION_TIMEOUT_MS = 5_000L
internal const val RECORDING_STOP_CONFIRMATION_TIMEOUT_MS = 30_000L

/** Acknowledged Stop can take longer while the camera finishes encoding the clip. */
internal suspend fun awaitRecordingStateConfirmation(
    recording: StateFlow<Boolean>,
    target: Boolean,
    timeoutMs: Long = if (target) RECORDING_START_CONFIRMATION_TIMEOUT_MS else RECORDING_STOP_CONFIRMATION_TIMEOUT_MS,
    pollDelayMs: Long = 500L,
    poll: suspend () -> Unit,
) {
    withTimeout(timeoutMs) {
        coroutineScope {
            val polling =
                launch {
                    while (recording.value != target) {
                        try {
                            poll()
                        } catch (_: TimeoutCancellationException) {
                            // A missing query reply does not invalidate a status notification.
                            // Retry within the confirmation budget, but preserve caller cancellation.
                            currentCoroutineContext().ensureActive()
                        }
                        delay(pollDelayMs)
                    }
                }
            try {
                // Observe notifications even while a query is waiting for its response.
                recording.first { it == target }
            } finally {
                polling.cancelAndJoin()
            }
        }
    }
}
