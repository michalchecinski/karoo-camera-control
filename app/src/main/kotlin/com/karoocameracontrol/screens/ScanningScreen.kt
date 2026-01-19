package com.karoocameracontrol.screens

import android.bluetooth.BluetoothDevice
import android.annotation.SuppressLint
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R
import com.karoocameracontrol.components.SimpleAlertDialog
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.PairedDevice

import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun ScanningScreen(
    connectionState: ConnectionState,
    scannedDevices: List<BluetoothDevice>,
    pairedDevices: List<PairedDevice>,
    permissionsGranted: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onConnectToPaired: (String) -> Unit,
    onRemovePaired: (String) -> Unit,
    onFinish: () -> Unit
) {
    var deviceToForget by remember { mutableStateOf<PairedDevice?>(null) }
    LaunchedEffect(permissionsGranted, pairedDevices) {
        // Auto-scan only if permissions granted and NO paired devices
        // and not already active
        if (permissionsGranted && pairedDevices.isEmpty() &&
            connectionState !is ConnectionState.Scanning &&
            connectionState !is ConnectionState.Connecting &&
            connectionState !is ConnectionState.Connected) {
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
            if (!permissionsGranted) {
                Text(
                    text = "Bluetooth/Location permissions not granted.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Text(
                text = "State: ${connectionState::class.simpleName}",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (connectionState is ConnectionState.Connecting) {
                Text(text = "Connecting to ${(connectionState as ConnectionState.Connecting).deviceName ?: "..."}...")
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            } else if (connectionState is ConnectionState.Error) {
                Text(text = "Error: ${(connectionState as ConnectionState.Error).message}", color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Scan Button
            Button(
                onClick = {
                    if (permissionsGranted) {
                        if (connectionState == ConnectionState.Scanning) {
                            onStopScan()
                        } else if (connectionState is ConnectionState.Connecting) {
                            onStopScan()
                        } else {
                            onStartScan()
                        }
                    }
                },
                // Allow clicking if Scanning OR Connecting (to cancel)
                enabled = permissionsGranted
            ) {
                Text(text = if (connectionState == ConnectionState.Scanning || connectionState is ConnectionState.Connecting) "Stop" else "Scan for new devices")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Device Lists
            LazyColumn(modifier = Modifier.fillMaxWidth()) {

                // Paired Devices Section
                if (pairedDevices.isNotEmpty()) {
                    item {
                        Text(
                            text = "Paired Devices:",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(pairedDevices) { device ->
                        PairedDeviceItem(
                            device = device,
                            onConnectClick = { onConnectToPaired(device.address) },
                            onForgetClick = { deviceToForget = device }
                        )
                    }
                }

                // Scanned Devices Section
                if (scannedDevices.isNotEmpty()) {
                    item {
                         Text(
                            text = "Discovered Devices:",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(scannedDevices) { device ->
                        // Filter out already paired devices from scanned list?
                        // Optional: prevents duplicates.
                        val isPaired = pairedDevices.any { it.address == device.address }
                        if (!isPaired) {
                            DeviceItem(device = device) {
                                onConnect(device)
                            }
                        }
                    }
                } else if (connectionState == ConnectionState.Scanning) {
                     item {
                        Text("Scanning...", modifier = Modifier.padding(top = 16.dp))
                     }
                }
            }
        }

        Image(
            painter = painterResource(id = R.drawable.back),
            contentDescription = "Back",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 10.dp)
                .size(54.dp)
                .clickable {
                    onFinish()
                }
        )
    }

    deviceToForget?.let { device ->
        SimpleAlertDialog(
            dialogTitle = "Confirm Unpair",
            dialogSubTitle = "Are you sure you want to unpair and forget ${device.name} (${device.address})?",
            onDismissRequest = { deviceToForget = null },
            onConfirmation = {
                onRemovePaired(device.address)
                deviceToForget = null
            }
        )
    }
}

@Composable
fun PairedDeviceItem(
    device: PairedDevice,
    onConnectClick: () -> Unit,
    onForgetClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onConnectClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = device.name, style = MaterialTheme.typography.titleSmall)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
        }
        Row {
             Button(onClick = { onConnectClick() }, modifier = Modifier.padding(end = 8.dp)) {
                Text("Connect")
            }
            IconButton(onClick = { onForgetClick() }) {
                Icon(Icons.Default.Delete, contentDescription = "Forget")
            }
        }
    }
}

@SuppressLint("MissingPermission")
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
        Column(modifier = Modifier.weight(1f)) {
            Text(text = device.name ?: "Unknown Device", style = MaterialTheme.typography.titleSmall)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { onConnectClick(device) }) {
            Text("Connect")
        }
    }
}