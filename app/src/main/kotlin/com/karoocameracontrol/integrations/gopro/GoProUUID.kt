package com.karoocameracontrol.integrations.gopro

import java.util.UUID

object GoProUUID {
    val GOPRO_SERVICE = UUID.fromString("0000FEA6-0000-1000-8000-00805F9B34FB")
    
    // Correct Base UUID: b5f9XXXX-aa8d-11e3-9046-0002a5d5c51b
    val WIFI_AP_PASSWORD = UUID.fromString("b5f90003-aa8d-11e3-9046-0002a5d5c51b")
    val COMMAND = UUID.fromString("b5f90072-aa8d-11e3-9046-0002a5d5c51b")
    val COMMAND_RESPONSE = UUID.fromString("b5f90073-aa8d-11e3-9046-0002a5d5c51b")
    val SETTING = UUID.fromString("b5f90074-aa8d-11e3-9046-0002a5d5c51b")
    val SETTING_RESPONSE = UUID.fromString("b5f90075-aa8d-11e3-9046-0002a5d5c51b")
    val QUERY = UUID.fromString("b5f90076-aa8d-11e3-9046-0002a5d5c51b")
    val QUERY_RESPONSE = UUID.fromString("b5f90077-aa8d-11e3-9046-0002a5d5c51b")
}
