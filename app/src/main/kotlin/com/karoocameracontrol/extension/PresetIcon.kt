package com.karoocameracontrol.extension

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

object PresetIcon {
    fun getIcon(id: Int): ImageVector {
        return when (id) {
            0 -> Icons.Filled.Videocam // Video
            1 -> Icons.Filled.DirectionsRun // Activity
            2 -> Icons.Filled.Movie // Cinematic
            3 -> Icons.Filled.PhotoCamera // Photo
            4 -> Icons.Filled.ShutterSpeed // Live Burst (Approximation)
            5 -> Icons.Filled.BurstMode // Burst
            6 -> Icons.Filled.Nightlight // Night
            7 -> Icons.Filled.Speed // TimeWarp
            8 -> Icons.Filled.Timelapse // Time Lapse
            9 -> Icons.Filled.NightlightRound // Night Lapse
            10 -> Icons.Filled.SlowMotionVideo // Snail/SlowMo ? Or Video
            11 -> Icons.Filled.SlowMotionVideo // Video 2
            13 -> Icons.Filled.PhotoCamera // Photo 2
            14 -> Icons.Filled.Panorama // Panorama
            16 -> Icons.Filled.Speed // TimeWarp 2
            18 -> Icons.Filled.Tune // Custom
            19 -> Icons.Filled.Air // Air
            20 -> Icons.Filled.DirectionsBike // Bike
            21 -> Icons.Filled.Landscape // Epic (Mountain?)
            22 -> Icons.Filled.Home // Indoor
            23 -> Icons.Filled.TwoWheeler // Motor
            24 -> Icons.Filled.CameraAlt // Mounted
            25 -> Icons.Filled.Terrain // Outdoor
            26 -> Icons.Filled.Visibility // POV
            27 -> Icons.Filled.Face // Selfie
            28 -> Icons.Filled.Skateboarding // Skate
            29 -> Icons.Filled.AcUnit // Snow
            30 -> Icons.Filled.Hiking // Trail
            31 -> Icons.Filled.Flight // Travel
            32 -> Icons.Filled.Water // Water
            33 -> Icons.Filled.Loop // Looping
            34 -> Icons.Filled.Star // Stars
            else -> Icons.Filled.Circle // Default
        }
    }
}
