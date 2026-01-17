package com.karoocameracontrol.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.karoocameracontrol.extension.ConnectionState
import com.karoocameracontrol.extension.GoProManager
import com.karoocameracontrol.theme.AppTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    val availablePresets by goProManager.availablePresets.collectAsState()
    val activePresetName by goProManager.activePresetName.collectAsState()
    val activePresetId by goProManager.activePresetId.collectAsState()

    var isProcessing by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) } // State for dropdown menu
    var isAutoConnecting by remember { mutableStateOf(false) } // New state for auto-connecting
    var showFeedbackScreen by remember { mutableStateOf(false) } // New state for FeedbackScreen
    var showPresetScreen by remember { mutableStateOf(false) } // New state for PresetSelectionScreen

    // Auto-connect on startup only
    LaunchedEffect(Unit) {
        if (pairedDevices.isNotEmpty()) {
            isAutoConnecting = true
            goProManager.tryAutoConnect()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            goProManager.stopScan()
            goProManager.disconnect()
        }
    }

    Scaffold(
        topBar = {
            val currentState = connectionState // Introduce local variable
            CenterAlignedTopAppBar(
                                title = {
                                    Text(
                                        text = if (showFeedbackScreen) {
                                            "Feedback" // Title for feedback screen
                                        } else if (currentState is ConnectionState.Connected) {
                                            "Connected to ${currentState.deviceName ?: "Unknown Device"}"
                                        } else {
                                            "Karoo Camera Control"
                                        },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                },
                                actions = {
                                    if (!showFeedbackScreen) { // Always show menu icon when not on feedback screen
                                        IconButton(onClick = { showMenu = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                                        }
                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Disconnect") },
                                                onClick = {
                                                    goProManager.disconnect()
                                                    showMenu = false
                                                },
                                                enabled = currentState is ConnectionState.Connected // Only enabled when connected
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Unpair / Forget") },
                                                onClick = {
                                                    goProManager.forgetConnectedDevice()
                                                    showMenu = false
                                                },
                                                enabled = currentState is ConnectionState.Connected // Only enabled when connected
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Leave Feedback") },
                                                onClick = {
                                                    showFeedbackScreen = true
                                                    showMenu = false
                                                }
                                            )
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val currentState = connectionState // Introduce local variable

            if (showFeedbackScreen) {
                FeedbackScreen(onFinish = { showFeedbackScreen = false })
            } else if (showPresetScreen) {
                val currentModeId = if (cameraMode < 1000) cameraMode + 1000 else cameraMode
                val currentPresets = availablePresets.find { it.id == currentModeId }?.presets ?: emptyList()

                PresetSelectionScreen(
                    presets = currentPresets,
                    activePresetId = activePresetId,
                    onPresetSelected = { preset ->
                        if (!isProcessing) {
                            isProcessing = true
                            scope.launch {
                                try {
                                    goProManager.loadPreset(preset.id)
                                    showPresetScreen = false
                                } finally {
                                    isProcessing = false
                                }
                            }
                        }
                    },
                    onBack = { showPresetScreen = false }
                )
            } else {
                when (val state = currentState) {
                    is ConnectionState.Connected -> {
                        isAutoConnecting = false
                        ConnectedScreen(
                            deviceName = state.deviceName,
                            isRecording = isRecording,
                            isProcessing = isProcessing,
                            recordingDuration = recordingDuration,
                            batteryLevel = batteryLevel,
                            remainingTime = remainingTime,
                            cameraMode = cameraMode,
                            availablePresets = availablePresets,
                            activePresetName = activePresetName,
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
                            onOpenPresetSelection = {
                                showPresetScreen = true
                            },
                            onDisconnect = { goProManager.disconnect() },
                            onForget = { goProManager.forgetConnectedDevice() },
                            onFinish = { onFinish() }
                        )
                    }
                    is ConnectionState.Connecting -> {
                        ConnectingScreen(
                            deviceName = state.deviceName,
                            onCancel = {
                                goProManager.disconnect()
                                isAutoConnecting = false
                                showMenu = false
                            }
                        )
                    }
                    else -> {
                        if (isAutoConnecting && currentState is ConnectionState.Disconnected) {
                             // Still trying to auto-connect, but not yet in Connecting state
                            ConnectingScreen(
                                deviceName = null,
                                onCancel = {
                                    goProManager.disconnect()
                                    isAutoConnecting = false
                                    showMenu = false
                                }
                            )
                        } else {
                            isAutoConnecting = false // Reset if we reach scanning/disconnected normally
                            ScanningScreen(
                                connectionState = currentState,
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
                                onFinish = { onFinish() }
                            )
                        }
                    }
                }
            }
        }
    }
}