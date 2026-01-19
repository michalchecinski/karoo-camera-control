package com.karoocameracontrol.integrations.gopro

import android.util.Log

/**
 * Parsed status values from GoPro TLV responses.
 * Null values indicate the status was not present in the response.
 */
data class ParsedStatus(
    val isRecording: Boolean? = null,
    val recordingDuration: Int? = null,
    val batteryLevel: Int? = null,
    val remainingSpace: Long? = null,
    val remainingVideoTime: Int? = null,
    val cameraMode: Int? = null,
    val activePresetId: Int? = null
)

/**
 * Sequential TLV parser for GoPro status responses.
 *
 * GoPro status format: [ID (1 byte)] [Length (1 byte)] [Value (N bytes)]
 * This parser reads TLV entries sequentially, avoiding false positives
 * from value bytes matching status IDs.
 */
object GoProStatusParser {
    private const val TAG = "GoProStatusParser"

    // Status IDs
    private const val STATUS_ENCODING: Byte = 0x0A           // 10: Recording state
    private const val STATUS_VIDEO_PROGRESS: Byte = 0x0D     // 13: Recording duration
    private const val STATUS_REMAINING_VIDEO_TIME: Byte = 0x23 // 35
    private const val STATUS_ACTIVE_PRESET_GROUP: Byte = 0x2B // 43
    private const val STATUS_REMAINING_SPACE: Byte = 0x36    // 54
    private const val STATUS_BATTERY_LEVEL: Byte = 0x46      // 70
    private const val STATUS_FLAT_MODE: Byte = 0x60          // 96
    private const val STATUS_ACTIVE_PRESET_ID: Byte = 0x61   // 97

    /**
     * Parse a GoPro status response into structured data.
     * The response must start after any command/query header bytes.
     */
    fun parse(data: ByteArray): ParsedStatus {
        if (data.isEmpty()) return ParsedStatus()

        var isRecording: Boolean? = null
        var recordingDuration: Int? = null
        var batteryLevel: Int? = null
        var remainingSpace: Long? = null
        var remainingVideoTime: Int? = null
        var cameraMode: Int? = null
        var activePresetId: Int? = null

        var position = 0

        while (position < data.size - 1) { // Need at least ID + Length
            val statusId = data[position]
            val length = data[position + 1].toInt() and 0xFF

            // Validate we have enough data for the value
            if (position + 2 + length > data.size) {
                Log.w(TAG, "Incomplete TLV at position $position: need $length bytes, have ${data.size - position - 2}")
                break
            }

            val valueStart = position + 2
            val valueBytes = data.sliceArray(valueStart until valueStart + length)

            when (statusId) {
                STATUS_ENCODING -> {
                    if (length >= 1) {
                        isRecording = valueBytes[0] == 0x01.toByte()
                    }
                }

                STATUS_VIDEO_PROGRESS -> {
                    recordingDuration = readInt(valueBytes)
                }

                STATUS_REMAINING_VIDEO_TIME -> {
                    remainingVideoTime = readInt(valueBytes)
                }

                STATUS_ACTIVE_PRESET_GROUP -> {
                    cameraMode = readInt(valueBytes)
                }

                STATUS_REMAINING_SPACE -> {
                    remainingSpace = readLong(valueBytes)
                }

                STATUS_BATTERY_LEVEL -> {
                    if (length >= 1) {
                        batteryLevel = valueBytes[0].toInt() and 0xFF
                    }
                }

                STATUS_FLAT_MODE -> {
                    cameraMode = readInt(valueBytes)
                }

                STATUS_ACTIVE_PRESET_ID -> {
                    activePresetId = readInt(valueBytes)
                }
            }

            // Move to next TLV entry
            position += 2 + length
        }

        return ParsedStatus(
            isRecording = isRecording,
            recordingDuration = recordingDuration,
            batteryLevel = batteryLevel,
            remainingSpace = remainingSpace,
            remainingVideoTime = remainingVideoTime,
            cameraMode = cameraMode,
            activePresetId = activePresetId
        )
    }

    /**
     * Read an integer from value bytes (big-endian, variable length 1-4 bytes).
     */
    private fun readInt(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null

        return when (bytes.size) {
            1 -> bytes[0].toInt() and 0xFF
            2 -> ((bytes[0].toInt() and 0xFF) shl 8) or
                 (bytes[1].toInt() and 0xFF)
            3 -> ((bytes[0].toInt() and 0xFF) shl 16) or
                 ((bytes[1].toInt() and 0xFF) shl 8) or
                 (bytes[2].toInt() and 0xFF)
            4 -> ((bytes[0].toInt() and 0xFF) shl 24) or
                 ((bytes[1].toInt() and 0xFF) shl 16) or
                 ((bytes[2].toInt() and 0xFF) shl 8) or
                 (bytes[3].toInt() and 0xFF)
            else -> {
                // For longer values, read last 4 bytes
                val start = bytes.size - 4
                ((bytes[start].toInt() and 0xFF) shl 24) or
                ((bytes[start + 1].toInt() and 0xFF) shl 16) or
                ((bytes[start + 2].toInt() and 0xFF) shl 8) or
                (bytes[start + 3].toInt() and 0xFF)
            }
        }
    }

    /**
     * Read a long from value bytes (big-endian, variable length 1-8 bytes).
     */
    private fun readLong(bytes: ByteArray): Long? {
        if (bytes.isEmpty()) return null

        var result = 0L
        for (b in bytes) {
            result = (result shl 8) or (b.toLong() and 0xFF)
        }
        return result
    }
}
