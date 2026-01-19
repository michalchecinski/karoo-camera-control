package com.karoocameracontrol.extension

import android.content.Intent
import android.util.Log
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.GoProBatteryDataType
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.integrations.gopro.GoProRecTimeDataType
import com.karoocameracontrol.integrations.gopro.GoProStatusDataType
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class KarooCameraControlExtension : KarooExtension("karoo-camera-control", "1.0") {

    private lateinit var goProManager: GoProManager
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var connectionStateJob: Job? = null

    override val types by lazy {
        listOf(
            GoProStatusDataType(extension, applicationContext),
            GoProRecTimeDataType(extension, applicationContext),
            GoProBatteryDataType(extension, applicationContext)
        )
    }

    override fun onCreate() {
        super.onCreate()
        goProManager = GoProManager.getInstance(applicationContext)

        // Observe GoProManager connection state
        connectionStateJob = scope.launch {
            goProManager.connectionState.collectLatest { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        Log.d(TAG, "GoPro is Connected! Ready to send commands.")
                    }
                    is ConnectionState.Disconnected -> {
                        Log.d(TAG, "GoPro is Disconnected.")
                    }
                    else -> { /* Handle other states as needed */ }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "KarooCameraControlExtension onStartCommand.")
        // If the extension is started manually or by the system,
        // we might want to automatically try to reconnect to a known GoPro.
        // For now, it will just observe the state.
        return START_STICKY
    }

    override fun onDestroy() {
        connectionStateJob?.cancel()
        goProManager.disconnect()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KarooCameraControlExtension"
    }
}
