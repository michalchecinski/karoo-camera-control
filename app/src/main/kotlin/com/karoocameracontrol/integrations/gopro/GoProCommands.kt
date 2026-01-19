package com.karoocameracontrol.integrations.gopro

object GoProCommands {

    // Command UUID actions
    object Recording {
        val START = byteArrayOf(0x03, 0x01, 0x01, 0x01)
        val STOP = byteArrayOf(0x03, 0x01, 0x01, 0x00)
    }

    object Modes {
        fun setMode(modeId: Int): ByteArray {
            val m1 = (modeId shr 8).toByte()
            val m2 = (modeId and 0xFF).toByte()
            // Length 0x04, Cmd 0x3E, Len 0x02, Val...
            return byteArrayOf(0x04, 0x3E, 0x02, m1, m2)
        }
    }

    object Presets {
        // Feature F5, Action 72, Proto: 08 01 (register_preset_status=true)
        val GET_AVAILABLE = byteArrayOf(0x04, 0xF5.toByte(), 0x72.toByte(), 0x08, 0x01)

        fun load(presetId: Int): ByteArray {
            val v1 = (presetId shr 24).toByte()
            val v2 = (presetId shr 16).toByte()
            val v3 = (presetId shr 8).toByte()
            val v4 = (presetId and 0xFF).toByte()
            // Length 0x06, Cmd 0x40, Len 0x04, Val...
            return byteArrayOf(0x06, 0x40, 0x04, v1, v2, v3, v4)
        }
    }

    // Query UUID actions
    object Query {
        val REGISTER_UPDATES = byteArrayOf(0x09, 0x53, 0x0A, 0x0D, 0x46, 0x36, 0x23, 0x2B, 0x60, 0x61)

        val STATUS = byteArrayOf(0x02, 0x13, 0x0A)
        val DURATION = byteArrayOf(0x02, 0x13, 0x0D)
        val BATTERY = byteArrayOf(0x02, 0x13, 0x46)
        val REMAINING_SPACE = byteArrayOf(0x02, 0x13, 0x36)
        val REMAINING_VIDEO_TIME = byteArrayOf(0x02, 0x13, 0x23)
        val MODE = byteArrayOf(0x02, 0x13, 0x2B)
        val PRESET_GROUP = byteArrayOf(0x02, 0x13, 0x60)
        val ACTIVE_PRESET = byteArrayOf(0x02, 0x13, 0x61)
    }
}
