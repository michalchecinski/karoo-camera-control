package com.karoocameracontrol.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.karoocameracontrol.R
import com.karoocameracontrol.extension.Preset
import com.karoocameracontrol.extension.PresetIcon
import com.karoocameracontrol.extension.PresetTitle
import androidx.compose.material3.Icon

@Composable
fun PresetSelectionScreen(
    presets: List<Preset>,
    activePresetId: Int?,
    onPresetSelected: (Preset) -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Select Preset",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(presets) { preset ->
                    val isActive = preset.id == activePresetId
                    PresetItem(preset = preset, isActive = isActive, onClick = { onPresetSelected(preset) })
                    HorizontalDivider()
                }
            }
        }

        Image(
            painter = painterResource(id = R.drawable.back),
            contentDescription = "Back",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 10.dp)
                .size(54.dp)
                .clickable {
                    onBack()
                }
        )
    }
}

@Composable
fun PresetItem(preset: Preset, isActive: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = PresetIcon.getIcon(preset.iconId)
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(end = 16.dp).size(24.dp)
        )

        // Use custom_name if available (from Proto field 10), otherwise map title_id
        val name = if (!preset.customName.isNullOrEmpty()) {
            preset.customName
        } else {
            PresetTitle.getTitle(preset.titleId)
        }
        
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}