package com.karoocameracontrol.integrations.gopro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One periodic task per ready BLE session, independent of UI and recording preferences. */
internal class ConnectionKeepAlive(
    private val scope: CoroutineScope,
    private val onResult: (Exception?) -> Unit,
    private val awaitCommandIdle: suspend () -> Unit = {},
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var session: Any? = null
    private var job: Job? = null
    private var running = true

    @Synchronized
    fun start(
        connection: Any,
        send: suspend () -> Unit,
    ) {
        if (!running) return
        if (session === connection && job?.isActive == true) return
        val previous = job
        previous?.cancel()
        session = connection
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                // A replaced session must finish releasing its BLE response/operation locks.
                previous?.join()
                var next = nowMs() + INTERVAL_MS
                while (currentCoroutineContext().isActive) {
                    wait((next - nowMs()).coerceAtLeast(0))
                    currentCoroutineContext().ensureActive()
                    try {
                        awaitCommandIdle()
                        send()
                        currentCoroutineContext().ensureActive()
                        onResult(null)
                    } catch (error: Exception) {
                        // An operation timeout leaves this job active; disconnect/shutdown does not.
                        currentCoroutineContext().ensureActive()
                        onResult(error)
                    }
                    next += INTERVAL_MS
                    // Never burst missed writes after a long-running camera command.
                    if (next <= nowMs()) next = nowMs() + INTERVAL_MS
                }
            }.also { it.start() }
    }

    @Synchronized
    fun stop() {
        session = null
        job?.cancel()
    }

    @Synchronized
    fun shutdown() {
        running = false
        stop()
    }

    @Synchronized
    fun resume() {
        running = true
    }

    companion object {
        const val INTERVAL_MS = 60_000L
    }
}
