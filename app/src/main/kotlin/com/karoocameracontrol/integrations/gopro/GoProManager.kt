package com.karoocameracontrol.integrations.gopro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.client.distinctByPeripheral
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class PairedDevice(val address: String, val name: String)

data class ScannedDevice(val address: String, val name: String?)

sealed class GoProConnectionState {
    object Disconnected : GoProConnectionState()

    object Scanning : GoProConnectionState()

    object BluetoothDisabled : GoProConnectionState()

    data class Connecting(val deviceName: String?) : GoProConnectionState()

    data class Connected(val deviceName: String?) : GoProConnectionState()

    data class Error(val message: String) : GoProConnectionState()
}

@OptIn(ExperimentalUuidApi::class, ExperimentalStdlibApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class GoProManager private constructor(private val context: Context) {
    private val _connectionState = MutableStateFlow<GoProConnectionState>(GoProConnectionState.Disconnected)
    val connectionState: StateFlow<GoProConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

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

    // Coroutine scope for BLE operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var recordingStartTimeMs: Long = 0L

    // Nordic BLE Manager
    private val centralManager by lazy {
        CentralManager.Factory.native(context, scope)
    }

    private val connectionOptions by lazy {
        CentralManager.ConnectionOptions.Direct(timeout = 10.seconds, retry = 10, retryDelay = 2.seconds)
    }

    // Currently connected peripheral
    private var activePeripheral: Peripheral? = null

    // Track connected device info for reconnection
    private var connectedDeviceAddress: String? = null
    private var connectedDeviceName: String? = null

    private val bluetoothStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    Log.d(TAG, "Bluetooth state changed: $state")

                    if (state == BluetoothAdapter.STATE_ON) {
                        Log.d(TAG, "Bluetooth ON. Restarting scan immediately.")
                        if (!scanning) {
                            startScan()
                        } else {
                            Log.d(TAG, "Already scanning, ignoring ON event")
                        }
                    } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        if (scanning) {
                            Log.d(TAG, "Bluetooth turning off, stopping active scan")
                            stopScan()
                            _connectionState.value = GoProConnectionState.BluetoothDisabled
                        }
                    }
                }
            }
        }

    init {
        context.registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
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

    private fun savePairedDevice(
        address: String,
        name: String,
    ) {
        val currentAddresses = prefs.getStringSet("paired_device_addresses", emptySet()) ?: emptySet()
        val newAddresses = currentAddresses + address

        prefs.edit()
            .putStringSet("paired_device_addresses", newAddresses)
            .putString("device_name_$address", name)
            .putString("last_connected_address", address)
            .apply()

        loadPairedDevices()
    }

    fun tryAutoConnect() {
        val lastAddress = prefs.getString("last_connected_address", null)
        val devices = _pairedDevices.value

        if (lastAddress != null) {
            if (devices.any { it.address == lastAddress }) {
                Log.d(TAG, "Auto-connecting to last device: $lastAddress")
                connectToDevice(lastAddress)
                return
            }
        }

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
    }

    fun startScan() {
        if (!hasPermissions()) {
            _connectionState.value = GoProConnectionState.Error("Permissions not granted")
            return
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            _connectionState.value = GoProConnectionState.Error("Bluetooth not supported")
            return
        }

        if (!scanning) {
            _scannedDevices.value = emptyList()
            scanning = true
            Log.d(TAG, "BLE Scan Started. Scanning flag set to true. SDK: ${Build.VERSION.SDK_INT}")
            _connectionState.value = GoProConnectionState.Scanning

            scanJob =
                centralManager.scan()
                    .distinctByPeripheral()
                    .onEach { scanResult ->
                        val deviceName = scanResult.peripheral.name
                        val deviceAddress = scanResult.peripheral.address
                        Log.d(TAG, "Scanned device: $deviceName ($deviceAddress)")

                        val isGoPro = deviceName?.contains("GoPro", ignoreCase = true) == true

                        if (isGoPro) {
                            val device =
                                ScannedDevice(
                                    address = scanResult.peripheral.address,
                                    name = deviceName,
                                )

                            // Add to list if not already present
                            if (_scannedDevices.value.none { it.address == device.address }) {
                                _scannedDevices.value = _scannedDevices.value + device
                                Log.d(TAG, "Found GoPro: ${device.name} (${device.address})")
                            }
                        }
                    }
                    .catch { e ->
                        Log.e(TAG, "BLE Scan Failed", e)
                        if (e.message?.contains("disabled", ignoreCase = true) == true ||
                            e.message?.contains("unavailable", ignoreCase = true) == true
                        ) {
                            _connectionState.value = GoProConnectionState.BluetoothDisabled
                        } else {
                            _connectionState.value = GoProConnectionState.Error("Scan failed: ${e.message}")
                        }
                        scanning = false
                        Log.d(TAG, "BLE Scan Failed. Scanning flag set to false.")
                    }
                    .launchIn(scope)
        }
    }

    fun stopScan() {
        if (scanning) {
            scanJob?.cancel()
            scanJob = null
            scanning = false
            if (_connectionState.value == GoProConnectionState.Scanning) {
                _connectionState.value = GoProConnectionState.Disconnected
            }
            Log.d(TAG, "BLE Scan Stopped")
        }
    }

    fun connectToDevice(address: String) {
        val pairedDevice = _pairedDevices.value.find { it.address == address }
        connect(address, pairedDevice?.name, isPaired = true)
    }

    fun connect(scannedDevice: ScannedDevice) {
        connect(scannedDevice.address, scannedDevice.name, isPaired = false)
    }

    private fun connect(
        address: String,
        name: String?,
        isPaired: Boolean = false,
    ) {
        if (!hasPermissions()) {
            _connectionState.value = GoProConnectionState.Error("Permissions not granted")
            return
        }

        if (_connectionState.value is GoProConnectionState.Connecting || _connectionState.value is GoProConnectionState.Connected) {
            Log.w(TAG, "Already connecting or connected, ignoring connect request.")
            return
        }

        stopScan()
        connectionJob?.cancel()

        connectionJob =
            scope.launch {
                _connectionState.value = GoProConnectionState.Connecting(name ?: address)
                connectedDeviceAddress = address
                connectedDeviceName = name

                val connectionFlow: kotlinx.coroutines.flow.Flow<Peripheral?> =
                    flow {
                        // Find device (scan briefly)
                        val peripheral =
                            centralManager.scan()
                                .filter { it.peripheral.address == address }
                                .map { it.peripheral }
                                .first()

                        try {
                            repeat(Int.MAX_VALUE) { i ->
                                if (!isActive) return@repeat

                                if (i == 0) {
                                    Log.d(TAG, "Found $peripheral, connecting...")
                                } else {
                                    if (!isPaired) {
                                        return@flow
                                    }
                                    delay(2000)
                                    Log.d(TAG, "Reconnecting to $peripheral...")
                                    _connectionState.value = GoProConnectionState.Connecting(name ?: address)
                                }

                                try {
                                    centralManager.connect(peripheral, connectionOptions)
                                    Log.d(TAG, "Connected to $peripheral")

                                    activePeripheral = peripheral

                                    // Initialize GoPro specific logic
                                    initializeGoProConnection(peripheral, name ?: address)

                                    // Block while connected
                                    peripheral.state
                                        .filter { it is ConnectionState.Disconnected }
                                        .first()

                                    Log.d(TAG, "Device disconnected from $peripheral")
                                    handleDisconnection()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Connection attempt failed: ${e.message}")
                                    if (!isPaired && i == 0) throw e
                                }
                            }
                        } finally {
                            Log.d(TAG, "Disconnecting from $peripheral")
                            peripheral.disconnect()
                            activePeripheral = null
                        }
                    }

                connectionFlow.catch { e ->
                    Log.e(TAG, "Connection flow error", e)
                    _connectionState.value = GoProConnectionState.Error(e.message ?: "Connection failed")
                    handleDisconnection()
                    emit(null)
                }.collect {}
            }
    }

    private suspend fun initializeGoProConnection(
        peripheral: Peripheral,
        deviceName: String,
    ) {
        try {
            Log.d(TAG, "Initializing GoPro connection...")

            // Check Bond State explicitly using Native Adapter
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val device = bluetoothAdapter.getRemoteDevice(peripheral.address)
            Log.d(TAG, "Bond state: ${device.bondState} (10=None, 11=Bonding, 12=Bonded)")

            // Handshake: Read Password (Pairs device)
            // We rely on Implicit Bonding (triggered by reading encrypted char) instead of explicit createBond
            Log.d(TAG, "Reading Wifi AP Password (Pairing)...")
            try {
                readCharacteristic(peripheral, GoProUUID.WIFI_AP_PASSWORD)
                Log.d(TAG, "Read Wifi AP Password success (First Attempt)")
            } catch (e: Exception) {
                Log.w(TAG, "Read Wifi AP Password failed: ${e.message}. Waiting 15s for potential implicit bonding...")
                delay(15000) // Wait for user to accept pair dialog triggered by the read attempt

                // Retry Read
                Log.d(TAG, "Retrying Read Wifi AP Password...")
                try {
                    readCharacteristic(peripheral, GoProUUID.WIFI_AP_PASSWORD)
                    Log.d(TAG, "Read Wifi AP Password success (Second Attempt)")
                } catch (retryEx: Exception) {
                    Log.e(TAG, "Read Wifi AP Password failed again: ${retryEx.message}. Proceeding anyway...")
                }
            }
            delay(1000)

            // Handshake: Enable Notifications sequentially with safe delays
            // Minimal setup to test connectivity

            // Request MTU first (Configured in ConnectionOptions)
            delay(500)

            Log.d(TAG, "Enabling QUERY_RESPONSE notifications ONLY...")
            enableNotifications(peripheral, GoProUUID.QUERY_RESPONSE)
            delay(3000)

            // Set GoPro to Video Mode to ensure it's responsive
            Log.d(TAG, "Setting GoPro to Video Mode...")
            writeCharacteristic(peripheral, GoProUUID.COMMAND, GoProCommands.Modes.setMode(PRESET_MODE_VIDEO))
            delay(1000)

            // Register for Status Updates
            Log.d(TAG, "Registering for status updates...")
            writeCharacteristic(peripheral, GoProUUID.QUERY, GoProCommands.Query.REGISTER_UPDATES)
            delay(1000)

            // Get Initial Status
            Log.d(TAG, "Polling initial status...")
            pollInitialStatus(peripheral)

            // Save as paired
            savePairedDevice(peripheral.address, deviceName)

            _connectionState.value = GoProConnectionState.Connected(deviceName)
            Log.d(TAG, "GoPro Connection Fully Established!")

            // Reset state
            _activePresetName.value = null
            _activePresetIcon.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            throw e
        }
    }

    private fun handleDisconnection() {
        _connectionState.value = GoProConnectionState.Disconnected
        _isRecording.value = false
        activePeripheral = null
        stopRecordingTimer()
    }

    fun forgetConnectedDevice() {
        connectedDeviceAddress?.let { address ->
            removePairedDevice(address)
        }
        disconnect()
    }

    fun disconnect() {
        connectionJob?.cancel()
        runBlockingDisconnect()
        handleDisconnection()
        connectedDeviceAddress = null
        connectedDeviceName = null
    }

    private fun runBlockingDisconnect() {
        val p = activePeripheral
        if (p != null) {
            scope.launch { p.disconnect() }
        }
    }

    // --- Characteristic Operations ---

    private suspend fun readCharacteristic(
        peripheral: Peripheral,
        uuid: Uuid,
    ): ByteArray? {
        val serviceUuid = GoProUUID.GOPRO_SERVICE
        return peripheral.services(listOf(serviceUuid))
            .mapNotNull { services -> services.firstOrNull() }
            .firstOrNull()
            ?.characteristics?.firstOrNull { it.uuid == uuid }
            ?.read()
    }

    private suspend fun writeCharacteristic(
        peripheral: Peripheral,
        uuid: Uuid,
        value: ByteArray,
    ) {
        val serviceUuid = GoProUUID.GOPRO_SERVICE
        Log.d(TAG, "Writing characteristic $uuid: ${value.joinToString("") { "%02x".format(it) }}")

        peripheral.services(listOf(serviceUuid))
            .mapNotNull { services -> services.firstOrNull() }
            .firstOrNull()
            ?.characteristics?.firstOrNull { it.uuid == uuid }
            ?.write(value, WriteType.WITH_RESPONSE)
        Log.d(TAG, "Write characteristic $uuid complete")
    }

    // Helper for public methods to get current peripheral
    private suspend fun writeCharacteristic(
        uuid: Uuid,
        value: ByteArray,
    ) {
        val p = activePeripheral ?: return
        writeCharacteristic(p, uuid, value)
    }

    private suspend fun enableNotifications(
        peripheral: Peripheral,
        uuid: Uuid,
    ) {
        val serviceUuid = GoProUUID.GOPRO_SERVICE

        peripheral.services(listOf(serviceUuid))
            .flatMapLatest { services ->
                val service = services.firstOrNull() ?: return@flatMapLatest kotlinx.coroutines.flow.emptyFlow()
                val characteristic = service.characteristics.firstOrNull { it.uuid == uuid } ?: return@flatMapLatest kotlinx.coroutines.flow.emptyFlow()
                characteristic.subscribe().map { it }
            }
            .onEach { data ->
                handleCharacteristicChanged(uuid, data)
            }
            .catch { e ->
                Log.e(TAG, "Notification error for $uuid", e)
            }
            .launchIn(scope)

        Log.d(TAG, "Notifications enabled for $uuid")
    }

    private fun handleCharacteristicChanged(
        uuid: Uuid,
        value: ByteArray,
    ) {
        val fullData = responseParser.processCharacteristicChange(uuid, value)
        if (fullData != null) {
            processCharacteristicData(fullData)
        }
    }

    // --- Logic copied from previous implementation ---

    suspend fun startRecording() {
        writeCharacteristic(GoProUUID.COMMAND, GoProCommands.Recording.START)

        for (i in 1..RECORDING_START_MAX_POLLS) {
            delay(RECORDING_POLL_DELAY_MS)
            if (_isRecording.value) break
            pollRecordingState()
        }
    }

    suspend fun stopRecording() {
        pauseRecordingTimer()
        try {
            writeCharacteristic(GoProUUID.COMMAND, GoProCommands.Recording.STOP)

            for (i in 1..RECORDING_STOP_MAX_POLLS) {
                delay(STOP_RECORDING_POLL_DELAY_MS)
                if (!_isRecording.value) break
                pollRecordingState()
            }
        } finally {
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
        writeCharacteristic(GoProUUID.COMMAND, cmd)
        delay(MODE_CHANGE_DELAY_MS)
        pollInitialStatus(activePeripheral)
    }

    suspend fun getAvailablePresets() {
        try {
            writeCharacteristic(GoProUUID.QUERY, GoProCommands.Presets.GET_AVAILABLE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available presets", e)
        }
    }

    suspend fun loadPreset(presetId: Int) {
        val cmd = GoProCommands.Presets.load(presetId)
        writeCharacteristic(GoProUUID.COMMAND, cmd)

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
            writeCharacteristic(GoProUUID.QUERY, GoProCommands.Query.STATUS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll recording state", e)
        }
    }

    private suspend fun pollInitialStatus(peripheral: Peripheral?) {
        val p = peripheral ?: activePeripheral ?: return

        // Helper to ignore errors during polling
        suspend fun tryWrite(
            uuid: Uuid,
            value: ByteArray,
        ) {
            try {
                writeCharacteristic(p, uuid, value)
            } catch (e: Exception) {
                Log.w(TAG, "Poll failed $uuid: ${e.message}")
            }
        }

        tryWrite(GoProUUID.QUERY, GoProCommands.Query.STATUS)

        if (_isRecording.value) {
            pollDuration()
        }

        tryWrite(GoProUUID.QUERY, GoProCommands.Query.BATTERY)
        tryWrite(GoProUUID.QUERY, GoProCommands.Query.REMAINING_SPACE)
        tryWrite(GoProUUID.QUERY, GoProCommands.Query.REMAINING_VIDEO_TIME)
        tryWrite(GoProUUID.QUERY, GoProCommands.Query.MODE)
        tryWrite(GoProUUID.QUERY, GoProCommands.Query.PRESET_GROUP)
        tryWrite(GoProUUID.QUERY, GoProCommands.Query.ACTIVE_PRESET)

        for (attempt in 1..PRESET_FETCH_MAX_RETRIES) {
            try {
                delay(PRESET_FETCH_BASE_DELAY_MS * attempt)
                writeCharacteristic(p, GoProUUID.QUERY, GoProCommands.Presets.GET_AVAILABLE)
                break
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get available presets (attempt $attempt)")
            }
        }
    }

    private suspend fun pollDuration() {
        try {
            writeCharacteristic(GoProUUID.QUERY, GoProCommands.Query.DURATION)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll duration: ${e.message}")
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        if (_recordingDuration.value == 0) {
            recordingStartTimeMs = SystemClock.elapsedRealtime()
        } else {
            recordingStartTimeMs = SystemClock.elapsedRealtime() - (_recordingDuration.value * 1000L)
        }

        recordingTimerJob =
            scope.launch {
                var ticksSinceSync = 0
                while (isActive) {
                    delay(200)
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

    private fun processCharacteristicData(value: ByteArray) {
        val hexValue = value.joinToString(" ") { "%02x".format(it) }
        Log.d(TAG, "Process Data (Notification Received): $hexValue")

        if (value.isNotEmpty() && value[0] == 0xF5.toByte()) {
            processProtobufData(value)
            return
        }

        val status = GoProStatusParser.parse(value)

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
        if (value.size < 2) return
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

        private const val RECONNECT_DELAY_MS = 2_000L
        private const val RECORDING_POLL_DELAY_MS = 500L
        private const val RECORDING_SYNC_INTERVAL_MS = 5_000L
        private const val STOP_RECORDING_POLL_DELAY_MS = 1_000L
        private const val MODE_CHANGE_DELAY_MS = 200L
        private const val PRESET_FETCH_BASE_DELAY_MS = 300L
        private const val RECORDING_START_MAX_POLLS = 10
        private const val RECORDING_STOP_MAX_POLLS = 5
        private const val PRESET_FETCH_MAX_RETRIES = 3

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
