package com.karoocameracontrol.screens

enum class ScreenDestination {
    MAIN,
    SCANNING,
    MODES,
    PRESETS,
    SETTINGS,
    AUTOMATIC_RECORDING,
    RADAR,
    DESCENT,
    ALERTS,
    CAMERA_CONNECTION,
    FEEDBACK,
}

/** Navigation is independent of BLE state; settings remember which main view opened them. */
data class ScreenNavigation(
    val destination: ScreenDestination = ScreenDestination.MAIN,
    val settingsOrigin: ScreenDestination = ScreenDestination.MAIN,
) {
    val showsSettingsAction: Boolean
        get() = destination == ScreenDestination.MAIN || destination == ScreenDestination.SCANNING

    fun open(next: ScreenDestination): ScreenNavigation =
        when {
            next == ScreenDestination.SETTINGS && showsSettingsAction -> ScreenNavigation(next, destination)
            next == ScreenDestination.SETTINGS -> this // Ignore a second tap on the entry point.
            else -> copy(destination = next)
        }

    fun back(): ScreenNavigation =
        copy(
            destination =
                when (destination) {
                    ScreenDestination.RADAR, ScreenDestination.DESCENT -> ScreenDestination.AUTOMATIC_RECORDING
                    ScreenDestination.AUTOMATIC_RECORDING, ScreenDestination.ALERTS,
                    ScreenDestination.CAMERA_CONNECTION, ScreenDestination.FEEDBACK,
                    -> ScreenDestination.SETTINGS
                    ScreenDestination.SETTINGS -> settingsOrigin
                    else -> ScreenDestination.MAIN
                },
        )

    fun cameraConnected(): ScreenNavigation =
        copy(
            destination = if (destination == ScreenDestination.SCANNING) ScreenDestination.MAIN else destination,
            settingsOrigin = ScreenDestination.MAIN,
        )
}
