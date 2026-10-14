# GoPro wake-up on connection

Starting the app reconnects to the last saved camera. Establishing a Bluetooth
connection wakes a sleeping GoPro; there is no separate power-on command.
The camera must have power, wireless connections enabled, and still be advertising.
Open GoPro documents advertising for the first eight hours after sleep. A camera
that is no longer advertising must be turned on manually.

Startup uses a direct BLE connection for both new and saved cameras, with a
10-second timeout per attempt. Saved cameras use the existing retry loop rather
than Android's passive `autoConnect` mode, which can wait indefinitely and leave
subsequent startup requests ignored while the app stays in Connecting.

After service discovery, pairing, and subscribing to command responses, the app
polls **Get Hardware Info** (command `0x3C`) until the camera acknowledges success.
Only then does it register status updates, fetch initial state, and report the
camera as connected. This handles the period when Bluetooth is connected but the
camera is still booting.

Readiness probes retry after 500 ms for up to 30 seconds, including when a probe
times out or the camera returns a failure status. A failed connection attempt,
including an operation or readiness timeout, closes the connection and retries
after two seconds for a saved, bonded camera. Disconnect cancels the attempt.
Diagnostic logs record the start and completion of readiness and command result
codes, without recording the hardware-info payload.

## Validation

Unit tests cover an awake camera, boot-time failures followed by success, a probe
timeout followed by success, bounded readiness waiting, and cancellation.

Hardware validation is still required: pair a GoPro, put it to sleep, open the
app, and verify it wakes, reaches Connected, displays camera status, and accepts
recording commands. Repeat with an already awake camera and cancel while it is
connecting. Test an unavailable camera to verify retries stop on Disconnect.

References: [Open GoPro BLE setup](https://gopro.github.io/OpenGoPro/docs/ble/protocol/ble_setup/)
and the [Kotlin connection tutorial](https://github.com/gopro/OpenGoPro/blob/main/demos/kotlin/tutorial/app/src/main/java/com/example/open_gopro_tutorial/tutorials/Tutorial1ConnectBle.kt).
