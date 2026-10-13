package com.karoocameracontrol.radar

import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState

object RadarData {
    fun presence(state: StreamState): RadarPresence {
        val values = (state as? StreamState.Streaming)?.dataPoint?.values ?: return RadarPresence.UNKNOWN
        val error = values[DataType.Field.RADAR_ERROR]
        if (error != null && (!error.isFinite() || error != 0.0)) return RadarPresence.UNKNOWN
        val threat = values[DataType.Field.RADAR_THREAT_LEVEL]
        if (threat == null || !threat.isFinite() || threat < 0.0) return RadarPresence.UNKNOWN
        // Zero threat is the radar's clear report; optional range slots can retain old values.
        if (threat == 0.0) return RadarPresence.CLEAR
        return RadarPresence.VEHICLE
    }
}
