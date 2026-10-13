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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.karoocameracontrol.R
import com.karoocameracontrol.diagnostics.DiagnosticReports
import com.karoocameracontrol.diagnostics.ReportFailureReason
import com.karoocameracontrol.diagnostics.ReportState

@Composable
fun FeedbackScreen(
    onFinish: () -> Unit,
    reportState: ReportState,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    configured: Boolean = DiagnosticReports.configured,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(8.dp)
                    // Add padding for back button
                    .padding(bottom = 80.dp),
        ) {
            DiagnosticsControls(reportState, onSend, onRetry, onDiscard, configured)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Want to suggest a feature or report a bug? Use the following qr code:",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
            )
            Box(
                modifier =
                    Modifier
                        .size(150.dp)
                        .background(MaterialTheme.colorScheme.surface),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.qrcode_feedback),
                    contentDescription = "QR Code with feedback link",
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Like the app? Consider leaving a tip at:",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
            )
            Box(
                modifier =
                    Modifier
                        .size(150.dp)
                        .background(MaterialTheme.colorScheme.surface),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.qrcode_tip),
                    contentDescription = "QR Code with buy a coffee link",
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Presets use icons from Material Design by Google",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "This product and/or service is not affiliated with, endorsed by or in any way associated with GoPro Inc. or its products and services. GoPro, HERO, and their respective logos are trademarks or registered trademarks of GoPro, Inc.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "THIS PROJECT IS NOT OWNED, COMMISSIONED BY OR ASSOCIATED WITH HAMMERHEAD OR SRAM OR GoPro.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
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
                    .zIndex(1f)
                    .clickable {
                        onFinish()
                    },
        )
    }
}

@Composable
fun DiagnosticsControls(
    state: ReportState,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    configured: Boolean = DiagnosticReports.configured,
) {
    var confirm by remember { mutableStateOf(false) }
    Text(stringResource(R.string.diagnostics_problem_heading), style = MaterialTheme.typography.titleMedium)
    Button(
        onClick = { confirm = true },
        enabled = configured && (state == ReportState.Idle || state is ReportState.Sent),
    ) { Text(stringResource(R.string.diagnostics_send)) }
    if (!configured) {
        Text(stringResource(R.string.diagnostics_unavailable), textAlign = TextAlign.Center)
    }
    when (state) {
        ReportState.Loading -> Text(stringResource(R.string.diagnostics_loading))
        ReportState.Sending -> Text(stringResource(R.string.diagnostics_sending), textAlign = TextAlign.Center)
        is ReportState.Sent -> {
            Text(stringResource(R.string.diagnostics_sent), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.diagnostics_support_code, state.code), textAlign = TextAlign.Center)
            Text(stringResource(R.string.diagnostics_feedback_instruction), textAlign = TextAlign.Center)
        }
        is ReportState.Failed -> {
            Text(stringResource(R.string.diagnostics_failed), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            val message =
                when (state.reason) {
                    ReportFailureReason.EXPIRED -> R.string.diagnostics_error_expired
                    ReportFailureReason.PREPARATION_FAILED -> R.string.diagnostics_error_preparation
                    ReportFailureReason.UPLOAD_FAILED -> R.string.diagnostics_error_upload
                }
            Text(stringResource(message), textAlign = TextAlign.Center)
            if (state.canRetry) Button(onClick = onRetry, enabled = configured) { Text(stringResource(R.string.diagnostics_retry)) }
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.diagnostics_discard)) }
        }
        ReportState.Idle -> Unit
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.diagnostics_confirm_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.diagnostics_confirm_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onSend()
                }) { Text(stringResource(R.string.diagnostics_confirm_send)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.diagnostics_cancel)) } },
        )
    }
}
