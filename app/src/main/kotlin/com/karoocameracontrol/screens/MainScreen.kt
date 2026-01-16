package com.karoocameracontrol.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.karoocameracontrol.extension.ConnectionState
import com.karoocameracontrol.extension.GoProManager
import com.karoocameracontrol.theme.AppTheme

@Composable
fun MainScreen(permissionsGranted: Boolean) {
    val context = LocalContext.current
    val goProManager = GoProManager.getInstance(context)

    val connectionState by goProManager.connectionState.collectAsState()
    val scannedDevices by goProManager.scannedDevices.collectAsState()
    val savedDeviceName by goProManager.savedDeviceNameFlow.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            // Only stop scan/disconnect if the screen is actually being destroyed (e.g. Activity finish),
            // but Compose might dispose/recompose on config changes. 
            // In a single-activity app, MainScreen is the root.
            goProManager.stopScan()
            goProManager.disconnect()
        }
    }

    when (val state = connectionState) {
        is ConnectionState.Connected -> {
            ConnectedScreen(
                deviceName = state.deviceName,
                onDisconnect = { goProManager.disconnect() },
                onForget = { goProManager.forgetPairedDevice() }
            )
        }
        else -> {
            ScanningScreen(
                connectionState = connectionState,
                scannedDevices = scannedDevices,
                permissionsGranted = permissionsGranted,
                onStartScan = { goProManager.startScan() },
                onStopScan = { goProManager.stopScan() },
                onConnect = { device -> goProManager.connect(device) },
                savedDeviceName = savedDeviceName
            )
        }
    }
}

@Preview(
    widthDp = 256,
    heightDp = 426,
    device = Devices.WEAR_OS_SMALL_ROUND
)
@Composable
fun DefaultPreview() {
    AppTheme {
        MainScreen(permissionsGranted = true) // Preview with permissions granted
    }
}