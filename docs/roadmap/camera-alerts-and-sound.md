# Native camera alerts and audible confirmation

Status: **Implemented; physical Karoo/GoPro validation pending**. Priority: **2** in the [Karoo integration roadmap](README.md).

The delivered behavior, preferences, and validation commands are documented in
[native camera alerts and sound](../camera-alerts.md). The original proposal below
provides the acceptance criteria; recovery thresholds use hysteresis as described
in the implementation document.

## Description

Use Karoo-native ride alerts and the Karoo beeper to communicate camera events
while the rider stays on the ride screen. Follow the presentation approach used
by [karoofirefly](https://github.com/derstrassi/karoofirefly): an extension dispatches
`InRideAlert` for the visual message and `PlayBeepPattern` for sound.

karoofirefly's extension uses those public SDK effects for its zone-change and
manual-action alerts. Its unrelated internal light-control APIs are not needed
for camera alerts. Use a camera icon, short title, readable detail, and automatic
dismissal in the native Karoo alert surface.

## Proposed events

| Event | Visual message | Sound |
| --- | --- | --- |
| Confirmed start | Recording started, with manual/descent/radar/whole-ride origin when known | Short start pattern |
| Confirmed stop | Recording stopped | Different short stop pattern |
| Failed start or stop | Camera command failed; check the camera | Error pattern |
| Camera disconnects while recording or a command is pending | Camera connection lost; recording state unknown | Error pattern |
| Low battery | Camera battery low, with known percentage | Optional warning pattern |
| Low recording capacity | Little recording time remaining, with known estimate | Optional warning pattern |

Start/stop confirmation must follow a verified recording transition, not a button
press or BLE acknowledgement alone. The same rules apply to manual controls,
automations, and observed physical-shutter changes. An initial status snapshot
after connecting is not a new start or stop event. Losing Bluetooth does not
prove the camera stopped recording.

## Proposed settings and notification policy

Provide independent settings for recording confirmations, camera warnings, and
sound. Proposed defaults: camera warnings on, routine recording confirmations
off, and sound off. This gives riders control over repeated radar/descent clips.

Proposed initial thresholds are battery at or below 20% and remaining recording
capacity at or below five minutes. These are camera-reported values, not a
prediction of battery runtime. Alert once per threshold crossing, rearming only
after recovery; ignore unknown values. Deduplicate repeated failures and retry
loops, and group simultaneous warnings so they do not flood the ride screen.

Use brief confirmations (proposed three seconds) and longer error/warning
messages (proposed six seconds). Scope alerts and sound to an active or paused
ride, with no queued playback of old events after reconnect or process restart.
Failure messages in the app remain available regardless of alert preferences.

## Acceptance criteria

- Alerts render as native Karoo ride alerts, following the referenced app's approach.
- Sound is produced by the Karoo, with distinguishable start, stop, and error patterns.
- Settings persist; disabling sound keeps visual alerts functional and vice versa.
- No success confirmation occurs for rejected, timed-out, or cancelled commands.
- Unknown state, initial connection snapshots, retries, and threshold chatter
  do not produce misleading or repeated confirmations.
- Manual, radar, descent, and whole-ride events share one notification policy.
- Rendering, dismissal, audibility, and interaction with navigation/radar alerts
  are checked on a physical Karoo.

## References

- [karoofirefly alert and beep implementation](https://github.com/derstrassi/karoofirefly/blob/main/app/src/main/kotlin/io/github/derstrassi/karoofirefly/KarooLightControllerExtension.kt)
- [Official InRideAlert and PlayBeepPattern effects](https://github.com/hammerheadnav/karoo-ext/blob/master/lib/src/main/kotlin/io/hammerhead/karooext/models/KarooEffect.kt)
- [Current command confirmation](../recording-controls.md)
