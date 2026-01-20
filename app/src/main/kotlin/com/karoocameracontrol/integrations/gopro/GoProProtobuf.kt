package com.karoocameracontrol.integrations.gopro

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

data class Preset(
    val id: Int = 0,
    val mode: Int = 0,
    val titleId: Int = 0,
    val titleNumber: Int = 0,
    val userDefined: Boolean = false,
    val iconId: Int = 0,
    val isModified: Boolean = false,
    val isFixed: Boolean = false,
    val customName: String? = null,
    val isVisible: Boolean = true,
)

data class PresetGroup(
    val id: Int = 0,
    val presets: List<Preset> = emptyList(),
    val canAddPreset: Boolean = false,
    val iconId: Int = 0,
)

object GoProProtobuf {
    fun parseNotifyPresetStatus(data: ByteArray): List<PresetGroup> {
        val buffer = ByteBuffer.wrap(data)
        val groups = mutableListOf<PresetGroup>()

        while (buffer.hasRemaining()) {
            val (tag, type) = readTag(buffer)
            if (tag == 1) { // preset_group_array
                val length = readVarint(buffer)
                val groupData = ByteArray(length)
                buffer.get(groupData)
                groups.add(parsePresetGroup(ByteBuffer.wrap(groupData)))
            } else {
                skipField(buffer, type)
            }
        }
        return groups
    }

    private fun parsePresetGroup(buffer: ByteBuffer): PresetGroup {
        var id = 0
        val presets = mutableListOf<Preset>()
        var canAddPreset = false
        var iconId = 0

        while (buffer.hasRemaining()) {
            val (tag, type) = readTag(buffer)
            when (tag) {
                1 -> id = readVarint(buffer) // id
                2 -> { // preset_array
                    val length = readVarint(buffer)
                    val presetData = ByteArray(length)
                    buffer.get(presetData)
                    presets.add(parsePreset(ByteBuffer.wrap(presetData)))
                }
                3 -> canAddPreset = readVarint(buffer) != 0 // can_add_preset
                4 -> iconId = readVarint(buffer) // icon
                else -> skipField(buffer, type)
            }
        }
        return PresetGroup(id, presets, canAddPreset, iconId)
    }

    private fun parsePreset(buffer: ByteBuffer): Preset {
        var id = 0
        var mode = 0
        var titleId = 0
        var titleNumber = 0
        var userDefined = false
        var iconId = 0
        var isModified = false
        var isFixed = false
        var customName: String? = null
        var isVisible = true

        while (buffer.hasRemaining()) {
            val (tag, type) = readTag(buffer)
            when (tag) {
                1 -> id = readVarint(buffer)
                2 -> mode = readVarint(buffer)
                3 -> titleId = readVarint(buffer)
                4 -> titleNumber = readVarint(buffer)
                5 -> userDefined = readVarint(buffer) != 0
                6 -> iconId = readVarint(buffer)
                // 7 is setting_array, skipping for now as not required for "list" per se, unless needed.
                8 -> isModified = readVarint(buffer) != 0
                9 -> isFixed = readVarint(buffer) != 0
                10 -> { // custom_name
                    val length = readVarint(buffer)
                    val nameBytes = ByteArray(length)
                    buffer.get(nameBytes)
                    customName = String(nameBytes, StandardCharsets.UTF_8)
                }
                11 -> isVisible = readVarint(buffer) != 0
                else -> skipField(buffer, type)
            }
        }
        return Preset(id, mode, titleId, titleNumber, userDefined, iconId, isModified, isFixed, customName, isVisible)
    }

    // Helpers

    private fun readTag(buffer: ByteBuffer): Pair<Int, Int> {
        val key = readVarint(buffer)
        val tag = key ushr 3
        val type = key and 0x07
        return Pair(tag, type)
    }

    private fun readVarint(buffer: ByteBuffer): Int {
        var value = 0
        var shift = 0
        while (true) {
            val b = buffer.get().toInt()
            value = value or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return value
    }

    private fun skipField(
        buffer: ByteBuffer,
        type: Int,
    ) {
        when (type) {
            0 -> readVarint(buffer) // Varint
            1 -> buffer.position(buffer.position() + 8) // 64-bit
            2 -> { // Length-delimited
                val length = readVarint(buffer)
                buffer.position(buffer.position() + length)
            }
            5 -> buffer.position(buffer.position() + 4) // 32-bit
            else -> {} // Should not happen for this subset
        }
    }
}
