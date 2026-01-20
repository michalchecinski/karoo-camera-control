package com.karoocameracontrol.integrations.gopro

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GoProRecTimeDataType(
    extension: String,
    private val context: Context,
) : DataTypeImpl(extension, "gopro-rec-time") {
    override fun startStream(emitter: Emitter<StreamState>) {
        val job =
            CoroutineScope(Dispatchers.IO).launch {
                val manager = GoProManager.getInstance(context)
                manager.recordingDuration.collectLatest { duration ->
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(
                                dataTypeId,
                                mapOf(DataType.Field.SINGLE to duration.toDouble()),
                            ),
                        ),
                    )
                }
            }
        emitter.setCancellable {
            job.cancel()
        }
    }
}
