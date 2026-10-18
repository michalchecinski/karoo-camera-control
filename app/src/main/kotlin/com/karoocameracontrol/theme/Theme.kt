package com.karoocameracontrol.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CameraColors =
    lightColorScheme(
        primary = Color(0xFF075D69),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE3EFF0),
        onPrimaryContainer = Color(0xFF075D69),
        secondary = Color(0xFF52656A),
        secondaryContainer = Color(0xFFE3EFF0),
        onSecondaryContainer = Color(0xFF182B30),
        tertiary = Color(0xFF075D69),
        background = Color(0xFFFAFCFB),
        onBackground = Color(0xFF182B30),
        surface = Color(0xFFFAFCFB),
        onSurface = Color(0xFF182B30),
        surfaceVariant = Color(0xFFE3EFF0),
        onSurfaceVariant = Color(0xFF52656A),
        surfaceContainer = Color(0xFFEFF5F4),
        surfaceContainerLow = Color(0xFFF4F8F7),
        surfaceContainerHigh = Color(0xFFE9F1F0),
        surfaceContainerHighest = Color(0xFFE3EFF0),
        outlineVariant = Color(0xFFCCDADC),
        error = Color(0xFFB3261E),
    )

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CameraColors,
        content = content,
    )
}
