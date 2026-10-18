package com.karoocameracontrol.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R

@Composable
fun ModeSelectionScreen(
    cameraMode: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(bottom = 64.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(stringResource(if (enabled) R.string.remote_mode_help else R.string.remote_capture_locked), style = MaterialTheme.typography.bodySmall)
            listOf(Icons.Default.Videocam, Icons.Default.CameraAlt, Icons.Default.Timelapse).forEachIndexed { mode, icon ->
                val selected = normalizedCameraMode(cameraMode) == mode
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                        .clickable(enabled = enabled, role = Role.Button) { onSelected(mode) }
                        .semantics { this.selected = selected }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp).padding(end = 4.dp))
                    Text(cameraModeLabel(mode), modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
                    if (selected) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.remote_selected), modifier = Modifier.size(20.dp))
                }
                HorizontalDivider()
            }
        }
        Image(painterResource(R.drawable.back), contentDescription = stringResource(R.string.camera_alert_back), modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 10.dp).size(54.dp).clickable(role = Role.Button, onClick = onBack))
    }
}

internal fun normalizedCameraMode(mode: Int): Int = if (mode >= 1000) mode - 1000 else mode

@Composable
internal fun cameraModeLabel(mode: Int): String =
    stringResource(
        when (normalizedCameraMode(mode)) {
            0 -> R.string.remote_video
            1 -> R.string.remote_photo
            2 -> R.string.remote_timelapse
            else -> R.string.remote_unavailable
        },
    )
