package com.karoocameracontrol.diagnostics

import android.content.Context
import android.os.Build
import com.karoocameracontrol.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

sealed interface ReportState {
    data object Loading : ReportState

    data object Idle : ReportState

    data object Sending : ReportState

    data class Sent(val code: String) : ReportState

    data class Failed(val canRetry: Boolean, val message: String) : ReportState
}

class DiagnosticReportRepository(
    private val store: PendingReportStore,
    private val uploader: ReportUploader,
    private val snapshot: suspend () -> ByteArray,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<ReportState>(ReportState.Loading)
    val state: StateFlow<ReportState> = mutableState

    init {
        scope.launch {
            mutex.lock()
            try {
                mutableState.value = if (store.load() == null) ReportState.Idle else failure(true)
            } catch (_: Exception) {
                mutableState.value = failure(false)
            } finally {
                mutex.unlock()
            }
            // Expire retained reports while the process stays alive; this never sends anything.
            while (true) {
                delay(60_000)
                if (mutex.tryLock()) {
                    try {
                        if (mutableState.value is ReportState.Failed && store.load() == null) mutableState.value = ReportState.Idle
                    } catch (_: Exception) {
                        mutableState.value = failure(false)
                    } finally {
                        mutex.unlock()
                    }
                }
            }
        }
    }

    fun send(metadata: Map<String, String>) =
        act {
            if (mutableState.value is ReportState.Failed) return@act
            mutableState.value = ReportState.Sending
            val report = DiagnosticReport.create(metadata, snapshot())
            store.save(report)
            submit(report)
        }

    fun retry() =
        act {
            val report = store.load()
            if (report == null) {
                mutableState.value = ReportState.Failed(false, "This report expired. Discard it and send a new report.")
            } else {
                mutableState.value = ReportState.Sending
                submit(report)
            }
        }

    fun discard() =
        act {
            store.discard()
            mutableState.value = ReportState.Idle
        }

    private fun submit(report: DiagnosticReport) {
        uploader.upload(report)
        store.discard()
        mutableState.value = ReportState.Sent(report.supportCode)
    }

    private fun act(block: suspend () -> Unit) {
        // Lock before scheduling so rapid taps cannot queue additional sends.
        if (mutableState.value == ReportState.Loading || !mutex.tryLock()) return
        scope.launch {
            try {
                block()
            } catch (_: Exception) {
                mutableState.value = failure(runCatching { store.load() != null }.getOrDefault(false))
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun failure(retry: Boolean) =
        ReportState.Failed(
            retry,
            if (retry) "Check Wi-Fi or a hotspot and retry. The service may be temporarily unavailable." else "Could not prepare diagnostics. Discard and try again.",
        )
}

object DiagnosticReports {
    val configured: Boolean get() = BuildConfig.SENTRY_DSN.isNotBlank()

    @Volatile private var instance: DiagnosticReportRepository? = null

    fun get(context: Context): DiagnosticReportRepository =
        instance ?: synchronized(this) {
            instance ?: DiagnosticReportRepository(
                PendingReportStore(java.io.File(context.noBackupFilesDir, "diagnostics/reports")),
                SentryReportUploader(BuildConfig.SENTRY_DSN),
                DiagnosticLog::snapshot,
            ).also { instance = it }
        }

    fun metadata(connection: String) =
        mapOf(
            "app_version" to BuildConfig.VERSION_NAME,
            "device_model" to Build.MODEL,
            "android_version" to Build.VERSION.RELEASE,
            "connection_state" to connection,
        )
}
