package com.karoocameracontrol.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.integrations.gopro.PresetGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val isAutoConnecting: Boolean = false,
    val isProcessing: Boolean = false,
    val showMenu: Boolean = false,
    val showFeedbackScreen: Boolean = false,
    val showScanningScreen: Boolean = false,
    val showPresetScreen: Boolean = false,
    // Device Data
    val scannedDevices: List<android.bluetooth.BluetoothDevice> = emptyList(),
    val pairedDevices: List<com.karoocameracontrol.integrations.gopro.PairedDevice> = emptyList(),
    val isRecording: Boolean = false,
    val recordingDuration: Int = 0,
    val batteryLevel: Int = 0,
    val remainingVideoTime: Int = 0,
    val cameraMode: Int = 0,
    val availablePresets: List<PresetGroup> = emptyList(),
    val activePresetName: String? = null,
    val activePresetId: Int? = null,
    val activePresetIcon: Int? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val goProManager = GoProManager.getInstance(application)

    private val _uiState = MutableStateFlow(MainUiState())
    private var scanTimeoutJob: Job? = null

    // 1. Group Connection Data
    private val connectionInfo =
        combine(
            goProManager.connectionState,
            goProManager.scannedDevices,
            goProManager.pairedDevices,
        ) { connectionState, scannedDevices, pairedDevices ->
            Triple(connectionState, scannedDevices, pairedDevices)
        }

    // 2. Group Status Data
    private val statusInfo =
        combine(
            goProManager.isRecording,
            goProManager.recordingDuration,
            goProManager.batteryLevel,
            goProManager.remainingVideoTime,
            goProManager.cameraMode,
        ) { isRecording, recordingDuration, batteryLevel, remainingVideoTime, cameraMode ->
            StatusInfo(isRecording, recordingDuration, batteryLevel, remainingVideoTime, cameraMode)
        }

    // 3. Group Preset Data
    private val presetInfo =
        combine(
            goProManager.availablePresets,
            goProManager.activePresetName,
            goProManager.activePresetId,
            goProManager.activePresetIcon,
        ) { availablePresets, activePresetName, activePresetId, activePresetIcon ->
            PresetInfo(availablePresets, activePresetName, activePresetId, activePresetIcon)
        }

    // 4. Combine all groups with local UI state
    val uiState: StateFlow<MainUiState> =
        combine(
            _uiState,
            connectionInfo,
            statusInfo,
            presetInfo,
        ) { localState, connInfo, statInfo, presInfo ->
            localState.copy(
                // Connection
                connectionState = connInfo.first,
                scannedDevices = connInfo.second,
                pairedDevices = connInfo.third,
                // Status
                isRecording = statInfo.isRecording,
                recordingDuration = statInfo.recordingDuration,
                batteryLevel = statInfo.batteryLevel,
                remainingVideoTime = statInfo.remainingVideoTime,
                cameraMode = statInfo.cameraMode,
                // Presets
                availablePresets = presInfo.availablePresets,
                activePresetName = presInfo.activePresetName,
                activePresetId = presInfo.activePresetId,
                activePresetIcon = presInfo.activePresetIcon,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MainUiState(),
        )

    // Helper Data Classes
    private data class StatusInfo(
        val isRecording: Boolean,
        val recordingDuration: Int,
        val batteryLevel: Int,
        val remainingVideoTime: Int,
        val cameraMode: Int,
    )

    private data class PresetInfo(
        val availablePresets: List<PresetGroup>,
        val activePresetName: String?,
        val activePresetId: Int?,
        val activePresetIcon: Int?,
    )

    init {
        // Trigger auto-connect logic on init
        // We delay slightly or just check paired devices
        // Note: In the original code, this was in LaunchedEffect.
        // Ideally we check if we should auto-connect.
        // For now, we mimic the original logic but kept in ViewModel.
        // However, `pairedDevices` might not be loaded immediately if it's async,
        // but GoProManager loads them in init block which is synchronous for SharedPreferences.

        // We need to wait until we have the paired devices to decide.
        // Since flows are involved, we launch a collection.
        viewModelScope.launch {
            goProManager.pairedDevices.collect { devices ->
                if (devices.isNotEmpty() && _uiState.value.connectionState is ConnectionState.Disconnected) {
                    // Logic to handle auto-connect if needed, currently triggered by LaunchedEffect in MainScreen.
                }
            }
        }

        viewModelScope.launch {
            // New logic: When connected, automatically dismiss scanning screen
            goProManager.connectionState.collect { connectionState ->
                if (connectionState is ConnectionState.Connected) {
                    _uiState.value = _uiState.value.copy(showScanningScreen = false)
                }
            }
        }
    }

    // Explicit AutoConnect trigger
    fun tryAutoConnect() {
        if (goProManager.pairedDevices.value.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(isAutoConnecting = true)
            goProManager.tryAutoConnect()
        }
    }

    fun startScan() {
        goProManager.startScan()
        scanTimeoutJob?.cancel()
        scanTimeoutJob = viewModelScope.launch {
            delay(120_000) // 2 minutes
            stopScan()
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        goProManager.stopScan()
        // We do NOT disconnect here anymore based on previous fix
    }

    fun connect(device: android.bluetooth.BluetoothDevice) = goProManager.connect(device)

    fun connectToPaired(address: String) = goProManager.connectToDevice(address)

    fun disconnect() {
        goProManager.disconnect()
        _uiState.value = _uiState.value.copy(showMenu = false, isAutoConnecting = false)
    }

    fun forgetDevice() {
        goProManager.forgetConnectedDevice()
        _uiState.value = _uiState.value.copy(showMenu = false, isAutoConnecting = false)
    }

    fun removePairedDevice(address: String) = goProManager.removePairedDevice(address)

    fun toggleRecording() {
        if (_uiState.value.isProcessing) return
        _uiState.value = _uiState.value.copy(isProcessing = true)
        viewModelScope.launch {
            try {
                if (goProManager.isRecording.value) {
                    goProManager.stopRecording()
                } else {
                    goProManager.startRecording()
                }
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    fun setMode(mode: Int) {
        if (_uiState.value.isProcessing) return
        _uiState.value = _uiState.value.copy(isProcessing = true)
        viewModelScope.launch {
            try {
                goProManager.setMode(mode)
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    fun loadPreset(presetId: Int) {
        if (_uiState.value.isProcessing) return
        _uiState.value = _uiState.value.copy(isProcessing = true)
        viewModelScope.launch {
            try {
                goProManager.loadPreset(presetId)
                _uiState.value = _uiState.value.copy(showPresetScreen = false)
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    // UI Navigation / Dialog State Setters
    fun setShowMenu(show: Boolean) {
        _uiState.value = _uiState.value.copy(showMenu = show)
    }

    fun setShowFeedbackScreen(show: Boolean) {
        _uiState.value = _uiState.value.copy(showFeedbackScreen = show)
    }

    fun setShowScanningScreen(show: Boolean) {
        _uiState.value = _uiState.value.copy(showScanningScreen = show)
        if (!show) {
            stopScan()
        }
    }

    fun setShowPresetScreen(show: Boolean) {
        _uiState.value = _uiState.value.copy(showPresetScreen = show)
    }

    fun cancelAutoConnect() {
        goProManager.disconnect()
        _uiState.value = _uiState.value.copy(isAutoConnecting = false, showMenu = false)
    }
}
