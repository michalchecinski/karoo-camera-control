package com.karoocameracontrol.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R

@Composable
fun PairingAccessScreen(
    deviceName: String?,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(start = 24.dp, top = 56.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "One-time setup required",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Tap Open settings, turn on Karoo Camera Control, return here, then tap Connect.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenSettings) {
                Text("Open settings")
            }
        }

        Image(
            painter = painterResource(id = R.drawable.back),
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
