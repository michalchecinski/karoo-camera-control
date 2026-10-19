package com.karoocameracontrol.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R

@Composable
fun WakingRecordingScreen(onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            Modifier.fillMaxSize().padding(bottom = 80.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Text(stringResource(R.string.recording_waking), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.recording_wake_help), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        Button(onClick = onCancel, modifier = Modifier.align(Alignment.BottomEnd).heightIn(min = 64.dp)) {
            Text(stringResource(R.string.recording_cancel_wake))
        }
    }
}
