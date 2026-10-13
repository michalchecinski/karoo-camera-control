package com.karoocameracontrol.diagnostics

import java.io.File

/** Accessed by a single recorder worker. Snapshots run behind previously queued writes. */
class DiagnosticStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fileLimit: Int = 256 * 1024,
) {
    private var lastPrune = Long.MIN_VALUE

    private fun file(index: Int) = File(directory, "log-$index.txt")

    @Synchronized
    fun append(line: String) {
        directory.mkdirs()
        if (lastPrune == Long.MIN_VALUE || clock() - lastPrune >= 60 * 60 * 1000L) prune()
        val bytes = (line.take(4000) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= fileLimit)
        if (file(0).length() + bytes.size > fileLimit) {
            file(3).delete()
            for (index in 2 downTo 0) {
                if (file(index).exists()) check(file(index).renameTo(file(index + 1)))
            }
        }
        file(0).appendBytes(bytes)
    }

    @Synchronized
    fun snapshot(): ByteArray {
        prune()
        val output = java.io.ByteArrayOutputStream()
        for (index in 3 downTo 0) {
            if (file(index).exists()) output.write(file(index).readBytes())
        }
        val bytes = output.toByteArray()
        if (bytes.size <= fileLimit) return bytes
        val start = bytes.size - fileLimit
        // Drop an incomplete first line, preserving UTF-8 boundaries.
        val newline = (start until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() }
        return if (newline == null) byteArrayOf() else bytes.copyOfRange(newline + 1, bytes.size)
    }

    private fun prune() {
        lastPrune = clock()
        val cutoff = lastPrune - RETENTION_MS
        for (index in 0..3) {
            val target = file(index)
            if (!target.exists()) continue
            // Files are bounded; remove old entries even if newer entries share the file.
            val lines = target.readLines()
            val retained = lines.filter { (it.substringBefore(' ').toLongOrNull() ?: 0L) >= cutoff }
            if (retained.size != lines.size) {
                if (retained.isEmpty()) target.delete() else target.writeText(retained.joinToString("\n", postfix = "\n"))
            }
        }
    }

    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
