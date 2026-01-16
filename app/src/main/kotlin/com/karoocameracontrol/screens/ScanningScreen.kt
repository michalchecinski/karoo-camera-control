package com.karoocameracontrol.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R
import com.karoocameracontrol.extension.ConnectionState

import androidx.compose.runtime.LaunchedEffect

@Composable
fun ScanningScreen(
    connectionState: ConnectionState,
    scannedDevices: List<BluetoothDevice>,
    permissionsGranted: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit
) {
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && connectionState !is ConnectionState.Scanning) {
            onStartScan()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (!permissionsGranted) {
                Text(
                    text = "Bluetooth/Location permissions not granted. Please grant them in settings to use this feature.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Text(
                text = "Connection State: ${connectionState::class.simpleName}",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            when (connectionState) {
                is ConnectionState.Connecting -> {
                    val deviceName = (connectionState as ConnectionState.Connecting).deviceName
                    Text(text = "Connecting to ${deviceName ?: "Unknown device"}...")
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
                is ConnectionState.Scanning -> {
                    Text(text = "Scanning for devices...")
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
                is ConnectionState.Error -> {
                    val errorMessage = (connectionState as ConnectionState.Error).message
                    Text(text = "Error: $errorMessage", color = MaterialTheme.colorScheme.error)
                }
                else -> { /* Do nothing for Disconnected */ }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (permissionsGranted) {
                        if (connectionState == ConnectionState.Scanning) {
                            onStopScan()
                        } else {
                            onStartScan()
                        }
                    }
                },
                enabled = permissionsGranted && connectionState !is ConnectionState.Connecting
            ) {
                Text(text = if (connectionState == ConnectionState.Scanning) "Stop Scan" else "Start Scan")
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (scannedDevices.isNotEmpty()) {
                Text(
                    text = "Discovered Devices:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(scannedDevices) { device ->
                        DeviceItem(device = device) {
                            onConnect(device)
                        }
                    }
                }
            } else if (connectionState != ConnectionState.Scanning && permissionsGranted) {
                Text("No devices found. Start scan to discover devices.")
            }
        }
    }
}

@Composable
fun DeviceItem(device: BluetoothDevice, onConnectClick: (BluetoothDevice) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onConnectClick(device) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = device.name ?: "Unknown Device", style = MaterialTheme.typography.titleSmall)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { onConnectClick(device) }) {
            Text("Connect")
        }
    }
}
