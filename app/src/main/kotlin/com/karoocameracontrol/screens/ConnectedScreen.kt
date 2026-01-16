package com.karoocameracontrol.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds

@Composable
fun ConnectedScreen(
    deviceName: String?,
    isRecording: Boolean,
    isProcessing: Boolean,
    recordingDuration: Int,
    batteryLevel: Int,
    remainingSpace: Long, // Changed from remainingTime: Int
    onToggleRecording: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
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
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Text(
                    text = "Battery: $batteryLevel%",
                    style = MaterialTheme.typography.bodyLarge
                )
                
                // Format KB to GB or MB
                val spaceText = if (remainingSpace > 1024 * 1024) {
                    "%.1f GB".format(remainingSpace / (1024f * 1024f))
                } else if (remainingSpace > 1024) {
                    "%.1f MB".format(remainingSpace / 1024f)
                } else {
                    "$remainingSpace KB"
                }
                
                Text(
                    text = "Storage: $spaceText",
                    style = MaterialTheme.typography.bodyLarge
                )
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
                modifier = Modifier.padding(bottom = 16.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    disabledContainerColor = if (isRecording) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(text = if (isRecording) "Stop Recording" else "Start Recording")
                }
            }

            Button(onClick = onDisconnect) {
                Text(text = "Disconnect")
            }
            
            Button(
                onClick = onForget,
                modifier = Modifier.padding(top = 16.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(text = "Unpair / Forget")
            }
        }
    }
}
