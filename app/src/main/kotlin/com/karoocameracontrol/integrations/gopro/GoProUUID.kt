package com.karoocameracontrol.integrations.gopro

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object GoProUUID {
    val GOPRO_SERVICE: Uuid = Uuid.parse("0000FEA6-0000-1000-8000-00805F9B34FB")

    // Correct Base UUID: b5f9XXXX-aa8d-11e3-9046-0002a5d5c51b
    val WIFI_AP_PASSWORD: Uuid = Uuid.parse("b5f90003-aa8d-11e3-9046-0002a5d5c51b")
    val COMMAND: Uuid = Uuid.parse("b5f90072-aa8d-11e3-9046-0002a5d5c51b")
    val COMMAND_RESPONSE: Uuid = Uuid.parse("b5f90073-aa8d-11e3-9046-0002a5d5c51b")
    val SETTING: Uuid = Uuid.parse("b5f90074-aa8d-11e3-9046-0002a5d5c51b")
    val SETTING_RESPONSE: Uuid = Uuid.parse("b5f90075-aa8d-11e3-9046-0002a5d5c51b")
    val QUERY: Uuid = Uuid.parse("b5f90076-aa8d-11e3-9046-0002a5d5c51b")
    val QUERY_RESPONSE: Uuid = Uuid.parse("b5f90077-aa8d-11e3-9046-0002a5d5c51b")

    // Client Characteristic Configuration Descriptor UUID
    val CCCD: Uuid = Uuid.parse("00002902-0000-1000-8000-00805F9B34FB")
}
