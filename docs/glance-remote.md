# Glance camera remote

The connected camera screen prioritizes recording status while retaining the
standard Karoo Back control and fixed Record/Stop position. It uses a light
theme with teal actions. Record/Stop has a rounded left end and a flat right
edge flush with the screen.

- Stopped: Camera ready is prominent; there is no idle recording timer.
- Pending: Starting… or Stopping… is distinct from confirmed recording.
- Recording: the elapsed timer is prominent and includes hours when needed.
- Unknown camera state: new starts and capture changes wait for camera data.
- Failed commands: the existing snackbar reports the failure.

Mode and Preset are independent controls. Mode opens a Video / Photo /
Timelapse selection page and returns directly after the existing mode command.
Changing mode does not force a preset choice or send a separate preset command.
Preset opens the presets supplied by the camera for the current mode. Selected
rows retain a check mark and an accessible button action, including when the
current mode is selected again. Capture controls lock while recording or busy.

Radar and Descent each have their own status row. Their existing controllers
provide readiness and blocking reasons; an enabled setting is not presented
as proof that recording can start. Existing settings and their Back hierarchy
remain under the header gear.

The activity handles a short Android NAVIGATE_IN key press only while the
connected remote is open, using the same manual recording path as touch.
Held/repeated/cancelled presses and stale screen/connection actions are ignored.
Synthetic key events are testable on the emulator; the physical Karoo button
mapping and real camera behavior still require hardware validation.

This change does not replace Karoo's native ride page or change the existing
[ride-screen recording field](ride-screen-recording-controls.md). Dependencies,
recording ownership, pairing and GoPro command confirmation are unchanged.
See [recording controls](recording-controls.md) for behavior and verification.

![Ready remote](../screenshots/emulator-karoo-480x800/connected-layout-ready.png)
![Recording](../screenshots/emulator-karoo-480x800/descent-recording-active.png)
![Mode selection](../screenshots/emulator-karoo-480x800/camera-mode-selection.png)
![Preset selection](../screenshots/emulator-karoo-480x800/camera-preset-selection.png)

## Validation

The debug APK and test APK build, ktlint and Android lint pass (lint retains
existing warnings). All 78 JUnit tests and all 41 deterministic descent checks
pass. The isolated Karoo emulator passes the connected-screen runner and the
production settings-navigation runner, including the synthetic right-key path,
repeat suppression, selected-mode return, hardware-action scoping, activity
recreation, stale Forget protection, direct-touch Disconnect, and scanning
return. App-only captures are 480×656. Physical Karoo/GoPro QA remains pending.
