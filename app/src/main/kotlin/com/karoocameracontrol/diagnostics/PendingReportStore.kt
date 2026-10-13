package com.karoocameracontrol.diagnostics

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

data class DiagnosticReport(
    val id: String,
    val createdAt: Long,
    val metadata: Map<String, String>,
    val logs: ByteArray,
) {
    val supportCode: String get() = id.take(12)

    companion object {
        fun create(
            metadata: Map<String, String>,
            logs: ByteArray,
            now: Long = System.currentTimeMillis(),
        ) =
            DiagnosticReport(UUID.randomUUID().toString().replace("-", ""), now, metadata, logs)
    }
}

class PendingReportStore(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val target get() = File(directory, "pending.bin")

    fun save(report: DiagnosticReport) {
        directory.mkdirs()
        val temporary = File(directory, "pending.tmp")
        DataOutputStream(temporary.outputStream()).use {
            it.writeUTF(report.id)
            it.writeLong(report.createdAt)
            it.writeInt(report.metadata.size)
            report.metadata.forEach { (key, value) ->
                it.writeUTF(key)
                it.writeUTF(value)
            }
            it.writeInt(report.logs.size)
            it.write(report.logs)
        }
        check(temporary.renameTo(target))
    }

    fun load(): DiagnosticReport? {
        File(directory, "pending.tmp").delete()
        if (!target.exists()) return null
        return try {
            DataInputStream(target.inputStream()).use { input ->
                val id = input.readUTF()
                require(id.matches(Regex("[0-9a-f]{32}")))
                val created = input.readLong()
                require(created <= clock() && clock() - created < EXPIRY_MS)
                val metadata =
                    buildMap<String, String> {
                        val count = input.readInt()
                        require(count in 0..10)
                        repeat(count) { put(input.readUTF(), input.readUTF()) }
                    }
                val length = input.readInt()
                require(length in 0..256 * 1024)
                val logs = ByteArray(length)
                input.readFully(logs)
                DiagnosticReport(id, created, metadata, logs)
            }
        } catch (_: Exception) {
            discard()
            null
        }
    }

    fun discard() {
        if (target.exists()) check(target.delete())
        File(directory, "pending.tmp").delete()
    }

    companion object {
        const val EXPIRY_MS = 24L * 60 * 60 * 1000
    }
}
