package com.karoocameracontrol.radar

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Runs deterministic policy checks on Android without adding a test dependency. */
class RadarTestInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val tests =
            listOf(
                "detection starts exactly once" to ::detectionStartsOnce,
                "delay begins at clear and expires without another sample" to ::clearDelay,
                "new vehicle resets the full delay" to ::newVehicle,
                "manual recording is never stopped" to ::manualRecording,
                "manual override waits for clear" to ::manualOverride,
                "manual override cancels pending start" to ::pendingStartOverride,
                "manual override stops an ambiguous start" to ::failedStartOverride,
                "disabled paused disconnected and photo modes cannot start" to ::startGuards,
                "disabling or ending the ride stops owned recording" to ::stopGuards,
                "silent radar expires and stops owned recording" to ::staleRadar,
                "radar loss stops after delay" to ::radarLoss,
                "failed start is cleaned up and failed stop retries" to ::commandFailures,
                "disconnect drops ownership and cannot stop a later manual recording" to ::disconnect,
                "camera shutter stop suppresses automatic restart" to ::physicalStop,
                "physical stop of manual recording suppresses traffic encounter" to ::manualPhysicalStop,
                "manual stop on clear radar leaves next detection armed" to ::manualStopOnClear,
                "automatic stop leaves the next encounter armed" to ::automaticStopRearms,
                "keep-alive timeout reports failure and retries" to ::keepAliveTimeout,
                "keep-alive cancellation stops without reporting a camera failure" to ::keepAliveCancellation,
                "decoder separates vehicles clear and unavailable data" to ::decoder,
                "settings persist and enforce delay limits" to ::settingsPersistence,
            )
        var failures = 0
        tests.forEachIndexed { index, (name, test) ->
            val status =
                Bundle().apply {
                    putString("class", RadarTestInstrumentation::class.java.name)
                    putString("test", name)
                    putInt("current", index + 1)
                    putInt("numtests", tests.size)
                }
            sendStatus(1, status)
            try {
                test()
                sendStatus(0, status)
            } catch (error: Throwable) {
                failures++
                status.putString("stack", error.stackTraceToString())
                sendStatus(-2, status)
            }
        }
        finish(
            Activity.RESULT_OK,
            Bundle().apply { putString("stream", "\n${tests.size} radar tests, $failures failures\n") },
        )
    }

    private fun RadarRecordingPolicy.tick(
        now: Long,
        recording: Boolean = ownsRecording,
        enabled: Boolean = true,
        active: Boolean = true,
        connected: Boolean = true,
        video: Boolean = true,
        delay: Long = 15_000,
    ) = nextAction(now, enabled, active, connected, recording, video, delay)

    private fun started(): RadarRecordingPolicy =
        RadarRecordingPolicy().apply {
            onRadar(RadarPresence.VEHICLE, 0)
            check(tick(0, recording = false) == RecordingAction.START)
            complete(RecordingAction.START, true, 0)
        }

    private fun detectionStartsOnce() {
        val policy = RadarRecordingPolicy()
        policy.onRadar(RadarPresence.VEHICLE, 0)
        check(policy.tick(0, recording = false) == RecordingAction.START)
        check(policy.tick(1, recording = false) == null)
        policy.complete(RecordingAction.START, true, 2)
        check(policy.tick(3) == null)
    }

    private fun clearDelay() {
        val policy = started()
        policy.onRadar(RadarPresence.CLEAR, 1_000)
        policy.onRadar(RadarPresence.CLEAR, 2_000)
        check(policy.tick(15_999) == null)
        check(policy.tick(16_000) == RecordingAction.STOP)
        policy.complete(RecordingAction.STOP, true, 16_001)
        check(!policy.ownsRecording)
    }

    private fun newVehicle() {
        val policy = started()
        policy.onRadar(RadarPresence.CLEAR, 1_000)
        policy.onRadar(RadarPresence.VEHICLE, 15_000)
        check(policy.tick(16_000) == null)
        policy.onRadar(RadarPresence.CLEAR, 17_000)
        check(policy.tick(31_999) == null)
        check(policy.tick(32_000) == RecordingAction.STOP)
    }

    private fun manualRecording() {
        val policy = RadarRecordingPolicy()
        policy.onRadar(RadarPresence.VEHICLE, 0)
        check(policy.tick(0, recording = true) == null)
        policy.onRadar(RadarPresence.CLEAR, 1_000)
        check(policy.tick(99_000, recording = true, enabled = false, active = false) == null)
    }

    private fun manualOverride() {
        val policy = started()
        policy.manualOverride()
        policy.onRadar(RadarPresence.VEHICLE, 1_000)
        check(policy.tick(1_000, recording = false) == null)
        policy.onRadar(RadarPresence.CLEAR, 2_000)
        policy.onRadar(RadarPresence.VEHICLE, 3_000)
        check(policy.tick(3_000, recording = false) == RecordingAction.START)
    }

    private fun pendingStartOverride() {
        val policy = RadarRecordingPolicy()
        policy.onRadar(RadarPresence.VEHICLE, 0)
        policy.tick(0, recording = false)
        check(policy.manualOverride())
        policy.complete(RecordingAction.START, true, 1)
        check(!policy.ownsRecording)
        check(policy.tick(2, recording = false) == null)
    }

    private fun startGuards() {
        val policy = RadarRecordingPolicy()
        policy.onRadar(RadarPresence.VEHICLE, 0)
        check(policy.tick(0, enabled = false) == null)
        check(policy.tick(0, active = false) == null)
        check(policy.tick(0, connected = false) == null)
        check(policy.tick(0, video = false) == null)
        check(policy.tick(5_000) == null)
    }

    private fun failedStartOverride() {
        val policy = RadarRecordingPolicy()
        policy.onRadar(RadarPresence.VEHICLE, 0)
        policy.tick(0, recording = false)
        policy.complete(RecordingAction.START, false, 100)
        check(policy.manualOverride())
        check(!policy.ownsRecording)
        check(policy.tick(200, recording = false) == null)
    }

    private fun stopGuards() {
        check(started().tick(1_000, enabled = false) == RecordingAction.STOP)
        check(started().tick(1_000, active = false) == RecordingAction.STOP)
        check(started().tick(1_000, video = false) == RecordingAction.STOP)
    }

    private fun staleRadar() {
        val policy = started()
        check(policy.tick(4_999) == null)
        check(policy.currentPresence(5_000) == RadarPresence.UNKNOWN)
        check(policy.tick(19_999) == null)
        check(policy.tick(20_000) == RecordingAction.STOP)
    }

    private fun radarLoss() {
        val policy = started()
        policy.onRadar(RadarPresence.UNKNOWN, 1_000)
        check(policy.tick(15_999) == null)
        check(policy.tick(16_000) == RecordingAction.STOP)
    }

    private fun commandFailures() {
        val policy = RadarRecordingPolicy()
        policy.onRadar(RadarPresence.VEHICLE, 0)
        policy.tick(0, recording = false)
        policy.complete(RecordingAction.START, false, 100)
        check(policy.tick(5_099, recording = false) == null)
        check(policy.tick(5_100, recording = false) == RecordingAction.STOP)
        policy.complete(RecordingAction.STOP, false, 5_200)
        check(policy.ownsRecording)
        check(policy.tick(10_199, recording = true) == null)
        check(policy.tick(10_200, recording = true) == RecordingAction.STOP)
        policy.complete(RecordingAction.STOP, true, 10_300)
        check(!policy.ownsRecording)
    }

    private fun disconnect() {
        val policy = started()
        check(policy.tick(1_000, connected = false) == null)
        check(!policy.ownsRecording)
        policy.onRadar(RadarPresence.CLEAR, 2_000)
        check(policy.tick(99_000, recording = true) == null)
    }

    private fun physicalStop() {
        val policy = started()
        check(policy.tick(1_000, recording = false) == null)
        policy.onRadar(RadarPresence.VEHICLE, 2_000)
        check(policy.tick(2_000, recording = false) == null)
        check(policy.suppressed)
    }

    private fun manualPhysicalStop() {
        val policy = RadarRecordingPolicy()
        policy.onRadar(RadarPresence.VEHICLE, 0)
        check(policy.tick(0, recording = true) == null)
        check(policy.tick(1_000, recording = false) == null)
        check(policy.suppressed)
        policy.onRadar(RadarPresence.VEHICLE, 2_000)
        check(policy.tick(2_000, recording = false) == null)
        policy.onRadar(RadarPresence.CLEAR, 3_000)
        policy.onRadar(RadarPresence.VEHICLE, 4_000)
        check(policy.tick(4_000, recording = false) == RecordingAction.START)
    }

    private fun manualStopOnClear() {
        val policy = RadarRecordingPolicy()
        policy.onRadar(RadarPresence.CLEAR, 0)
        check(policy.tick(0, recording = true) == null)
        check(policy.tick(1_000, recording = false) == null)
        check(!policy.suppressed)
        policy.onRadar(RadarPresence.VEHICLE, 2_000)
        check(policy.tick(2_000, recording = false) == RecordingAction.START)
    }

    private fun automaticStopRearms() {
        val policy = started()
        policy.onRadar(RadarPresence.CLEAR, 1_000)
        check(policy.tick(16_000) == RecordingAction.STOP)
        policy.complete(RecordingAction.STOP, true, 16_100)
        policy.onRadar(RadarPresence.VEHICLE, 16_200)
        check(policy.tick(16_200, recording = false) == RecordingAction.START)
    }

    private fun keepAliveTimeout() =
        runBlocking {
            var attempts = 0
            val failure = CompletableDeferred<Exception>()
            val recovered = CompletableDeferred<Unit>()
            val job =
                launch {
                    keepAliveLoop(
                        intervalMs = 1,
                        shouldSend = { true },
                        send = {
                            attempts++
                            if (attempts == 1) withTimeout(1) { delay(Long.MAX_VALUE) }
                        },
                        onResult = { error ->
                            if (error != null) failure.complete(error) else recovered.complete(Unit)
                        },
                    )
                }
            try {
                withTimeout(2_000) { recovered.await() }
                check(withTimeout(2_000) { failure.await() } is TimeoutCancellationException)
                check(attempts >= 2)
            } finally {
                job.cancelAndJoin()
            }
        }

    private fun keepAliveCancellation() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            var reportedResults = 0
            var attempts = 0
            val job =
                launch {
                    keepAliveLoop(
                        intervalMs = 1,
                        shouldSend = { true },
                        send = {
                            attempts++
                            started.complete(Unit)
                            delay(Long.MAX_VALUE)
                        },
                        onResult = { reportedResults++ },
                    )
                }
            try {
                withTimeout(2_000) { started.await() }
            } finally {
                job.cancelAndJoin()
            }
            check(job.isCancelled)
            check(attempts == 1)
            check(reportedResults == 0)
        }

    private fun decoder() {
        fun decode(vararg values: Pair<String, Double>) =
            RadarData.presence(
                StreamState.Streaming(DataPoint(DataType.Type.RADAR, mapOf(*values))),
            )
        val threat = DataType.Field.RADAR_THREAT_LEVEL
        check(decode(threat to 1.0) == RadarPresence.VEHICLE)
        check(decode(threat to 2.0) == RadarPresence.VEHICLE)
        check(decode(threat to 0.0, DataType.Field.RADAR_TARGET_1_RANGE to 42.0) == RadarPresence.CLEAR)
        check(decode(threat to 1.0, DataType.Field.RADAR_ERROR to 1.0) == RadarPresence.UNKNOWN)
        check(decode(threat to Double.NaN) == RadarPresence.UNKNOWN)
        check(decode(threat to -1.0) == RadarPresence.UNKNOWN)
        check(decode() == RadarPresence.UNKNOWN)
        check(RadarData.presence(StreamState.Searching) == RadarPresence.UNKNOWN)
        check(RadarData.presence(StreamState.NotAvailable) == RadarPresence.UNKNOWN)
        check(RadarData.presence(StreamState.Idle) == RadarPresence.UNKNOWN)
    }

    private fun settingsPersistence() {
        // Use the test APK's context so production preferences are untouched.
        val settings = RadarRecordingSettings(context)
        settings.setEnabled(true)
        settings.setStopDelay(30)
        check(RadarRecordingSettings(context).settings.value == RadarSettings(true, 30))
        settings.setStopDelay(-1)
        check(settings.settings.value.stopDelaySeconds == 5)
        settings.setStopDelay(200)
        check(settings.settings.value.stopDelaySeconds == 120)
        settings.setEnabled(false)
        settings.setStopDelay(15)
    }
}
