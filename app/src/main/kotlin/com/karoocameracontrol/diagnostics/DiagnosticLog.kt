package com.karoocameracontrol.diagnostics

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID

object DiagnosticSanitizer {
    private val mac = Regex("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}")
    private val secret = Regex("(?i)(password|passwd|token|secret|ssid)\\s*[:=]\\s*\\S+")

    fun message(value: String): String = secret.replace(mac.replace(value, "[device]"), "$1=[redacted]").replace('\n', ' ').replace('\r', ' ')

    fun stack(error: Throwable): String =
        buildString {
            var current: Throwable? = error
            repeat(4) {
                val cause = current ?: return@repeat
                append(" ").append(cause.javaClass.name)
                cause.stackTrace.take(12).forEach { append(" at ").append(it.toString()) }
                current = cause.cause.takeUnless { it === cause }
            }
        }
}

object DiagnosticLog {
    private sealed interface Work {
        data class Line(val value: String) : Work

        data class Snapshot(val result: CompletableDeferred<ByteArray>) : Work
    }

    private val queue = Channel<Work>(128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = UUID.randomUUID().toString().take(8)
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val store = DiagnosticStore(java.io.File(context.noBackupFilesDir, "diagnostics/logs"))
        scope.launch {
            for (work in queue) {
                try {
                    when (work) {
                        is Work.Line -> store.append(work.value)
                        is Work.Snapshot -> work.result.complete(store.snapshot())
                    }
                } catch (error: Exception) {
                    if (work is Work.Snapshot) work.result.completeExceptionally(error)
                }
            }
        }
        i("Application", "Diagnostic session started")
    }

    fun d(
        tag: String,
        message: String,
    ) = record("D", tag, message)

    fun i(
        tag: String,
        message: String,
    ) = record("I", tag, message)

    fun w(
        tag: String,
        message: String,
    ) = record("W", tag, message)

    fun e(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) = record("E", tag, message, error)

    private fun record(
        level: String,
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        val safe = DiagnosticSanitizer.message(message) + (error?.let(DiagnosticSanitizer::stack) ?: "")
        queue.trySend(Work.Line("${System.currentTimeMillis()} $session $level $tag $safe"))
    }

    suspend fun snapshot(): ByteArray {
        val result = CompletableDeferred<ByteArray>()
        queue.send(Work.Snapshot(result))
        return result.await()
    }
}
