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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R
import com.karoocameracontrol.recording.DescentSettings
import com.karoocameracontrol.recording.DescentStatus

@Composable
fun DescentRecordingScreen(
    settings: DescentSettings,
    status: DescentStatus,
    onSettingsChanged: (DescentSettings) -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 64.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Start on descents",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { onSettingsChanged(settings.copy(enabled = it)) },
                    modifier = Modifier.semantics { contentDescription = "Start on descents" },
                )
            }
            Text(
                text = "Starts video during rides.\nStop recording manually.",
                style = MaterialTheme.typography.bodySmall,
            )
            SettingStepper(
                label = "Descent gradient",
                value = "−${settings.gradientPercent}%",
                enabled = settings.enabled,
                canDecrease = settings.gradientPercent > 1,
                canIncrease = settings.gradientPercent < 10,
                decreaseDescription = "Less steep gradient",
                increaseDescription = "Steeper gradient",
                onDecrease = { onSettingsChanged(settings.copy(gradientPercent = settings.gradientPercent - 1)) },
                onIncrease = { onSettingsChanged(settings.copy(gradientPercent = settings.gradientPercent + 1)) },
            )
            SettingStepper(
                label = "Confirmation time",
                value = "${settings.confirmationSeconds} s",
                enabled = settings.enabled,
                canDecrease = settings.confirmationSeconds > 1,
                canIncrease = settings.confirmationSeconds < 10,
                decreaseDescription = "Shorter confirmation",
                increaseDescription = "Longer confirmation",
                onDecrease = { onSettingsChanged(settings.copy(confirmationSeconds = settings.confirmationSeconds - 1)) },
                onIncrease = { onSettingsChanged(settings.copy(confirmationSeconds = settings.confirmationSeconds + 1)) },
            )
            Text(
                text = "Connected GoPro • Video • ≥5 km/h",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = status.message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Image(
            painter = painterResource(R.drawable.back),
            contentDescription = "Back",
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 10.dp)
                    .size(54.dp)
                    .clickable(onClick = onBack),
        )
    }
}

@Composable
private fun SettingStepper(
    label: String,
    value: String,
    enabled: Boolean,
    canDecrease: Boolean,
    canIncrease: Boolean,
    decreaseDescription: String,
    increaseDescription: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        OutlinedIconButton(
            onClick = onDecrease,
            enabled = enabled && canDecrease,
            modifier = Modifier.semantics { contentDescription = decreaseDescription },
        ) { Icon(Icons.Default.Remove, contentDescription = null) }
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.size(width = 40.dp, height = 28.dp))
        OutlinedIconButton(
            onClick = onIncrease,
            enabled = enabled && canIncrease,
            modifier = Modifier.semantics { contentDescription = increaseDescription },
        ) { Icon(Icons.Default.Add, contentDescription = null) }
    }
}
