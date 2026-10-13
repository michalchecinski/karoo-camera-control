package com.karoocameracontrol.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rotationBoundsDiskAndSnapshotAndKeepsNewestCompleteLines() {
        val dir = temporary.newFolder()
        val store = DiagnosticStore(dir, { 1000L }, 128)
        repeat(100) { store.append("1000 session I Camera entry-$it") }
        assertTrue(dir.listFiles()!!.size <= 4)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 4 * 128)
        val snapshot = store.snapshot()
        assertTrue(snapshot.size <= 128)
        assertTrue(snapshot.toString(Charsets.UTF_8).contains("entry-99"))
        assertFalse(snapshot.toString(Charsets.UTF_8).contains("entry-0\n"))
    }

    @Test fun retentionRemovesOldEntriesSharingAFileWithNewEntries() {
        var now = DiagnosticStore.RETENTION_MS + 1000
        val dir = temporary.newFolder()
        dir.resolve("log-0.txt").writeText("999 old\n1001 new\n")
        val store = DiagnosticStore(dir, { now })
        assertEquals("1001 new\n", store.snapshot().toString(Charsets.UTF_8))
        now += DiagnosticStore.RETENTION_MS
        assertEquals(0, store.snapshot().size)
    }

    @Test fun concurrentWritesAndSnapshotsProduceCompleteRecords() {
        val store = DiagnosticStore(temporary.newFolder(), { 1000L })
        val pool = Executors.newFixedThreadPool(4)
        repeat(100) { index ->
            pool.submit {
                store.append("1000 line-$index")
                store.snapshot()
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(100, store.snapshot().toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }.size)
    }

    @Test fun unicodeSnapshotsRemainValidUtf8() {
        val store = DiagnosticStore(temporary.newFolder(), { 1000L }, 128)
        repeat(20) { store.append("1000 🚲🚲 camera-$it") }
        val result = store.snapshot()
        assertArrayEquals(result, result.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8))
    }

    @Test fun sanitizerOmitsAddressesSecretsAndExceptionMessages() {
        val sanitized = DiagnosticSanitizer.message("AA:BB:CC:DD:EE:FF password=private token=secret\n")
        assertFalse(sanitized.contains("AA:BB"))
        assertFalse(sanitized.contains("private"))
        val stack = DiagnosticSanitizer.stack(IllegalStateException("Personal camera password"))
        assertFalse(stack.contains("Personal camera"))
        assertTrue(stack.contains("IllegalStateException"))
        assertTrue(stack.contains("DiagnosticStoreTest"))
    }

    @Test fun storageFailuresAreObservableToSnapshotCaller() {
        val store = DiagnosticStore(temporary.newFile(), { 1000L })
        assertThrows(Exception::class.java) { store.append("1000 entry") }
    }
}
