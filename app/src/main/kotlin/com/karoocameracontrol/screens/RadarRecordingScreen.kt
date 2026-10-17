package com.karoocameracontrol.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R
import com.karoocameracontrol.radar.RadarSettings

@Composable
fun RadarRecordingScreen(
    settings: RadarSettings,
    status: String,
    onEnabledChanged: (Boolean) -> Unit,
    onDelayChanged: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var showSetup by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = 64.dp).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Record detected traffic", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(
                    modifier = Modifier.semantics { contentDescription = "Record detected traffic" },
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChanged,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stop after radar clears", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        modifier = Modifier.width(56.dp),
                        contentPadding = PaddingValues(0.dp),
                        onClick = { onDelayChanged(settings.stopDelaySeconds - 5) },
                        enabled = settings.stopDelaySeconds > 5,
                    ) { Text("−5s") }
                    Text("${settings.stopDelaySeconds} seconds", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        modifier = Modifier.width(56.dp),
                        contentPadding = PaddingValues(0.dp),
                        onClick = { onDelayChanged(settings.stopDelaySeconds + 5) },
                        enabled = settings.stopDelaySeconds < 120,
                    ) { Text("+5s") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Text(status, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Image(
            painter = painterResource(R.drawable.back),
            contentDescription = "Back",
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 10.dp).size(54.dp).clickable(onClick = onBack),
        )
        TextButton(
            onClick = { showSetup = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 10.dp),
        ) { Text("Setup & limits") }
    }
    if (showSetup) {
        AlertDialog(
            onDismissRequest = { showSetup = false },
            title = { Text("Radar recording setup") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Pair a radar in Karoo Sensors, connect a GoPro in Video mode, then start a ride. Settings save automatically.")
                    Text("Starts video on a vehicle detection. A new detection keeps recording and resets the stop delay.")
                    Text("Keeps the GoPro awake during enabled rides, using camera battery.")
                    Text("Radar clear estimates the last vehicle passing; there is no exact pass event.")
                    Text("Manual controls override this encounter. Only recordings started by radar are stopped automatically.")
                    Text("If radar data is lost for 5 seconds, recording stops after the chosen delay. Camera disconnection or app shutdown may require a manual stop.")
                }
            },
            confirmButton = { TextButton(onClick = { showSetup = false }) { Text("Done") } },
        )
    }
}
