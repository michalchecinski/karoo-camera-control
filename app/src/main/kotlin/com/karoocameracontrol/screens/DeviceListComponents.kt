package com.karoocameracontrol.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.integrations.gopro.PairedDevice

@Composable
fun PairedDeviceItem(
    device: PairedDevice,
    onConnectClick: () -> Unit,
    onForgetClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable { onConnectClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
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
fun DeviceItem(
    device: BluetoothDevice,
    onConnectClick: (BluetoothDevice) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable { onConnectClick(device) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
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
