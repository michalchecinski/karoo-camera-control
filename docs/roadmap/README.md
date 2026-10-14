# Karoo integration roadmap

This roadmap tracks four Karoo integrations. Their documents describe
the intended experience, proposed behaviour, and acceptance criteria. Defaults
and design choices marked as proposals can be refined before implementation.
There are no release dates or dependency updates committed by this roadmap.

## Features and proposed delivery order

| Order | Feature | Purpose | Status |
| --- | --- | --- | --- |
| 1 | [Recording controls from the ride screen](ride-screen-recording-controls.md) | Start and stop a camera without leaving the ride screen. | Implemented; hardware QA pending |
| 2 | [Native camera alerts and audible confirmation](camera-alerts-and-sound.md) | Confirm recording changes and report camera problems during a ride. | Implemented; hardware validation pending |
| 3 | [Opt-in whole-ride recording](whole-ride-recording.md) | Automatically record from ride start to ride finish. | Planned |
| 4 | [Automation status field](automation-status-field.md) | Explain what camera automation is doing and why it is waiting. | Planned |

The order above follows the selected feature list. Alerts should work with the
existing app controls and automations as well as the new ride-screen controls.
The status field can initially support descent and radar recording, then add
whole-ride recording when available; it does not need to wait for every feature.

## Shared boundaries

- Reuse the existing camera command controller and its acknowledgement plus
  recording-state confirmation. A request is not proof that recording started.
- Manual controls remain available and take precedence over automation.
- Preserve recording ownership: one automation must not stop a manual clip or
  a clip owned by another automation. Reconnection must not claim an existing clip.
- Distinguish unknown/disconnected camera state from confirmed stopped state.
- Run background monitoring through the Karoo extension lifecycle, with proper
  subscription cleanup. A closed settings screen must not stop monitoring.
- Keep existing numeric fields and manual camera functionality compatible.
- The project currently uses `karoo-ext` 1.1.3. Any required dependency update
  needs separate explicit approval before implementation.

## Existing functionality

The app already provides numeric GoPro status, recording-time, and battery
fields, a shared recording controller, [descent recording](../descent-recording.md),
and [radar recording](../radar-recording.md). These roadmap features extend that
foundation. See [recording controls](../recording-controls.md) for current command
confirmation and manual override behaviour.

## Validation expectations

Each implementation should verify state transitions, failures, and manual
override; visually check changed screens and fields at Karoo resolution; and
capture only the changed UI using the repository's screenshot workflow.
Native ride-screen rendering, hardware action mappings, alerts, sound, and BLE
behaviour also require physical Karoo/GoPro validation. Emulator screenshots
alone cannot establish that those integrations work.
