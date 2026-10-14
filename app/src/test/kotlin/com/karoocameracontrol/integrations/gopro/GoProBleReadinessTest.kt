package com.karoocameracontrol.integrations.gopro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoProBleReadinessTest {
    @Test fun awakeCameraProceedsAfterOneSuccessfulProbe() =
        runBlocking {
            var probes = 0
            awaitGoProBleReadiness { probes++ }
            assertEquals(1, probes)
            assertArrayEquals(byteArrayOf(0x01, 0x3C), GoProCommands.GET_HARDWARE_INFO)
        }

    @Test fun bootingCameraMustAcknowledgeBeforeSetupContinues() =
        runBlocking {
            var probes = 0
            var setupContinued = false
            awaitGoProBleReadiness(retryDelayMs = 0) {
                assertFalse(setupContinued)
                if (++probes < 3) error("Camera not ready")
            }
            setupContinued = true
            assertEquals(3, probes)
            assertTrue(setupContinued)
        }

    @Test fun probeTimeoutDoesNotAbandonWakeup() =
        runBlocking {
            var probes = 0
            awaitGoProBleReadiness(retryDelayMs = 0) {
                if (++probes == 1) withTimeout(1) { awaitCancellation() }
            }
            assertEquals(2, probes)
        }

    @Test fun unresponsiveCameraNeverContinuesSetupAndReadinessIsBounded() =
        runBlocking {
            var setupContinued = false
            val result =
                withTimeoutOrNull(1_000) {
                    try {
                        awaitGoProBleReadiness(timeoutMs = 10, retryDelayMs = 0) { awaitCancellation() }
                        setupContinued = true
                        fail("An unresponsive camera must time out")
                    } catch (_: TimeoutCancellationException) {
                        // The readiness window expired, not the caller's safety timeout.
                        assertTrue(currentCoroutineContext().isActive)
                    }
                }
            assertNotNull(result)
            assertFalse(setupContinued)
        }

    @Test fun disconnectCancelsWakeupWithoutAnotherProbe() =
        runBlocking {
            val probing = CompletableDeferred<Unit>()
            var probes = 0
            val wakeup =
                launch {
                    awaitGoProBleReadiness(retryDelayMs = 0) {
                        probes++
                        probing.complete(Unit)
                        awaitCancellation()
                    }
                }
            probing.await()
            wakeup.cancelAndJoin()
            assertTrue(wakeup.isCancelled)
            assertEquals(1, probes)
        }
}
