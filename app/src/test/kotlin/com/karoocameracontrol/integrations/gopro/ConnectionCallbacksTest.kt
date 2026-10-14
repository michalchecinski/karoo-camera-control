package com.karoocameracontrol.integrations.gopro

import com.karoocameracontrol.alerts.CameraAlert
import com.karoocameracontrol.alerts.CameraAlertFeed
import com.karoocameracontrol.alerts.CameraAlertKind
import com.karoocameracontrol.alerts.CameraAlertPolicy
import com.karoocameracontrol.alerts.CameraAlertSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCallbacksTest {
    // Equal camera identity does not make an old GATT session the current instance.
    private data class Connection(val camera: String = "sample-camera")

    @Test fun staleCallbackCannotParseCompleteResponseOrChangeReplacementCameraState() {
        val old = Connection()
        val replacement = Connection()
        assertEquals(old, replacement)
        val callbacks = ConnectionCallbacks { replacement }
        val feed = CameraAlertFeed()
        feed.status(false, 80, 900)
        feed.connection(true)
        val before = feed.snapshot
        var parsed = 0
        var completed = 0
        assertFalse(
            callbacks.runIfCurrent(old) {
                parsed++
                feed.status(true, 1, 1)
                completed++
            },
        )
        assertEquals(0, parsed)
        assertEquals(0, completed)
        assertEquals(before, feed.snapshot)
    }

    @Test fun acceptedNotificationIsDiscardedIfItsConnectionIsReplacedBeforeMainApplication() {
        val old = Connection()
        var current: Connection? = old
        val callbacks = ConnectionCallbacks { current }
        val feed = CameraAlertFeed()
        feed.status(false, 80, 900)
        feed.connection(true)
        val alerts = mutableListOf<CameraAlert>()
        val policy =
            CameraAlertPolicy().apply {
                settings = CameraAlertSettings(true, true, false)
                rideActive = true
                seed(feed.snapshot)
            }
        feed.listener = { alerts += policy.accept(it) }
        var applyOnMain: (() -> Unit)? = null
        assertTrue(
            callbacks.runIfCurrent(old) {
                applyOnMain = {
                    callbacks.runIfCurrent(old) { feed.status(true, 1, 1) }
                    Unit
                }
            },
        )
        current = Connection()
        checkNotNull(applyOnMain).invoke()
        assertFalse(feed.snapshot.recording == true)
        assertEquals(80, feed.snapshot.battery)
        assertTrue(alerts.isEmpty())
        val replacement = checkNotNull(current)
        assertTrue(callbacks.runIfCurrent(replacement) { feed.status(true, 80, 900) })
        assertEquals(CameraAlertKind.STARTED, alerts.single().kind)
    }

    @Test fun notificationsQueuedBeforeDisconnectionCannotRestoreKnownRecordingState() {
        val source = Connection()
        var current: Connection? = source
        val callbacks = ConnectionCallbacks { current }
        val feed = CameraAlertFeed()
        feed.status(true, 80, 900)
        feed.connection(true)
        current = null
        feed.connection(false)
        assertFalse(callbacks.runIfCurrent(source) { feed.status(true, 80, 900) })
        assertEquals(null, feed.snapshot.recording)
        assertFalse(feed.snapshot.connected)
    }
}
