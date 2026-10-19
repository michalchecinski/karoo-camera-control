package com.karoocameracontrol.integrations.gopro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateConfirmationTest {
    @Test fun acknowledgedStopCanConfirmAfterTheOldFiveSecondDeadline() =
        runBlocking {
            val recording = MutableStateFlow(true)
            launch {
                delay(5_100)
                recording.value = false
            }
            withTimeout(10_000) {
                awaitRecordingStateConfirmation(recording, target = false, pollDelayMs = 10) {}
            }
            assertFalse(recording.value)
            assertEquals(5_000L, RECORDING_START_CONFIRMATION_TIMEOUT_MS)
            assertEquals(30_000L, RECORDING_STOP_CONFIRMATION_TIMEOUT_MS)
        }

    @Test fun notificationConfirmsStopWhileQueryResponseIsMissingAndReleasesTheQuery() =
        runBlocking {
            val recording = MutableStateFlow(true)
            val queried = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val confirmation =
                async {
                    awaitRecordingStateConfirmation(recording, target = false) {
                        queried.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            released.complete(Unit)
                        }
                    }
                }
            withTimeout(2_000) {
                queried.await()
                recording.value = false
                confirmation.await()
                released.await()
            }
        }

    @Test fun statusQueryCanConfirmStartOrStop() =
        runBlocking {
            for (target in listOf(true, false)) {
                val recording = MutableStateFlow(!target)
                awaitRecordingStateConfirmation(recording, target) { recording.value = target }
                assertEquals(target, recording.value)
            }
        }

    @Test fun matchingStateNeedsNoAdditionalQuery() =
        runBlocking {
            awaitRecordingStateConfirmation(MutableStateFlow(false), target = false) {
                error("Already confirmed")
            }
        }

    @Test fun individualQueryTimeoutCanRetryWithinConfirmationBudget() =
        runBlocking {
            val recording = MutableStateFlow(true)
            var queries = 0
            withTimeout(2_000) {
                awaitRecordingStateConfirmation(recording, target = false, pollDelayMs = 0) {
                    if (++queries == 1) withTimeout(1) { awaitCancellation() }
                    recording.value = false
                }
            }
            assertEquals(2, queries)
        }

    @Test fun unchangedStateStillTimesOutAndReleasesPendingQuery() =
        runBlocking {
            val released = CompletableDeferred<Unit>()
            var timedOut = false
            try {
                awaitRecordingStateConfirmation(MutableStateFlow(true), target = false, timeoutMs = 50) {
                    try {
                        awaitCancellation()
                    } finally {
                        released.complete(Unit)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
            }
            assertTrue(timedOut)
            assertTrue(released.isCompleted)
        }

    @Test fun cancellationReleasesPollingWithoutRetrying() =
        runBlocking {
            val queried = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            var queries = 0
            val confirmation =
                launch {
                    awaitRecordingStateConfirmation(MutableStateFlow(true), target = false) {
                        queries++
                        queried.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            released.complete(Unit)
                        }
                    }
                }
            withTimeout(2_000) {
                queried.await()
                confirmation.cancelAndJoin()
            }
            assertTrue(released.isCompleted)
            assertEquals(1, queries)
        }

    @Test fun queryFailurePropagates() =
        runBlocking {
            val failure = IllegalStateException("Camera disconnected")
            var caught: Exception? = null
            try {
                withTimeoutOrNull(2_000) {
                    awaitRecordingStateConfirmation(MutableStateFlow(true), target = false) { throw failure }
                }
            } catch (error: IllegalStateException) {
                caught = error
            }
            assertEquals(failure.javaClass, caught?.javaClass)
            assertEquals(failure.message, caught?.message)
        }
}
