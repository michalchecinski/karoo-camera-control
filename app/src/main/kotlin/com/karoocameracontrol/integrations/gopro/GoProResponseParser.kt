package com.karoocameracontrol.integrations.gopro

import java.io.ByteArrayOutputStream
import java.util.UUID

class GoProResponseParser {
    private val responseBuffer = ByteArrayOutputStream()
    private var expectedResponseLength = -1

    /**
     * Processes a BLE characteristic change event.
     * Handles packet reassembly for GoPro's packetized response protocol.
     * Returns the fully reassembled data packet if complete, or null if waiting for more packets.
     */
    fun processCharacteristicChange(
        uuid: UUID,
        value: ByteArray,
    ): ByteArray? {
        if (uuid != GoProUUID.QUERY_RESPONSE && uuid != GoProUUID.COMMAND_RESPONSE) {
            return null
        }

        if (value.isEmpty()) return null

        val header = value[0].toInt() and 0xFF

        if ((header and 0x80) == 0x80) {
            // Continuation packet
            // Header is 1 byte (0b1xxxxxxx), payload follows.
            // Check if we are expecting a continuation
            if (expectedResponseLength == -1) {
                // Unexpected continuation, discard
                return null
            }
            responseBuffer.write(value, 1, value.size - 1)
        } else {
            // Start packet
            responseBuffer.reset()
            var offset = 1

            if ((header and 0x20) == 0x20) {
                // 13-bit length
                if (value.size < 2) return null
                val lenHigh = header and 0x1F
                val lenLow = value[1].toInt() and 0xFF
                expectedResponseLength = (lenHigh shl 8) or lenLow
                offset = 2
            } else if ((header and 0x40) == 0x40) {
                // 5-bit length
                expectedResponseLength = header and 0x1F
            } else {
                // Standard length (also 5-bit in this context usually, or implied)
                expectedResponseLength = header and 0x1F
            }

            if (value.size > offset) {
                responseBuffer.write(value, offset, value.size - offset)
            }
        }

        // Check if we have the full packet
        if (expectedResponseLength > 0 && responseBuffer.size() >= expectedResponseLength) {
            val fullData = responseBuffer.toByteArray()
            // Reset for next
            responseBuffer.reset()
            expectedResponseLength = -1
            return fullData
        }

        return null
    }
}
