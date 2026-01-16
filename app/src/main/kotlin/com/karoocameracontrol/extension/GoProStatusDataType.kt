package com.karoocameracontrol.extension

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.karoocameracontrol.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.glance.Image

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class GoProStatusDataType(
    extension: String,
    private val context: Context
) : DataTypeImpl(extension, "gopro-status") {

    private val glance = GlanceRemoteViews()

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val manager = GoProManager.getInstance(context)
            manager.isRecording.collectLatest { isRecording ->
                val value = if (isRecording) 1.0 else 0.0
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId,
                            mapOf(DataType.Field.SINGLE to value)
                        )
                    )
                )
            }
        }
        emitter.setCancellable {
            job.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(formatDataTypeId = DataType.Type.ELAPSED_TIME))
            awaitCancellation()
        }
        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            val manager = GoProManager.getInstance(context)
            manager.isRecording.collectLatest { isRecording ->
                val result = glance.compose(context, DpSize.Unspecified) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(if (isRecording) Color(0xFFFFCDD2) else Color(0xFFF0F0F0)) // Light Red or Light Gray
                            .clickable(
                                actionSendBroadcast(
                                    Intent(context, GoProControlReceiver::class.java).apply {
                                        action = GoProControlReceiver.ACTION_TOGGLE_RECORDING
                                    }
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Using a placeholder icon or text since I don't have custom drawables yet.
                            // R.drawable.ic_launcher_foreground is available.
                            Image(
                                provider = ImageProvider(R.drawable.ic_launcher_foreground), // TODO: Use better icon
                                contentDescription = "Status",
                                modifier = GlanceModifier.size(48.dp),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                text = if (isRecording) "REC" else "STOP",
                                style = TextStyle(
                                    color = androidx.glance.unit.ColorProvider(Color.Black),
                                    fontSize = 24.sp
                                )
                            )
                        }
                    }
                }
                emitter.updateView(result.remoteViews)
            }
        }
        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
        }
    }
}