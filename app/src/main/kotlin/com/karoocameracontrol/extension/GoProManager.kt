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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    private val _savedDeviceNameFlow = MutableStateFlow<String?>(null)
    val savedDeviceNameFlow: StateFlow<String?> = _savedDeviceNameFlow.asStateFlow()

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

    private var scanning = false
    
    private val prefs by lazy {
        context.getSharedPreferences("GoProPrefs", Context.MODE_PRIVATE)
    }
    
    private var savedDeviceAddress: String?
        get() = prefs.getString("saved_device_address", null)
        set(value) = prefs.edit().putString("saved_device_address", value).apply()
        
    private var savedDeviceName: String?
        get() = prefs.getString("saved_device_name", null)
        set(value) {
            prefs.edit().putString("saved_device_name", value).apply()
            _savedDeviceNameFlow.value = value // Update the StateFlow
        }
    
    init {
        // Initialize the StateFlow with the current saved device name from preferences
        _savedDeviceNameFlow.value = prefs.getString("saved_device_name", null)
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
            
            // Auto-connect if matches saved device
            val savedAddress = savedDeviceAddress
            if (savedAddress != null && device.address == savedAddress) {
                Log.d(TAG, "Found known device ${device.name}, auto-connecting.")
                if (_connectionState.value !is ConnectionState.Connecting && _connectionState.value !is ConnectionState.Connected) {
                     connect(device)
                }
                return
            }
            
            if (!_scannedDevices.value.contains(device)) {
                _scannedDevices.value = _scannedDevices.value + device
                Log.d(TAG, "Found GoPro: ${device.name} (${device.address})")
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            for (result in results) {
                val device = result.device
                val savedAddress = savedDeviceAddress
                
                if (savedAddress != null && device.address == savedAddress) {
                     Log.d(TAG, "Found known device (batch) ${device.name}, auto-connecting.")
                     if (_connectionState.value !is ConnectionState.Connecting && _connectionState.value !is ConnectionState.Connected) {
                         connect(device)
                     }
                     return
                }
                
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
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "onConnectionStateChange: Connected to $deviceName")
                    if (currentOperationType == BleOperationType.CONNECT) {
                        resumeActiveContinuation(Unit)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "onConnectionStateChange: Disconnected from $deviceName")
                    connectedGatt?.close()
                    connectedGatt = null
                    _connectionState.value = ConnectionState.Disconnected
                    _isRecording.value = false
                    _recordingDuration.value = 0
                    // If we were waiting for something else, this is an error
                    if (currentOperationType != BleOperationType.NONE) {
                        resumeActiveContinuationWithException(Exception("Disconnected unexpectedly"))
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
                    logGattTable(gatt) // Debugging: Print all found services/chars
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
            val hexValue = value.joinToString(" ") { "%02x".format(it) }
            Log.d(TAG, "Notification on ${characteristic.uuid}: $hexValue")
            
            if (characteristic.uuid == GoProUUID.QUERY_RESPONSE || characteristic.uuid == GoProUUID.COMMAND_RESPONSE) {
                // Parser for Status ID 10 (Encoding) - 0x0A, 0x01 (Boolean), Value
                for (i in 0 until value.size - 2) {
                    if (value[i] == 0x0A.toByte() && value[i+1] == 0x01.toByte()) {
                        val isRecording = value[i+2] == 0x01.toByte()
                        Log.d(TAG, "Parsed Recording State: $isRecording")
                        _isRecording.value = isRecording
                        if (!isRecording) _recordingDuration.value = 0
                    }
                }
                
                // Generic Parser for 4-byte Integers (Type 0x03 or 0x04)
                // We look for ID 13 (Duration) and ID 54 (Remaining Time)
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
                                Log.d(TAG, "Parsed Recording Duration: $duration seconds")
                                _recordingDuration.value = duration
                            }
                        }
                    }
                    
                    // ID 54 (0x36): Remaining Space (KB likely) - Type 0x08 (Long)
                    if (id == 0x36.toByte()) {
                         if (i + 1 < value.size) {
                            val typeByte = value[i+1]
                            // Type 0x08 is 64-bit integer
                            if (typeByte == 0x08.toByte() && i + 9 < value.size) {
                                val valBytes = value.sliceArray((i+2)..(i+9))
                                var remSpace: Long = 0
                                for (b in valBytes) {
                                    remSpace = (remSpace shl 8) or (b.toLong() and 0xFF)
                                }
                                Log.d(TAG, "Parsed Remaining Space: $remSpace KB")
                                _remainingSpace.value = remSpace
                            } else if ((typeByte == 0x03.toByte() || typeByte == 0x04.toByte()) && i + 5 < value.size) {
                                // Fallback for 4-byte int if it occurs
                                val valBytes = value.sliceArray((i+2)..(i+5))
                                val remSpace = (valBytes[0].toInt() and 0xFF shl 24) or 
                                              (valBytes[1].toInt() and 0xFF shl 16) or 
                                              (valBytes[2].toInt() and 0xFF shl 8) or 
                                              (valBytes[3].toInt() and 0xFF)
                                Log.d(TAG, "Parsed Remaining Space (Int): $remSpace KB")
                                _remainingSpace.value = remSpace.toLong()
                            }
                        }
                    }
                    
                    // ID 35 (0x23): Remaining Video Time (Seconds) - Type 0x04 (Int) or 0x03 (Short)
                    if (id == 0x23.toByte()) {
                         if (i + 1 < value.size) {
                            val typeByte = value[i+1]
                            if ((typeByte == 0x03.toByte() || typeByte == 0x04.toByte()) && i + 5 < value.size) {
                                val valBytes = value.sliceArray((i+2)..(i+5))
                                val remTime = (valBytes[0].toInt() and 0xFF shl 24) or 
                                              (valBytes[1].toInt() and 0xFF shl 16) or 
                                              (valBytes[2].toInt() and 0xFF shl 8) or 
                                              (valBytes[3].toInt() and 0xFF)
                                Log.d(TAG, "Parsed Remaining Video Time: $remTime seconds")
                                _remainingVideoTime.value = remTime
                            }
                        }
                    }

                    // ID 70 (0x46): Battery Level - Type 0x01 or 0x02
                    if (id == 0x46.toByte()) {
                         if (i + 1 < value.size) {
                             val typeByte = value[i+1]
                             // Accept Type 0x01 (Byte) or 0x02 (Short/Byte)
                             if ((typeByte == 0x01.toByte() || typeByte == 0x02.toByte()) && i + 2 < value.size) {
                                 val batLevel = value[i+2].toInt() and 0xFF
                                 Log.d(TAG, "Parsed Battery Level: $batLevel")
                                 _batteryLevel.value = batLevel
                             }
                         }
                    }
                }
            }
        }
    }
    
    private fun logGattTable(gatt: BluetoothGatt) {
        Log.d(TAG, "--- GATT Table ---")
        gatt.services.forEach { service ->
            Log.d(TAG, "Service: ${service.uuid}")
            service.characteristics.forEach { char ->
                Log.d(TAG, "  |-- Char: ${char.uuid}")
            }
        }
        Log.d(TAG, "------------------")
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

    fun connectToSavedDevice() {
        val address = savedDeviceAddress ?: return
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        connect(device)
    }
    
    suspend fun startRecording() {
        // Command: Shutter On (03 01 01 01)
        // Note: Length 3, Cmd 01, Params 01 01. Works for user.
        val cmd = byteArrayOf(0x03, 0x01, 0x01, 0x01)
        writeCharacteristicSuspend(GoProUUID.COMMAND, cmd)
        
        // Polling sequence to ensure we catch the "Recording" state.
        // We poll up to 10 times (every 500ms). If state becomes true, we stop polling.
        for (i in 1..10) {
            kotlinx.coroutines.delay(500)
            if (_isRecording.value) {
                Log.d(TAG, "Recording start detected early, stopping poll.")
                break
            }
            pollRecordingState()
        }
    }

    suspend fun stopRecording() {
        // Command: Shutter Off (03 01 01 00)
        val cmd = byteArrayOf(0x03, 0x01, 0x01, 0x00)
        writeCharacteristicSuspend(GoProUUID.COMMAND, cmd)
        
        // Poll sequence: GoPro takes time to save the file (Busy state).
        // We poll up to 5 times (5 seconds). If state becomes false, we stop polling.
        for (i in 1..5) {
            kotlinx.coroutines.delay(1000)
            if (!_isRecording.value) {
                Log.d(TAG, "Recording stopped detected early, stopping poll.")
                break
            }
            pollRecordingState()
        }
    }
    
    private suspend fun pollRecordingState() {
        try {
             // Cmd 0x13 (Get Status), Param 0x0A (Encoding/IsRecording)
            val statusCmd = byteArrayOf(0x02, 0x13, 0x0A)
            writeCharacteristicSuspend(GoProUUID.QUERY, statusCmd)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll recording state", e)
        }
    }

    private suspend fun pollInitialStatus() {
        try {
            // Cmd 0x13 (Get Status), Param 0x0A (Encoding/IsRecording)
            val statusCmd = byteArrayOf(0x02, 0x13, 0x0A)
            writeCharacteristicSuspend(GoProUUID.QUERY, statusCmd)
            
            // Cmd 0x13 (Get Status), Param 0x0D (Video Progress/Duration)
            if (_isRecording.value) {
                val durationCmd = byteArrayOf(0x02, 0x13, 0x0D)
                writeCharacteristicSuspend(GoProUUID.QUERY, durationCmd)
            }
            
            // Cmd 0x13 (Get Status), Param 0x46 (Battery Level)
            val batteryCmd = byteArrayOf(0x02, 0x13, 0x46)
            writeCharacteristicSuspend(GoProUUID.QUERY, batteryCmd)
            
            // Cmd 0x13 (Get Status), Param 0x36 (Remaining Space)
            val remSpaceCmd = byteArrayOf(0x02, 0x13, 0x36)
            writeCharacteristicSuspend(GoProUUID.QUERY, remSpaceCmd)

            // Cmd 0x13 (Get Status), Param 0x23 (Remaining Video Time)
            val remTimeCmd = byteArrayOf(0x02, 0x13, 0x23)
            writeCharacteristicSuspend(GoProUUID.QUERY, remTimeCmd)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll status", e)
        }
    }

    private suspend fun pollDuration() {
        try {
            // Cmd 0x13 (Get Status), Param 0x0D (Video Progress/Duration)
            val durationCmd = byteArrayOf(0x02, 0x13, 0x0D)
            writeCharacteristicSuspend(GoProUUID.QUERY, durationCmd)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll duration: ${e.message}")
        }
    }

    fun connect(device: BluetoothDevice) {
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
            _connectionState.value = ConnectionState.Connecting(device.name ?: savedDeviceName)
            
            while (isActive) {
                try {
                    // 1. Connect
                    val isReconnect = device.address == savedDeviceAddress
                    // If it is a saved device, we use autoConnect=true which waits indefinitely.
                    // If it is a new device, we use autoConnect=false with timeout.
                    connectGattSuspend(device, autoConnect = isReconnect)
                    
                    // 2. Discover Services
                    discoverServicesSuspend()
                    
                    // 3. Handshake: Read Password (Pairs device)
                    readCharacteristicSuspend(GoProUUID.WIFI_AP_PASSWORD)
                    
                    // 4. Handshake: Enable Notifications on RESPONSE characteristics
                    enableNotificationSuspend(GoProUUID.COMMAND_RESPONSE)
                    enableNotificationSuspend(GoProUUID.SETTING_RESPONSE)
                    enableNotificationSuspend(GoProUUID.QUERY_RESPONSE)
                    
                    // 5. Register for Status Updates (ID 10 - Encoding, ID 13 - Duration)
                    registerForStatusUpdatesSuspend()
                    
                    // 6. Get Initial Status (Encoding, Battery, Storage)
                    pollInitialStatus()
                    
                    savedDeviceAddress = device.address
                    savedDeviceName = device.name ?: device.address // Fallback if name is missing
                    _connectionState.value = ConnectionState.Connected(savedDeviceName)
                    Log.d(TAG, "GoPro Connection Fully Established!")
                    
                    // Launch a separate coroutine to manage duration polling based on recording state
                    launch {
                        _isRecording.collectLatest { recording ->
                            if (recording) {
                                Log.d(TAG, "Recording active, starting duration poll loop.")
                                while (isActive) {
                                    pollDuration()
                                    kotlinx.coroutines.delay(500)
                                }
                            } else {
                                Log.d(TAG, "Recording inactive, stopping duration poll loop.")
                            }
                        }
                    }
                    
                    // Exit the retry loop on success
                    break
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Connection failed", e)
                    disconnect() // Close GATT to reset state
                    
                    // Check if we should retry
                    if (device.address == savedDeviceAddress) {
                        Log.d(TAG, "Retrying connection for saved device in 2 seconds...")
                        // Update state to reflect we are still trying to connect
                        _connectionState.value = ConnectionState.Connecting(device.name ?: savedDeviceName)
                        kotlinx.coroutines.delay(2000)
                        // Loop continues
                    } else {
                        // For non-saved devices, report error and stop
                        _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
                        break
                    }
                }
            }
        }
    }
    
    private suspend fun registerForStatusUpdatesSuspend() {
        try {
            // Command 0x53 (Register), ID 0x0A (Encoding), ID 0x0D (Duration), ID 0x46 (Battery), ID 0x36 (Space), ID 0x23 (Rem Time)
            // Length: 1 (Cmd 0x53) + 5 (IDs) = 6 bytes.
            val cmd = byteArrayOf(0x06, 0x53, 0x0A, 0x0D, 0x46, 0x36, 0x23)
            writeCharacteristicSuspend(GoProUUID.COMMAND, cmd)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register for status updates: ${e.message}")
            // Don't fail the connection for this, but log it
        }
    }

    fun disconnect() {
        if (!hasPermissions()) return
        connectedGatt?.disconnect()
        connectedGatt?.close()
        connectedGatt = null
        _connectionState.value = ConnectionState.Disconnected
        _isRecording.value = false
        _recordingDuration.value = 0
    }
    
    fun forgetPairedDevice() {
        savedDeviceAddress = null
        savedDeviceName = null
        disconnect()
    }

    // Suspend Functions

    private suspend fun connectGattSuspend(device: BluetoothDevice, autoConnect: Boolean) = operationMutex.withLock {
        // If autoConnect is true, we wait indefinitely (no timeout) for the device to appear
        if (autoConnect) {
            suspendCancellableCoroutine<Unit> { cont ->
                currentOperationType = BleOperationType.CONNECT
                @Suppress("UNCHECKED_CAST")
                activeContinuation = cont as CancellableContinuation<Any?>
                connectedGatt = device.connectGatt(context, true, gattCallback)
            }
        } else {
            // For direct connection, we use a timeout
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
        // Robustness: Search all services for the characteristic
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
        
        // Write Type: Default is WRITE, but some use WRITE_NO_RESPONSE. GoPro usually expects responses for Commands?
        // Actually COMMAND char usually supports WRITE or WRITE_NO_RESPONSE.
        // If we want onCharacteristicWrite callback, we usually use WRITE (default).
        // Let's set value and write.
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
        // Robustness: Search all services
        val char = findCharacteristic(gatt, uuid) ?: throw Exception("Characteristic $uuid not found in any service")
        
        // 1. Enable locally
        gatt.setCharacteristicNotification(char, true)
        
        // 2. Write Descriptor remotely
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
