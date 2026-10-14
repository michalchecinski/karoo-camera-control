package com.karoocameracontrol.extension

import android.content.Intent
import com.karoocameracontrol.alerts.CameraAlertMonitor
import com.karoocameracontrol.alerts.CameraAlertSettingsStore
import com.karoocameracontrol.integrations.gopro.GoProBatteryDataType
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.integrations.gopro.GoProRecTimeDataType
import com.karoocameracontrol.integrations.gopro.GoProRecordingDataType
import com.karoocameracontrol.integrations.gopro.GoProStatusDataType
import com.karoocameracontrol.radar.RadarRecordingController
import com.karoocameracontrol.radar.RadarRecordingSettings
import com.karoocameracontrol.recording.DescentRecordingMonitor
import com.karoocameracontrol.recording.DescentSettingsStore
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.karoocameracontrol.diagnostics.DiagnosticLog as Log

class KarooCameraControlExtension : KarooExtension("karoo-camera-control", "1.0") {
    private lateinit var goProManager: GoProManager
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var alertMonitor: CameraAlertMonitor
    private lateinit var descentMonitor: DescentRecordingMonitor
    private val radarRecording by lazy { RadarRecordingController.getInstance(applicationContext) }

    private val karooSystem by lazy { KarooSystemService(applicationContext) }

    override val types by lazy {
        listOf(
            GoProStatusDataType(extension, applicationContext),
            GoProRecTimeDataType(extension, applicationContext),
            GoProBatteryDataType(extension, applicationContext),
            GoProRecordingDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()

        goProManager = GoProManager.getInstance(applicationContext)
        descentMonitor =
            DescentRecordingMonitor(
                scope,
                karooSystem,
                DescentSettingsStore.getInstance(applicationContext),
                goProManager,
            )
        alertMonitor = CameraAlertMonitor(applicationContext, scope, karooSystem, goProManager.alertFeed, CameraAlertSettingsStore.getInstance(applicationContext))
        alertMonitor.start()
        descentMonitor.start()
        radarRecording.start(karooSystem)

        karooSystem.connect { connected ->
            scope.launch {
                alertMonitor.systemConnectionChanged(connected)
                descentMonitor.systemConnectionChanged(connected)
                radarRecording.onSystemConnection(connected)
                if (connected) {
                    karooSystem.dispatch(RequestBluetooth(extension))
                    if (RadarRecordingSettings.getInstance(applicationContext).settings.value.enabled) {
                        goProManager.tryAutoConnect()
                    }
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "KarooCameraControlExtension onStartCommand.")
        // If the extension is started manually or by the system,
        // we might want to automatically try to reconnect to a known GoPro.
        // For now, it will just observe the state.
        return START_STICKY
    }

    override fun onDestroy() {
        alertMonitor.stop()
        radarRecording.stop()
        descentMonitor.stop()
        scope.cancel()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()

        super.onDestroy()
    }

    companion object {
        private const val TAG = "KarooCameraControlExtension"
    }
}
