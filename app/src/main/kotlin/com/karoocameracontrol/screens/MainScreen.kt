package com.karoocameracontrol.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karoocameracontrol.R
import com.karoocameracontrol.components.SimpleAlertDialog
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.PairedDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    permissionsGranted: Boolean,
    onFinish: () -> Unit,
) {
    val viewModel: MainViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    var showUnpairDialog by remember { mutableStateOf(false) }

    // Auto-connect trigger
    LaunchedEffect(Unit) {
        viewModel.tryAutoConnect()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopScan()
        }
    }

    Scaffold(
        topBar = {
            val currentState = uiState.connectionState
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text =
                            if (uiState.showFeedbackScreen) {
                                "Feedback"
                            } else if (uiState.showScanningScreen) {
                                "Scan Devices"
                            } else if (currentState is ConnectionState.Connected) {
                                "Connected to ${currentState.deviceName ?: "Unknown Device"}"
                            } else {
                                "Karoo Camera Control"
                            },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    if (!uiState.showFeedbackScreen && !uiState.showScanningScreen) {
                        IconButton(onClick = { viewModel.setShowMenu(true) }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = uiState.showMenu,
                            onDismissRequest = { viewModel.setShowMenu(false) },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                onClick = { viewModel.disconnect() },
                                enabled = currentState is ConnectionState.Connected,
                            )
                            DropdownMenuItem(
                                text = { Text("Unpair / Forget") },
                                onClick = {
                                    showUnpairDialog = true
                                    viewModel.setShowMenu(false)
                                },
                                enabled = currentState is ConnectionState.Connected,
                            )
                            DropdownMenuItem(
                                text = { Text("Leave Feedback") },
                                onClick = {
                                    viewModel.setShowFeedbackScreen(true)
                                    viewModel.setShowMenu(false)
                                },
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background),
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
                    onBack = { viewModel.setShowPresetScreen(false) },
                )
            } else if (currentState is ConnectionState.Connected) {
                ConnectedScreen(
                    deviceName = currentState.deviceName,
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
                    onForget = { showUnpairDialog = true },
                    onFinish = { onFinish() },
                )
            } else if (uiState.showScanningScreen) {
                ScanningScreen(
                    connectionState = currentState,
                    scannedDevices = uiState.scannedDevices,
                    pairedDevices = uiState.pairedDevices,
                    permissionsGranted = permissionsGranted,
                    onStartScan = { viewModel.startScan() },
                    onStopScan = { viewModel.stopScan() },
                    onConnect = { device -> viewModel.connect(device) },
                    onFinish = { viewModel.setShowScanningScreen(false) },
                )
            } else {
                when (val state = currentState) {
                    is ConnectionState.Connecting -> {
                        ConnectingScreen(
                            deviceName = state.deviceName,
                            onCancel = { viewModel.cancelAutoConnect() },
                        )
                    }
                    else -> {
                        if (uiState.isAutoConnecting && currentState is ConnectionState.Disconnected) {
                            ConnectingScreen(
                                deviceName = null,
                                onCancel = { viewModel.cancelAutoConnect() },
                            )
                        } else {
                            PairedDevicesScreen(
                                pairedDevices = uiState.pairedDevices,
                                onConnectToPaired = { address -> viewModel.connectToPaired(address) },
                                onRemovePaired = { address -> viewModel.removePairedDevice(address) },
                                onAddDevice = { viewModel.setShowScanningScreen(true) },
                                onFinish = { onFinish() },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showUnpairDialog) {
        val currentState = uiState.connectionState
        val deviceName = (currentState as? ConnectionState.Connected)?.deviceName ?: "this device"
        SimpleAlertDialog(
            dialogTitle = "Confirm Unpair",
            dialogSubTitle = "Are you sure you want to unpair and forget $deviceName?",
            onDismissRequest = { showUnpairDialog = false },
            onConfirmation = {
                viewModel.forgetDevice()
                showUnpairDialog = false
            },
        )
    }
}

@Composable
fun PairedDevicesScreen(
    pairedDevices: List<PairedDevice>,
    onConnectToPaired: (String) -> Unit,
    onRemovePaired: (String) -> Unit,
    onAddDevice: () -> Unit,
    onFinish: () -> Unit,
) {
    var deviceToForget by remember { mutableStateOf<PairedDevice?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (pairedDevices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No cameras paired.\nTap + to add one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                items(pairedDevices) { device ->
                    PairedDeviceItem(
                        device = device,
                        onConnectClick = { onConnectToPaired(device.address) },
                        onForgetClick = { deviceToForget = device },
                    )
                }
            }
        }

        // Back button
        Image(
            painter = painterResource(id = R.drawable.back),
            contentDescription = "Back",
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 10.dp)
                    .size(54.dp)
                    .clickable {
                        onFinish()
                    },
        )

        // Add Button
        Image(
            painter = painterResource(id = R.drawable.add),
            contentDescription = "Add Device",
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 10.dp)
                    .size(54.dp)
                    .clickable { onAddDevice() },
        )
    }

    deviceToForget?.let {
        SimpleAlertDialog(
            dialogTitle = "Confirm Unpair",
            dialogSubTitle = "Are you sure you want to unpair and forget ${it.name} (${it.address})?",
            onDismissRequest = { deviceToForget = null },
            onConfirmation = {
                onRemovePaired(it.address)
                deviceToForget = null
            },
        )
    }
}
