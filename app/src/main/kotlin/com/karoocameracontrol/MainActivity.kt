package com.karoocameracontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.karoocameracontrol.extension.KarooCameraControlExtension
import com.karoocameracontrol.screens.MainScreen
import com.karoocameracontrol.theme.AppTheme
import com.karoocameracontrol.diagnostics.DiagnosticLog as Log

class MainActivity : ComponentActivity() {
    internal var recordHardwareAction: (() -> Unit)? = null
    private var pressedRecordAction: (() -> Unit)? = null
    private var recordKeyRepeated = false
    private val otherHeldKeys = mutableSetOf<Int>()
    private var permissionsGranted by mutableStateOf(false)

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Log.d(TAG, "All required permissions granted.")
                permissionsGranted = true
            } else {
                Log.w(TAG, "Not all required permissions granted.")
                permissionsGranted = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        // Start while the activity is foreground; Karoo can also bind the extension.
        startService(Intent(this, KarooCameraControlExtension::class.java))

        setContent {
            AppTheme {
                MainScreen(
                    permissionsGranted = permissionsGranted,
                    onFinish = { finish() },
                    onRecordActionChanged = { recordHardwareAction = it },
                )
            }
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_NAVIGATE_IN) {
            otherHeldKeys.add(keyCode)
            recordKeyRepeated = true
            return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount == 0) {
            pressedRecordAction = recordHardwareAction
            recordKeyRepeated = otherHeldKeys.isNotEmpty() || !event.hasNoModifiers()
        } else {
            recordKeyRepeated = true
        }
        return if (pressedRecordAction != null) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        otherHeldKeys.remove(keyCode)
        if (keyCode == KeyEvent.KEYCODE_NAVIGATE_IN) {
            val action = pressedRecordAction
            pressedRecordAction = null
            if (action != null) {
                if (!event.isCanceled && !recordKeyRepeated && event.hasNoModifiers() && action === recordHardwareAction &&
                    event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
                ) {
                    action()
                }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        pressedRecordAction = null
        otherHeldKeys.clear()
        super.onPause()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // Always request Location permission for BLE scanning
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All necessary permissions already granted.")
            permissionsGranted = true
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
