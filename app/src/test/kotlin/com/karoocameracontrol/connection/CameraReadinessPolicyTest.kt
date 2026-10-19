package com.karoocameracontrol.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraReadinessPolicyTest {
    @Test fun readinessRequiresAnActiveRideAndEitherAutomationOrTheFallback() {
        for (system in listOf(false, true)) {
            for (ride in listOf(false, true)) {
                for (radar in listOf(false, true)) {
                    for (descent in listOf(false, true)) {
                        for (fallback in listOf(false, true)) {
                            val actual = shouldKeepCameraReady(system, ride, radar, descent, fallback)
                            when {
                                !system || !ride -> assertEquals(false, actual)
                                radar -> assertEquals(true, actual)
                                descent -> assertEquals(true, actual)
                                else -> assertEquals(fallback, actual)
                            }
                        }
                    }
                }
            }
        }
    }
}
