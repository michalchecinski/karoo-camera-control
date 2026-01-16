package com.karoocameracontrol.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class GoProControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_RECORDING) {
            Log.d("GoProControlReceiver", "Toggle recording requested")
            GoProManager.getInstance(context).toggleRecording()
        }
    }

    companion object {
        const val ACTION_TOGGLE_RECORDING = "com.karoocameracontrol.action.TOGGLE_RECORDING"
    }
}
