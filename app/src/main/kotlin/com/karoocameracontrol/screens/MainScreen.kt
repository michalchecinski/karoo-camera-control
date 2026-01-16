package com.karoocameracontrol.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.karoocameracontrol.extension.ConnectionState
import com.karoocameracontrol.extension.GoProManager
import com.karoocameracontrol.theme.AppTheme
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    permissionsGranted: Boolean,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val goProManager = GoProManager.getInstance(context)
    val scope = rememberCoroutineScope()

    val connectionState by goProManager.connectionState.collectAsState()
    val scannedDevices by goProManager.scannedDevices.collectAsState()
    val pairedDevices by goProManager.pairedDevices.collectAsState()
    val isRecording by goProManager.isRecording.collectAsState()
    val recordingDuration by goProManager.recordingDuration.collectAsState()
    val batteryLevel by goProManager.batteryLevel.collectAsState()
    val remainingTime by goProManager.remainingVideoTime.collectAsState()
    val cameraMode by goProManager.cameraMode.collectAsState()
    
    var isProcessing by remember { mutableStateOf(false) }

    // Auto-connect on startup only
    LaunchedEffect(Unit) {
        if (connectionState is ConnectionState.Disconnected && pairedDevices.isNotEmpty()) {
            goProManager.tryAutoConnect()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            goProManager.stopScan()
            goProManager.disconnect()
        }
    }

    when (val state = connectionState) {
        is ConnectionState.Connected -> {
            ConnectedScreen(
                deviceName = state.deviceName,
                isRecording = isRecording,
                isProcessing = isProcessing,
                recordingDuration = recordingDuration,
                batteryLevel = batteryLevel,
                remainingTime = remainingTime,
                cameraMode = cameraMode,
                onToggleRecording = {
                    if (isProcessing) return@ConnectedScreen
                    isProcessing = true
                    scope.launch {
                        try {
                            if (isRecording) {
                                goProManager.stopRecording()
                            } else {
                                goProManager.startRecording()
                            }
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                onSetMode = { mode ->
                    if (isProcessing) return@ConnectedScreen
                    isProcessing = true
                    scope.launch {
                        try {
                            goProManager.setMode(mode)
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                onDisconnect = { goProManager.disconnect() },
                onForget = { goProManager.forgetConnectedDevice() },
                onFinish = { goProManager.disconnect() }
            )
        }
        else -> {
            ScanningScreen(
                connectionState = connectionState,
                scannedDevices = scannedDevices,
                pairedDevices = pairedDevices,
                permissionsGranted = permissionsGranted,
                onStartScan = { goProManager.startScan() },
                onStopScan = { 
                    goProManager.stopScan()
                    // Also cancel any pending connection if user clicked Stop
                    goProManager.disconnect()
                },
                onConnect = { device -> goProManager.connect(device) },
                onConnectToPaired = { address -> goProManager.connectToDevice(address) },
                onRemovePaired = { address -> goProManager.removePairedDevice(address) },
                onFinish = {
                    goProManager.stopScan()
                    goProManager.disconnect()
                }
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
        MainScreen(permissionsGranted = true, onFinish = {})
    }
}
