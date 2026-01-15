package com.karoocameracontrol.extension

import android.content.Intent
import android.util.Log
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TemplateExtension : KarooExtension("template-id", "1.0") {

    private lateinit var goProManager: GoProManager
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var connectionStateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        goProManager = GoProManager.getInstance(applicationContext)
        Log.d(TAG, "TemplateExtension onCreate. GoProManager initialized.")

        // Observe GoProManager connection state
        connectionStateJob = scope.launch {
            goProManager.connectionState.collectLatest { state ->
                Log.d(TAG, "GoPro Connection State in Extension: $state")
                when (state) {
                    is ConnectionState.Connected -> {
                        // TODO: Update Karoo data fields (e.g., battery, recording status)
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
        Log.d(TAG, "TemplateExtension onStartCommand.")
        // If the extension is started manually or by the system,
        // we might want to automatically try to reconnect to a known GoPro.
        // For now, it will just observe the state.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "TemplateExtension onDestroy. Disconnecting GoPro.")
        connectionStateJob?.cancel()
        goProManager.disconnect()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TemplateExtension"
    }
}
