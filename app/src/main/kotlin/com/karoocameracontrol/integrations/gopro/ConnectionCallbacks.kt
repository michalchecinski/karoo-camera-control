package com.karoocameracontrol.integrations.gopro

/** Keeps the originating connection attached to callbacks, including deferred application. */
internal class ConnectionCallbacks<C : Any>(private val current: () -> C?) {
    fun runIfCurrent(
        source: C,
        operation: () -> Unit,
    ): Boolean {
        if (current() !== source) return false
        operation()
        return true
    }
}
