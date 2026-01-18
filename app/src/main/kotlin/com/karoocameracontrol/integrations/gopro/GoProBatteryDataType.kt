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

class GoProBatteryDataType(
    extension: String,
    private val context: Context
) : DataTypeImpl(extension, "gopro-battery") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val manager = GoProManager.getInstance(context)
            manager.batteryLevel.collectLatest { level ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId,
                            mapOf(DataType.Field.SINGLE to level.toDouble())
                        )
                    )
                )
            }
        }
        emitter.setCancellable {
            job.cancel()
        }
    }
}
