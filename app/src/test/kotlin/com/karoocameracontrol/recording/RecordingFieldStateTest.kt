package com.karoocameracontrol.recording

import com.karoocameracontrol.alerts.CameraAlertInput
import com.karoocameracontrol.alerts.RecordingOrigin
import com.karoocameracontrol.radar.RadarPresence
import com.karoocameracontrol.radar.RadarRecordingPolicy
import com.karoocameracontrol.radar.RecordingAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFieldStateTest {
    private val ready = RecordingFieldInput(true, true, false, true, true)

    @Test
    fun `ready and confirmed recording expose explicit actions`() {
        assertEquals(RecordingFieldState(RecordingFieldStatus.READY, RecordingFieldAction.START), recordingFieldState(ready))
        assertEquals(
            RecordingFieldState(RecordingFieldStatus.RECORDING, RecordingFieldAction.STOP, seconds = 42),
            recordingFieldState(ready.copy(recording = true, seconds = 42)),
        )
    }

    @Test
    fun `unknown disconnected and wrong mode cannot start`() {
        assertEquals(RecordingFieldStatus.DISCONNECTED, recordingFieldState(ready.copy(connected = false)).status)
        assertEquals(RecordingFieldStatus.UNKNOWN, recordingFieldState(ready.copy(recordingKnown = false)).status)
        assertEquals(RecordingFieldStatus.UNKNOWN, recordingFieldState(ready.copy(videoModeKnown = false)).status)
        assertEquals(RecordingFieldStatus.VIDEO_REQUIRED, recordingFieldState(ready.copy(videoMode = false)).status)
        listOf(
            ready.copy(connected = false),
            ready.copy(recordingKnown = false),
            ready.copy(videoModeKnown = false),
            ready.copy(videoMode = false),
        ).forEach { assertNull(recordingFieldState(it).action) }
        // Stop does not depend on the camera still reporting Video mode.
        assertEquals(RecordingFieldAction.STOP, recordingFieldState(ready.copy(recording = true, videoModeKnown = false)).action)
    }

    @Test
    fun `start pending allows cancellation even before known camera status`() {
        val input = ready.copy(recordingKnown = false, command = CameraCommandState(true, true, targetRecording = true))
        assertEquals(RecordingFieldStatus.STARTING, recordingFieldState(input).status)
        assertEquals(RecordingFieldAction.STOP, recordingFieldState(input).action)
        assertEquals(RecordingFieldStatus.DISCONNECTED, recordingFieldState(input.copy(connected = false)).status)
    }

    @Test
    fun `stopping and configuration reject overlapping commands`() {
        assertEquals(
            RecordingFieldState(RecordingFieldStatus.STOPPING),
            recordingFieldState(ready.copy(command = CameraCommandState(true, true, targetRecording = false))),
        )
        assertEquals(RecordingFieldState(RecordingFieldStatus.BUSY), recordingFieldState(ready.copy(command = CameraCommandState(busy = true))))
    }

    @Test
    fun `failure permits safe retry or stop and reconnect clears unavailable controls`() {
        val failed = ready.copy(command = CameraCommandState(error = "Timed out"))
        assertEquals(RecordingFieldState(RecordingFieldStatus.FAILED, RecordingFieldAction.START, "Timed out"), recordingFieldState(failed))
        assertEquals(RecordingFieldAction.STOP, recordingFieldState(failed.copy(recording = true)).action)
        assertEquals(
            RecordingFieldState(RecordingFieldStatus.FAILED, error = "Timed out"),
            recordingFieldState(failed.copy(recordingKnown = false)),
        )
        assertNull(recordingFieldState(failed.copy(connected = false)).action)
        assertNull(recordingFieldState(ready.copy(recordingKnown = false)).action)
    }

    @Test
    fun `timer handles hours and invalid durations`() {
        assertEquals("00:00:00", recordingFieldTimer(-1))
        assertEquals("00:01:05", recordingFieldTimer(65))
        assertEquals("01:01:01", recordingFieldTimer(3661))
    }

    @Test
    fun `repeated and stale taps preserve cancellation and manual ownership`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            var recording = false
            var starts = 0
            var stops = 0
            var overrides = 0
            val startGate = CompletableDeferred<Unit>()
            val stopGate = CompletableDeferred<Unit>()
            val controller =
                CameraCommandController(
                    scope,
                    { true },
                    { recording },
                    { true },
                    {
                        starts++
                        startGate.await()
                        recording = true
                    },
                    {
                        stops++
                        stopGate.await()
                        recording = false
                    },
                )

            fun tap(action: RecordingFieldAction): Boolean =
                performRecordingFieldAction(
                    ready.copy(recording = recording, command = controller.state.value),
                    action,
                    controller,
                ) {
                    overrides++
                    false
                }
            try {
                assertTrue(tap(RecordingFieldAction.START))
                assertEquals(1, starts)
                assertFalse(tap(RecordingFieldAction.START))
                assertTrue(tap(RecordingFieldAction.STOP))
                yield()
                assertEquals(1, stops)
                assertFalse(tap(RecordingFieldAction.STOP))
                assertFalse(tap(RecordingFieldAction.START))
                assertEquals(2, overrides)
                assertEquals(1L, controller.manualStopCount.value)
                assertEquals(false, controller.state.value.targetRecording)
                stopGate.complete(Unit)
                yield()
                assertNull(controller.state.value.targetRecording)
                assertEquals(RecordingFieldAction.START, recordingFieldState(ready.copy(command = controller.state.value)).action)
                recording = true // Physical shutter change, no in-app command.
                assertFalse(tap(RecordingFieldAction.START))
                assertTrue(tap(RecordingFieldAction.STOP))
                assertEquals(2, stops)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `manual action releases automatic ownership and honors radar pending start override`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            var recording = false
            var stops = 0
            val controller =
                CameraCommandController(scope, { true }, { recording }, { true }, { recording = true }, {
                    recording = false
                    stops++
                })
            try {
                assertTrue(controller.startAutomatically(AutomaticRecordingOwner.DESCENT))
                assertTrue(controller.ownsRecording(AutomaticRecordingOwner.DESCENT))
                assertTrue(
                    performRecordingFieldAction(ready.copy(recording = true), RecordingFieldAction.STOP, controller) { false },
                )
                assertFalse(controller.ownsRecording(AutomaticRecordingOwner.DESCENT))
                assertEquals(1, stops)
                val ambiguous = ready.copy(radarStopRequired = true)
                assertFalse(performRecordingFieldAction(ambiguous, RecordingFieldAction.START, controller) { error("Stale Start released ownership") })
                assertTrue(performRecordingFieldAction(ambiguous, RecordingFieldAction.STOP, controller) { true })
                assertEquals(2, stops)
                assertFalse(recording)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `failed radar start offers cleanup Stop and rejects stale Start without releasing ownership`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val policy = RadarRecordingPolicy()
            var starts = 0
            var stops = 0
            var overrides = 0
            val controller =
                CameraCommandController(scope, { true }, { false }, { true }, {
                    starts++
                    error("Camera command timed out")
                }, { stops++ })

            fun snapshot() = ready.copy(command = controller.state.value, radarStopRequired = policy.manualStopRequired)

            fun tap(action: RecordingFieldAction) =
                performRecordingFieldAction(snapshot(), action, controller) {
                    overrides++
                    policy.manualOverride()
                }
            try {
                policy.onRadar(RadarPresence.VEHICLE, 0)
                assertEquals(RecordingAction.START, policy.nextAction(0, true, true, true, false, true, 15_000))
                assertTrue(controller.startAutomatically(AutomaticRecordingOwner.RADAR))
                policy.complete(RecordingAction.START, false, 0)
                assertEquals(
                    RecordingFieldState(RecordingFieldStatus.FAILED, RecordingFieldAction.STOP, "Camera command timed out"),
                    recordingFieldState(snapshot()),
                )
                assertFalse(tap(RecordingFieldAction.START))
                assertEquals(0, overrides)
                assertEquals(0, stops)
                assertTrue(policy.ownsRecording)
                assertTrue(controller.ownsRecording(AutomaticRecordingOwner.RADAR))
                // Clearing the error must not expose Start, even with unknown camera state/mode.
                controller.clearError()
                assertEquals(RecordingFieldAction.STOP, recordingFieldState(snapshot().copy(recordingKnown = false, videoModeKnown = false)).action)
                assertTrue(tap(RecordingFieldAction.STOP))
                assertEquals(1, starts)
                assertEquals(1, stops)
                assertEquals(1, overrides)
                assertFalse(policy.ownsRecording)
                assertFalse(controller.ownsRecording(AutomaticRecordingOwner.RADAR))
                assertEquals(RecordingFieldAction.START, recordingFieldState(snapshot()).action)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `field command target and native alert events stay synchronized during manual cancellation`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val events = mutableListOf<CameraAlertInput>()
            val startGate = CompletableDeferred<Unit>()
            val stopGate = CompletableDeferred<Unit>()
            val controller =
                CameraCommandController(
                    scope,
                    connected = { true },
                    recording = { false },
                    videoMode = { true },
                    startRecording = { startGate.await() },
                    stopRecording = { stopGate.await() },
                    alertEvent = events::add,
                )
            try {
                assertTrue(controller.startAutomatically(AutomaticRecordingOwner.DESCENT))
                assertEquals(true, controller.state.value.targetRecording)
                assertEquals(CameraAlertInput.CommandStarted(true, RecordingOrigin.DESCENT), events.single())
                assertTrue(
                    performRecordingFieldAction(ready.copy(command = controller.state.value), RecordingFieldAction.STOP, controller) { false },
                )
                yield()
                assertEquals(false, controller.state.value.targetRecording)
                assertEquals(RecordingFieldStatus.STOPPING, recordingFieldState(ready.copy(command = controller.state.value)).status)
                assertEquals(CameraAlertInput.CommandStarted(false, RecordingOrigin.MANUAL), events.last())
                assertEquals(2, events.size)
                stopGate.complete(Unit)
                yield()
                assertEquals(CameraAlertInput.CommandFinished(true, false), events.last())
                assertNull(controller.state.value.targetRecording)
                assertEquals(3, events.size)
            } finally {
                scope.cancel()
            }
        }
}
