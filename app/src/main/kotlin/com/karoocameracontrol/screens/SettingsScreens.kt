package com.karoocameracontrol.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R
import com.karoocameracontrol.alerts.CameraAlertSettings
import com.karoocameracontrol.integrations.gopro.ConnectionState
import com.karoocameracontrol.recording.DescentStatus

@Composable
fun SettingsHubScreen(
    radarEnabled: Boolean,
    descentEnabled: Boolean,
    alerts: CameraAlertSettings,
    connectionState: ConnectionState,
    onNavigate: (ScreenDestination) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(onBack) {
        SettingsRow(
            stringResource(R.string.settings_automatic_recording),
            stringResource(R.string.settings_automation_summary, enabledText(radarEnabled), enabledText(descentEnabled)),
            Icons.Default.Videocam,
        ) { onNavigate(ScreenDestination.AUTOMATIC_RECORDING) }
        SettingsRow(
            stringResource(R.string.settings_alerts_sound),
            stringResource(
                R.string.settings_alerts_summary,
                enabledText(alerts.confirmations),
                enabledText(alerts.warnings),
                enabledText(alerts.sound),
            ),
            Icons.Default.Notifications,
        ) { onNavigate(ScreenDestination.ALERTS) }
        Text(
            stringResource(R.string.settings_camera_support),
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        SettingsRow(
            stringResource(R.string.settings_camera_connection),
            connectionSummary(connectionState),
            Icons.Default.Bluetooth,
        ) { onNavigate(ScreenDestination.CAMERA_CONNECTION) }
        SettingsRow(
            stringResource(R.string.settings_feedback),
            stringResource(R.string.settings_feedback_summary),
            Icons.AutoMirrored.Filled.HelpOutline,
        ) { onNavigate(ScreenDestination.FEEDBACK) }
    }
}

@Composable
fun AutomaticRecordingScreen(
    radarEnabled: Boolean,
    radarStatus: String,
    descentEnabled: Boolean,
    descentStatus: DescentStatus,
    onNavigate: (ScreenDestination) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(onBack) {
        Text(stringResource(R.string.settings_automation_help), style = MaterialTheme.typography.bodySmall)
        SettingsRow(
            stringResource(R.string.settings_radar_recording),
            if (radarEnabled) stringResource(R.string.settings_automation_status, enabledText(true), radarStatus) else enabledText(false),
            Icons.Default.Sensors,
        ) { onNavigate(ScreenDestination.RADAR) }
        SettingsRow(
            stringResource(R.string.settings_descent_recording),
            if (descentEnabled) {
                val status = if (descentStatus == DescentStatus.OFF) DescentStatus.WAITING_FOR_KAROO else descentStatus
                stringResource(R.string.settings_automation_status, enabledText(true), status.message)
            } else {
                enabledText(false)
            },
            Icons.Default.Terrain,
        ) { onNavigate(ScreenDestination.DESCENT) }
    }
}

@Composable
fun CameraConnectionScreen(
    connectionState: ConnectionState,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
    wholeRideKeepAlive: Boolean = false,
    onWholeRideKeepAliveChanged: (Boolean) -> Unit = {},
) {
    val connected = connectionState is ConnectionState.Connected
    SettingsPage(onBack) {
        Text(connectionSummary(connectionState), style = MaterialTheme.typography.titleSmall)
        val keepAwakeTitle = stringResource(R.string.keepawake_title)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(keepAwakeTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(
                checked = wholeRideKeepAlive,
                onCheckedChange = onWholeRideKeepAliveChanged,
                modifier = Modifier.semantics { contentDescription = keepAwakeTitle },
            )
        }
        Text(stringResource(R.string.keepawake_help), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(
            stringResource(if (connected) R.string.settings_connection_help else R.string.settings_connection_unavailable),
            style = MaterialTheme.typography.bodySmall,
        )
        SettingsRow(
            stringResource(R.string.settings_disconnect),
            stringResource(R.string.settings_disconnect_help),
            Icons.Default.LinkOff,
            enabled = connected,
            navigation = false,
            onClick = onDisconnect,
        )
        SettingsRow(
            stringResource(R.string.settings_forget),
            stringResource(R.string.settings_forget_help),
            Icons.Default.DeleteOutline,
            enabled = connected,
            navigation = false,
            onClick = onForget,
        )
    }
}

@Composable
private fun SettingsPage(
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxSize().padding(bottom = 64.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
        Image(
            painter = painterResource(R.drawable.back),
            contentDescription = stringResource(R.string.camera_alert_back),
            modifier =
                Modifier.align(Alignment.BottomStart).padding(bottom = 10.dp).size(54.dp)
                    .clickable(role = Role.Button, onClick = onBack),
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    summary: String,
    icon: ImageVector,
    enabled: Boolean = true,
    navigation: Boolean = true,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Column {
        Row(
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .semantics {
                        contentDescription = title
                        if (!enabled) disabled()
                    }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = color)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = color)
            }
            if (navigation) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun enabledText(enabled: Boolean): String = stringResource(if (enabled) R.string.settings_on else R.string.settings_off)

@Composable
private fun connectionSummary(state: ConnectionState): String =
    when (state) {
        is ConnectionState.Connected -> stringResource(R.string.settings_connected, state.deviceName ?: stringResource(R.string.settings_unknown_camera))
        is ConnectionState.Connecting -> stringResource(R.string.settings_connecting)
        is ConnectionState.Pairing -> stringResource(R.string.settings_pairing)
        is ConnectionState.PairingAccessRequired -> stringResource(R.string.settings_pairing_access)
        is ConnectionState.Scanning -> stringResource(R.string.settings_scanning)
        is ConnectionState.Error -> stringResource(R.string.settings_connection_error)
        ConnectionState.Disconnected -> stringResource(R.string.settings_disconnected)
    }
