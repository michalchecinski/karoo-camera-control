package com.karoocameracontrol

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karoocameracontrol.alerts.CameraAlert
import com.karoocameracontrol.alerts.CameraAlertEffects
import com.karoocameracontrol.alerts.CameraAlertKind
import com.karoocameracontrol.alerts.RecordingOrigin
import com.karoocameracontrol.theme.AppTheme
import io.hammerhead.karooext.models.InRideAlert

/** Illustrates real SDK payloads on an AVD, explicitly not the proprietary Karoo renderer. */
class CameraAlertPreviewInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val samples =
                listOf(
                    CameraAlert(CameraAlertKind.STARTED, RecordingOrigin.MANUAL, visual = true, audible = false),
                    CameraAlert(CameraAlertKind.DISCONNECTED, visual = true, audible = false),
                    CameraAlert(CameraAlertKind.LOW_RESOURCES, battery = 20, capacitySeconds = 299, visual = true, audible = false),
                ).map { CameraAlertEffects.create(targetContext, it).filterIsInstance<InRideAlert>().single() }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runOnMainSync {
                activity.setContent {
                    AppTheme {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Camera alert previews", style = MaterialTheme.typography.titleMedium)
                            Text("Emulator preview. Native Karoo layout may differ.", style = MaterialTheme.typography.bodySmall)
                            samples.forEach { alert ->
                                val foreground = Color(targetContext.getColor(alert.textColor))
                                Row(
                                    Modifier.fillMaxWidth().background(Color(targetContext.getColor(alert.backgroundColor))).padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(painterResource(alert.icon), null, tint = foreground, modifier = Modifier.size(22.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(alert.title, color = foreground, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                                        alert.detail?.let { Text(it, color = foreground, fontSize = 12.sp, lineHeight = 16.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (samples.any { findText(uiAutomation.rootInActiveWindow, it.title) == null }) {
                check(SystemClock.elapsedRealtime() < deadline) { "Preview did not render all alerts" }
                SystemClock.sleep(100)
            }
            samples.forEach { alert ->
                val title = checkNotNull(findText(uiAutomation.rootInActiveWindow, alert.title))
                val detail = checkNotNull(findText(uiAutomation.rootInActiveWindow, checkNotNull(alert.detail)))
                for (node in listOf(title, detail)) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    check(bounds.top >= 48 && bounds.bottom <= 704 && bounds.left >= 0 && bounds.right <= 480) { "Clipped alert preview: $bounds" }
                }
            }
            waitForIdleSync()
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            check(bitmap.width == 480 && bitmap.height == 800) { "Use the isolated Karoo AVD" }
            targetContext.cacheDir.resolve("camera-alert-preview.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
            result.putString("stream", "PASS: three production alert payloads rendered without clipping; labelled emulator preview captured\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun findText(
        node: AccessibilityNodeInfo?,
        text: String,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
        return null
    }
}
