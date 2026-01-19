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
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    permissionsGranted: Boolean,
    onFinish: () -> Unit
) {
    val viewModel: MainViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    // Auto-connect trigger (logic moved to VM, but we can trigger it once here if needed)
    // The previous implementation used LaunchedEffect to trigger auto-connect if paired devices existed.
    // We can rely on ViewModel init, but to strictly replicate "on screen load":
    LaunchedEffect(Unit) {
        viewModel.tryAutoConnect()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopScan()
            // Connection persistence is preserved as per previous fix
        }
    }

    Scaffold(
        topBar = {
            val currentState = uiState.connectionState
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (uiState.showFeedbackScreen) {
                            "Feedback"
                        } else if (currentState is ConnectionState.Connected) {
                            "Connected to ${currentState.deviceName ?: "Unknown Device"}"
                        } else {
                            "Karoo Camera Control"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    if (!uiState.showFeedbackScreen) {
                        IconButton(onClick = { viewModel.setShowMenu(true) }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = uiState.showMenu,
                            onDismissRequest = { viewModel.setShowMenu(false) }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                onClick = { viewModel.disconnect() },
                                enabled = currentState is ConnectionState.Connected
                            )
                            DropdownMenuItem(
                                text = { Text("Unpair / Forget") },
                                onClick = { viewModel.forgetDevice() },
                                enabled = currentState is ConnectionState.Connected
                            )
                            DropdownMenuItem(
                                text = { Text("Leave Feedback") },
                                onClick = {
                                    viewModel.setShowFeedbackScreen(true)
                                    viewModel.setShowMenu(false)
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
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val currentState = uiState.connectionState

            if (uiState.showFeedbackScreen) {
                FeedbackScreen(onFinish = { viewModel.setShowFeedbackScreen(false) })
            } else if (uiState.showPresetScreen) {
                val currentModeId = if (uiState.cameraMode < 1000) uiState.cameraMode + 1000 else uiState.cameraMode
                val currentPresets = uiState.availablePresets.find { it.id == currentModeId }?.presets ?: emptyList()

                PresetSelectionScreen(
                    presets = currentPresets,
                    activePresetId = uiState.activePresetId,
                    onPresetSelected = { preset ->
                        viewModel.loadPreset(preset.id)
                    },
                    onBack = { viewModel.setShowPresetScreen(false) }
                )
            } else {
                when (val state = currentState) {
                    is ConnectionState.Connected -> {
                        ConnectedScreen(
                            deviceName = state.deviceName,
                            isRecording = uiState.isRecording,
                            isProcessing = uiState.isProcessing,
                            recordingDuration = uiState.recordingDuration,
                            batteryLevel = uiState.batteryLevel,
                            remainingTime = uiState.remainingVideoTime,
                            cameraMode = uiState.cameraMode,
                            availablePresets = uiState.availablePresets,
                            activePresetName = uiState.activePresetName,
                            activePresetIcon = uiState.activePresetIcon,
                            onToggleRecording = { viewModel.toggleRecording() },
                            onSetMode = { mode -> viewModel.setMode(mode) },
                            onOpenPresetSelection = { viewModel.setShowPresetScreen(true) },
                            onDisconnect = { viewModel.disconnect() },
                            onForget = { viewModel.forgetDevice() },
                            onFinish = { onFinish() }
                        )
                    }
                    is ConnectionState.Connecting -> {
                        ConnectingScreen(
                            deviceName = state.deviceName,
                            onCancel = { viewModel.cancelAutoConnect() }
                        )
                    }
                    else -> {
                        if (uiState.isAutoConnecting && currentState is ConnectionState.Disconnected) {
                            ConnectingScreen(
                                deviceName = null,
                                onCancel = { viewModel.cancelAutoConnect() }
                            )
                        } else {
                            ScanningScreen(
                                connectionState = currentState,
                                scannedDevices = uiState.scannedDevices,
                                pairedDevices = uiState.pairedDevices,
                                permissionsGranted = permissionsGranted,
                                onStartScan = { viewModel.startScan() },
                                onStopScan = { viewModel.stopScan() },
                                onConnect = { device -> viewModel.connect(device) },
                                onConnectToPaired = { address -> viewModel.connectToPaired(address) },
                                onRemovePaired = { address -> viewModel.removePairedDevice(address) },
                                onFinish = { onFinish() }
                            )
                        }
                    }
                }
            }
        }
    }
}
