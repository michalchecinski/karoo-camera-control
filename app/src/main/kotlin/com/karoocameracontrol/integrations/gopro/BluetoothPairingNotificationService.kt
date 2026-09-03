package com.karoocameracontrol.integrations.gopro

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale

/**
 * Bridges the Android Settings pairing notification to the pairing flow.
 *
 * Notification access is granted explicitly by the device owner. The service only inspects the
 * Settings Bluetooth pairing notification and never persists notification content.
 */
class BluetoothPairingNotificationService : NotificationListenerService() {
    override fun onListenerConnected() {
        activeService = this
        Log.i(TAG, "Bluetooth pairing notification access is active")
    }

    override fun onListenerDisconnected() {
        if (activeService === this) {
            activeService = null
        }
        Log.i(TAG, "Bluetooth pairing notification access is inactive")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isSystemBluetoothPairingNotification(sbn)) {
            Log.d(TAG, "System Bluetooth pairing notification posted")
        }
    }

    private fun invokePairingAction(): Boolean {
        val notification =
            activeNotifications
                ?.firstOrNull(::isSystemBluetoothPairingNotification)
                ?: return false
        val action = findPairingAction(notification.notification) ?: return false

        return try {
            action.actionIntent.send()
            Log.i(TAG, "Requested Bluetooth pairing confirmation through Android Settings")
            true
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to invoke Bluetooth pairing action", exception)
            false
        }
    }

    private fun isSystemBluetoothPairingNotification(notification: StatusBarNotification): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notification.packageName == SETTINGS_PACKAGE &&
            notification.notification.channelId == BLUETOOTH_NOTIFICATION_CHANNEL
    }

    private fun findPairingAction(notification: Notification): Notification.Action? {
        val actions = notification.actions?.filter { it.actionIntent != null }.orEmpty()
        val actionTitles = actions.map { it.title?.toString().orEmpty() }
        Log.d(TAG, "Bluetooth pairing notification actions: $actionTitles")

        val pairingActions =
            actions.filter { action ->
                val title = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
                title.contains("pair") && title.contains("connect")
            }
        if (pairingActions.size != 1) {
            Log.w(TAG, "Could not identify a unique Pair & connect action")
            return null
        }
        return pairingActions.single()
    }

    companion object {
        private const val TAG = "BluetoothPairing"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val BLUETOOTH_NOTIFICATION_CHANNEL = "bluetooth_notification_channel"

        @Volatile
        private var activeService: BluetoothPairingNotificationService? = null

        fun isActive(): Boolean = activeService != null

        fun requestConfirmation(): Boolean = activeService?.invokePairingAction() ?: false
    }
}
