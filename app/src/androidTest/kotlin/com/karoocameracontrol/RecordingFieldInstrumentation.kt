package com.karoocameracontrol

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.karoocameracontrol.integrations.gopro.GoProManager
import com.karoocameracontrol.integrations.gopro.GoProRecordingDataType
import com.karoocameracontrol.integrations.gopro.recordingFieldView
import com.karoocameracontrol.recording.RecordingFieldAction
import com.karoocameracontrol.recording.RecordingFieldReceiver
import com.karoocameracontrol.recording.RecordingFieldState
import com.karoocameracontrol.recording.RecordingFieldStatus
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import java.util.concurrent.atomic.AtomicInteger

/** Applies production RemoteViews on an isolated emulator; no fake BLE or personal camera. */
class RecordingFieldInstrumentation : Instrumentation() {
    private var scene = "confirmed"
    private var capture = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        scene = arguments?.getString("scene") ?: scene
        capture = arguments?.getString("capture") == "true"
        start()
    }

    override fun onStart() {
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                val receiver = targetContext.packageManager.getReceiverInfo(ComponentName(targetContext, RecordingFieldReceiver::class.java), 0)
                check(!receiver.exported) { "Recording receiver must be private" }
                val parent = LinearLayout(activity)
                val ready = RecordingFieldState(RecordingFieldStatus.READY, RecordingFieldAction.START)
                val remote = recordingFieldView(activity, ready, 480 to 288, false)
                val view = remote.apply(activity, parent)
                val action = view.findViewById<Button>(R.id.recording_field_action)
                check(action.visibility == View.VISIBLE && action.isEnabled && action.hasOnClickListeners())
                // Reuse the same host view just as Karoo may do: inactive states remove old actions.
                recordingFieldView(activity, RecordingFieldState(RecordingFieldStatus.STOPPING), 480 to 288, false).reapply(activity, view)
                check(action.visibility == View.GONE && !action.isEnabled)
                recordingFieldView(activity, ready, 480 to 288, true).reapply(activity, view)
                check(!action.isEnabled)
                recordingFieldView(activity, ready, 120 to 80, false).reapply(activity, view)
                check(action.visibility == View.GONE)
                check(view.findViewById<TextView>(R.id.recording_field_status).text.toString() == activity.getString(R.string.recording_field_larger))
                recordingFieldView(activity, ready, 480 to 288, false).reapply(activity, view)
                check(action.visibility == View.VISIBLE && action.isEnabled)
                // Production receiver sees a disconnected emulator camera; a click must be safe.
                action.performClick()

                val states =
                    when (scene) {
                        "pending" -> listOf(RecordingFieldState(RecordingFieldStatus.STARTING, RecordingFieldAction.STOP), RecordingFieldState(RecordingFieldStatus.STOPPING))
                        "unavailable" -> listOf(RecordingFieldState(RecordingFieldStatus.DISCONNECTED), RecordingFieldState(RecordingFieldStatus.UNKNOWN))
                        "failure" -> listOf(RecordingFieldState(RecordingFieldStatus.FAILED, RecordingFieldAction.START, "Camera command timed out"), RecordingFieldState(RecordingFieldStatus.VIDEO_REQUIRED))
                        else -> listOf(ready, RecordingFieldState(RecordingFieldStatus.RECORDING, RecordingFieldAction.STOP, seconds = 65))
                    }
                val host =
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.BLACK)
                    }
                host.addView(
                    TextView(activity).apply {
                        text = "GoPro Recording Controls"
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    },
                    LinearLayout.LayoutParams(480, 64),
                )
                states.forEach { state ->
                    host.addView(recordingFieldView(activity, state, 480 to 288, false).apply(activity, host), LinearLayout.LayoutParams(480, 288))
                    host.addView(View(activity).apply { setBackgroundColor(Color.GRAY) }, LinearLayout.LayoutParams(480, 2))
                }
                activity.setContentView(host)
            }
            waitForIdleSync()
            if (capture) {
                sendStatus(0, Bundle().apply { putString("stream", "READY: $scene screenshot") })
                Thread.sleep(15000)
            }
            check(!GoProManager.getInstance(targetContext).recordingCommands.state.value.busy)
            check(GoProManager.getInstance(targetContext).recordingCommands.manualStopCount.value == 0L)
            val updates = AtomicInteger()
            val emitter =
                ViewEmitter(
                    targetContext.packageName,
                    object : IHandler.Stub() {
                        override fun onNext(bundle: Bundle) {
                            if (bundle.containsKey("view")) updates.incrementAndGet()
                        }

                        override fun onError(error: String?) {
                            error("Field error: $error")
                        }

                        override fun onComplete() = Unit
                    },
                )
            runOnMainSync {
                GoProRecordingDataType("karoo-camera-control").startView(
                    targetContext,
                    ViewConfig(60 to 15, 480 to 288, 18),
                    emitter,
                )
            }
            Thread.sleep(1200)
            check(updates.get() >= 2) { "Field must emit current state once per second" }
            runOnMainSync { emitter.cancel() }
            val cancelledAt = updates.get()
            Thread.sleep(1200)
            check(updates.get() == cancelledAt) { "Removed field leaked rendering job" }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: private receiver, RemoteViews rendering/reapply, preview and small-size guards, safe disconnected PendingIntent click, 1Hz emissions and cancellation; scene=$scene") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${e.stackTraceToString()}") })
        }
    }
}
