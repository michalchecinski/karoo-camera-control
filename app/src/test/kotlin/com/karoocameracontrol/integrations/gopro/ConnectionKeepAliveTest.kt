package com.karoocameracontrol.integrations.gopro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionKeepAliveTest {
    private class Fixture(parent: CoroutineScope) {
        val parentJob = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + parentJob)
        val waits = Channel<Long>(Channel.UNLIMITED)
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val results = Channel<Exception?>(Channel.UNLIMITED)
        var time = 0L
        var awaitCommandIdle: suspend () -> Unit = {}
        val loop =
            ConnectionKeepAlive(
                scope,
                onResult = { results.trySend(it) },
                awaitCommandIdle = { awaitCommandIdle() },
                wait = { ms ->
                    waits.send(ms)
                    ticks.receive()
                    time += ms
                },
                nowMs = { time },
            )

        suspend fun tick() {
            assertEquals(3_000L, withTimeout(2_000) { waits.receive() })
            ticks.send(Unit)
            yield()
        }

        suspend fun result(): Exception? = withTimeout(2_000) { results.receive() }

        suspend fun close() {
            loop.stop()
            parentJob.cancelAndJoin()
        }
    }

    @Test fun oneLoopPerConnectionSendsOnlyAtThreeSecondIntervals() =
        runBlocking {
            val f = Fixture(this)
            try {
                var writes = 0
                val connection = Any()
                yield()
                assertTrue(f.waits.tryReceive().isFailure)
                f.loop.start(connection) { writes++ }
                f.loop.start(connection) { error("Duplicate loop") }
                yield()
                assertEquals(0, writes)
                repeat(3) {
                    f.tick()
                    assertEquals(null, f.result())
                    assertEquals(it + 1, writes)
                }
                assertArrayEquals(byteArrayOf(0x03, 0x5B, 0x01, 0x42), GoProCommands.KEEP_ALIVE)
            } finally {
                f.close()
            }
        }

    @Test fun responseTimeoutAndFailureRetryOnFollowingIntervals() =
        runBlocking {
            val f = Fixture(this)
            try {
                var writes = 0
                f.loop.start(Any()) {
                    when (++writes) {
                        1 -> withTimeout(1) { awaitCancellation() }
                        2 -> error("Rejected by camera")
                    }
                }
                repeat(2) {
                    f.tick()
                    assertTrue(f.result() is Exception)
                }
                f.tick()
                assertEquals(null, f.result())
                assertEquals(3, writes)
            } finally {
                f.close()
            }
        }

    @Test fun busyCommandDefersWriteWithoutLosingTheDueAttempt() =
        runBlocking {
            val f = Fixture(this)
            try {
                val commandComplete = CompletableDeferred<Unit>()
                f.awaitCommandIdle = { commandComplete.await() }
                var writes = 0
                f.loop.start(Any()) {
                    writes++
                }
                f.tick()
                assertEquals(0, writes)
                assertTrue(f.results.tryReceive().isFailure)
                commandComplete.complete(Unit)
                assertEquals(null, f.result())
                assertEquals(1, writes)
            } finally {
                f.close()
            }
        }

    @Test fun disconnectDuringResponseCancelsWithoutFailureAndReconnectStartsFresh() =
        runBlocking {
            val f = Fixture(this)
            try {
                var cancelled = false
                val sending = CompletableDeferred<Unit>()
                f.loop.start(Any()) {
                    try {
                        sending.complete(Unit)
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
                f.tick()
                sending.await()
                f.loop.stop()
                f.loop.start(Any()) { assertTrue(cancelled) }
                f.tick()
                assertEquals(null, f.result())
                assertTrue(f.results.tryReceive().isFailure)
            } finally {
                f.close()
            }
        }

    @Test fun shutdownWhileWaitingStopsWritesAndSameConnectionCanRestart() =
        runBlocking {
            val f = Fixture(this)
            try {
                var writes = 0
                val connection = Any()
                f.loop.start(connection) { writes++ }
                yield()
                assertEquals(3_000L, f.waits.receive())
                f.loop.shutdown()
                yield()
                assertEquals(0, writes)
                assertTrue(f.results.tryReceive().isFailure)
                // A connection handshake that finishes after shutdown cannot restart it.
                f.loop.start(Any()) { error("Started after service shutdown") }
                yield()
                assertTrue(f.waits.tryReceive().isFailure)
                f.loop.resume()
                f.loop.start(connection) { writes++ }
                f.tick()
                assertEquals(null, f.result())
                assertEquals(1, writes)
            } finally {
                f.close()
            }
        }

    @Test fun replacingSessionJoinsCancelledResponseBeforeStartingNewLoop() =
        runBlocking {
            val f = Fixture(this)
            try {
                var released = false
                val sending = CompletableDeferred<Unit>()
                f.loop.start(Any()) {
                    try {
                        sending.complete(Unit)
                        awaitCancellation()
                    } finally {
                        released = true
                    }
                }
                f.tick()
                sending.await()
                f.loop.start(Any()) { assertTrue(released) }
                f.tick()
                assertEquals(null, f.result())
                assertTrue(f.results.tryReceive().isFailure)
            } finally {
                f.close()
            }
        }

    @Test fun disconnectWhileBusyNeverWritesWhenCommandCompletes() =
        runBlocking {
            val f = Fixture(this)
            try {
                val commandComplete = CompletableDeferred<Unit>()
                f.awaitCommandIdle = { commandComplete.await() }
                var writes = 0
                f.loop.start(Any()) { writes++ }
                f.tick()
                f.loop.stop()
                commandComplete.complete(Unit)
                yield()
                assertEquals(0, writes)
                assertTrue(f.results.tryReceive().isFailure)
            } finally {
                f.close()
            }
        }

    @Test fun responseTimeDoesNotShiftCadenceAndMissedPeriodsNeverBurst() =
        runBlocking {
            val f = Fixture(this)
            try {
                var writes = 0
                f.loop.start(Any()) {
                    f.time += if (++writes == 1) 1_000 else 9_000
                }
                f.tick()
                assertEquals(null, f.result())
                assertEquals(2_000L, f.waits.receive())
                f.ticks.send(Unit)
                assertEquals(null, f.result())
                assertEquals(3_000L, f.waits.receive())
                assertFalse(f.results.tryReceive().isSuccess)
            } finally {
                f.close()
            }
        }
}
