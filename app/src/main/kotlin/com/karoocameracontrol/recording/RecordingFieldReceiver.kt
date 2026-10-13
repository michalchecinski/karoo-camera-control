package com.karoocameracontrol.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only an explicit immutable PendingIntent from our field can reach this private receiver. */
class RecordingFieldReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action =
            when (intent.action) {
                ACTION_START -> RecordingFieldAction.START
                ACTION_STOP -> RecordingFieldAction.STOP
                else -> return
            }
        ManualRecording.perform(context.applicationContext, action)
    }

    companion object {
        const val ACTION_START = "com.karoocameracontrol.recording.START"
        const val ACTION_STOP = "com.karoocameracontrol.recording.STOP"
    }
}
