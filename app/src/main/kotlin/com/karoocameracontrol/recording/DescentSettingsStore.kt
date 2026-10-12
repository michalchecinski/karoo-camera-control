package com.karoocameracontrol.recording

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DescentStatus(val message: String) {
    OFF("Off"),
    WAITING_FOR_KAROO("Waiting for Karoo"),
    WAITING_FOR_RIDE("Waiting for an active ride"),
    WAITING_FOR_DATA("Waiting for gradient and speed"),
    WAITING_FOR_CAMERA("Connect a camera to record"),
    VIDEO_REQUIRED("Select Video mode to record"),
    READY("Ready for the next descent"),
    CONFIRMING("Confirming descent…"),
    CONSUMED("Waiting for the next descent"),
}

class DescentSettingsStore private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("DescentRecording", Context.MODE_PRIVATE)
    private val _settings =
        MutableStateFlow(
            DescentSettings(
                enabled = preferences.getBoolean("enabled", false),
                gradientPercent = preferences.getInt("gradient_percent", 3).coerceIn(1, 10),
                confirmationSeconds = preferences.getInt("confirmation_seconds", 3).coerceIn(1, 10),
            ),
        )
    val settings = _settings.asStateFlow()
    private val _status = MutableStateFlow(if (_settings.value.enabled) DescentStatus.WAITING_FOR_KAROO else DescentStatus.OFF)
    val status = _status.asStateFlow()

    fun update(settings: DescentSettings) {
        preferences.edit {
            putBoolean("enabled", settings.enabled)
            putInt("gradient_percent", settings.gradientPercent)
            putInt("confirmation_seconds", settings.confirmationSeconds)
        }
        _settings.value = settings
        if (!settings.enabled) _status.value = DescentStatus.OFF
    }

    fun setStatus(status: DescentStatus) {
        _status.value = status
    }

    companion object {
        @Volatile
        private var instance: DescentSettingsStore? = null

        fun getInstance(context: Context): DescentSettingsStore =
            instance ?: synchronized(this) {
                instance ?: DescentSettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
