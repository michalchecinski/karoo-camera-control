package com.karoocameracontrol.alerts

import com.karoocameracontrol.recording.AutomaticRecordingOwner
import com.karoocameracontrol.recording.CameraCommandController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAlertPolicyTest {
    private fun policy() =
        CameraAlertPolicy().apply {
            settings = CameraAlertSettings(confirmations = true, warnings = true, sound = true)
            rideActive = true
        }

    private fun CameraAlertPolicy.status(
        recording: Boolean?,
        battery: Int? = null,
        capacity: Int? = null,
        connected: Boolean = true,
    ) =
        accept(CameraAlertInput.Snapshot(CameraAlertSnapshot(connected, recording, battery, capacity)))

    @Test fun defaultsAreQuietExceptWarnings() {
        assertEquals(CameraAlertSettings(false, true, false), CameraAlertSettings())
    }

    @Test fun firstSnapshotAndReconnectNeverConfirmRecording() {
        val p = policy()
        assertTrue(p.status(true).isEmpty())
        assertEquals(CameraAlertKind.DISCONNECTED, p.status(null, connected = false).single().kind)
        assertTrue(p.status(true).isEmpty())
        assertEquals(CameraAlertKind.STOPPED, p.status(false).single().kind)
    }

    @Test fun unknownRecordingIsNotAStopAndFirstKnownStateIsBaseline() {
        val p = policy()
        assertTrue(p.status(null).isEmpty())
        assertTrue(p.status(true).isEmpty())
        assertTrue(p.status(null).isEmpty())
        assertTrue(p.status(false).isEmpty())
    }

    @Test fun physicalTransitionsConfirmOnce() {
        val p = policy()
        p.status(false)
        val started = p.status(true).single()
        assertEquals(CameraAlertKind.STARTED, started.kind)
        assertEquals(null, started.origin)
        assertTrue(p.status(true).isEmpty())
        assertEquals(CameraAlertKind.STOPPED, p.status(false).single().kind)
    }

    @Test fun acknowledgementAloneDoesNotConfirm() {
        val p = policy()
        p.status(false)
        p.accept(CameraAlertInput.CommandStarted(true, RecordingOrigin.MANUAL))
        assertTrue(p.accept(CameraAlertInput.CommandFinished(true)).isEmpty())
    }

    @Test fun commandTransitionsWaitForSuccessfulCompletionAndRetainOrigin() {
        RecordingOrigin.entries.forEach { origin ->
            val p = policy()
            p.status(false)
            p.accept(CameraAlertInput.CommandStarted(true, origin))
            assertTrue(p.status(true).isEmpty())
            val alert = p.accept(CameraAlertInput.CommandFinished(true)).single()
            assertEquals(CameraAlertKind.STARTED, alert.kind)
            assertEquals(origin, alert.origin)
            assertTrue(p.status(true).isEmpty())
            p.accept(CameraAlertInput.CommandStarted(false, origin))
            assertTrue(p.status(false).isEmpty())
            assertEquals(CameraAlertKind.STOPPED, p.accept(CameraAlertInput.CommandFinished(true)).single().kind)
        }
    }

    @Test fun failedOrCancelledCommandsDoNotConfirmIncludingLateState() {
        listOf(false, true).forEach { cancelled ->
            val p = policy()
            p.status(false)
            p.accept(CameraAlertInput.CommandStarted(true, RecordingOrigin.MANUAL))
            val failure = p.accept(CameraAlertInput.CommandFinished(false, cancelled))
            if (cancelled) assertTrue(failure.isEmpty()) else assertEquals(CameraAlertKind.FAILED, failure.single().kind)
            assertTrue(p.status(true).isEmpty())
            assertEquals(CameraAlertKind.STOPPED, p.status(false).single().kind)
        }
    }

    @Test fun cancelledStartCannotConfirmLateAfterNoOpStop() {
        val p = policy()
        p.status(false)
        p.accept(CameraAlertInput.CommandStarted(true, RecordingOrigin.DESCENT))
        p.accept(CameraAlertInput.CommandStarted(false, RecordingOrigin.MANUAL))
        assertTrue(p.accept(CameraAlertInput.CommandFinished(true)).isEmpty())
        assertTrue(p.status(true).isEmpty())
        assertEquals(CameraAlertKind.STOPPED, p.status(false).single().kind)
        assertEquals(CameraAlertKind.STARTED, p.status(true).single().kind)
    }

    @Test fun rejectedCommandAfterEncodingUpdateStillDoesNotConfirm() {
        val p = policy()
        p.status(false)
        p.accept(CameraAlertInput.CommandStarted(true, RecordingOrigin.RADAR))
        assertTrue(p.status(true).isEmpty())
        assertEquals(CameraAlertKind.FAILED, p.accept(CameraAlertInput.CommandFinished(false)).single().kind)
        assertTrue(p.status(true).isEmpty())
    }

    @Test fun retryFailuresAreDeduplicatedUntilSuccessOrPhysicalRecovery() {
        val p = policy()
        p.status(false)
        repeat(3) { attempt ->
            p.accept(CameraAlertInput.CommandStarted(true, RecordingOrigin.RADAR))
            val result = p.accept(CameraAlertInput.CommandFinished(false))
            assertEquals(if (attempt == 0) 1 else 0, result.size)
        }
        p.accept(CameraAlertInput.CommandStarted(true, RecordingOrigin.MANUAL))
        p.status(true)
        p.accept(CameraAlertInput.CommandFinished(true))
        p.accept(CameraAlertInput.CommandStarted(false, RecordingOrigin.MANUAL))
        assertEquals(CameraAlertKind.FAILED, p.accept(CameraAlertInput.CommandFinished(false)).single().kind)
    }

    @Test fun disconnectWarnsOnlyDuringRecordingOrPendingCommand() {
        val p = policy()
        p.status(false)
        assertTrue(p.status(null, connected = false).isEmpty())
        p.status(false)
        p.accept(CameraAlertInput.CommandStarted(true, RecordingOrigin.DESCENT))
        assertEquals(CameraAlertKind.DISCONNECTED, p.status(null, connected = false).single().kind)
        assertTrue(p.status(null, connected = false).isEmpty())
        assertTrue(p.accept(CameraAlertInput.CommandFinished(false)).isEmpty())
    }

    @Test fun batteryAndCapacityCrossingsGroupAndRearmWithHysteresis() {
        val p = policy()
        p.status(false, 50, 1000)
        val warning = p.status(false, 20, 300).single()
        assertEquals(CameraAlertKind.LOW_RESOURCES, warning.kind)
        assertEquals(20, warning.battery)
        assertEquals(300, warning.capacitySeconds)
        assertTrue(p.status(false, 0, 0).isEmpty())
        assertTrue(p.status(false, 21, 301).isEmpty())
        assertTrue(p.status(false, 20, 300).isEmpty())
        assertTrue(p.status(false, 25, 361).isEmpty())
        assertEquals(1, p.status(false, 19, 299).size)
    }

    @Test fun invalidResourceValuesAreIgnored() {
        val p = policy()
        assertTrue(p.status(false, -1, -1).isEmpty())
        assertTrue(p.status(false, 255, Int.MIN_VALUE).isEmpty())
        assertEquals(CameraAlertKind.LOW_RESOURCES, p.status(false, 0, 0).single().kind)
    }

    @Test fun outOfRideEventsAreConsumedWithoutPlayback() {
        val p = policy()
        p.rideActive = false
        p.status(false, 50, 1000)
        assertTrue(p.status(true, 20, 300).isEmpty())
        p.rideActive = true
        assertTrue(p.status(true, 20, 300).isEmpty())
        assertEquals(CameraAlertKind.STOPPED, p.status(false, 20, 300).single().kind)
    }

    @Test fun preferencesKeepVisualAndSoundIndependent() {
        listOf(CameraAlertSettings(true, true, false), CameraAlertSettings(false, false, true), CameraAlertSettings(false, false, false)).forEach { settings ->
            val p = policy()
            p.settings = settings
            p.status(false)
            val events = p.status(true)
            if (!settings.confirmations && !settings.sound) {
                assertTrue(events.isEmpty())
            } else {
                assertEquals(settings.confirmations, events.single().visual)
                assertEquals(settings.sound, events.single().audible)
            }
        }
        val p = policy()
        p.settings = CameraAlertSettings()
        p.status(false)
        assertTrue(p.status(true).isEmpty())
        assertTrue(p.status(null, connected = false).single().visual)
        assertFalse(p.settings.sound)
    }

    @Test fun restartSeedsCurrentStateWithoutReplayingWarnings() {
        val p = policy()
        p.seed(CameraAlertSnapshot(true, true, 10, 100))
        assertTrue(p.status(true, 10, 100).isEmpty())
        assertEquals(CameraAlertKind.STOPPED, p.status(false, 10, 100).single().kind)
    }

    @Test fun liveFeedDropsDetachedEventsAndKeepsUnknownResourcesUnknown() {
        val feed = CameraAlertFeed()
        feed.status(false, null, null)
        feed.connection(true)
        assertEquals(null, feed.snapshot.battery)
        feed.status(true, 255, -1)
        val p = policy()
        p.seed(feed.snapshot)
        val events = mutableListOf<CameraAlert>()
        feed.listener = { events += p.accept(it) }
        feed.status(true, null, null)
        assertTrue(events.isEmpty())
        feed.status(false, 10, 100)
        assertEquals(listOf(CameraAlertKind.STOPPED, CameraAlertKind.LOW_RESOURCES), events.map { it.kind })
        feed.connection(false)
        assertEquals(CameraAlertSnapshot(), feed.snapshot)
    }

    @Test fun sharedControllerEventsGateManualAndAutomaticConfirmations() =
        runBlocking {
            for (owner in listOf(null, AutomaticRecordingOwner.DESCENT, AutomaticRecordingOwner.RADAR)) {
                val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
                try {
                    var recording = false
                    val gate = CompletableDeferred<Unit>()
                    val p = policy()
                    p.status(false)
                    val alerts = mutableListOf<CameraAlert>()
                    val controller =
                        CameraCommandController(
                            scope,
                            { true },
                            { recording },
                            { true },
                            startRecording = {
                                gate.await()
                                recording = true
                                alerts += p.status(true)
                            },
                            stopRecording = {
                                recording = false
                                alerts += p.status(false)
                            },
                            alertEvent = { alerts += p.accept(it) },
                        )
                    if (owner == null) controller.toggleManually() else assertTrue(controller.startAutomatically(owner))
                    assertTrue(alerts.isEmpty())
                    gate.complete(Unit)
                    yield()
                    assertEquals(CameraAlertKind.STARTED, alerts.single().kind)
                    assertEquals(
                        when (owner) {
                            AutomaticRecordingOwner.DESCENT -> RecordingOrigin.DESCENT
                            AutomaticRecordingOwner.RADAR -> RecordingOrigin.RADAR
                            null -> RecordingOrigin.MANUAL
                        },
                        alerts.single().origin,
                    )
                    controller.toggleManually()
                    assertEquals(CameraAlertKind.STOPPED, alerts.last().kind)
                    assertEquals(RecordingOrigin.MANUAL, alerts.last().origin)
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test fun sharedControllerTimeoutWarnsOnceAndRejectsLateSuccess() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            try {
                val p = policy()
                p.status(false)
                val alerts = mutableListOf<CameraAlert>()
                val controller =
                    CameraCommandController(
                        scope,
                        { true },
                        { false },
                        { true },
                        startRecording = { withTimeout(10) { CompletableDeferred<Unit>().await() } },
                        stopRecording = {},
                        alertEvent = { alerts += p.accept(it) },
                    )
                controller.toggleManually()
                delay(50)
                assertFalse(controller.state.value.busy)
                assertEquals(CameraAlertKind.FAILED, alerts.single().kind)
                assertTrue(p.status(true).isEmpty())
            } finally {
                scope.cancel()
            }
        }

    @Test fun manualOverrideCancelsPendingStartWithoutSuccessOrFailure() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            try {
                var recording = false
                val p = policy()
                p.status(false)
                val alerts = mutableListOf<CameraAlert>()
                val controller =
                    CameraCommandController(
                        scope,
                        { true },
                        { recording },
                        { true },
                        startRecording = { CompletableDeferred<Unit>().await() },
                        stopRecording = {
                            recording = false
                            alerts += p.status(false)
                        },
                        alertEvent = { alerts += p.accept(it) },
                    )
                controller.startAutomatically()
                controller.toggleManually()
                yield()
                assertTrue(alerts.isEmpty())
                assertFalse(controller.state.value.busy)
            } finally {
                scope.cancel()
            }
        }
}
