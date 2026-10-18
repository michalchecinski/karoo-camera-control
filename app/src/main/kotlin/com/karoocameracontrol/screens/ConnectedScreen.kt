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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karoocameracontrol.R

@Composable
fun ConnectedScreen(
    deviceName: String?,
    isRecording: Boolean,
    isProcessing: Boolean,
    isRecordingOperationInFlight: Boolean,
    recordingDuration: Int,
    batteryLevel: Int,
    remainingTime: Int,
    cameraMode: Int,
    activePresetName: String?,
    onToggleRecording: () -> Unit,
    onOpenModeSelection: () -> Unit,
    onOpenPresetSelection: () -> Unit,
    onFinish: () -> Unit,
    recordingTarget: Boolean? = null,
    commandError: String? = null,
    presetsAvailable: Boolean = true,
    radarStatus: String = "Off",
    descentStatus: String = "Off",
    cameraStateKnown: Boolean = true,
) {
    val pending = isRecordingOperationInFlight
    val stopAvailable = isRecording || pending
    val status =
        when {
            commandError != null -> stringResource(R.string.remote_failed)
            pending && recordingTarget == false -> stringResource(R.string.remote_stopping)
            pending -> stringResource(R.string.remote_starting)
            isProcessing -> stringResource(R.string.remote_busy)
            !cameraStateKnown -> stringResource(R.string.remote_waiting)
            isRecording -> stringResource(R.string.remote_recording)
            else -> stringResource(R.string.remote_ready)
        }
    val mode = cameraModeLabel(cameraMode)
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.remote_battery, batteryLevel), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.remote_storage, "%dh %02dm".format(remainingTime.coerceAtLeast(0) / 3600, remainingTime.coerceAtLeast(0) % 3600 / 60)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(
                Modifier.fillMaxWidth().height(70.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    status,
                    style = if ((isRecording && !pending) || !cameraStateKnown) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.headlineSmall,
                    color = if (stopAvailable || commandError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                if (isRecording && !pending && cameraStateKnown) {
                    Text(
                        recordingFieldTime(recordingDuration),
                        fontSize = 40.sp,
                        lineHeight = 44.sp,
                    )
                }
                if (!isRecording || pending || commandError != null) {
                    Text(
                        commandError ?: if (pending) stringResource(R.string.remote_confirming) else stringResource(R.string.remote_connected, deviceName ?: "GoPro"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CaptureChoice(
                    label = stringResource(R.string.remote_mode),
                    value = mode,
                    enabled = cameraStateKnown && !isProcessing && !isRecording,
                    onClick = onOpenModeSelection,
                    modifier = Modifier.weight(1f),
                )
                CaptureChoice(
                    label = stringResource(R.string.remote_preset),
                    value = activePresetName ?: stringResource(R.string.remote_unavailable),
                    enabled = cameraStateKnown && !isProcessing && !isRecording && presetsAvailable,
                    onClick = onOpenPresetSelection,
                    modifier = Modifier.weight(1f),
                )
            }
            AutomationStatusRow(stringResource(R.string.remote_radar), radarStatus)
            AutomationStatusRow(stringResource(R.string.remote_descent), descentStatus)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(
                painterResource(R.drawable.back),
                contentDescription = stringResource(R.string.camera_alert_back),
                modifier = Modifier.size(54.dp).clickable(role = Role.Button, onClick = onFinish),
            )
            Button(
                onClick = onToggleRecording,
                enabled = (cameraStateKnown && !isProcessing) || stopAvailable,
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (stopAvailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            ) {
                Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.onPrimary, if (stopAvailable) RectangleShape else CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (stopAvailable) R.string.remote_stop else R.string.remote_record), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun CaptureChoice(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp).semantics { contentDescription = label },
        shape = RoundedCornerShape(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›")
    }
}

@Composable
private fun AutomationStatusRow(
    label: String,
    status: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.25f))
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.75f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

internal fun recordingFieldTime(seconds: Int): String =
    if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    } else {
        "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)
    }
