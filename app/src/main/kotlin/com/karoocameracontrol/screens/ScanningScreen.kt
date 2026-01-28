package com.karoocameracontrol.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R
import com.karoocameracontrol.integrations.gopro.GoProConnectionState
import com.karoocameracontrol.integrations.gopro.PairedDevice
import com.karoocameracontrol.integrations.gopro.ScannedDevice

@Composable
fun ScanningScreen(
    connectionState: GoProConnectionState,
    scannedDevices: List<ScannedDevice>,
    pairedDevices: List<PairedDevice>,
    permissionsGranted: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (ScannedDevice) -> Unit,
    onFinish: () -> Unit,
) {
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted &&
            connectionState !is GoProConnectionState.Scanning &&
            connectionState !is GoProConnectionState.Connecting &&
            connectionState !is GoProConnectionState.Connected
        ) {
            onStartScan()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onStopScan()
        }
    }

    val context = LocalContext.current

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

            if (connectionState is GoProConnectionState.Connecting) {
                Text(text = "Connecting to ${(connectionState as GoProConnectionState.Connecting).deviceName ?: "..."}...")
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            } else if (connectionState is GoProConnectionState.Scanning) {
                Text("Scanning...", modifier = Modifier.padding(top = 8.dp))
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            } else if (connectionState is GoProConnectionState.Error) {
                Text(text = "Error: ${(connectionState as GoProConnectionState.Error).message}", color = MaterialTheme.colorScheme.error)
            } else if (connectionState is GoProConnectionState.BluetoothDisabled) {
                Text(text = "Bluetooth is disabled.", color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBtIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(enableBtIntent)
                    } catch (e: Exception) {
                        // If system prevents this, we can't do much
                    }
                }) {
                    Text("Turn On Bluetooth")
                }
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

        val isScanningOrConnecting = connectionState is GoProConnectionState.Scanning || connectionState is GoProConnectionState.Connecting
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
