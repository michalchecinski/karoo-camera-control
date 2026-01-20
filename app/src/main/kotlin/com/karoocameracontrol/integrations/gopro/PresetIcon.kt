package com.karoocameracontrol.integrations.gopro

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ShutterSpeed
import androidx.compose.material.icons.filled.Skateboarding
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Water
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
            20 -> Icons.Filled.PedalBike // Bike
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
            76 -> Icons.Filled.AutoAwesome // Star Trails (Moving Star)
            77 -> Icons.Filled.FlashlightOn // Light Painting
            78 -> Icons.Filled.DirectionsCar // Light Trail (Car Headlights/Vehicle Light)
            else -> Icons.Filled.Circle // Default
        }
    }
}
