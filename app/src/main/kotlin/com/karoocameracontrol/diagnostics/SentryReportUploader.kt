package com.karoocameracontrol.diagnostics

import io.sentry.Attachment
import io.sentry.Hint
import io.sentry.RequestDetails
import io.sentry.RequestDetailsResolver
import io.sentry.SentryClient
import io.sentry.SentryEnvelope
import io.sentry.SentryEvent
import io.sentry.SentryItemType
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import io.sentry.protocol.SentryId
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

fun interface ReportUploader {
    fun upload(report: DiagnosticReport)
}

/** No global Sentry initialization, integrations, disk cache, or retry executor. */
class SentryReportUploader(
    private val dsn: String,
    private val connectionFactory: (RequestDetails) -> HttpURLConnection = { it.url.openConnection() as HttpURLConnection },
) : ReportUploader {
    override fun upload(report: DiagnosticReport) {
        if (report.logs.size > 256 * 1024) throw IOException("Diagnostic attachment exceeds its size limit")
        val options =
            SentryOptions().apply {
                this.dsn = this@SentryReportUploader.dsn
                isSendClientReports = false
                isEnableAutoSessionTracking = false
                isEnableUncaughtExceptionHandler = false
                maxAttachmentSize = 256L * 1024
                release = "com.karoocameracontrol@${report.metadata["app_version"]}"
                integrations.clear()
                eventProcessors.clear()
            }
        val details = RequestDetailsResolver(dsn, "karoo-camera-control/1").resolve()
        require(details.url.protocol == "https")
        val transport = ManualSentryTransport(options, details) { connectionFactory(details) }
        options.setTransportFactory { _, _ -> transport }
        val client = SentryClient(options)
        try {
            val event =
                SentryEvent().apply {
                    eventId = SentryId(report.id)
                    timestamp = java.util.Date(report.createdAt)
                    level = SentryLevel.INFO
                    message = Message().apply { message = "User-initiated diagnostics" }
                    setTag("report_id", report.supportCode)
                    report.metadata.forEach { (key, value) -> setTag(key, value) }
                }
            val hint = Hint().apply { addAttachment(Attachment(report.logs, "diagnostics.txt", "text/plain")) }
            client.captureEvent(event, null, hint)
            // captureEvent catches transport exceptions, so require an explicit transport acknowledgement.
            if (!transport.accepted) throw IOException("Report submission failed")
        } finally {
            client.close()
        }
    }
}

/** One synchronous request, invoked only on the application's IO worker. */
class ManualSentryTransport(
    private val options: SentryOptions,
    private val details: RequestDetails,
    private val connect: () -> HttpURLConnection = { details.url.openConnection() as HttpURLConnection },
) : ITransport {
    var accepted = false
        private set

    override fun send(
        envelope: SentryEnvelope,
        hint: Hint,
    ) {
        accepted = false
        val items = envelope.items.toList()
        val types = items.map { it.header.type }
        if (types.count { it == SentryItemType.Event } != 1 || types.count { it == SentryItemType.Attachment } != 1) {
            throw IOException("A complete report requires an event and attachment")
        }
        // Materialize both payloads before serialization. The SDK serializer can skip
        // an item whose lazy payload throws, leaving an otherwise accepted event.
        for (item in items) {
            val data =
                try {
                    item.data
                } catch (error: Exception) {
                    throw IOException("Unable to serialize complete report", error)
                }
            if (item.header.type == SentryItemType.Attachment && data.size > 256 * 1024) {
                throw IOException("Diagnostic attachment exceeds its size limit")
            }
        }
        val bytes = ByteArrayOutputStream().also { options.serializer.serialize(envelope, it) }.toByteArray()
        val connection = connect()
        val expired = AtomicBoolean(false)
        val timeout = Timer("diagnostic-upload-timeout", true)
        timeout.schedule(
            object : TimerTask() {
                override fun run() {
                    expired.set(true)
                    connection.disconnect()
                }
            },
            30_000,
        )
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            details.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.setRequestProperty("Content-Type", "application/x-sentry-envelope")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            // A successful event response can still carry an attachment/category quota rejection.
            val limits = connection.getHeaderField("X-Sentry-Rate-Limits").orEmpty()
            accepted = !expired.get() && status in 200..299 && limits.isBlank()
            if (!accepted) throw IOException("Sentry rejected report")
        } finally {
            timeout.cancel()
            connection.disconnect()
        }
    }

    override fun flush(timeoutMillis: Long) = Unit

    override fun getRateLimiter(): RateLimiter? = null

    override fun close() = Unit

    override fun close(isRestarting: Boolean) = Unit
}
