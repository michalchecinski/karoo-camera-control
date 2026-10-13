package com.karoocameracontrol.radar

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RadarSettings(val enabled: Boolean = false, val stopDelaySeconds: Int = 15)

class RadarRecordingSettings internal constructor(context: Context) {
    private val preferences = context.getSharedPreferences("radar_recording", Context.MODE_PRIVATE)
    private val mutableSettings =
        MutableStateFlow(
            RadarSettings(
                preferences.getBoolean("enabled", false),
                preferences.getInt("stop_delay_seconds", 15).coerceIn(5, 120),
            ),
        )
    val settings = mutableSettings.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean("enabled", enabled) }
        mutableSettings.value = mutableSettings.value.copy(enabled = enabled)
    }

    fun setStopDelay(seconds: Int) {
        val delay = seconds.coerceIn(5, 120)
        preferences.edit { putInt("stop_delay_seconds", delay) }
        mutableSettings.value = mutableSettings.value.copy(stopDelaySeconds = delay)
    }

    companion object {
        @Volatile private var instance: RadarRecordingSettings? = null

        fun getInstance(context: Context): RadarRecordingSettings =
            instance ?: synchronized(this) {
                instance ?: RadarRecordingSettings(context.applicationContext).also { instance = it }
            }
    }
}
