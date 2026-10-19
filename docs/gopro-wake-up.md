# GoPro wake-up on connection

Opening the app no longer connects automatically. Choose Connect or use
[Wake & record](connection-keep-alive.md) in the app or ride-screen field to wake
the saved camera and record in one action. Active-ride readiness can also wake
a camera when radar, descent or the saved whole-ride fallback requires it.
Establishing a Bluetooth connection wakes a sleeping GoPro; there is no separate
power-on command. The camera must have power, wireless connections enabled and
still be advertising. Open GoPro documents advertising for the first eight hours
after sleep. A camera no longer advertising must be turned on manually.
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
Readiness retries apply only while the original BLE connection is current. If
the link is lost or replaced, the readiness loop exits at its next connection
check instead of waiting out the 30-second window. An in-flight response can
still take up to its five-second timeout before that check; saved cameras then
enter the existing two-second reconnection delay.
Diagnostic logs record the start and completion of readiness and command result
codes, without recording the hardware-info payload.

After setup reaches Connected, [ride-scoped keep-alive](connection-keep-alive.md)
runs every three seconds only while active-ride readiness requires it. Manual
wake-and-record requests are bounded to 60 seconds, including any shared readiness
wait; they cancel their owned connection attempt on failure or cancellation.
Explicit Connect retains the saved-device retry behavior described above.

## Validation

Unit tests cover an awake camera, boot-time failures followed by success, a probe
timeout followed by success, bounded readiness waiting, cancellation, and link
loss before a probe, during a failed probe, or after an acknowledged probe.

Hardware validation is still required: pair a GoPro, put it to sleep, open the
app, and verify it wakes, reaches Connected, displays camera status, and accepts
recording commands. Repeat with an already awake camera and cancel while it is
connecting. Test an unavailable camera to verify retries stop on Disconnect.

References: [Open GoPro BLE setup](https://gopro.github.io/OpenGoPro/docs/ble/protocol/ble_setup/)
and the [Kotlin connection tutorial](https://github.com/gopro/OpenGoPro/blob/main/demos/kotlin/tutorial/app/src/main/java/com/example/open_gopro_tutorial/tutorials/Tutorial1ConnectBle.kt).
