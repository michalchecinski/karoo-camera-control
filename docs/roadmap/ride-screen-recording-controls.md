# Recording controls from the ride screen

Status: **Implemented; physical Karoo/GoPro QA pending**. See [usage and verification](../ride-screen-recording-controls.md). Priority: **1** in the [Karoo integration roadmap](README.md).

## Description

Let the rider start and stop a connected GoPro without opening the camera app
and leaving the Karoo ride screen. Provide an interactive camera data field
that can be added to a ride profile. The field shows the current recording
state and a large action suitable for quick use during a ride.

## Implemented experience

- When ready, show **Start recording**.
- While a start is pending, show **Starting…** and keep **Stop / cancel** available.
- Once recording is confirmed, show **Stop recording** and the recording timer.
- While stopping, show **Stopping…**; avoid overlapping duplicate commands.
- When disconnected or the state is unknown, explain why controls are unavailable.
- Show failed commands in the field; native alerts can additionally report them
  when [camera alerts](camera-alerts-and-sound.md) are available.

The first version focuses on Video recording. It must not change the camera's
mode or preset automatically. Photo, preset selection, and multi-camera control
remain separate possible extensions. Fields smaller than 160 × 112 dp explain that a larger field is required.

## Behaviour and integration

Use the same manual command path as the app, including radar/descent overrides.
A ride-screen Stop must cancel a pending start and suppress automation exactly
as an app Stop does. Display confirmed camera state separately from command
progress, and keep both synchronized with the app and physical camera shutter.

The field follows Karoo's official custom graphical data-field example through
`DataTypeImpl.startView` and RemoteViews on the pinned SDK. Android host actions
are verified in the emulator; native Karoo touch forwarding still requires
physical target-firmware validation.
Keep existing numeric data types intact; add a dedicated graphical control field.

An optional later enhancement is a **Toggle recording** Bonus Action that users
can assign through Karoo's supported button mappings. The public Bonus Action
API is documented as available since `karoo-ext` 1.1.7, while this project uses
1.1.3. This enhancement therefore requires explicit approval for an SDK update
and hardware/firmware compatibility testing. It is not a prerequisite for the
proposed graphical field.

## Acceptance criteria

- Start and Stop work from the ride screen with the app activity closed.
- Pending, confirmed, failed, unknown, and disconnected states are distinct.
- Repeated presses do not create competing camera commands; Stop remains
  effective while a start is awaiting confirmation.
- Manual override works for radar, descent, and future whole-ride recording.
- Camera-side changes and reconnects update the field without stale controls.
- Supported field sizes, day/night presentation, and touch behaviour are
  checked on a physical Karoo; existing fields and app controls still work.

## References

- [Official custom graphical field example](https://github.com/hammerheadnav/karoo-ext/blob/master/app/src/main/kotlin/io/hammerhead/sampleext/extension/CustomSpeedDataType.kt)
- [Official Bonus Action API and version requirement](https://github.com/hammerheadnav/karoo-ext/blob/master/lib/src/main/kotlin/io/hammerhead/karooext/models/BonusAction.kt)
- [Current recording controls](../recording-controls.md)
