package com.karoocameracontrol.integrations.ble

import android.content.Context
import android.util.Log
import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.client.distinctByPeripheral
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)
class BleManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val centralManager by lazy {
        CentralManager.Factory.native(context, scope)
    }

    private val connectionOptions by lazy {
        CentralManager.ConnectionOptions.Direct(timeout = 120.seconds, retry = 10, retryDelay = 12.seconds)
    }

    fun scan(services: List<Uuid>): Flow<Pair<String, String?>> {
        return centralManager
            .scan() // Empty scan to start with, filtering can be added later or filtered in flow
            .distinctByPeripheral()
            .map { Pair(it.peripheral.address, it.peripheral.name) }
            .catch {
                Log.w(TAG, "Error in BLE scan", it)
            }
    }

    fun connect(address: String): Flow<Peripheral?> {
        return flow {
            // Start by scanning until the device is seen and connectable
            val peripheral = centralManager.scan()
                .filter { it.peripheral.address == address } // Manual filtering by address
                .filter { it.isConnectable }
                .map { it.peripheral }
                .first()
            
            try {
                // Connect to the device (including retries)
                repeat(10) { i ->
                    if (i == 0) {
                        Log.i(TAG, "Found $peripheral, connecting...")
                    } else {
                        delay(3.seconds)
                        Log.i(TAG, "Reconnecting to $peripheral...")
                    }
                    centralManager.connect(peripheral, connectionOptions)
                    Log.i(TAG, "Connected to $peripheral")
                    // Emit the peripheral to the caller so they can setup observers
                    emit(peripheral)
                    // Block while connected
                    peripheral.state
                        .filter { it is ConnectionState.Disconnected }
                        .first()
                    Log.i(TAG, "Device disconnected from $peripheral, state is ${peripheral.state.value}")
                    // Emit null to the caller so they know it disconnected before re-entering this loop to try to connect again
                    emit(null)
                }
            } finally {
                Log.i(TAG, "Disconnecting from $peripheral")
                peripheral.disconnect()
                Log.i(TAG, "Disconnected from $peripheral")
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class, ExperimentalUuidApi::class)
    fun <T> observeCharacteristic(peripheral: Peripheral, service: Uuid, characteristic: Uuid, parser: (ByteArray) -> T): Flow<T> {
        return peripheral.services(listOf(service))
            .flatMapLatest {
                Log.d(TAG, "Services changed: $it")
                it.firstOrNull()?.characteristics?.firstOrNull { it.uuid == characteristic }?.let { characteristic ->
                    characteristic.subscribe()
                        .map { data ->
                            val value = parser(data)
                            Log.d(TAG, "Data changed: 0x${data.toHexString()} -> $value")
                            value
                        }
                        .onCompletion {
                            Log.d(TAG, "Stopped observing $service:$characteristic")
                        }
                } ?: run {
                    Log.w(TAG, "Characteristic $service:$characteristic not found")
                    emptyFlow()
                }
            }
            .catch {
                Log.w(TAG, "Characteristic $service:$characteristic error: ${it.message}")
            }
    }

    suspend fun <T> readCharacteristic(peripheral: Peripheral, service: Uuid, characteristic: Uuid, parser: (ByteArray) -> T): T? {
        // Wait for service to be discovered then read the characteristic
        return peripheral.services(listOf(service)).mapNotNull { it.firstOrNull() }.firstOrNull()?.let { service ->
            service.characteristics.firstOrNull { it.uuid == characteristic }?.let { characteristic ->
                parser(characteristic.read())
            }
        }
    }

    suspend fun writeCharacteristic(peripheral: Peripheral, service: Uuid, characteristic: Uuid, value: ByteArray) {
        peripheral.services(listOf(service)).mapNotNull { it.firstOrNull() }.firstOrNull()?.let { service ->
            service.characteristics.firstOrNull { it.uuid == characteristic }?.let { char ->
                char.write(value, WriteType.WITH_RESPONSE)
            }
        }
    }

    companion object {
        private const val TAG = "BleManager"

        // UUIDs for the Device Information service (DIS)
        val DIS_SERVICE_UUID = Uuid.parse("0000180A-0000-1000-8000-00805f9b34fb")
        val MANUFACTURER_NAME_CHARACTERISTIC_UUID = Uuid.parse("00002A29-0000-1000-8000-00805f9b34fb")
        val SERIAL_NUMBER_CHARACTERISTIC_UUID = Uuid.parse("00002a25-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER_CHARACTERISTIC_UUID = Uuid.parse("00002a24-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Battery Service (BAS)
        val BTS_SERVICE_UUID = Uuid.parse("0000180F-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHARACTERISTIC_UUID = Uuid.parse("00002A19-0000-1000-8000-00805f9b34fb")
    }
}
