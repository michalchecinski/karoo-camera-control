package com.karoocameracontrol.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticReportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pendingReportSurvivesRestartAndExpiresOrDiscards() {
        var now = 1000L
        val directory = temporary.newFolder()
        val store = PendingReportStore(directory) { now }
        val report = DiagnosticReport.create(mapOf("app_version" to "1.0"), "logs".toByteArray(), now)
        store.save(report)
        assertEquals(report.id, PendingReportStore(directory) { now }.load()!!.id)
        assertArrayEquals(report.logs, store.load()!!.logs)
        now += PendingReportStore.EXPIRY_MS
        assertNull(store.load())
        assertFalse(directory.resolve("pending.bin").exists())
        store.save(DiagnosticReport.create(emptyMap(), byteArrayOf(), now))
        store.discard()
        assertNull(store.load())
    }

    @Test fun corruptPendingReportIsRemoved() {
        val dir = temporary.newFolder()
        dir.resolve("pending.bin").writeText("broken")
        assertNull(PendingReportStore(dir).load())
        assertFalse(dir.resolve("pending.bin").exists())
    }

    @Test fun failedUploadRetriesSameSnapshotOnlyWhenUserRequestsIt() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = PendingReportStore(temporary.newFolder())
                val submissions = mutableListOf<DiagnosticReport>()
                var fail = true
                val repository =
                    DiagnosticReportRepository(store, { report ->
                        submissions.add(report)
                        if (fail) throw IOException("offline")
                    }, { "original logs".toByteArray() }, scope)
                await(repository) { it == ReportState.Idle }
                assertEquals(0, submissions.size)
                repository.send(emptyMap())
                val failure = await(repository) { it is ReportState.Failed } as ReportState.Failed
                assertEquals(ReportFailureReason.UPLOAD_FAILED, failure.reason)
                assertTrue(failure.canRetry)
                assertNotNull(store.load())
                delay(100)
                assertEquals(1, submissions.size)
                fail = false
                repository.retry()
                val sent = await(repository) { it is ReportState.Sent } as ReportState.Sent
                assertEquals(submissions[0].id, submissions[1].id)
                assertArrayEquals(submissions[0].logs, submissions[1].logs)
                assertEquals(submissions[0].supportCode, sent.code)
                assertNull(store.load())
            } finally {
                scope.cancel()
            }
        }

    @Test fun duplicateTapsCannotQueueAnAdditionalUpload() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val count = AtomicInteger()
            try {
                val repository =
                    DiagnosticReportRepository(PendingReportStore(temporary.newFolder()), {
                        count.incrementAndGet()
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }, { byteArrayOf() }, scope)
                await(repository) { it == ReportState.Idle }
                repository.send(emptyMap())
                assertTrue(started.await(5, TimeUnit.SECONDS))
                repeat(20) {
                    repository.send(emptyMap())
                    repository.retry()
                    repository.discard()
                }
                release.countDown()
                await(repository) { it is ReportState.Sent }
                assertEquals(1, count.get())
            } finally {
                release.countDown()
                scope.cancel()
            }
        }

    @Test fun restoredReportIsNotSentUntilRetry() =
        runBlocking {
            val store = PendingReportStore(temporary.newFolder())
            store.save(DiagnosticReport.create(emptyMap(), byteArrayOf()))
            val count = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val repository = DiagnosticReportRepository(store, { count.incrementAndGet() }, { byteArrayOf() }, scope)
                await(repository) { it is ReportState.Failed }
                delay(100)
                assertEquals(0, count.get())
                repository.discard()
                await(repository) { it == ReportState.Idle }
                assertNull(store.load())
            } finally {
                scope.cancel()
            }
        }

    @Test fun snapshotStorageFailureDoesNotUpload() =
        runBlocking {
            val count = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val repository = DiagnosticReportRepository(PendingReportStore(temporary.newFolder()), { count.incrementAndGet() }, { throw IOException("storage full") }, scope)
                await(repository) { it == ReportState.Idle }
                repository.send(emptyMap())
                val failure = await(repository) { it is ReportState.Failed } as ReportState.Failed
                assertEquals(ReportFailureReason.PREPARATION_FAILED, failure.reason)
                assertFalse(failure.canRetry)
                assertEquals(0, count.get())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun expiredReportCannotBeRetried() =
        runBlocking {
            var now = 1000L
            val store = PendingReportStore(temporary.newFolder()) { now }
            store.save(DiagnosticReport.create(emptyMap(), byteArrayOf(), now))
            val count = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val repository = DiagnosticReportRepository(store, { count.incrementAndGet() }, { byteArrayOf() }, scope)
                await(repository) { it is ReportState.Failed }
                now += PendingReportStore.EXPIRY_MS
                repository.retry()
                val failure = await(repository) { it is ReportState.Failed && !it.canRetry } as ReportState.Failed
                assertEquals(ReportFailureReason.EXPIRED, failure.reason)
                assertEquals(0, count.get())
                assertNull(store.load())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun interruptedSaveTemporaryFileIsCleanedOnRestart() {
        val dir = temporary.newFolder()
        dir.resolve("pending.tmp").writeText("interrupted snapshot")
        assertNull(PendingReportStore(dir).load())
        assertFalse(dir.resolve("pending.tmp").exists())
    }

    private suspend fun await(
        repository: DiagnosticReportRepository,
        predicate: (ReportState) -> Boolean,
    ): ReportState =
        withTimeout(5000) { repository.state.first(predicate) }
}
