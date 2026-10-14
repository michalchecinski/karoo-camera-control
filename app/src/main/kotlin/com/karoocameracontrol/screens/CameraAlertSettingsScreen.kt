package com.karoocameracontrol.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R
import com.karoocameracontrol.alerts.CameraAlertSettings

@Composable
fun CameraAlertSettingsScreen(
    settings: CameraAlertSettings,
    onSettingsChanged: (CameraAlertSettings) -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxSize().padding(bottom = 64.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AlertPreference(
                stringResource(R.string.camera_alert_confirmations),
                stringResource(R.string.camera_alert_confirmations_help),
                settings.confirmations,
            ) { onSettingsChanged(settings.copy(confirmations = it)) }
            AlertPreference(
                stringResource(R.string.camera_alert_warnings),
                stringResource(R.string.camera_alert_warnings_help),
                settings.warnings,
            ) { onSettingsChanged(settings.copy(warnings = it)) }
            AlertPreference(
                stringResource(R.string.camera_alert_sound),
                stringResource(R.string.camera_alert_sound_help),
                settings.sound,
            ) { onSettingsChanged(settings.copy(sound = it)) }
            Text(stringResource(R.string.camera_alert_settings_help), style = MaterialTheme.typography.bodySmall)
        }
        Image(
            painter = painterResource(R.drawable.back),
            contentDescription = stringResource(R.string.camera_alert_back),
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 10.dp).size(54.dp).clickable(onClick = onBack),
        )
    }
}

@Composable
private fun AlertPreference(
    title: String,
    detail: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChanged, modifier = Modifier.semantics { contentDescription = title })
    }
}
