package com.karoocameracontrol.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.karoocameracontrol.R

@Composable
fun FeedbackScreen(
    onFinish: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = "Do you like the app? Consider leaving a tip at:",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    text = "QR Code 1 Placeholder",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Do you want to suggest a feature or report a bug? Use the following qr code:",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    text = "QR Code 2 Placeholder",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Presets use icons from Material Design by Google",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Image(
            painter = painterResource(id = R.drawable.back),
            contentDescription = "Back",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 10.dp)
                .size(54.dp)
                .clickable {
                    onFinish()
                }
        )
    }
}