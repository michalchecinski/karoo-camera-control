package com.karoocameracontrol

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.karoocameracontrol.alerts.CameraAlert
import com.karoocameracontrol.alerts.CameraAlertEffects
import com.karoocameracontrol.alerts.CameraAlertKind
import com.karoocameracontrol.alerts.CameraAlertSettings
import com.karoocameracontrol.alerts.CameraAlertSettingsStore
import com.karoocameracontrol.alerts.RecordingOrigin
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern

/** No camera or proprietary Karoo services required: verifies persisted settings and SDK effects. */
class CameraAlertInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val store = CameraAlertSettingsStore(targetContext)
        val original = store.settings.value
        var resultCode = Activity.RESULT_OK
        try {
            var count = 0
            for (confirmations in listOf(false, true)) {
                for (warnings in listOf(false, true)) {
                    for (sound in listOf(false, true)) {
                        val settings = CameraAlertSettings(confirmations, warnings, sound)
                        store.update(settings)
                        check(CameraAlertSettingsStore(targetContext).settings.value == settings) { "Settings did not persist independently" }
                        count += 1
                    }
                }
            }
            val start = CameraAlertEffects.create(targetContext, CameraAlert(CameraAlertKind.STARTED, RecordingOrigin.RADAR, visual = true, audible = true))
            val started = start.filterIsInstance<InRideAlert>().single()
            check(started.title == targetContext.getString(R.string.camera_alert_started))
            check(started.detail == targetContext.getString(R.string.camera_alert_origin_radar))
            check(started.autoDismissMs == 3000L)
            check(started.icon == R.drawable.camera_alert)
            check(targetContext.getColor(started.textColor) != targetContext.getColor(started.backgroundColor))
            count += 1
            val stop = CameraAlertEffects.create(targetContext, CameraAlert(CameraAlertKind.STOPPED, visual = true, audible = true))
            val failure = CameraAlertEffects.create(targetContext, CameraAlert(CameraAlertKind.FAILED, visual = true, audible = true))
            val patterns = listOf(start, stop, failure).map { it.filterIsInstance<PlayBeepPattern>().single().tones }
            check(patterns.distinct().size == 3) { "Start, stop and error sounds must differ" }
            check(patterns.all { tones -> tones.all { it.durationMs > 0 } })
            check(failure.filterIsInstance<InRideAlert>().single().autoDismissMs == 6000L)
            count += 1
            val low = CameraAlertEffects.create(targetContext, CameraAlert(CameraAlertKind.LOW_RESOURCES, battery = 20, capacitySeconds = 299, visual = true, audible = false))
            check(low.size == 1)
            check(low.single() is InRideAlert)
            check((low.single() as InRideAlert).detail == "Battery 20% • 4:59 recording time left")
            count += 1
            val soundOnly = CameraAlertEffects.create(targetContext, CameraAlert(CameraAlertKind.DISCONNECTED, visual = false, audible = true))
            check(soundOnly.single() is PlayBeepPattern)
            val neither = CameraAlertEffects.create(targetContext, CameraAlert(CameraAlertKind.STARTED, visual = false, audible = false))
            check(neither.isEmpty())
            count += 1
            result.putString("stream", "Passed $count camera alert settings/effects checks\n")
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            resultCode = Activity.RESULT_CANCELED
        } finally {
            store.update(original)
        }
        finish(resultCode, result)
    }
}
