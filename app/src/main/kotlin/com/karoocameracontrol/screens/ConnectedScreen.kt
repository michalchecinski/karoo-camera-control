package com.karoocameracontrol.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R
import com.karoocameracontrol.components.SimpleAlertDialog
import com.karoocameracontrol.integrations.gopro.PresetGroup
import com.karoocameracontrol.integrations.gopro.PresetIcon
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
    availablePresets: List<PresetGroup>,
    activePresetName: String?,
    // Added Icon ID
    activePresetIcon: Int?,
    onToggleRecording: () -> Unit,
    onSetMode: (Int) -> Unit,
    onOpenPresetSelection: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onFinish: () -> Unit,
) {
    var showForgetConfirmation by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Status Row
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                Text(
                    text = "Battery: $batteryLevel%",
                    style = MaterialTheme.typography.bodyLarge,
                )

                val hours = remainingTime / 3600
                val minutes = (remainingTime % 3600) / 60
                val seconds = remainingTime % 60

                val timeText =
                    if (hours > 0) {
                        "%dh %02dm".format(hours, minutes)
                    } else {
                        "%02dm %02ds".format(minutes, seconds)
                    }

                Text(
                    text = "Storage: $timeText",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            // Mode Selection
            Row(
                modifier = Modifier.padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val current = if (cameraMode >= 1000) cameraMode - 1000 else cameraMode

                // Video
                Button(
                    onClick = { onSetMode(0) },
                    enabled = !isProcessing && !isRecording,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = if (current == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        ),
                ) {
                    Text("Video")
                }

                // Photo
                Button(
                    onClick = { onSetMode(1) },
                    enabled = !isProcessing && !isRecording,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = if (current == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        ),
                ) {
                    Text("Photo")
                }

                // Timelapse
                Button(
                    onClick = { onSetMode(2) },
                    enabled = !isProcessing && !isRecording,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = if (current == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        ),
                ) {
                    Text("Timelapse")
                }
            }

            activePresetName?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    activePresetIcon?.let { iconId ->
                        Icon(
                            imageVector = PresetIcon.getIcon(iconId),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp).size(24.dp),
                        )
                    }
                    Text(
                        text = "Preset: $it",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            val currentModeId = if (cameraMode < 1000) cameraMode + 1000 else cameraMode
            val currentPresets = availablePresets.find { it.id == currentModeId }?.presets ?: emptyList()

            if (currentPresets.isNotEmpty()) {
                Button(
                    onClick = onOpenPresetSelection,
                    enabled = !isProcessing && !isRecording,
                    modifier = Modifier.padding(bottom = 16.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                        ),
                ) {
                    Text("Change Preset")
                }
            }

            if (isRecording) {
                val duration = recordingDuration.seconds
                val formattedDuration =
                    duration.toComponents { _, minutes, seconds, _ ->
                        "%02d:%02d".format(minutes, seconds)
                    }
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }

            Button(
                onClick = onToggleRecording,
                // Stop must remain available if the camera has already begun
                // recording, even while start confirmation is still in progress.
                enabled = !isProcessing || isRecording,
                modifier =
                    Modifier
                        .width(200.dp)
                        .height(100.dp)
                        .padding(bottom = 16.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        disabledContainerColor =
                            if (isRecording) {
                                MaterialTheme.colorScheme.error.copy(
                                    alpha = 0.5f,
                                )
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            },
                    ),
            ) {
                if (isProcessing && !isRecording) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(24.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        shape = if (isRecording) RectangleShape else CircleShape,
                                    ),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = if (isRecording) "Stop" else "Record",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
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
                },
            )
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
    }
}
