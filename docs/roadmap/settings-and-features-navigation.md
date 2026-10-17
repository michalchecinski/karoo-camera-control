# Settings and features navigation

Status: **Implemented; physical-device validation pending**. The first release
replaces More options with the gear-opened hub. See [Settings and features](../settings-and-features.md)
for current usage. This document retains the design and future extension plan.

## Problem and goal

The current six-entry overflow menu mixes camera alerts, recording automation,
connection actions and feedback. Its existing Karoo-resolution screenshot
already fills most of the app area. Adding every planned feature to that list
would make configuration harder to find and leave little room for useful status.

Replace the dropdown with a full-screen settings hub, reached through a gear
icon on the main camera screen. Organize settings by rider task and provide
predictable parent navigation. Keep the main screen focused on camera control.

## First release

The first release reorganizes existing features. It does not implement the
features tracked in issues #53–#57 or the other recording roadmap documents.

The hub's page title is **Settings & features**. Its header entry point has the
accessible label **Settings and features**. Use this initial hierarchy:

```text
Main camera screen
└── Settings & features
    ├── Automatic recording
    │   ├── Radar recording
    │   └── Descent recording
    ├── Alerts & sound
    └── Camera & support [section heading, not another screen]
        ├── Camera connection
        │   ├── Disconnect
        │   └── Forget camera…
        └── Feedback & diagnostics
```

- **Automatic recording** opens a list of the two existing automation screens.
  This extra level provides space for summaries and future whole-ride recording.
- **Alerts & sound** opens the existing camera-alert preferences directly.
  Keep their current defaults, independence and immediate persistence.
- **Camera connection** shows the current connection state and the existing
  Disconnect and Forget actions. Preserve their current availability rules:
  both require a connected camera. Explain disabled actions when disconnected.
  Forget retains confirmation and operates on the current camera; this change
  does not introduce saved-device management or new pairing behavior.
- **Feedback & diagnostics** opens the existing Feedback screen, including its
  QR codes, explicit diagnostic-send confirmation, retry and discard flow.

Make the gear available from the main connected, disconnected, scanning and
connection/pairing views that currently expose the overflow menu. It is absent
inside the hub, its children and preset selection. Opening settings must not
cancel connection/pairing work. Keep pairing-access instructions reachable
when returning to the main screen.

## Presentation and state

- Use a scrollable single-column list of full-width rows with an icon, title,
  short summary and navigation indicator. The row opens a page; no row-level
  toggle changes settings accidentally. Use touch targets of at least 48 dp.
- Keep the existing compact app header and fixed bottom Back control. Reserve
  space so scrolling content is never covered by Back. Check the long hub title
  using the header's existing minimum font size and ellipsis behavior.
- The hub summary for Automatic recording can say **Radar on · Descent off**.
  These words describe saved preferences, not confirmed readiness.
- The automation list shows each feature's saved On/Off choice and its current
  status separately, using existing status sources. For example, **On** with
  **Waiting for camera** must not become **Armed** just because it is enabled.
- The alerts summary describes selected preferences; camera connection reports
  actual connection state. Feedback uses a short static explanation.
- Use text as well as color for state. Store new user-facing text in Android
  string resources. Allow summaries to wrap instead of shrinking row text.
- Settings remain accessible and editable without a connected camera. Connection
  loss updates status without closing the current settings page.
- Each list restores its scroll position when returning from a child page.
  Settings values continue to save immediately; there is no new Save button.

## Navigation contract

Replace the independent menu/settings visibility flags with explicit UI
destinations and parent navigation. Use the existing Compose stack and a small
typed destination model; no navigation dependency is required. Keep camera
connection state separate from the displayed destination.

- On-screen Back and Android/hardware Back perform the same action.
- Radar/descent settings return to Automatic recording, then to the hub, then
  to the main camera screen. Alerts, connection and feedback return to the hub.
- Main-screen Back retains its existing exit behavior; scanning and preset
  selection retain their existing cancel/return behavior.
- Opening and leaving settings does not issue camera commands, change recording
  ownership, stop monitoring, or acquire/release the camera connection.
- Disconnect and confirmed Forget invoke the existing explicit actions and
  return to the main screen. Cancelling Forget stays on Camera connection.
  Recheck the current connection before acting if it changed while a dialog was
  open; never apply confirmation to a different camera.
- Navigation survives activity recreation. After a full process restart, open
  the main screen and reload persisted settings. Do not restore confirmation
  dialogs or replay commands and diagnostic uploads.
- One owner handles Back for the current destination; remove conflicting
  screen-level handlers as the destinations are migrated.

## Where later features belong

Add entries only when the underlying feature ships. Do not show empty categories
or planned/coming-soon rows in the production hub.

| Feature | Destination and boundary |
| --- | --- |
| [Whole-ride recording](whole-ride-recording.md) | Add to Automatic recording. Its proposed exclusivity with radar/descent requires an explicit choice in that feature's implementation; this redesign changes no arbitration policy. |
| [Automatic HiLights, #55](https://github.com/michalchecinski/karoo-camera-control/issues/55) | Add a top-level Automatic HiLights page with independent descent, traffic, power, speed and HR trigger rows. Each opens its own configuration, with advanced recovery/cooldown controls grouped below basic settings. Keep tagging preferences separate from automatic recording. |
| [HindSight, #56](https://github.com/michalchecinski/karoo-camera-control/issues/56) | Add Capture options when a supported implementation exists. Distinguish the user's opt-in from actual buffer readiness and explain compatibility and power costs. |
| [Bonus Actions, #53](https://github.com/michalchecinski/karoo-camera-control/issues/53), [Quick photo, #54](https://github.com/michalchecinski/karoo-camera-control/issues/54) | Add Buttons & ride controls for supported setup guidance. Actual mappings stay in Karoo. These actions do not each become a settings-hub row. |
| HiLight confirmations in #53/#55 | Extend Alerts & sound with their independent confirmation preference. |
| [Connection keep-alive, #57](https://github.com/michalchecinski/karoo-camera-control/issues/57) | No settings entry or toggle; it belongs to the connection lifecycle. |
| [Ride-screen controls](ride-screen-recording-controls.md), [automation status field](automation-status-field.md) | Remain Karoo ride-profile fields; optional setup guidance can live under Buttons & ride controls. Their live controls/status are not new hub pages. |

The grown hub therefore has five feature rows: Automatic recording, Automatic
HiLights, Capture options, Alerts & sound, and Buttons & ride controls, followed
by the Camera & support section. Scrolling is expected; retain large touch
targets rather than compressing everything onto one screen.

## Implementation sequence

1. **Navigation foundation:** introduce explicit destinations and shared Back
   handling, migrate existing screen entry points, and verify that recording,
   scanning, pairing and feedback remain functional.
2. **Hub and grouping:** replace the overflow with the gear entry point, add
   the hub, Automatic recording list and Camera connection page, and wire
   summaries to the existing stores/status streams. Reuse existing detail
   screens and confirmation/send actions.
3. **Verification and documentation:** update affected navigation checks and
   screenshot scripts; capture changed production screens at Karoo resolution;
   update the feature docs and README paths/images; complete the normal checks.

Deliver this as one focused navigation change. Later feature implementations
add their own destinations and documentation. No dependency updates, new camera
commands, automation changes or main-screen control redesign are in scope.

Implementation touchpoints include `MainScreen.kt`, `MainViewModel.kt`, shared
settings-row UI, the new hub/list/connection screens, existing screen Back
callbacks and `strings.xml`. Account for instrumentation that constructs
`MainUiState` or searches for **More options**.

## Acceptance and validation

- Every existing overflow destination/action remains reachable through the new
  hierarchy, with no production placeholders for future features.
- Verify both Back paths from every destination, scroll restoration, activity
  recreation, connection loss, dialog cancellation and repeated taps. No
  conflicting screens can be active simultaneously.
- Verify current preference persistence and summaries when both automations
  are off, one is on and both are on; an enabled but unavailable automation is
  never presented as ready. Verify alerts summaries with visual-only,
  sound-only and all-disabled combinations.
- Confirm navigation while idle, recording and awaiting a camera command does
  not change command state or ownership. Returning to main keeps Stop available.
- Confirm Disconnect/Forget availability and confirmation behavior, scanning
  cancellation, pairing-access return, preset return and feedback/diagnostic
  retry/discard flows. Navigation must never submit diagnostics by itself.
- Run `:app:ktlintCheck`, `:app:testDebugUnitTest`,
  `:app:verifyDescentRecording`, `:app:assembleDebug` and `:app:lintDebug`;
  update and run relevant navigation/settings instrumentation on the isolated
  Karoo emulator. Add tests for destination transitions and action boundaries,
  rather than tests that merely mirror row declarations.
- Use the `karoo-screenshots` skill during implementation. Inspect the main
  header change, hub, automatic-recording list, connection page and changed
  detail headers/navigation at 480×800 with app-only crops. Check readable
  summaries, long camera names, scrolling, fixed Back and day/night contrast.
- Update `docs/recording-controls.md`, `docs/descent-recording.md`,
  `docs/radar-recording.md`, `docs/camera-alerts.md`,
  `docs/diagnostic-reports.md` and affected roadmap navigation references when
  implementation ships. Keep current instructions accurate until then.
- Before release, validate navigation and camera-control regression on a real
  Karoo with a supported GoPro. Report hardware checks as pending until run.
  A PR must use the repository template, include only changed-screen images,
  carry a `version:` label, and record checks truthfully.
