package com.karoocameracontrol.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactTopAppBar(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    onTitleLayout: (TextLayoutResult) -> Unit = {},
) {
    CenterAlignedTopAppBar(
        expandedHeight = 48.dp,
        title = {
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.bodySmall
            BoxWithConstraints {
                val fontSize =
                    remember(title, constraints.maxWidth, measurer, style) {
                        // Measure before drawing to avoid resize/recomposition flicker.
                        (24 downTo 20).firstOrNull { halfSp ->
                            !measurer.measure(
                                text = title,
                                style = style.copy(fontSize = (halfSp / 2f).sp),
                                maxLines = 1,
                                softWrap = false,
                                constraints = Constraints(maxWidth = constraints.maxWidth),
                            ).hasVisualOverflow
                        }?.let { (it / 2f).sp } ?: 10.sp
                    }
                Text(
                    text = title,
                    style = style.copy(fontSize = fontSize),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = onTitleLayout,
                )
            }
        },
        actions = actions,
        colors =
            TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
    )
}
