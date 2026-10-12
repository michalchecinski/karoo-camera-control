package com.karoocameracontrol.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/** Runnable deterministic checks, using existing Kotlin/coroutines dependencies. */
object DescentRecordingChecks {
    private var passed = 0
    private val settings = DescentSettings(enabled = true)

    @JvmStatic
    fun main(args: Array<String>) {
        detectorChecks()
        commandChecks()
        println("Passed $passed descent recording checks")
    }

    private fun verify(
        name: String,
        block: () -> Unit,
    ) {
        block()
        passed += 1
        println("PASS: $name")
    }

    private fun DescentDetector.sample(
        timeMs: Long,
        grade: Double? = -4.0,
        speed: Double? = 4.0,
        active: Boolean = true,
        config: DescentSettings = settings,
    ) = onSample(timeMs, grade, speed, active, config)

    private fun detectorChecks() {
        verify("cached telemetry expires at 2.5 seconds and rejects future or non-finite values") {
            check(TimedRideValue(4.0, 1000).freshValue(3500) == 4.0)
            check(TimedRideValue(4.0, 1000).freshValue(3501) == null)
            check(TimedRideValue(4.0, 1000).freshValue(999) == null)
            check(TimedRideValue(Double.NaN, 1000).freshValue(1000) == null)
        }
        verify("sustained descent fires once at the confirmation boundary") {
            val detector = DescentDetector()
            for (time in 0L..2000L step 1000L) check(!detector.sample(time))
            check(detector.sample(3000))
            for (time in 4000L..20000L step 1000L) check(!detector.sample(time))
        }
        verify("brief and fluctuating slopes restart confirmation") {
            val detector = DescentDetector()
            check(!detector.sample(0))
            check(!detector.sample(1000, grade = -2.0))
            for (time in 2000L..4000L step 1000L) check(!detector.sample(time))
            check(detector.sample(5000))
        }
        verify("threshold is inclusive and settings are configurable") {
            val detector = DescentDetector()
            val config = settings.copy(gradientPercent = 5, confirmationSeconds = 2)
            check(!detector.sample(0, grade = -4.9, config = config))
            check(!detector.sample(1000, grade = -5.0, config = config))
            check(!detector.sample(2000, grade = -5.0, config = config))
            check(detector.sample(3000, grade = -5.0, config = config))
        }
        verify("slow and stationary riding cannot confirm or rearm") {
            for (speed in listOf(0.0, -1.0, 1.0)) {
                val detector = DescentDetector()
                for (time in 0L..10000L step 1000L) check(!detector.sample(time, speed = speed))
                detector.suppressUntilNextDescent()
                for (time in 11000L..16000L step 1000L) check(!detector.sample(time, grade = 0.0, speed = speed))
                check(detector.descentConsumed)
            }
        }
        verify("five km/h meets the movement threshold") {
            val detector = DescentDetector()
            for (time in 0L..2000L step 1000L) check(!detector.sample(time, speed = 5.0 / 3.6))
            check(detector.sample(3000, speed = 5.0 / 3.6))
        }
        verify("idle, paused and disabled samples cannot confirm") {
            val detector = DescentDetector()
            for (time in 0L..4000L step 1000L) check(!detector.sample(time, active = false))
            for (time in 5000L..9000L step 1000L) check(!detector.sample(time, config = settings.copy(enabled = false)))
            for (time in 10000L..12000L step 1000L) check(!detector.sample(time))
            check(detector.sample(13000))
        }
        verify("missing and non-finite sensor values interrupt confirmation") {
            for ((grade, speed) in listOf(null to 4.0, -4.0 to null, Double.NaN to 4.0, -4.0 to Double.POSITIVE_INFINITY)) {
                val detector = DescentDetector()
                check(!detector.sample(0))
                check(!detector.sample(1000, grade, speed))
                for (time in 2000L..4000L step 1000L) check(!detector.sample(time))
                check(detector.sample(5000))
            }
        }
        verify("a telemetry gap and backwards clock restart confirmation") {
            val detector = DescentDetector()
            check(!detector.sample(0))
            check(!detector.sample(3000))
            check(!detector.sample(2000))
            check(!detector.sample(3000))
            check(!detector.sample(4000))
            check(detector.sample(5000))
        }
        verify("manual Stop stays suppressed through pauses, missing data and setting changes") {
            val detector = DescentDetector()
            detector.suppressUntilNextDescent()
            check(!detector.sample(0, active = false))
            check(!detector.sample(1000, grade = null))
            check(!detector.sample(2000, config = settings.copy(enabled = false)))
            for (time in 3000L..10000L step 1000L) check(!detector.sample(time))
            check(detector.descentConsumed)
        }
        verify("only a sustained flat section rearms the next descent") {
            val detector = DescentDetector()
            detector.suppressUntilNextDescent()
            check(!detector.sample(0, grade = -0.5))
            check(!detector.sample(1000, grade = -2.0))
            for (time in 2000L..4000L step 1000L) check(!detector.sample(time, grade = -0.5))
            check(detector.descentConsumed)
            check(!detector.sample(5000, grade = -0.5))
            check(!detector.descentConsumed)
            for (time in 6000L..8000L step 1000L) check(!detector.sample(time))
            check(detector.sample(9000))
        }
        verify("interrupted rearm requires a new flat confirmation") {
            val detector = DescentDetector()
            detector.suppressUntilNextDescent()
            check(!detector.sample(0, grade = 0.0))
            check(!detector.sample(1000, grade = 0.0))
            detector.interrupt()
            for (time in 2000L..4000L step 1000L) check(!detector.sample(time, grade = 0.0))
            check(detector.descentConsumed)
            check(!detector.sample(5000, grade = 0.0))
            check(!detector.descentConsumed)
        }
        verify("the shallowest trigger cannot rearm on the same sustained slope") {
            val detector = DescentDetector()
            val config = settings.copy(gradientPercent = 1, confirmationSeconds = 1)
            check(!detector.sample(0, grade = -1.0, config = config))
            check(detector.sample(1000, grade = -1.0, config = config))
            for (time in 2000L..12000L step 1000L) check(!detector.sample(time, grade = -1.0, config = config))
            check(detector.descentConsumed)
        }
        verify("ending the ride resets suppression") {
            val detector = DescentDetector()
            detector.suppressUntilNextDescent()
            detector.endRide()
            for (time in 0L..2000L step 1000L) check(!detector.sample(time))
            check(detector.sample(3000))
        }
        verify("invalid stored settings must be rejected") {
            check(runCatching { DescentSettings(gradientPercent = 0) }.isFailure)
            check(runCatching { DescentSettings(confirmationSeconds = 11) }.isFailure)
        }
    }

    private fun commandChecks() =
        runBlocking {
            val commandScope = CoroutineScope(coroutineContext)
            var connected = true
            var recording = false
            var video = true
            var starts = 0
            var stops = 0
            var changes = 0
            var startGate = CompletableDeferred<Unit>()
            var failStart = false
            val controller =
                CameraCommandController(
                    scope = commandScope,
                    connected = { connected },
                    recording = { recording },
                    videoMode = { video },
                    startRecording = {
                        starts += 1
                        if (failStart) error("Camera rejected recording")
                        startGate.await()
                        recording = true
                    },
                    stopRecording = {
                        stops += 1
                        recording = false
                    },
                )
            verify("automatic start requires connection and Video mode") {
                connected = false
                check(!controller.startAutomatically())
                connected = true
                video = false
                check(!controller.startAutomatically())
                video = true
                recording = true
                check(!controller.startAutomatically())
                recording = false
            }
            check(controller.startAutomatically())
            yield()
            verify("pending automatic start exposes Stop and blocks duplicate starts and mode changes") {
                check(controller.state.value.busy && controller.state.value.recordingInFlight)
                check(starts == 1)
                check(!controller.startAutomatically())
                controller.configure { changes += 1 }
                check(changes == 0)
            }
            controller.toggleManually()
            yield()
            yield()
            verify("manual Stop cancels a pending automatic start and records suppression") {
                check(stops == 1 && !recording)
                check(controller.manualStopCount.value == 1L)
                check(!controller.state.value.busy)
            }
            startGate = CompletableDeferred(Unit)
            check(controller.startAutomatically())
            yield()
            verify("a completed automatic start reports recording with no pending command") {
                check(recording && !controller.state.value.busy)
            }
            controller.toggleManually()
            yield()
            verify("manual Stop remains available after automatic recording starts") {
                check(stops == 2 && !recording)
            }
            val configurationGate = CompletableDeferred<Unit>()
            controller.configure {
                changes += 1
                configurationGate.await()
            }
            yield()
            verify("camera configuration blocks automatic and manual starts") {
                check(controller.state.value.busy && !controller.state.value.recordingInFlight)
                check(!controller.startAutomatically())
                controller.toggleManually()
                check(changes == 1 && starts == 2)
            }
            configurationGate.complete(Unit)
            yield()
            failStart = true
            check(controller.startAutomatically())
            yield()
            verify("command failures are reported without crashing or leaving controls busy") {
                check(controller.state.value.error == "Camera rejected recording")
                check(!controller.state.value.busy)
                controller.clearError()
                check(controller.state.value.error == null)
            }
            failStart = false
            startGate = CompletableDeferred()
            check(controller.startAutomatically())
            yield()
            controller.disconnected()
            yield()
            verify("disconnect cancels pending commands and resets busy state") {
                check(!controller.state.value.busy && !recording)
                check(controller.state.value.error == null)
            }
            // The scope uses the runner's context; all command jobs have completed.
        }
}
