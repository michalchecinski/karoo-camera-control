package com.karoocameracontrol.integrations.gopro

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.karoocameracontrol.R
import com.karoocameracontrol.recording.ManualRecording
import com.karoocameracontrol.recording.RecordingFieldAction
import com.karoocameracontrol.recording.RecordingFieldReceiver
import com.karoocameracontrol.recording.RecordingFieldState
import com.karoocameracontrol.recording.RecordingFieldStatus
import com.karoocameracontrol.recording.recordingFieldState
import com.karoocameracontrol.recording.recordingFieldTimer
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GoProRecordingDataType(extension: String) : DataTypeImpl(extension, "gopro-recording-controls") {
    override fun startView(
        context: Context,
        config: ViewConfig,
        emitter: ViewEmitter,
    ) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val job =
            CoroutineScope(Dispatchers.Main.immediate).launch {
                val camera = GoProManager.getInstance(context.applicationContext)
                while (isActive) {
                    val state =
                        if (config.preview) {
                            RecordingFieldState(RecordingFieldStatus.READY, RecordingFieldAction.START)
                        } else {
                            recordingFieldState(ManualRecording.snapshot(camera))
                        }
                    emitter.updateView(recordingFieldView(context, state, config.viewSize, config.preview))
                    // SDK drops emissions faster than 1Hz. Read the latest complete snapshot
                    // on every tick so quick transitions cannot leave a stale final view.
                    delay(1000)
                }
            }
        emitter.setCancellable { job.cancel() }
    }
}

/** Shared renderer used by the actual field and emulator RemoteViews integration checks. */
fun recordingFieldView(
    context: Context,
    state: RecordingFieldState,
    size: Pair<Int, Int>,
    preview: Boolean,
): RemoteViews {
    val density = context.resources.displayMetrics.density
    val tooSmall = size.first / density < 160 || size.second / density < 112
    val status =
        when (state.status) {
            RecordingFieldStatus.DISCONNECTED -> context.getString(R.string.recording_field_disconnected)
            RecordingFieldStatus.UNKNOWN -> context.getString(R.string.recording_field_unknown)
            RecordingFieldStatus.VIDEO_REQUIRED -> context.getString(R.string.recording_field_video_required)
            RecordingFieldStatus.READY -> context.getString(R.string.recording_field_ready)
            RecordingFieldStatus.STARTING -> context.getString(R.string.recording_field_starting)
            RecordingFieldStatus.RECORDING -> context.getString(R.string.recording_field_recording, recordingFieldTimer(state.seconds))
            RecordingFieldStatus.STOPPING -> context.getString(R.string.recording_field_stopping)
            RecordingFieldStatus.BUSY -> context.getString(R.string.recording_field_busy)
            RecordingFieldStatus.FAILED -> context.getString(R.string.recording_field_failed, state.error.orEmpty())
        }
    return RemoteViews(context.packageName, R.layout.recording_field).apply {
        setTextViewText(
            R.id.recording_field_status,
            if (tooSmall) context.getString(R.string.recording_field_larger) else status,
        )
        val action = state.action.takeUnless { tooSmall }
        setViewVisibility(R.id.recording_field_action, if (action == null) View.GONE else View.VISIBLE)
        setBoolean(R.id.recording_field_action, "setEnabled", action != null && !preview)
        if (action != null) {
            val label =
                if (action == RecordingFieldAction.START) {
                    R.string.recording_field_start
                } else if (state.status == RecordingFieldStatus.STARTING) {
                    R.string.recording_field_cancel
                } else {
                    R.string.recording_field_stop
                }
            setTextViewText(R.id.recording_field_action, context.getString(label))
        }
        // Clear the action explicitly: Karoo may reapply this RemoteViews to an existing view.
        val pendingIntent =
            if (action == null || preview) {
                null
            } else {
                PendingIntent.getBroadcast(
                    context,
                    action.ordinal,
                    Intent(context, RecordingFieldReceiver::class.java).setAction(
                        if (action == RecordingFieldAction.START) RecordingFieldReceiver.ACTION_START else RecordingFieldReceiver.ACTION_STOP,
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        setOnClickPendingIntent(R.id.recording_field_action, pendingIntent)
    }
}
