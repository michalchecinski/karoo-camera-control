package com.karoocameracontrol.recording

import com.karoocameracontrol.alerts.CameraAlertFeed
import com.karoocameracontrol.alerts.RecordingOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAndRecordTest {
    private class Fixture(parent: CoroutineScope) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        var connected = false
        var recording = false
        var video = true
        var starts = 0
        var stops = 0
        var wakeCalls = 0
        var wakeReleased = false
        val ready = CompletableDeferred<Unit>()
        var prepare: suspend () -> Unit = {
            ready.await()
            connected = true
        }
        val controller =
            CameraCommandController(
                scope,
                connected = { connected },
                recording = { recording },
                videoMode = { video },
                startRecording = {
                    starts++
                    recording = true
                },
                stopRecording = {
                    stops++
                    recording = false
                },
                canWake = { true },
                prepareRecording = {
                    wakeCalls++
                    try {
                        prepare()
                    } finally {
                        wakeReleased = true
                    }
                },
            )

        suspend fun close() = job.cancelAndJoin()
    }

    @Test fun onePressWaitsForReadinessThenStartsExactlyOnce() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.controller.startManually()
                yield()
                assertTrue(f.controller.state.value.waking)
                assertEquals(0, f.starts)
                f.controller.startManually()
                f.ready.complete(Unit)
                yield()
                assertEquals(1, f.wakeCalls)
                assertEquals(1, f.starts)
                assertTrue(f.recording)
                assertFalse(f.controller.state.value.busy)
            } finally {
                f.close()
            }
        }

    @Test fun cancelDuringWakeNeverSendsShutterEvenIfReadinessArrivesLater() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.controller.toggleManually()
                yield()
                f.controller.toggleManually()
                f.ready.complete(Unit)
                yield()
                assertTrue(f.wakeReleased)
                assertEquals(0, f.starts)
                assertEquals(0, f.stops)
                assertFalse(f.controller.state.value.busy)
                assertNull(f.controller.state.value.error)
                assertEquals(1L, f.controller.manualStopCount.value)
            } finally {
                f.close()
            }
        }

    @Test fun connectionSetupTransitionsPreserveWakeButExplicitDisconnectInvalidatesIt() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.controller.startManually()
                yield()
                f.controller.disconnected(preserveWake = true)
                assertTrue(f.controller.state.value.waking)
                f.controller.disconnected()
                f.ready.complete(Unit)
                yield()
                assertEquals(0, f.starts)
                assertTrue(f.wakeReleased)
            } finally {
                f.close()
            }
        }

    @Test fun readinessTimeoutReleasesControlsAndAnExplicitRetryCanSucceed() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.prepare = { withTimeout(1) { awaitCancellation() } }
                f.controller.startManually()
                withTimeout(2_000) {
                    while (f.controller.state.value.busy) yield()
                }
                assertEquals("Camera command timed out", f.controller.state.value.error)
                assertEquals(0, f.starts)
                f.prepare = { f.connected = true }
                f.controller.startManually()
                yield()
                assertEquals(1, f.starts)
                assertNull(f.controller.state.value.error)
            } finally {
                f.close()
            }
        }

    @Test fun alreadyRecordingAfterWakeIsPreservedAndNeverClaimed() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.prepare = {
                    f.connected = true
                    f.recording = true
                }
                f.controller.startManually()
                yield()
                assertTrue(f.recording)
                assertEquals(0, f.starts)
                assertEquals(0, f.stops)
                assertFalse(f.controller.ownsRecording(AutomaticRecordingOwner.RADAR))
                assertFalse(f.controller.ownsRecording(AutomaticRecordingOwner.DESCENT))
            } finally {
                f.close()
            }
        }

    @Test fun wrongModeOrFailedReadinessDoesNotSendStart() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.prepare = {
                    f.connected = true
                    f.video = false
                }
                f.controller.startManually()
                yield()
                assertEquals(0, f.starts)
                assertEquals("Select Video mode on the camera", f.controller.state.value.error)
                f.prepare = { error("Camera unavailable") }
                f.controller.startManually()
                yield()
                assertEquals(0, f.starts)
                assertEquals("Camera unavailable", f.controller.state.value.error)
            } finally {
                f.close()
            }
        }

    @Test fun onceShutterIsSentCancelUsesStopInsteadOfWakeCancellation() =
        runBlocking {
            val f = Fixture(this)
            val shutter = CompletableDeferred<Unit>()
            val controller =
                CameraCommandController(
                    f.scope,
                    { f.connected },
                    { false },
                    { true },
                    {
                        f.starts++
                        shutter.await()
                    },
                    { f.stops++ },
                    canWake = { true },
                    prepareRecording = { f.connected = true },
                )
            try {
                controller.startManually()
                yield()
                assertFalse(controller.state.value.waking)
                controller.toggleManually()
                yield()
                assertEquals(1, f.starts)
                assertEquals(1, f.stops)
            } finally {
                f.close()
            }
        }

    @Test fun serviceShutdownCancelsWakeWithoutShutterAndPreservesExistingClips() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.controller.startManually()
                yield()
                f.controller.cancelForServiceShutdown()
                f.ready.complete(Unit)
                yield()
                assertEquals(0, f.starts)
                assertEquals(0, f.stops)
                f.recording = true
                f.controller.cancelForServiceShutdown()
                assertTrue(f.recording)
            } finally {
                f.close()
            }
        }

    @Test fun serviceShutdownAfterShutterUsesBoundedStopCleanup() =
        runBlocking {
            val f = Fixture(this)
            val shutter = CompletableDeferred<Unit>()
            val controller =
                CameraCommandController(
                    f.scope,
                    { f.connected },
                    { false },
                    { true },
                    {
                        f.starts++
                        shutter.await()
                    },
                    { f.stops++ },
                    canWake = { true },
                    prepareRecording = { f.connected = true },
                )
            try {
                controller.startManually()
                yield()
                controller.cancelForServiceShutdown()
                yield()
                assertEquals(1, f.starts)
                assertEquals(1, f.stops)
            } finally {
                f.close()
            }
        }

    @Test fun shutterRequestRemainsBoundToAlertFeedAfterWakeSelectsTheCamera() =
        runBlocking {
            val f = Fixture(this)
            val feed = CameraAlertFeed()
            val controller =
                CameraCommandController(
                    f.scope, { f.connected }, { false }, { true },
                    {
                        assertNotNull(feed.pendingCommand)
                        assertEquals(RecordingOrigin.MANUAL, feed.pendingCommand?.origin)
                        f.starts++
                    }, {},
                    alertEvent = feed::command,
                    canWake = { true },
                    prepareRecording = {
                        // Production connection selection resets an earlier pending alert command.
                        feed.selectCamera("sample")
                        feed.connection(true)
                        feed.status(false, null, null)
                        f.connected = true
                    },
                )
            try {
                controller.startManually()
                yield()
                assertEquals(1, f.starts)
                assertNull(controller.state.value.error)
            } finally {
                f.close()
            }
        }

    @Test fun disconnectedFieldOffersWakeAndRejectsRepeatedStartWhileWaking() =
        runBlocking {
            val f = Fixture(this)
            val input = RecordingFieldInput(false, false, false, false, false, canWake = true)

            fun tap(action: RecordingFieldAction) =
                performRecordingFieldAction(input.copy(command = f.controller.state.value), action, f.controller) { false }
            try {
                assertEquals(RecordingFieldStatus.WAKE_READY, recordingFieldState(input).status)
                assertTrue(tap(RecordingFieldAction.START))
                yield()
                assertEquals(RecordingFieldStatus.WAKING, recordingFieldState(input.copy(command = f.controller.state.value)).status)
                assertFalse(tap(RecordingFieldAction.START))
                assertTrue(tap(RecordingFieldAction.STOP))
                yield()
                assertEquals(0, f.starts)
                assertEquals(0, f.stops)
            } finally {
                f.close()
            }
        }
}
