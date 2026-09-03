# Karoo 3 GoPro pairing notification flow

## Purpose

Karoo 3 may require Android Settings to confirm a GoPro's Bluetooth "Just
Works" pairing request. Karoo Camera Control uses an Android
`NotificationListenerService` only after the device owner explicitly grants
notification access.

## First pairing

1. Before a first pairing, the scan screen explains that this is a one-time
   setup. Its **Open settings and allow access** button opens Android's
   notification-access settings and tells the user to turn on Karoo Camera
   Control, accept the system prompt, return to the app, and tap **Connect**.
2. The user enables notification access for Karoo Camera Control.
3. The app connects to the selected GoPro and reads its protected WAP-password
   characteristic, which starts Bluetooth pairing.
4. The listener finds the Android Settings Bluetooth notification and invokes
   only its uniquely identified **Pair & connect** action.
5. The app waits for `BOND_BONDED` before it enables GoPro response
   notifications or sends commands.

If notification access is absent, the app does not start pairing. It shows the
notification-access setup screen instead. If the expected Settings action is
not available, it fails with an actionable error and records the available
action titles in debug logs. It never calls privileged Bluetooth confirmation
APIs or sends an unrecognised notification action.

## Scope and validation

This is a Karoo 3-specific integration workaround. The Settings package,
Bluetooth notification channel, and action title must be verified on the
target firmware before release. A successful first-pairing test must show:

`ACTION_PAIRING_REQUEST` -> Settings pairing notification -> Pair & connect
action -> `BOND_BONDED` -> GoPro notifications and commands succeed.
