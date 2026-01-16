package com.karoocameracontrol.screens

import com.karoocameracontrol.components.SimpleAlertDialog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import com.karoocameracontrol.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds

@Composable
fun ConnectedScreen(
    deviceName: String?,
    isRecording: Boolean,
    isProcessing: Boolean,
    recordingDuration: Int,
    batteryLevel: Int,
    remainingTime: Int,
    cameraMode: Int,
    onToggleRecording: () -> Unit,
    onSetMode: (Int) -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onFinish: () -> Unit
) {
    var showForgetConfirmation by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Connected to ${deviceName ?: "Unknown Device"}",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Status Row
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(
                    text = "Battery: $batteryLevel%",
                    style = MaterialTheme.typography.bodyLarge
                )

                // Format Seconds to HH:MM or MM:SS
                val hours = remainingTime / 3600
                val minutes = (remainingTime % 3600) / 60
                val seconds = remainingTime % 60

                val timeText = if (hours > 0) {
                    "%dh %02dm".format(hours, minutes)
                } else {
                    "%02dm %02ds".format(minutes, seconds)
                }

                Text(
                    text = "Storage: $timeText",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // Mode Selection
            Row(
                modifier = Modifier.padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Normalize mode (support 0,1,2 or 1000,1001,1002)
                val current = if (cameraMode >= 1000) cameraMode - 1000 else cameraMode

                Button(
                    onClick = { onSetMode(0) }, // Video
                    enabled = !isProcessing && !isRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (current == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Video")
                }

                Button(
                    onClick = { onSetMode(1) }, // Photo
                    enabled = !isProcessing && !isRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (current == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Photo")
                }

                Button(
                    onClick = { onSetMode(2) }, // Timelapse
                    enabled = !isProcessing && !isRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (current == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Timelapse")
                }
            }

            if (isRecording) {
                val duration = recordingDuration.seconds
                val formattedDuration = duration.toComponents { _, minutes, seconds, _ ->
                    "%02d:%02d".format(minutes, seconds)
                }
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }

            Button(
                onClick = onToggleRecording,
                enabled = !isProcessing,
                modifier = Modifier
                    .width(200.dp)
                    .height(100.dp)
                    .padding(bottom = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    disabledContainerColor = if (isRecording) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    shape = if (isRecording) RectangleShape else CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = if (isRecording) "Stop" else "Record",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }

        var showMenu by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Disconnect") },
                    onClick = {
                        onDisconnect()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Unpair / Forget") },
                    onClick = {
                        showForgetConfirmation = true
                        showMenu = false
                    }
                )
            }
        }

        if (showForgetConfirmation) {
            SimpleAlertDialog(
                dialogTitle = "Confirm Unpair",
                dialogSubTitle = "Are you sure you want to unpair and forget this device?",
                onDismissRequest = { showForgetConfirmation = false },
                onConfirmation = {
                    onForget()
                    showForgetConfirmation = false
                }
            )
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
}