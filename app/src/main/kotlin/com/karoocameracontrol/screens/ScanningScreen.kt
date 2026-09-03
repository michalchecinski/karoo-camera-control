package com.karoocameracontrol.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.PairedDevice

@Composable
fun ScanningScreen(
    connectionState: ConnectionState,
    scannedDevices: List<BluetoothDevice>,
    pairedDevices: List<PairedDevice>,
    permissionsGranted: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onFinish: () -> Unit,
) {
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted &&
            connectionState !is ConnectionState.Scanning &&
            connectionState !is ConnectionState.Connecting &&
            connectionState !is ConnectionState.Connected
        ) {
            onStartScan()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onStopScan()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!permissionsGranted) {
                Text(
                    text = "Bluetooth/Location permissions not granted.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            Text(
                text = "${connectionState::class.simpleName}",
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (connectionState is ConnectionState.Connecting) {
                Text(text = "Connecting to ${(connectionState as ConnectionState.Connecting).deviceName ?: "..."}...")
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            } else if (connectionState is ConnectionState.Scanning) {
                Text("Scanning...", modifier = Modifier.padding(top = 8.dp))
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            } else if (connectionState is ConnectionState.Pairing) {
                Text("Confirming Bluetooth pairing...", modifier = Modifier.padding(top = 8.dp))
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            } else if (connectionState is ConnectionState.PairingAccessRequired) {
                Text(
                    text = "Notification access is required to pair this camera.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = onOpenNotificationAccess,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Open notification access")
                }
            } else if (connectionState is ConnectionState.Error) {
                Text(text = "Error: ${(connectionState as ConnectionState.Error).message}", color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Scanned Devices List
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (scannedDevices.isNotEmpty()) {
                    item {
                        Text(
                            text = "Discovered Devices:",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(scannedDevices) { device ->
                        // Filter out already paired devices from scanned list
                        val isPaired = pairedDevices.any { it.address == device.address }
                        if (!isPaired) {
                            DeviceItem(device = device) {
                                onConnect(device)
                            }
                        }
                    }
                }
            }
        }

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

        val isScanningOrConnecting = connectionState is ConnectionState.Scanning || connectionState is ConnectionState.Connecting
        val buttonAlpha = if (isScanningOrConnecting) 0.3f else 1.0f
        val buttonClickable = !isScanningOrConnecting

        Image(
            painter = painterResource(id = R.drawable.refresh),
            contentDescription = "Retry scanning",
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 10.dp)
                    .size(54.dp)
                    .alpha(buttonAlpha)
                    .clickable(enabled = buttonClickable) { onStartScan() },
        )
    }
}
