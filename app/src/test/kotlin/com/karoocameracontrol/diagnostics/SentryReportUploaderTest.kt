package com.karoocameracontrol.diagnostics

import io.sentry.Attachment
import io.sentry.Hint
import io.sentry.RequestDetailsResolver
import io.sentry.SentryClient
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class SentryReportUploaderTest {
    private val dsn = "https://public@example.invalid/42"

    @Test fun onlyExplicitSubmissionSendsBothEventAndAttachment() {
        val requests = AtomicInteger()
        val connection = FakeConnection(200)
        val uploader =
            SentryReportUploader(dsn) {
                requests.incrementAndGet()
                connection
            }
        assertEquals(0, requests.get())
        val report = DiagnosticReport.create(mapOf("app_version" to "test", "connection_state" to "Disconnected"), "safe log line".toByteArray())
        uploader.upload(report)
        assertEquals(1, requests.get())
        val envelope = connection.body.toString("UTF-8")
        assertTrue(envelope.contains("\"type\":\"event\""))
        assertTrue(envelope.contains("\"type\":\"attachment\""))
        assertTrue(envelope.contains("safe log line"))
        assertTrue(envelope.contains(report.supportCode))
        assertTrue(envelope.contains(report.id))
        assertFalse(envelope.contains("client_report"))
        assertTrue(connection.disconnected)
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
    }

    @Test fun quotaServerAndRedirectResponsesCannotReportSuccess() {
        for (status in listOf(302, 400, 429, 500)) {
            assertThrows(IOException::class.java) {
                SentryReportUploader(dsn) { FakeConnection(status) }.upload(report())
            }
        }
    }

    @Test fun eventAcceptanceWithAttachmentRateLimitCannotReportSuccess() {
        assertThrows(IOException::class.java) {
            SentryReportUploader(dsn) { FakeConnection(200, "60:attachment:organization") }.upload(report())
        }
    }

    @Test fun offlineAndTimeoutFailuresDoNotRetry() {
        for (error in listOf(IOException("offline"), SocketTimeoutException("timeout"))) {
            val requests = AtomicInteger()
            assertThrows(IOException::class.java) {
                SentryReportUploader(dsn) {
                    requests.incrementAndGet()
                    FakeConnection(200, error = error)
                }.upload(report())
            }
            assertEquals(1, requests.get())
        }
    }

    @Test fun missingAttachmentNeverCreatesAnHttpRequest() {
        val requests = AtomicInteger()
        val options = SentryOptions().apply { this.dsn = this@SentryReportUploaderTest.dsn }
        val transport =
            ManualSentryTransport(options, RequestDetailsResolver(dsn, "test").resolve()) {
                requests.incrementAndGet()
                FakeConnection(200)
            }
        options.setTransportFactory { _, _ -> transport }
        val client = SentryClient(options)
        client.captureEvent(SentryEvent(), null, Hint())
        client.close()
        assertEquals(0, requests.get())
        assertFalse(transport.accepted)
    }

    @Test
    fun attachmentSerializationFailureCannotReportSuccess() {
        val requests = AtomicInteger()
        val options = SentryOptions().apply { this.dsn = this@SentryReportUploaderTest.dsn }
        val transport =
            ManualSentryTransport(options, RequestDetailsResolver(dsn, "test").resolve()) {
                requests.incrementAndGet()
                FakeConnection(200)
            }
        options.setTransportFactory { _, _ -> transport }
        val client = SentryClient(options)
        val hint = Hint().apply { addAttachment(Attachment("/nonexistent-diagnostic-attachment/log.txt")) }
        client.captureEvent(SentryEvent(), null, hint)
        client.close()
        assertEquals(0, requests.get())
        assertFalse(transport.accepted)
    }

    @Test
    fun insecureDsnCannotCreateANetworkRequest() {
        val requests = AtomicInteger()
        assertThrows(IllegalArgumentException::class.java) {
            SentryReportUploader("http://public@example.invalid/42") {
                requests.incrementAndGet()
                FakeConnection(200)
            }.upload(report())
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun oversizedAttachmentCannotReportSuccess() {
        val requests = AtomicInteger()
        assertThrows(Exception::class.java) {
            SentryReportUploader(dsn) {
                requests.incrementAndGet()
                FakeConnection(200)
            }.upload(
                DiagnosticReport.create(emptyMap(), ByteArray(256 * 1024 + 1)),
            )
        }
        assertEquals(0, requests.get())
    }

    private fun report() = DiagnosticReport.create(emptyMap(), "logs".toByteArray())

    private class FakeConnection(
        private val status: Int,
        private val limits: String = "",
        private val error: IOException? = null,
    ) : HttpURLConnection(URL("https://example.invalid")) {
        val body = ByteArrayOutputStream()
        var disconnected = false

        override fun getOutputStream() = body

        override fun getResponseCode(): Int {
            error?.let { throw it }
            return status
        }

        override fun getHeaderField(name: String): String? = if (name == "X-Sentry-Rate-Limits") limits else null

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun connect() = Unit
    }
}
