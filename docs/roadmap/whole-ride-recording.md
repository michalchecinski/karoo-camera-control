# Opt-in whole-ride recording

Status: **Planned**. Priority: **3** in the [Karoo integration roadmap](README.md).

## Description

Offer a persistent, off-by-default setting to start GoPro video recording when
a Karoo ride starts and stop it when that ride finishes. The rider should not
need to remember a separate camera start before each ride.

Expose the option under **More options → Whole-ride recording**, with a short
explanation of camera battery/storage use, connection requirements, and manual
override. Closing the settings screen must not stop monitoring.

## Proposed lifecycle

| Situation | Proposed behaviour |
| --- | --- |
| A new ride enters Recording | Start once if the enabled feature has a connected, ready GoPro in Video mode. |
| Camera unavailable, wrong mode, or command busy at ride start | Explain the missed start; do not change modes or repeatedly issue Start. |
| Ride pauses | Keep recording by default. |
| Optional Stop while paused is enabled | Stop only this feature's clip; resume recording on ride resume if not manually suppressed. |
| Ride finishes | Stop only a recording owned by this feature, including cancellation/cleanup of a pending start. |
| Feature is disabled mid-ride | Stop its owned recording; release ownership after confirmation. |
| Rider stops manually, in the app/field or on the camera | Suppress whole-ride starts for the rest of that ride, including pause/resume. |
| Camera disconnects | Release ownership and report uncertainty; do not assume the camera stopped. |
| Reconnect or extension process restart during a ride | Observe state without claiming an existing recording or automatically restarting. |
| Feature is enabled during an existing ride | Arm for the next new ride; the rider can start the current recording manually. |

These are proposed defaults rather than approved product decisions. The optional
pause setting should explain whether it includes Karoo auto-pauses; the proposal
is to apply it to both manual and automatic pauses, with duplicate-event guards.
Starting a ride cannot guarantee video from its first instant: BLE command and
camera confirmation latency still apply.

## Interaction with other recording modes

Reuse the shared command controller with a distinct whole-ride owner. Never
stop a pre-existing manual, descent, or radar recording. A manual Start transfers
control to the rider, so ride finish must not subsequently stop that manual clip.

Proposed first-version policy: make whole-ride recording mutually exclusive with
descent and radar automatic recording settings. Explain the conflict and require
an explicit settings choice rather than silently disabling another feature.
This avoids an existing short-clip automation stopping video during a whole ride.
Manual controls remain available in every mode. If simultaneous automation is
later desired, define arbitration before implementation; ownership alone is
not a complete priority policy.

## Integration and failure handling

Subscribe to Karoo `RideState` through the extension, using the existing SDK and
the existing camera acknowledgement/state-confirmation path. Require known
recording state and mode before starting. Reuse the saved-camera connection flow
where appropriate; first pairing still requires normal user setup.

Do not repeatedly retry automatic starts. Failed or ambiguous owned stops need
bounded retry behaviour while connected, a visible failure state, and working
manual Stop. Define cleanup for a ride ending while Start is pending. Process
termination or camera disconnection cannot guarantee a stop; make that limitation
clear in settings. Integrate [native alerts](camera-alerts-and-sound.md) and the
[automation status field](automation-status-field.md) when available.

## Acceptance criteria

- The feature is off by default and settings survive app/device restarts.
- New ride start/finish produces confirmed camera start/stop while the app is closed.
- Duplicate ride events do not create duplicate commands.
- Default pause behaviour and optional pause/resume recording work as described.
- Manual suppression lasts until a genuinely new ride, not merely a reconnect.
- Ownership guards preserve manual clips and other automations' clips.
- Missing camera, wrong mode, ambiguous commands, disconnect/reconnect, restart,
  and finish-during-start paths are covered and explained to the rider.
- Real Karoo/GoPro checks verify background operation and ride-state transitions.

## References

- [Official Karoo event models, including RideState](https://github.com/hammerheadnav/karoo-ext/blob/master/lib/src/main/kotlin/io/hammerhead/karooext/models/KarooEvent.kt)
- [Existing descent ownership rules](../descent-recording.md)
- [Existing radar lifecycle and connection limitations](../radar-recording.md)
