package com.karoocameracontrol.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.integrations.gopro.PairedDevice
import kotlinx.coroutines.delay

private const val SCAN_HELP_DELAY_MS = 30_000L

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
    val availableDevices = scannedDevices.filter { device -> pairedDevices.none { it.address == device.address } }
    var showScanHelp by remember { mutableStateOf(false) }

    LaunchedEffect(connectionState, availableDevices.isEmpty()) {
        if (connectionState is ConnectionState.Scanning) {
            showScanHelp = false
            if (availableDevices.isEmpty()) {
                delay(SCAN_HELP_DELAY_MS)
                showScanHelp = true
            }
        } else if (connectionState !is ConnectionState.Disconnected) {
            showScanHelp = false
        }
    }

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
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 74.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!permissionsGranted) {
                        Text(
                            text = "Bluetooth/Location permissions not granted.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }

                    if (connectionState is ConnectionState.Scanning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.camera_scan_scanning), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (connectionState is ConnectionState.Disconnected && permissionsGranted) {
                        Text(stringResource(R.string.camera_scan_finished), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            text = "${connectionState::class.simpleName}",
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }

                    if (connectionState is ConnectionState.Connecting) {
                        Text(text = "Connecting to ${(connectionState as ConnectionState.Connecting).deviceName ?: "..."}...")
                        CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                    } else if (connectionState is ConnectionState.Pairing) {
                        Text("Confirming Bluetooth pairing...", modifier = Modifier.padding(top = 8.dp))
                        CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                    } else if (connectionState is ConnectionState.PairingAccessRequired) {
                        Text(
                            text = "One-time setup before first pairing",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            text = "Before first pairing, allow Karoo Camera Control to access pairing notifications.",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Button(
                            onClick = onOpenNotificationAccess,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Open settings")
                        }
                        Text(
                            text = "On the next screen, turn on Karoo Camera Control. Return here, then tap Connect.",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else if (connectionState is ConnectionState.Error) {
                        Text(text = "Error: ${(connectionState as ConnectionState.Error).message}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (permissionsGranted && availableDevices.isEmpty() &&
                (connectionState is ConnectionState.Scanning || connectionState is ConnectionState.Disconnected)
            ) {
                item {
                    Text(
                        text = stringResource(if (showScanHelp) R.string.camera_scan_help_heading else R.string.camera_scan_pairing_heading),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        text = stringResource(if (showScanHelp) R.string.camera_scan_help_instructions else R.string.camera_scan_pairing_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (availableDevices.isNotEmpty()) {
                item {
                    Text(
                        text = "Discovered Devices:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
                items(availableDevices) { device ->
                    DeviceItem(device = device) {
                        onConnect(device)
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
