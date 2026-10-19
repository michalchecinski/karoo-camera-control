package com.karoocameracontrol.integrations.gopro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCoordinatorTest {
    @Test fun manualWakeRetainsBackgroundAttemptWhenRidePauses() =
        runBlocking {
            val coordinator = WakeCoordinator()
            val connecting = CompletableDeferred<Unit>()
            val handshake = CompletableDeferred<Unit>()
            var closed = false
            val ride =
                launch {
                    coordinator.run(forRide = true) {
                        connecting.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            if (coordinator.shouldCancelOwnedAttempt(forRide = true)) closed = true
                        }
                    }
                }
            connecting.await()
            val manual =
                async {
                    coordinator.run(forRide = false) {
                        assertFalse(closed)
                        handshake.await()
                    }
                }
            yield()
            ride.cancelAndJoin() // Pause or settings disable withdraws only the ride's request.
            assertFalse(closed)
            handshake.complete(Unit)
            withTimeout(2_000) { manual.await() }
        }

    @Test fun cancelledManualWaiterDoesNotKeepAnUnusedRideAttemptAlive() =
        runBlocking {
            val coordinator = WakeCoordinator()
            val connecting = CompletableDeferred<Unit>()
            var closed = false
            val ride =
                launch {
                    coordinator.run(forRide = true) {
                        connecting.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            closed = coordinator.shouldCancelOwnedAttempt(forRide = true)
                        }
                    }
                }
            connecting.await()
            val manual = launch { coordinator.run(forRide = false) { error("Must not proceed after cancellation") } }
            yield()
            manual.cancelAndJoin()
            ride.cancelAndJoin()
            assertTrue(closed)
        }

    @Test fun cancellingQueuedManualRequestAfterRideHandoffClosesAbandonedAttempt() =
        runBlocking {
            var retained = false
            var closed = false
            val coordinator = WakeCoordinator { if (retained) closed = true }
            val connecting = CompletableDeferred<Unit>()
            val handedOff = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val ride =
                launch {
                    coordinator.run(forRide = true) {
                        connecting.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            retained = !coordinator.shouldCancelOwnedAttempt(forRide = true)
                            handedOff.complete(Unit)
                            withContext(NonCancellable) { release.await() }
                        }
                    }
                }
            connecting.await()
            val manual = launch { coordinator.run(forRide = false) { error("Cancelled waiter must not acquire the connection") } }
            yield()
            ride.cancel()
            handedOff.await()
            manual.cancelAndJoin()
            assertTrue(retained)
            assertTrue(closed)
            release.complete(Unit)
            ride.join()
        }
}
