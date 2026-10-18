package com.karoocameracontrol.screens

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karoocameracontrol.alerts.CameraAlertSettingsStore
import com.karoocameracontrol.diagnostics.DiagnosticReports
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.integrations.gopro.PresetGroup
import com.karoocameracontrol.radar.RadarRecordingController
import com.karoocameracontrol.radar.RadarRecordingSettings
import com.karoocameracontrol.recording.AutomaticRecordingOwner
import com.karoocameracontrol.recording.DescentSettings
import com.karoocameracontrol.recording.DescentSettingsStore
import com.karoocameracontrol.recording.ManualRecording
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
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
    val isRecordingOperationInFlight: Boolean = false,
    val recordingTarget: Boolean? = null,
    val descentOwnsRecording: Boolean = false,
    val navigation: ScreenNavigation = ScreenNavigation(),
    val cameraCommandError: String? = null,
    // Device Data
    val scannedDevices: List<android.bluetooth.BluetoothDevice> = emptyList(),
    val pairedDevices: List<com.karoocameracontrol.integrations.gopro.PairedDevice> = emptyList(),
    val isRecording: Boolean = false,
    val cameraStateKnown: Boolean = false,
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
    private val reports = DiagnosticReports.get(application)
    val reportState = reports.state

    fun sendDiagnostics() {
        if (!DiagnosticReports.configured) return
        val connection =
            when (goProManager.connectionState.value) {
                is ConnectionState.Connected -> "Connected"
                is ConnectionState.Connecting -> "Connecting"
                is ConnectionState.Pairing -> "Pairing"
                is ConnectionState.PairingAccessRequired -> "PairingAccessRequired"
                is ConnectionState.Error -> "Error"
                ConnectionState.Scanning -> "Scanning"
                ConnectionState.Disconnected -> "Disconnected"
            }
        reports.send(DiagnosticReports.metadata(connection))
    }

    fun retryDiagnostics() {
        if (DiagnosticReports.configured) reports.retry()
    }

    fun discardDiagnostics() = reports.discard()

    private val goProManager = GoProManager.getInstance(application)
    private val karooSystem = KarooSystemService(application)
    private val radarRecording = RadarRecordingController.getInstance(application)
    val cameraAlertSettings = CameraAlertSettingsStore.getInstance(application)
    val radarSettings = RadarRecordingSettings.getInstance(application)
    val radarStatus =
        combine(radarRecording.status, radarSettings.settings) { status, settings ->
            if (radarRecording.isRunning) {
                status
            } else if (settings.enabled) {
                "Waiting for Karoo extension"
            } else {
                "Radar recording off"
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Waiting for Karoo extension")
    private val descentSettingsStore = DescentSettingsStore.getInstance(application)
    val descentSettings = descentSettingsStore.settings
    val descentStatus = descentSettingsStore.status

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
            combine(goProManager.cameraMode, goProManager.recordingStateKnown, goProManager.cameraModeKnown) { mode, recordingKnown, modeKnown ->
                mode to (recordingKnown && modeKnown)
            },
        ) { isRecording, recordingDuration, batteryLevel, remainingVideoTime, mode ->
            StatusInfo(isRecording, recordingDuration, batteryLevel, remainingVideoTime, mode.first, mode.second)
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
            goProManager.recordingCommands.state,
        ) { localState, connInfo, statInfo, presInfo, commandState ->
            localState.copy(
                isProcessing = commandState.busy,
                isRecordingOperationInFlight = commandState.recordingInFlight,
                recordingTarget = commandState.targetRecording,
                descentOwnsRecording = goProManager.recordingCommands.ownsRecording(AutomaticRecordingOwner.DESCENT),
                cameraCommandError = commandState.error,
                // Connection
                connectionState = connInfo.first,
                scannedDevices = connInfo.second,
                pairedDevices = connInfo.third,
                // Status
                isRecording = statInfo.isRecording,
                cameraStateKnown = statInfo.cameraStateKnown,
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
        val cameraStateKnown: Boolean,
    )

    private data class PresetInfo(
        val availablePresets: List<PresetGroup>,
        val activePresetName: String?,
        val activePresetId: Int?,
        val activePresetIcon: Int?,
    )

    init {
        karooSystem.connect { connected ->
            if (connected) {
                karooSystem.dispatch(RequestBluetooth("karoo-camera-control-ui"))
            }
        }

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
                    _uiState.value = _uiState.value.copy(navigation = _uiState.value.navigation.cameraConnected())
                }
            }
        }
    }

    override fun onCleared() {
        stopScan()
        karooSystem.dispatch(ReleaseBluetooth("karoo-camera-control-ui"))
        karooSystem.disconnect()
        super.onCleared()
    }

    // Explicit AutoConnect trigger
    private var autoConnectAttempted = false

    fun tryAutoConnect() {
        if (autoConnectAttempted) return
        autoConnectAttempted = true
        if (goProManager.pairedDevices.value.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(isAutoConnecting = true)
            goProManager.tryAutoConnect()
        }
    }

    fun startScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob =
            viewModelScope.launch {
                goProManager.startScan()
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

    fun openNotificationAccess() {
        getApplication<Application>().startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun disconnect() {
        goProManager.disconnect()
        _uiState.value = _uiState.value.copy(navigation = ScreenNavigation(), isAutoConnecting = false)
    }

    fun forgetDevice(expectedConnection: ConnectionState.Connected) {
        if (goProManager.connectionState.value !== expectedConnection) return
        goProManager.forgetConnectedDevice()
        _uiState.value = _uiState.value.copy(navigation = ScreenNavigation(), isAutoConnecting = false)
    }

    fun removePairedDevice(address: String) = goProManager.removePairedDevice(address)

    fun toggleRecording(expectedConnection: ConnectionState.Connected? = null) {
        if (expectedConnection != null && goProManager.connectionState.value !== expectedConnection) return
        val command = goProManager.recordingCommands.state.value
        val stopAvailable = goProManager.isRecording.value || command.recordingInFlight
        if (!stopAvailable && (!goProManager.recordingStateKnown.value || !goProManager.cameraModeKnown.value || command.busy)) return
        ManualRecording.toggle(getApplication())
    }

    fun setMode(mode: Int) {
        if (goProManager.connectionState.value !is ConnectionState.Connected) return
        if (!goProManager.recordingStateKnown.value || !goProManager.cameraModeKnown.value) return
        if (goProManager.recordingCommands.state.value.busy || goProManager.isRecording.value) return
        if (normalizedCameraMode(goProManager.cameraMode.value) == mode) {
            if (_uiState.value.navigation.destination == ScreenDestination.MODES) navigateBack()
            return
        }
        radarRecording.manualOverride()
        goProManager.recordingCommands.configure {
            goProManager.setMode(mode)
            if (_uiState.value.navigation.destination == ScreenDestination.MODES) navigateBack()
        }
    }

    fun loadPreset(presetId: Int) {
        if (goProManager.connectionState.value !is ConnectionState.Connected) return
        if (!goProManager.recordingStateKnown.value || !goProManager.cameraModeKnown.value) return
        if (goProManager.recordingCommands.state.value.busy || goProManager.isRecording.value) return
        radarRecording.manualOverride()
        goProManager.recordingCommands.configure {
            goProManager.loadPreset(presetId)
            if (_uiState.value.navigation.destination == ScreenDestination.PRESETS) navigateBack()
        }
    }

    fun clearCameraCommandError() = goProManager.recordingCommands.clearError()

    fun updateDescentSettings(settings: DescentSettings) = descentSettingsStore.update(settings)

    fun navigate(destination: ScreenDestination) {
        _uiState.value = _uiState.value.copy(navigation = _uiState.value.navigation.open(destination))
    }

    fun navigateBack() {
        if (_uiState.value.navigation.destination == ScreenDestination.SCANNING) stopScan()
        _uiState.value = _uiState.value.copy(navigation = _uiState.value.navigation.back())
    }

    fun cancelAutoConnect() {
        goProManager.disconnect()
        _uiState.value = _uiState.value.copy(isAutoConnecting = false, navigation = ScreenNavigation())
    }
}
