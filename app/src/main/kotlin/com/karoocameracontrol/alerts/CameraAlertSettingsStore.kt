package com.karoocameracontrol.alerts

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraAlertSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("CameraAlerts", Context.MODE_PRIVATE)
    private val _settings =
        MutableStateFlow(
            CameraAlertSettings(
                confirmations = preferences.getBoolean("confirmations", false),
                warnings = preferences.getBoolean("warnings", true),
                sound = preferences.getBoolean("sound", false),
            ),
        )
    val settings = _settings.asStateFlow()

    fun update(value: CameraAlertSettings) {
        preferences.edit {
            putBoolean("confirmations", value.confirmations)
            putBoolean("warnings", value.warnings)
            putBoolean("sound", value.sound)
        }
        _settings.value = value
    }

    companion object {
        @Volatile private var instance: CameraAlertSettingsStore? = null

        fun getInstance(context: Context): CameraAlertSettingsStore =
            instance ?: synchronized(this) {
                instance ?: CameraAlertSettingsStore(context).also { instance = it }
            }
    }
}
