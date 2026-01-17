package com.karoocameracontrol.extension

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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
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

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private val bluetoothLeScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

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
    private val responseBuffer = java.io.ByteArrayOutputStream()
    private var expectedResponseLength = -1

    private val prefs by lazy {
        context.getSharedPreferences("GoProPrefs", Context.MODE_PRIVATE)
    }

    init {
        loadPairedDevices()
    }

    private fun loadPairedDevices() {
        val addresses = prefs.getStringSet("paired_device_addresses", emptySet()) ?: emptySet()
        val devices = addresses.mapNotNull { address ->
            val name = prefs.getString("device_name_$address", null) ?: return@mapNotNull null
            PairedDevice(address, name)
        }

        val lastAddress = prefs.getString("last_connected_address", null)
        val sortedDevices = if (lastAddress != null) {
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

    // BLE Operation Management
    private var connectedGatt: BluetoothGatt? = null
    private val operationMutex = Mutex()

    // Holds the continuation for the currently active BLE operation (Connect, Read, Write, etc.)
    private var activeContinuation: CancellableContinuation<Any?>? = null

    // To distinguish which operation we are waiting for
    private enum class BleOperationType {
        CONNECT, DISCOVER_SERVICES, READ_CHARACTERISTIC, WRITE_DESCRIPTOR, WRITE_CHARACTERISTIC, NONE
    }
    private var currentOperationType = BleOperationType.NONE

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
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

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            val deviceName = gatt.device.name

            // Status 19 is GATT_CONN_TERMINATE_PEER_USER (Remote device disconnected)
            // We should treat it as a normal disconnect event, not an error.
            if (status == BluetoothGatt.GATT_SUCCESS || status == 19) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "onConnectionStateChange: Connected to $deviceName")
                    if (currentOperationType == BleOperationType.CONNECT) {
                        resumeActiveContinuation(Unit)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "onConnectionStateChange: Disconnected from $deviceName (status=$status)")
                    connectedGatt?.close()
                    connectedGatt = null
                    _connectionState.value = ConnectionState.Disconnected
                    _isRecording.value = false
                    _recordingDuration.value = 0
                    // If we were waiting for something else, this is an error
                    if (currentOperationType != BleOperationType.NONE) {
                        resumeActiveContinuationWithException(Exception("Disconnected unexpectedly (status=$status)"))
                    }
                }
            } else {
                Log.e(TAG, "onConnectionStateChange: Error $status")
                connectedGatt?.close()
                connectedGatt = null
                _connectionState.value = ConnectionState.Error("Connection error: $status")
                _isRecording.value = false
                _recordingDuration.value = 0
                resumeActiveContinuationWithException(Exception("Connection error: $status"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (currentOperationType == BleOperationType.DISCOVER_SERVICES) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services discovered successfully.")
                    resumeActiveContinuation(Unit)
                } else {
                    Log.e(TAG, "Service discovery failed: $status")
                    resumeActiveContinuationWithException(Exception("Service discovery failed: $status"))
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (currentOperationType == BleOperationType.READ_CHARACTERISTIC) {
                 if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Read characteristic ${characteristic.uuid}")
                    resumeActiveContinuation(Unit)
                } else {
                    Log.e(TAG, "Read failed: $status")
                    resumeActiveContinuationWithException(Exception("Read failed: $status"))
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
             if (currentOperationType == BleOperationType.WRITE_CHARACTERISTIC) {
                 if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Characteristic write success for ${characteristic.uuid}")
                    resumeActiveContinuation(Unit)
                } else {
                    Log.e(TAG, "Characteristic write failed: $status")
                    resumeActiveContinuationWithException(Exception("Characteristic write failed: $status"))
                }
             }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
             super.onDescriptorWrite(gatt, descriptor, status)
             if (currentOperationType == BleOperationType.WRITE_DESCRIPTOR) {
                 if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Descriptor write success for ${descriptor.characteristic.uuid}")
                    resumeActiveContinuation(Unit)
                } else {
                    Log.e(TAG, "Descriptor write failed: $status")
                    resumeActiveContinuationWithException(Exception("Descriptor write failed: $status"))
                }
             }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            val value = characteristic.value ?: return

            if (characteristic.uuid == GoProUUID.QUERY_RESPONSE || characteristic.uuid == GoProUUID.COMMAND_RESPONSE) {
                val header = value[0].toInt() and 0xFF

                if ((header and 0x80) == 0x80) {
                    // Continuation packet
                    responseBuffer.write(value, 1, value.size - 1)
                } else {
                    // Start packet
                    responseBuffer.reset()
                    var offset = 1

                    if ((header and 0x20) == 0x20) {
                        // 13-bit length
                        if (value.size < 2) return
                        val lenHigh = header and 0x1F
                        val lenLow = value[1].toInt() and 0xFF
                        expectedResponseLength = (lenHigh shl 8) or lenLow
                        offset = 2
                    } else if ((header and 0x40) == 0x40) {
                        expectedResponseLength = header and 0x1F
                    } else {
                        expectedResponseLength = header and 0x1F
                    }

                    if (value.size > offset) {
                        responseBuffer.write(value, offset, value.size - offset)
                    }
                }

                // Check if we have the full packet
                if (expectedResponseLength > 0 && responseBuffer.size() >= expectedResponseLength) {
                    val fullData = responseBuffer.toByteArray()
                    processCharacteristicData(fullData)

                    // Reset for next
                    responseBuffer.reset()
                    expectedResponseLength = -1
                }
            }
        }

        private fun processCharacteristicData(value: ByteArray) {
            val hexValue = value.joinToString(" ") { "%02x".format(it) }
            Log.d(TAG, "Process Data: $hexValue")

            if (value.isNotEmpty() && value[0] == 0xF5.toByte()) {
                processProtobufData(value)
                return
            }

            // Parser for Status ID 10 (Encoding)
            for (i in 0 until value.size - 2) {
                if (value[i] == 0x0A.toByte() && value[i+1] == 0x01.toByte()) {
                    val isRecording = value[i+2] == 0x01.toByte()
                    _isRecording.value = isRecording
                    if (!isRecording) _recordingDuration.value = 0
                }
            }

            // Generic Parser for TLV
            for (i in 0 until value.size) {
                val id = value[i]

                // ID 13: Video Progress / Duration
                if (id == 0x0D.toByte()) {
                     if (i + 1 < value.size) {
                        val typeByte = value[i+1]
                        if ((typeByte == 0x03.toByte() || typeByte == 0x04.toByte()) && i + 5 < value.size) {
                            val valBytes = value.sliceArray((i+2)..(i+5))
                            val duration = (valBytes[0].toInt() and 0xFF shl 24) or
                                           (valBytes[1].toInt() and 0xFF shl 16) or
                                           (valBytes[2].toInt() and 0xFF shl 8) or
                                           (valBytes[3].toInt() and 0xFF)
                            _recordingDuration.value = duration
                        }
                    }
                }

                // ID 54 (0x36): Remaining Space
                if (id == 0x36.toByte()) {
                     if (i + 1 < value.size) {
                        val typeByte = value[i+1]
                        if (typeByte == 0x08.toByte() && i + 9 < value.size) {
                            val valBytes = value.sliceArray((i+2)..(i+9))
                            var remSpace: Long = 0
                            for (b in valBytes) {
                                remSpace = (remSpace shl 8) or (b.toLong() and 0xFF)
                            }
                            _remainingSpace.value = remSpace
                        } else if ((typeByte == 0x03.toByte() || typeByte == 0x04.toByte()) && i + 5 < value.size) {
                            val valBytes = value.sliceArray((i+2)..(i+5))
                            val remSpace = (valBytes[0].toInt() and 0xFF shl 24) or
                                          (valBytes[1].toInt() and 0xFF shl 16) or
                                          (valBytes[2].toInt() and 0xFF shl 8) or
                                          (valBytes[3].toInt() and 0xFF)
                            _remainingSpace.value = remSpace.toLong()
                        }
                    }
                }

                // ID 35 (0x23): Remaining Video Time
                if (id == 0x23.toByte()) {
                     if (i + 1 < value.size) {
                        val typeByte = value[i+1]
                        if ((typeByte == 0x03.toByte() || typeByte == 0x04.toByte()) && i + 5 < value.size) {
                            val valBytes = value.sliceArray((i+2)..(i+5))
                            val remTime = (valBytes[0].toInt() and 0xFF shl 24) or
                                          (valBytes[1].toInt() and 0xFF shl 16) or
                                          (valBytes[2].toInt() and 0xFF shl 8) or
                                          (valBytes[3].toInt() and 0xFF)
                            _remainingVideoTime.value = remTime
                        }
                    }
                }

                // ID 43 (0x2B): Active Preset Group (Mode)
                if (id == 0x2B.toByte()) {
                    if (i + 1 < value.size) {
                        val typeByte = value[i+1]
                        if ((typeByte == 0x02.toByte() || typeByte == 0x03.toByte()) && i + 3 < value.size) {
                             val b1 = value[i+2].toInt() and 0xFF
                             val b2 = value[i+3].toInt() and 0xFF
                             val modeVal = (b1 shl 8) or b2
                             _cameraMode.value = modeVal
                        }
                        else if (typeByte == 0x04.toByte() && i + 5 < value.size) {
                             val b1 = value[i+2].toInt() and 0xFF
                             val b2 = value[i+3].toInt() and 0xFF
                             val b3 = value[i+4].toInt() and 0xFF
                             val b4 = value[i+5].toInt() and 0xFF
                             val modeVal = (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
                             _cameraMode.value = modeVal
                        }
                    }
                }

                // ID 96 (0x60): Flat Mode / Preset Group
                if (id == 0x60.toByte()) {
                    if (i + 1 < value.size) {
                        val typeByte = value[i+1]
                        if (typeByte == 0x04.toByte() && i + 5 < value.size) {
                             val b1 = value[i+2].toInt() and 0xFF
                             val b2 = value[i+3].toInt() and 0xFF
                             val b3 = value[i+4].toInt() and 0xFF
                             val b4 = value[i+5].toInt() and 0xFF
                             val modeVal = (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
                             _cameraMode.value = modeVal
                        }
                    }
                }

                // ID 97 (0x61): Active Preset ID
                if (id == 0x61.toByte()) {
                    if (i + 1 < value.size) {
                        val typeByte = value[i+1]
                        if ((typeByte == 0x03.toByte() || typeByte == 0x04.toByte()) && i + 5 < value.size) {
                             val valBytes = value.sliceArray((i+2)..(i+5))
                                                          val presetId = (valBytes[0].toInt() and 0xFF shl 24) or
                                                                         (valBytes[1].toInt() and 0xFF shl 16) or
                                                                         (valBytes[2].toInt() and 0xFF shl 8) or
                                                                         (valBytes[3].toInt() and 0xFF)
                                                          
                                                          _activePresetId.value = presetId
                                                          updateActivePresetName()
                                                     }
                                                 }
                                             }
                                             // ID 70 (0x46): Battery Level
                if (id == 0x46.toByte()) {
                     if (i + 1 < value.size) {
                         val typeByte = value[i+1]
                         if ((typeByte == 0x01.toByte() || typeByte == 0x02.toByte()) && i + 2 < value.size) {
                             val batLevel = value[i+2].toInt() and 0xFF
                             _batteryLevel.value = batLevel
                         }
                     }
                }
            }
        }

        private fun processProtobufData(value: ByteArray) {
            // Feature ID 0xF5
            if (value.size < 2) return
            val actionId = value[1]

            // We assume Action ID 0x72 or similar corresponds to NotifyPresetStatus
            // Since we only requested one thing via F5, we try to parse it as presets.
            // Ideally we check Action ID.
            // Request: F5 72. Response Action ID: ?? (Likely 72 or 73)
            // For now, let's try parsing regardless of Action ID if it's F5.

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
             val loadedPreset = currentPresets
                .flatMap { it.presets }
                .find { it.id == currentId }

             if (loadedPreset != null) {
                _activePresetName.value = if (!loadedPreset.customName.isNullOrEmpty()) {
                    loadedPreset.customName
                } else {
                    PresetTitle.getTitle(loadedPreset.titleId)
                }
                _activePresetIcon.value = loadedPreset.iconId
             }
        }
    }

    private fun resumeActiveContinuation(value: Any?) {
        activeContinuation?.let {
            if (it.isActive) {
                it.resume(value)
            }
        }
        activeContinuation = null
        currentOperationType = BleOperationType.NONE
    }

    private fun resumeActiveContinuationWithException(exception: Exception) {
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

            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(GoProUUID.GOPRO_SERVICE))
                    .build()
            )
            val settings = ScanSettings.Builder()
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
        val cmd = byteArrayOf(0x03, 0x01, 0x01, 0x01)
        writeCharacteristicSuspend(GoProUUID.COMMAND, cmd)

        for (i in 1..10) {
            kotlinx.coroutines.delay(500)
            if (_isRecording.value) {
                break
            }
            pollRecordingState()
        }
    }

    suspend fun stopRecording() {
        val cmd = byteArrayOf(0x03, 0x01, 0x01, 0x00)
        writeCharacteristicSuspend(GoProUUID.COMMAND, cmd)

        for (i in 1..5) {
            kotlinx.coroutines.delay(1000)
            if (!_isRecording.value) {
                break
            }
            pollRecordingState()
        }
    }

    suspend fun setMode(mode: Int) {
        val modeId = when (mode) {
            0 -> 1000
            1 -> 1001
            2 -> 1002
            else -> 1000
        }

        val m1 = (modeId shr 8).toByte()
        val m2 = (modeId and 0xFF).toByte()

        val cmd = byteArrayOf(0x04, 0x3E, 0x02, m1, m2)
        writeCharacteristicSuspend(GoProUUID.COMMAND, cmd)

        kotlinx.coroutines.delay(200)
        pollInitialStatus()
    }

    suspend fun getAvailablePresets() {
        // Feature F5, Action 72, Proto: 08 01 (register_preset_status=true)
        // Length: 4 (F5, 72, 08, 01)
        val cmd = byteArrayOf(0x04, 0xF5.toByte(), 0x72.toByte(), 0x08, 0x01)
        try {
            writeCharacteristicSuspend(GoProUUID.QUERY, cmd)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available presets", e)
        }
    }

    suspend fun loadPreset(presetId: Int) {
        // Cmd: 40 (Load Preset)
        // Length: 4 (32-bit int)
        // Val: presetId (4 bytes)
        // Total Pkt Len: 1 (Cmd) + 1 (Len) + 4 (Val) = 6

        val v1 = (presetId shr 24).toByte()
        val v2 = (presetId shr 16).toByte()
        val v3 = (presetId shr 8).toByte()
        val v4 = (presetId and 0xFF).toByte()

        val cmd = byteArrayOf(0x06, 0x40, 0x04, v1, v2, v3, v4)
        writeCharacteristicSuspend(GoProUUID.COMMAND, cmd)

        // Optimistically update the active preset name
        val loadedPreset = _availablePresets.value
            .flatMap { it.presets }
            .find { it.id == presetId }

        _activePresetName.value = loadedPreset?.let {
            if (!it.customName.isNullOrEmpty()) it.customName else PresetTitle.getTitle(it.titleId)
        }
        _activePresetIcon.value = loadedPreset?.iconId
    }

    private suspend fun pollRecordingState() {
        try {
            val statusCmd = byteArrayOf(0x02, 0x13, 0x0A)
            writeCharacteristicSuspend(GoProUUID.QUERY, statusCmd)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll recording state", e)
        }
    }

    private suspend fun pollInitialStatus() {
        // We wrap each command in try-catch so one failure doesn't stop the rest.
        // especially important because timeouts can happen if the camera is busy.

        try {
            val statusCmd = byteArrayOf(0x02, 0x13, 0x0A)
            writeCharacteristicSuspend(GoProUUID.QUERY, statusCmd)
        } catch (e: Exception) { Log.w(TAG, "Failed to poll recording state: ${e.message}") }

        if (_isRecording.value) {
            try {
                val durationCmd = byteArrayOf(0x02, 0x13, 0x0D)
                writeCharacteristicSuspend(GoProUUID.QUERY, durationCmd)
            } catch (e: Exception) { Log.w(TAG, "Failed to poll duration: ${e.message}") }
        }

        try {
            val batteryCmd = byteArrayOf(0x02, 0x13, 0x46)
            writeCharacteristicSuspend(GoProUUID.QUERY, batteryCmd)
        } catch (e: Exception) { Log.w(TAG, "Failed to poll battery: ${e.message}") }

        try {
            val remSpaceCmd = byteArrayOf(0x02, 0x13, 0x36)
            writeCharacteristicSuspend(GoProUUID.QUERY, remSpaceCmd)
        } catch (e: Exception) { Log.w(TAG, "Failed to poll space: ${e.message}") }

        try {
            val remTimeCmd = byteArrayOf(0x02, 0x13, 0x23)
            writeCharacteristicSuspend(GoProUUID.QUERY, remTimeCmd)
        } catch (e: Exception) { Log.w(TAG, "Failed to poll remaining time: ${e.message}") }

        try {
            val modeCmd = byteArrayOf(0x02, 0x13, 0x2B)
            writeCharacteristicSuspend(GoProUUID.QUERY, modeCmd)
        } catch (e: Exception) { Log.w(TAG, "Failed to poll mode: ${e.message}") }

        try {
            val mode96Cmd = byteArrayOf(0x02, 0x13, 0x60)
            writeCharacteristicSuspend(GoProUUID.QUERY, mode96Cmd)
        } catch (e: Exception) { Log.w(TAG, "Failed to poll mode 96: ${e.message}") }

        try {
            val activePresetCmd = byteArrayOf(0x02, 0x13, 0x61)
            writeCharacteristicSuspend(GoProUUID.QUERY, activePresetCmd)
        } catch (e: Exception) { Log.w(TAG, "Failed to poll active preset ID 0x61: ${e.message}") }

        // Fetch presets - Critical for UI
        try {
            kotlinx.coroutines.delay(200) // Small delay to avoid congestion
            getAvailablePresets()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available presets", e)
        }
    }

    fun connect(device: BluetoothDevice, isPaired: Boolean = false) {
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
        connectionJob = scope.launch {
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
                        Log.d(TAG, "Retrying connection for saved device in 2 seconds...")
                        _connectionState.value = ConnectionState.Connecting(device.name ?: device.address)
                        kotlinx.coroutines.delay(2000)
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
            val cmd = byteArrayOf(0x09, 0x53, 0x0A, 0x0D, 0x46, 0x36, 0x23, 0x2B, 0x60, 0x61)
            writeCharacteristicSuspend(GoProUUID.QUERY, cmd)
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
        _recordingDuration.value = 0
    }

    // Suspend Functions

    private suspend fun connectGattSuspend(device: BluetoothDevice, autoConnect: Boolean) = operationMutex.withLock {
        if (autoConnect) {
            suspendCancellableCoroutine<Unit> { cont ->
                currentOperationType = BleOperationType.CONNECT
                @Suppress("UNCHECKED_CAST")
                activeContinuation = cont as CancellableContinuation<Any?>
                connectedGatt = device.connectGatt(context, true, gattCallback)
            }
        } else {
            withTimeout(10000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    currentOperationType = BleOperationType.CONNECT
                    @Suppress("UNCHECKED_CAST")
                    activeContinuation = cont as CancellableContinuation<Any?>
                    connectedGatt = device.connectGatt(context, false, gattCallback)
                }
            }
        }
    }

    private suspend fun discoverServicesSuspend() = operationMutex.withLock {
        val gatt = connectedGatt ?: throw Exception("Not connected")
        withTimeout(5000) {
             suspendCancellableCoroutine<Unit> { cont ->
                currentOperationType = BleOperationType.DISCOVER_SERVICES
                @Suppress("UNCHECKED_CAST")
                activeContinuation = cont as CancellableContinuation<Any?>
                gatt.discoverServices()
            }
        }
    }

    private suspend fun readCharacteristicSuspend(uuid: UUID) = operationMutex.withLock {
        val gatt = connectedGatt ?: throw Exception("Not connected")
        val char = findCharacteristic(gatt, uuid) ?: throw Exception("Characteristic $uuid not found in any service")

        withTimeout(5000) {
            suspendCancellableCoroutine<Unit> { cont ->
                currentOperationType = BleOperationType.READ_CHARACTERISTIC
                @Suppress("UNCHECKED_CAST")
                activeContinuation = cont as CancellableContinuation<Any?>
                if (!gatt.readCharacteristic(char)) {
                    cont.resumeWithException(Exception("Failed to initiate read"))
                }
            }
        }
    }

    private suspend fun writeCharacteristicSuspend(uuid: UUID, value: ByteArray) = operationMutex.withLock {
        val gatt = connectedGatt ?: throw Exception("Not connected")
        val char = findCharacteristic(gatt, uuid) ?: throw Exception("Characteristic $uuid not found in any service")

        char.value = value
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        withTimeout(5000) {
            suspendCancellableCoroutine<Unit> { cont ->
                currentOperationType = BleOperationType.WRITE_CHARACTERISTIC
                @Suppress("UNCHECKED_CAST")
                activeContinuation = cont as CancellableContinuation<Any?>
                if (!gatt.writeCharacteristic(char)) {
                    cont.resumeWithException(Exception("Failed to initiate write to $uuid"))
                }
            }
        }
    }

    private suspend fun enableNotificationSuspend(uuid: UUID) = operationMutex.withLock {
        val gatt = connectedGatt ?: throw Exception("Not connected")
        val char = findCharacteristic(gatt, uuid) ?: throw Exception("Characteristic $uuid not found in any service")

        gatt.setCharacteristicNotification(char, true)

        val cccUuid = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        val descriptor = char.getDescriptor(cccUuid) ?: throw Exception("CCCD Descriptor not found for $uuid")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        withTimeout(5000) {
            suspendCancellableCoroutine<Unit> { cont ->
                currentOperationType = BleOperationType.WRITE_DESCRIPTOR
                @Suppress("UNCHECKED_CAST")
                activeContinuation = cont as CancellableContinuation<Any?>
                if (!gatt.writeDescriptor(descriptor)) {
                    cont.resumeWithException(Exception("Failed to initiate descriptor write"))
                }
            }
        }
    }

    private fun findCharacteristic(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
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

        @Volatile
        private var INSTANCE: GoProManager? = null

        fun getInstance(context: Context): GoProManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GoProManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}