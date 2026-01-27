package com.karoocameracontrol.integrations.gopro

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PairedDevice(val address: String, val name: String)

sealed class ConnectionState {
    object Disconnected : ConnectionState()

    object Scanning : ConnectionState()

    data class Connecting(val deviceName: String?) : ConnectionState()

    data class Connected(val deviceName: String?) : ConnectionState()

    data class Error(val message: String) : ConnectionState()
}

@SuppressLint("MissingPermission") // Permissions are checked before calls
class GoProManager private constructor(private val context: Context) {
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private val bluetoothLeScanner
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0)
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()

    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _remainingSpace = MutableStateFlow<Long>(0L)
    val remainingSpace: StateFlow<Long> = _remainingSpace.asStateFlow()

    private val _remainingVideoTime = MutableStateFlow(0)
    val remainingVideoTime: StateFlow<Int> = _remainingVideoTime.asStateFlow()

    private val _cameraMode = MutableStateFlow(0)
    val cameraMode: StateFlow<Int> = _cameraMode.asStateFlow()

    private val _availablePresets = MutableStateFlow<List<PresetGroup>>(emptyList())
    val availablePresets: StateFlow<List<PresetGroup>> = _availablePresets.asStateFlow()

    private val _activePresetName = MutableStateFlow<String?>(null)
    val activePresetName: StateFlow<String?> = _activePresetName.asStateFlow()

    private val _activePresetId = MutableStateFlow<Int?>(null)
    val activePresetId: StateFlow<Int?> = _activePresetId.asStateFlow()

    private val _activePresetIcon = MutableStateFlow<Int?>(null)
    val activePresetIcon: StateFlow<Int?> = _activePresetIcon.asStateFlow()

    private var scanning = false

    // Packet Reassembly
    private val responseParser = GoProResponseParser()

    private val prefs by lazy {
        context.getSharedPreferences("GoProPrefs", Context.MODE_PRIVATE)
    }

    init {
        loadPairedDevices()
    }

    private fun loadPairedDevices() {
        val addresses = prefs.getStringSet("paired_device_addresses", emptySet()) ?: emptySet()
        val devices =
            addresses.mapNotNull { address ->
                val name = prefs.getString("device_name_$address", null) ?: return@mapNotNull null
                PairedDevice(address, name)
            }

        val lastAddress = prefs.getString("last_connected_address", null)
        val sortedDevices =
            if (lastAddress != null) {
                devices.sortedByDescending { it.address == lastAddress }
            } else {
                devices
            }

        _pairedDevices.value = sortedDevices
    }

    private fun savePairedDevice(device: BluetoothDevice) {
        val address = device.address
        val name = device.name ?: "Unknown GoPro"

        val currentAddresses = prefs.getStringSet("paired_device_addresses", emptySet()) ?: emptySet()
        val newAddresses = currentAddresses + address

        prefs.edit()
            .putStringSet("paired_device_addresses", newAddresses)
            .putString("device_name_$address", name)
            .putString("last_connected_address", address) // Save as last connected
            .apply()

        loadPairedDevices()
    }

    fun tryAutoConnect() {
        val lastAddress = prefs.getString("last_connected_address", null)
        val devices = _pairedDevices.value

        if (lastAddress != null) {
            // Check if it's still in the paired list
            if (devices.any { it.address == lastAddress }) {
                Log.d(TAG, "Auto-connecting to last device: $lastAddress")
                connectToDevice(lastAddress)
                return
            }
        }

        // Fallback: Connect to first paired device if exists
        if (devices.isNotEmpty()) {
            val firstDevice = devices.first()
            Log.d(TAG, "Auto-connecting to first paired device: ${firstDevice.address}")
            connectToDevice(firstDevice.address)
        }
    }

    fun removePairedDevice(address: String) {
        val currentAddresses = prefs.getStringSet("paired_device_addresses", emptySet()) ?: emptySet()
        val newAddresses = currentAddresses - address

        prefs.edit()
            .putStringSet("paired_device_addresses", newAddresses)
            .remove("device_name_$address")
            .apply()

        loadPairedDevices()

        // If we removed the currently connected device, disconnect?
        val connectedDeviceName = (connectionState.value as? ConnectionState.Connected)?.deviceName
        if (connectedDeviceName != null) {
            // Logic: usually manual disconnect is better, but removing pairing implies forgetting.
            // We'll leave it connected for now.
        }
    }

    // Coroutine scope for BLE operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var recordingStartTimeMs: Long = 0L

    // BLE Operation Management
    private var connectedGatt: BluetoothGatt? = null
    private val operationMutex = Mutex()

    // Holds the continuation for the currently active BLE operation (Connect, Read, Write, etc.)
    // Access must be synchronized via continuationLock
    private var activeContinuation: CancellableContinuation<Any?>? = null

    // To distinguish which operation we are waiting for
    private enum class BleOperationType {
        CONNECT,
        DISCOVER_SERVICES,
        READ_CHARACTERISTIC,
        WRITE_DESCRIPTOR,
        WRITE_CHARACTERISTIC,
        NONE,
    }

    // Access must be synchronized via continuationLock
    private var currentOperationType = BleOperationType.NONE

    // Lock for thread-safe access to continuation state from callbacks and coroutines
    private val continuationLock = Any()

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                super.onScanResult(callbackType, result)
                val device = result.device

                if (!_scannedDevices.value.contains(device)) {
                    _scannedDevices.value = _scannedDevices.value + device
                    Log.d(TAG, "Found GoPro: ${device.name} (${device.address})")
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                super.onBatchScanResults(results)
                for (result in results) {
                    val device = result.device

                    if (!_scannedDevices.value.contains(device)) {
                        _scannedDevices.value = _scannedDevices.value + device
                        Log.d(TAG, "Found GoPro (batch): ${device.name} (${device.address})")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(TAG, "BLE Scan Failed: $errorCode")
                _connectionState.value = ConnectionState.Error("Scan failed: $errorCode")
                scanning = false
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                super.onConnectionStateChange(gatt, status, newState)
                val deviceName = gatt.device.name

                // GATT_CONN_TERMINATE_PEER_USER means remote device disconnected - treat as normal disconnect
                if (status == BluetoothGatt.GATT_SUCCESS || status == GATT_CONN_TERMINATE_PEER_USER) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "onConnectionStateChange: Connected to $deviceName")
                        synchronized(continuationLock) {
                            if (currentOperationType == BleOperationType.CONNECT) {
                                resumeActiveContinuationLocked(Unit)
                            }
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "onConnectionStateChange: Disconnected from $deviceName (status=$status)")
                        connectedGatt?.close()
                        connectedGatt = null
                        _connectionState.value = ConnectionState.Disconnected
                        _isRecording.value = false
                        stopRecordingTimer()
                        // If we were waiting for something else, this is an error
                        synchronized(continuationLock) {
                            if (currentOperationType != BleOperationType.NONE) {
                                resumeActiveContinuationWithExceptionLocked(Exception("Disconnected unexpectedly (status=$status)"))
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "onConnectionStateChange: Error $status")
                    connectedGatt?.close()
                    connectedGatt = null
                    _connectionState.value = ConnectionState.Error("Connection error: $status")
                    _isRecording.value = false
                    stopRecordingTimer()
                    synchronized(continuationLock) {
                        resumeActiveContinuationWithExceptionLocked(Exception("Connection error: $status"))
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                super.onServicesDiscovered(gatt, status)
                synchronized(continuationLock) {
                    if (currentOperationType == BleOperationType.DISCOVER_SERVICES) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d(TAG, "Services discovered successfully.")
                            resumeActiveContinuationLocked(Unit)
                        } else {
                            Log.e(TAG, "Service discovery failed: $status")
                            resumeActiveContinuationWithExceptionLocked(Exception("Service discovery failed: $status"))
                        }
                    }
                }
            }

            // API 33+ callback
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                handleCharacteristicRead(characteristic, status)
            }

            // Legacy callback for API < 33
            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    handleCharacteristicRead(characteristic, status)
                }
            }

            private fun handleCharacteristicRead(
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                synchronized(continuationLock) {
                    if (currentOperationType == BleOperationType.READ_CHARACTERISTIC) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d(TAG, "Read characteristic ${characteristic.uuid}")
                            resumeActiveContinuationLocked(Unit)
                        } else {
                            Log.e(TAG, "Read failed: $status")
                            resumeActiveContinuationWithExceptionLocked(Exception("Read failed: $status"))
                        }
                    }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                super.onCharacteristicWrite(gatt, characteristic, status)
                synchronized(continuationLock) {
                    if (currentOperationType == BleOperationType.WRITE_CHARACTERISTIC) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d(TAG, "Characteristic write success for ${characteristic.uuid}")
                            resumeActiveContinuationLocked(Unit)
                        } else {
                            Log.e(TAG, "Characteristic write failed: $status")
                            resumeActiveContinuationWithExceptionLocked(Exception("Characteristic write failed: $status"))
                        }
                    }
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                super.onDescriptorWrite(gatt, descriptor, status)
                synchronized(continuationLock) {
                    if (currentOperationType == BleOperationType.WRITE_DESCRIPTOR) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d(TAG, "Descriptor write success for ${descriptor.characteristic.uuid}")
                            resumeActiveContinuationLocked(Unit)
                        } else {
                            Log.e(TAG, "Descriptor write failed: $status")
                            resumeActiveContinuationWithExceptionLocked(Exception("Descriptor write failed: $status"))
                        }
                    }
                }
            }

            // API 33+ callback
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleCharacteristicChanged(characteristic.uuid, value)
            }

            // Legacy callback for API < 33
            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    val value = characteristic.value ?: return
                    handleCharacteristicChanged(characteristic.uuid, value)
                }
            }

            private fun handleCharacteristicChanged(
                uuid: java.util.UUID,
                value: ByteArray,
            ) {
                val fullData = responseParser.processCharacteristicChange(uuid, value)
                if (fullData != null) {
                    processCharacteristicData(fullData)
                }
            }

            private fun processCharacteristicData(value: ByteArray) {
                val hexValue = value.joinToString(" ") { "%02x".format(it) }
                Log.d(TAG, "Process Data: $hexValue")

                if (value.isNotEmpty() && value[0] == 0xF5.toByte()) {
                    processProtobufData(value)
                    return
                }

                // Use sequential TLV parser for status responses
                val status = GoProStatusParser.parse(value)

                // Apply parsed values to state flows (only if present in response)
                status.isRecording?.let { recording ->
                    val wasRecording = _isRecording.value
                    _isRecording.value = recording
                    if (recording && !wasRecording) {
                        startRecordingTimer()
                    } else if (!recording && wasRecording) {
                        stopRecordingTimer()
                    }
                }

                status.recordingDuration?.let { duration ->
                    _recordingDuration.value = duration
                    recordingStartTimeMs = SystemClock.elapsedRealtime() - (duration * 1000L)
                }

                status.batteryLevel?.let { battery ->
                    _batteryLevel.value = battery
                }

                status.remainingSpace?.let { space ->
                    _remainingSpace.value = space
                }

                status.remainingVideoTime?.let { time ->
                    _remainingVideoTime.value = time
                }

                status.cameraMode?.let { mode ->
                    _cameraMode.value = mode
                }

                status.activePresetId?.let { presetId ->
                    _activePresetId.value = presetId
                    updateActivePresetName()
                }
            }

            private fun processProtobufData(value: ByteArray) {
                // Feature ID 0xF5
                // Format: [F5] [ActionID] [Payload...]
                // Note: Unlike other commands, F5 responses often assume success or encode status in proto,
                // and start payload immediately after ActionID.
                if (value.size < 2) return

                val actionId = value[1]

                // Payload is from index 2
                if (value.size > 2) {
                    val protoData = value.sliceArray(2 until value.size)
                    try {
                        val groups = GoProProtobuf.parseNotifyPresetStatus(protoData)
                        if (groups.isNotEmpty()) {
                            _availablePresets.value = groups
                            Log.d(TAG, "Parsed ${groups.size} preset groups")
                            updateActivePresetName()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse protobuf data", e)
                    }
                }
            }
        }

    private fun updateActivePresetName() {
        val currentId = _activePresetId.value ?: return
        val currentPresets = _availablePresets.value

        if (currentPresets.isNotEmpty()) {
            val loadedPreset =
                currentPresets
                    .flatMap { it.presets }
                    .find { it.id == currentId }

            if (loadedPreset != null) {
                _activePresetName.value =
                    if (!loadedPreset.customName.isNullOrEmpty()) {
                        loadedPreset.customName
                    } else {
                        PresetTitle.getTitle(loadedPreset.titleId)
                    }
                _activePresetIcon.value = loadedPreset.iconId
            }
        }
    }

    // Must be called while holding continuationLock
    private fun resumeActiveContinuationLocked(value: Any?) {
        activeContinuation?.let {
            if (it.isActive) {
                it.resume(value)
            }
        }
        activeContinuation = null
        currentOperationType = BleOperationType.NONE
    }

    // Must be called while holding continuationLock
    private fun resumeActiveContinuationWithExceptionLocked(exception: Exception) {
        activeContinuation?.let {
            if (it.isActive) {
                it.resumeWithException(exception)
            }
        }
        activeContinuation = null
        currentOperationType = BleOperationType.NONE
    }

    // Public API

    fun startScan() {
        if (!hasPermissions()) {
            _connectionState.value = ConnectionState.Error("Permissions not granted")
            return
        }

        val scanner = bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = ConnectionState.Error("BLE scanner unavailable")
            return
        }

        if (!scanning) {
            _scannedDevices.value = emptyList()

            val filters =
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(GoProUUID.GOPRO_SERVICE))
                        .build(),
                )
            val settings =
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            _connectionState.value = ConnectionState.Scanning
            Log.d(TAG, "BLE Scan Started")
        }
    }

    fun stopScan() {
        if (!hasPermissions() || bluetoothLeScanner == null) return

        if (scanning) {
            bluetoothLeScanner?.stopScan(scanCallback)
            scanning = false
            if (_connectionState.value == ConnectionState.Scanning) {
                _connectionState.value = ConnectionState.Disconnected
            }
            Log.d(TAG, "BLE Scan Stopped")
        }
    }

    fun connectToDevice(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        connect(device, isPaired = true)
    }

    suspend fun startRecording() {
        writeCharacteristicSuspend(GoProUUID.COMMAND, GoProCommands.Recording.START)

        for (i in 1..RECORDING_START_MAX_POLLS) {
            kotlinx.coroutines.delay(RECORDING_POLL_DELAY_MS)
            if (_isRecording.value) {
                break
            }
            pollRecordingState()
        }
    }

    suspend fun stopRecording() {
        pauseRecordingTimer()
        try {
            writeCharacteristicSuspend(GoProUUID.COMMAND, GoProCommands.Recording.STOP)

            for (i in 1..RECORDING_STOP_MAX_POLLS) {
                kotlinx.coroutines.delay(STOP_RECORDING_POLL_DELAY_MS)
                if (!_isRecording.value) {
                    break
                }
                pollRecordingState()
            }
        } finally {
            // If we are still recording (stop failed or timed out), resume the timer
            if (_isRecording.value) {
                startRecordingTimer()
            }
        }
    }

    suspend fun setMode(mode: Int) {
        val modeId =
            when (mode) {
                0 -> PRESET_MODE_VIDEO
                1 -> PRESET_MODE_PHOTO
                2 -> PRESET_MODE_TIMELAPSE
                else -> PRESET_MODE_VIDEO
            }

        val cmd = GoProCommands.Modes.setMode(modeId)
        writeCharacteristicSuspend(GoProUUID.COMMAND, cmd)

        kotlinx.coroutines.delay(MODE_CHANGE_DELAY_MS)
        pollInitialStatus()
    }

    suspend fun getAvailablePresets() {
        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Presets.GET_AVAILABLE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available presets", e)
        }
    }

    suspend fun loadPreset(presetId: Int) {
        val cmd = GoProCommands.Presets.load(presetId)
        writeCharacteristicSuspend(GoProUUID.COMMAND, cmd)

        // Optimistically update the active preset name
        val loadedPreset =
            _availablePresets.value
                .flatMap { it.presets }
                .find { it.id == presetId }

        _activePresetName.value =
            loadedPreset?.let {
                if (!it.customName.isNullOrEmpty()) it.customName else PresetTitle.getTitle(it.titleId)
            }
        _activePresetIcon.value = loadedPreset?.iconId
    }

    private suspend fun pollRecordingState() {
        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Query.STATUS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll recording state", e)
        }
    }

    private suspend fun pollInitialStatus() {
        // We wrap each command in try-catch so one failure doesn't stop the rest.
        // especially important because timeouts can happen if the camera is busy.

        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Query.STATUS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll recording state: ${e.message}")
        }

        if (_isRecording.value) {
            pollDuration() // Only poll duration if already recording, timer will start on isRecording update
        }

        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Query.BATTERY)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll battery: ${e.message}")
        }

        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Query.REMAINING_SPACE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll space: ${e.message}")
        }

        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Query.REMAINING_VIDEO_TIME)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll remaining time: ${e.message}")
        }

        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Query.MODE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll mode: ${e.message}")
        }

        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Query.PRESET_GROUP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll mode 96: ${e.message}")
        }

        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Query.ACTIVE_PRESET)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll active preset ID 0x61: ${e.message}")
        }

        // Fetch presets - Critical for UI, retry on failure
        for (attempt in 1..PRESET_FETCH_MAX_RETRIES) {
            try {
                kotlinx.coroutines.delay(PRESET_FETCH_BASE_DELAY_MS * attempt)
                getAvailablePresets()
                break // Success, exit retry loop
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get available presets (attempt $attempt/$PRESET_FETCH_MAX_RETRIES): ${e.message}")
                if (attempt == PRESET_FETCH_MAX_RETRIES) {
                    Log.e(TAG, "Failed to get available presets after $PRESET_FETCH_MAX_RETRIES attempts", e)
                }
            }
        }
    }

    private suspend fun pollDuration() {
        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Query.DURATION)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll duration: ${e.message}")
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        // Initialize start time if not already set (e.g. if we just started recording)
        // If we are resuming, the first poll will correct this.
        if (_recordingDuration.value == 0) {
            recordingStartTimeMs = SystemClock.elapsedRealtime()
        } else {
            recordingStartTimeMs = SystemClock.elapsedRealtime() - (_recordingDuration.value * 1000L)
        }

        recordingTimerJob =
            scope.launch {
                var ticksSinceSync = 0
                while (isActive) {
                    kotlinx.coroutines.delay(200) // Update UI frequently

                    val now = SystemClock.elapsedRealtime()
                    val newDuration = ((now - recordingStartTimeMs) / 1000).toInt()

                    if (newDuration != _recordingDuration.value) {
                        _recordingDuration.value = newDuration
                    }

                    ticksSinceSync++
                    if (ticksSinceSync * 200L >= RECORDING_SYNC_INTERVAL_MS) {
                        pollDuration()
                        ticksSinceSync = 0
                    }
                }
            }
    }

    private fun pauseRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _recordingDuration.value = 0
    }

    fun connect(
        device: BluetoothDevice,
        isPaired: Boolean = false,
    ) {
        if (!hasPermissions()) {
            _connectionState.value = ConnectionState.Error("Permissions not granted")
            return
        }

        if (_connectionState.value is ConnectionState.Connecting || _connectionState.value is ConnectionState.Connected) {
            Log.w(TAG, "Already connecting or connected, ignoring connect request.")
            return
        }

        stopScan()

        connectionJob?.cancel()
        connectionJob =
            scope.launch {
                _connectionState.value = ConnectionState.Connecting(device.name ?: device.address)

                while (isActive) {
                    try {
                        // 1. Connect
                        connectGattSuspend(device, autoConnect = isPaired)

                        // 2. Discover Services
                        discoverServicesSuspend()

                        // 3. Handshake: Read Password (Pairs device)
                        readCharacteristicSuspend(GoProUUID.WIFI_AP_PASSWORD)

                        // 4. Handshake: Enable Notifications
                        enableNotificationSuspend(GoProUUID.COMMAND_RESPONSE)
                        enableNotificationSuspend(GoProUUID.SETTING_RESPONSE)
                        enableNotificationSuspend(GoProUUID.QUERY_RESPONSE)

                        // 5. Register for Status Updates
                        registerForStatusUpdatesSuspend()

                        // 6. Get Initial Status
                        pollInitialStatus()

                        savePairedDevice(device)
                        _connectionState.value = ConnectionState.Connected(device.name ?: device.address)
                        Log.d(TAG, "GoPro Connection Fully Established!")
                        _activePresetName.value = null // Clear when connected, will be set on loadPreset or by camera update
                        _activePresetIcon.value = null

                        break
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Connection failed", e)
                        disconnect()

                        if (isPaired) {
                            Log.d(TAG, "Retrying connection for saved device...")
                            _connectionState.value = ConnectionState.Connecting(device.name ?: device.address)
                            kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
                        } else {
                            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
                            break
                        }
                    }
                }
            }
    }

    private suspend fun registerForStatusUpdatesSuspend() {
        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, GoProCommands.Query.REGISTER_UPDATES)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register for status updates: ${e.message}")
        }
    }

    fun forgetConnectedDevice() {
        connectedGatt?.device?.address?.let { address ->
            removePairedDevice(address)
        }
        disconnect()
    }

    fun disconnect() {
        if (!hasPermissions()) return

        // Cancel the connection job to stop any ongoing connection attempts
        connectionJob?.cancel()

        connectedGatt?.disconnect()
        connectedGatt?.close()
        connectedGatt = null
        _connectionState.value = ConnectionState.Disconnected
        _isRecording.value = false
        stopRecordingTimer()
    }

    // Suspend Functions

    private suspend fun connectGattSuspend(
        device: BluetoothDevice,
        autoConnect: Boolean,
    ) = operationMutex.withLock {
        if (autoConnect) {
            suspendCancellableCoroutine<Unit> { cont ->
                synchronized(continuationLock) {
                    currentOperationType = BleOperationType.CONNECT
                    @Suppress("UNCHECKED_CAST")
                    activeContinuation = cont as CancellableContinuation<Any?>
                }
                cont.invokeOnCancellation {
                    synchronized(continuationLock) {
                        activeContinuation = null
                        currentOperationType = BleOperationType.NONE
                    }
                    // Clean up GATT on cancellation
                    connectedGatt?.close()
                    connectedGatt = null
                }
                connectedGatt = device.connectGatt(context, true, gattCallback)
            }
        } else {
            withTimeout(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    synchronized(continuationLock) {
                        currentOperationType = BleOperationType.CONNECT
                        @Suppress("UNCHECKED_CAST")
                        activeContinuation = cont as CancellableContinuation<Any?>
                    }
                    cont.invokeOnCancellation {
                        synchronized(continuationLock) {
                            activeContinuation = null
                            currentOperationType = BleOperationType.NONE
                        }
                        connectedGatt?.close()
                        connectedGatt = null
                    }
                    connectedGatt = device.connectGatt(context, false, gattCallback)
                }
            }
        }
    }

    private suspend fun discoverServicesSuspend() =
        operationMutex.withLock {
            val gatt = connectedGatt ?: throw Exception("Not connected")
            withTimeout(BLE_OPERATION_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    synchronized(continuationLock) {
                        currentOperationType = BleOperationType.DISCOVER_SERVICES
                        @Suppress("UNCHECKED_CAST")
                        activeContinuation = cont as CancellableContinuation<Any?>
                    }
                    cont.invokeOnCancellation {
                        synchronized(continuationLock) {
                            activeContinuation = null
                            currentOperationType = BleOperationType.NONE
                        }
                    }
                    gatt.discoverServices()
                }
            }
        }

    private suspend fun readCharacteristicSuspend(uuid: UUID) =
        operationMutex.withLock {
            val gatt = connectedGatt ?: throw Exception("Not connected")
            val char = findCharacteristic(gatt, uuid) ?: throw Exception("Characteristic $uuid not found in any service")

            withTimeout(BLE_OPERATION_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    synchronized(continuationLock) {
                        currentOperationType = BleOperationType.READ_CHARACTERISTIC
                        @Suppress("UNCHECKED_CAST")
                        activeContinuation = cont as CancellableContinuation<Any?>
                    }
                    cont.invokeOnCancellation {
                        synchronized(continuationLock) {
                            activeContinuation = null
                            currentOperationType = BleOperationType.NONE
                        }
                    }
                    if (!gatt.readCharacteristic(char)) {
                        synchronized(continuationLock) {
                            activeContinuation = null
                            currentOperationType = BleOperationType.NONE
                        }
                        cont.resumeWithException(Exception("Failed to initiate read"))
                    }
                }
            }
        }

    private suspend fun writeCharacteristicSuspend(
        uuid: UUID,
        value: ByteArray,
    ) = operationMutex.withLock {
        val gatt = connectedGatt ?: throw Exception("Not connected")
        val char = findCharacteristic(gatt, uuid) ?: throw Exception("Characteristic $uuid not found in any service")

        withTimeout(BLE_OPERATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                synchronized(continuationLock) {
                    currentOperationType = BleOperationType.WRITE_CHARACTERISTIC
                    @Suppress("UNCHECKED_CAST")
                    activeContinuation = cont as CancellableContinuation<Any?>
                }
                cont.invokeOnCancellation {
                    synchronized(continuationLock) {
                        activeContinuation = null
                        currentOperationType = BleOperationType.NONE
                    }
                }

                val writeResult =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(
                            char,
                            value,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = value
                        @Suppress("DEPRECATION")
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(char)
                    }

                if (!writeResult) {
                    synchronized(continuationLock) {
                        activeContinuation = null
                        currentOperationType = BleOperationType.NONE
                    }
                    cont.resumeWithException(Exception("Failed to initiate write to $uuid"))
                }
            }
        }
    }

    private suspend fun enableNotificationSuspend(uuid: UUID) =
        operationMutex.withLock {
            val gatt = connectedGatt ?: throw Exception("Not connected")
            val char = findCharacteristic(gatt, uuid) ?: throw Exception("Characteristic $uuid not found in any service")

            gatt.setCharacteristicNotification(char, true)

            val cccUuid = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
            val descriptor = char.getDescriptor(cccUuid) ?: throw Exception("CCCD Descriptor not found for $uuid")

            withTimeout(BLE_OPERATION_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    synchronized(continuationLock) {
                        currentOperationType = BleOperationType.WRITE_DESCRIPTOR
                        @Suppress("UNCHECKED_CAST")
                        activeContinuation = cont as CancellableContinuation<Any?>
                    }
                    cont.invokeOnCancellation {
                        synchronized(continuationLock) {
                            activeContinuation = null
                            currentOperationType = BleOperationType.NONE
                        }
                    }

                    val writeResult =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(
                                descriptor,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                            ) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }

                    if (!writeResult) {
                        synchronized(continuationLock) {
                            activeContinuation = null
                            currentOperationType = BleOperationType.NONE
                        }
                        cont.resumeWithException(Exception("Failed to initiate descriptor write"))
                    }
                }
            }
        }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        uuid: UUID,
    ): BluetoothGattCharacteristic? {
        gatt.services.forEach { service ->
            val char = service.getCharacteristic(uuid)
            if (char != null) return char
        }
        return null
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val TAG = "GoProManager"

        // Timeout values (milliseconds)
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val BLE_OPERATION_TIMEOUT_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 2_000L

        // Polling delays (milliseconds)
        private const val RECORDING_POLL_DELAY_MS = 500L
        private const val RECORDING_SYNC_INTERVAL_MS = 5_000L
        private const val STOP_RECORDING_POLL_DELAY_MS = 1_000L
        private const val MODE_CHANGE_DELAY_MS = 200L
        private const val PRESET_FETCH_BASE_DELAY_MS = 300L

        // Retry configuration
        private const val RECORDING_START_MAX_POLLS = 10
        private const val RECORDING_STOP_MAX_POLLS = 5
        private const val PRESET_FETCH_MAX_RETRIES = 3

        // GATT status codes
        private const val GATT_CONN_TERMINATE_PEER_USER = 19

        // GoPro preset mode IDs
        private const val PRESET_MODE_VIDEO = 1000
        private const val PRESET_MODE_PHOTO = 1001
        private const val PRESET_MODE_TIMELAPSE = 1002

        @Volatile
        private var instance: GoProManager? = null

        fun getInstance(context: Context): GoProManager {
            return instance ?: synchronized(this) {
                instance ?: GoProManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
