package com.karoocameracontrol.extension

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.karoocameracontrol.MainActivity
import com.karoocameracontrol.R
import com.karoocameracontrol.integrations.gopro.GoProBatteryDataType
import com.karoocameracontrol.integrations.gopro.GoProConnectionState
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.integrations.gopro.GoProRecTimeDataType
import com.karoocameracontrol.integrations.gopro.GoProStatusDataType
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class KarooCameraControlExtension : KarooExtension("karoo-camera-control", "1.0") {
    private lateinit var goProManager: GoProManager
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var connectionStateJob: Job? = null
    
    private val karooSystem by lazy { KarooSystemService(applicationContext) }

    override val types by lazy {
        listOf(
            GoProStatusDataType(extension, applicationContext),
            GoProRecTimeDataType(extension, applicationContext),
            GoProBatteryDataType(extension, applicationContext),
        )
    }

    override fun onCreate() {
        super.onCreate()
        
        karooSystem.connect { connected ->
            if (connected) {
                Log.d(TAG, "Connected to Karoo System. Requesting Bluetooth...")
                karooSystem.dispatch(RequestBluetooth(extension))
            }
        }

        goProManager = GoProManager.getInstance(applicationContext)

        // Observe GoProManager connection state
        connectionStateJob =
            scope.launch {
                goProManager.connectionState.collectLatest { state ->
                    updateNotification(state)
                    when (state) {
                        is GoProConnectionState.Connected -> {
                            Log.d(TAG, "GoPro is Connected! Ready to send commands.")
                        }
                        is GoProConnectionState.Disconnected -> {
                            Log.d(TAG, "GoPro is Disconnected.")
                        }
                        else -> { /* Handle other states as needed */ }
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
        startForegroundService()
        
        // Ensure auto-connect is attempted when service starts
        goProManager.tryAutoConnect()
        
        return START_STICKY
    }

    private fun startForegroundService() {
        createNotificationChannel()

        val notification = buildNotification(GoProConnectionState.Disconnected)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: GoProConnectionState) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: GoProConnectionState): Notification {
        val pendingIntent:
            PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

        val contentText = when (state) {
            is GoProConnectionState.Connected -> "Connected to ${state.deviceName}"
            is GoProConnectionState.Connecting -> "Connecting to ${state.deviceName}..."
            is GoProConnectionState.Scanning -> "Scanning for cameras..."
            is GoProConnectionState.BluetoothDisabled -> "Bluetooth is disabled"
            is GoProConnectionState.Error -> "Error: ${state.message}"
            else -> "Ready to connect"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Karoo Camera Control")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.karoo_camera_control) // Ensure this resource exists or use a system one
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Karoo Camera Control Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()

        connectionStateJob?.cancel()
        goProManager.disconnect()
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KarooCameraControlExtension"
        private const val CHANNEL_ID = "KarooCameraControlChannel"
        private const val NOTIFICATION_ID = 1
    }
}
