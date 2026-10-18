package com.karoocameracontrol.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteDisplayTest {
    @Test
    fun elapsedTimeDoesNotWrapAfterAnHourOrShowNegativeSeconds() {
        assertEquals("00:00", recordingFieldTime(-1))
        assertEquals("00:12", recordingFieldTime(12))
        assertEquals("59:59", recordingFieldTime(3599))
        assertEquals("1:00:00", recordingFieldTime(3600))
        assertEquals("2:03:04", recordingFieldTime(7384))
    }

    @Test
    fun modeSelectionAcceptsBothCameraModeRepresentationsWithoutGuessingUnknowns() {
        for (mode in 0..2) {
            assertEquals(mode, normalizedCameraMode(mode))
            assertEquals(mode, normalizedCameraMode(1000 + mode))
        }
        assertEquals(-1, normalizedCameraMode(-1))
    }
}
