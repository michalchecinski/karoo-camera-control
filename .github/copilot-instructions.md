# Code review instructions

Review pull requests for concrete defects, regressions, security problems, and
missing tests. Comment only when the changed code has a clear, actionable
problem. Do not leave praise, style-only comments, speculative concerns, or
requests to change dependencies unless the pull request explicitly changes
them.

## Product boundaries

- This is a focused sports-camera remote for a Hammerhead Karoo, not a general
  camera configuration tool, media browser, or arbitrary Open GoPro client.
  Flag changes that expand those boundaries without an explicit product
  decision.
- Preserve the current GoPro capabilities: managing multiple cameras,
  starting and stopping recording, reporting recording time, selecting modes
  and presets, and reporting battery and remaining storage. Do not silently
  substitute a missing, changed, or disconnected camera with another camera.
- Changes to current behaviour must agree with the relevant project
  documentation, including `README.md` and the GoPro integration README.

## Security and action safety

- Treat camera Wi-Fi credentials, pairing data, device addresses, access
  tokens, authorization codes, PINs, keystore material, and signing secrets as
  sensitive. Flag code that logs, exposes, persists insecurely, backs up, or
  transmits them to an unintended destination.
- Bluetooth permissions and pairing flows must not be weakened or bypassed.
  Do not introduce workarounds that require privileged Bluetooth APIs or
  unsafe pairing confirmation.
- Recording, mode changes, and preset selection must result from an explicit
  user action. Flag automatic command replay after a disconnect, timeout, or
  failed command, and automatic retries of state-changing camera commands.
- Do not show a state-changing command as successful until the expected camera
  state is confirmed. Preserve the distinction between a definite failure and
  an uncertain result after a command may have reached the camera.

## Connectivity and camera behaviour

- Review BLE lifecycle and error handling carefully: unavailable Bluetooth,
  missing permissions, disconnects, GATT failures, incomplete packets, and
  unknown or unsupported camera state must not trigger an unsafe default
  command or be shown as successfully completed.
- Preserve the selected-camera identity through scanning, connecting,
  persistence, and reconnection. Auto-connect may use only the user's stored
  camera selection and must not fall back to a different camera without an
  explicit user decision.
- Keep BLE and protocol work off the main thread, serialize GATT operations,
  and clean up callbacks, scans, GATT connections, and coroutines when their
  lifecycle ends.

## Android and Karoo UI

- Flag main-thread blocking for Bluetooth, protocol, cryptographic, or
  persistent-storage work.
- The UI targets Karoo's small portrait display and must remain glanceable,
  touch-friendly, and accessible without relying on colour alone. Preserve the
  established navigation behaviour.
- Keep user-visible strings in Android string resources; do not introduce
  hard-coded UI text in Kotlin composables.

## Tests and review comments

- Require focused unit tests when changed logic affects BLE packet parsing,
  command encoding, connection state transitions, camera selection,
  persistence, recording-state handling, preset mapping, or outcome
  classification. Require tests for a bug fix when practical.
- Do not demand tests for documentation-only, build-metadata-only, or purely
  visual asset changes unless they alter runtime behaviour.
- For each finding, explain the triggering scenario and user impact. Reference
  the smallest relevant changed line range and propose a fix when it is clear.

## Evidence threshold

- Report a finding only when it is directly supported by the changed code and
  relevant repository context. If you cannot identify a concrete execution
  path that produces the problem, do not comment.
- Do not infer defects from redacted, masked, generated, or truncated values
  in the review UI. Review the source and tests as written.
- Do not comment merely because a different implementation might be
  preferable; require a correctness, safety, security, user-impact, or
  maintainability failure.

## Finding quality

- Do not restate an existing test, implementation, or documented invariant as
  a concern unless the change demonstrably violates it.
- When uncertain whether a concern is real, omit it rather than presenting it
  as a defect.
