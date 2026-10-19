package com.karoocameracontrol.connection

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Extra readiness for manual-only rides; does not control recording or camera power settings. */
class CameraReadinessSettings private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("CameraReadiness", Context.MODE_PRIVATE)
    private val mutableWholeRide = MutableStateFlow(preferences.getBoolean("whole_ride", false))
    val wholeRide = mutableWholeRide.asStateFlow()

    fun setWholeRide(enabled: Boolean) {
        preferences.edit { putBoolean("whole_ride", enabled) }
        mutableWholeRide.value = enabled
    }

    companion object {
        @Volatile private var instance: CameraReadinessSettings? = null

        fun getInstance(context: Context): CameraReadinessSettings =
            instance ?: synchronized(this) {
                instance ?: CameraReadinessSettings(context.applicationContext).also { instance = it }
            }
    }
}
