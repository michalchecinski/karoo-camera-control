---
name: karoo-screenshots
description: Capture and maintain clean app-only screenshots for Karoo Camera Control. Use when a UI change needs visual verification, README screenshots, release images, or a pull-request visual summary; includes emulator capture at Karoo resolution, system-UI removal, README gallery updates, and PR-body image links.
---

# Karoo Screenshots

Capture the affected screens after UI work. Keep production README assets in `screenshots/emulator-karoo-480x800/` and never publish Android status or navigation bars.

## Workflow

1. Read `docs/` when it exists and inspect the changed composables. Identify the smallest representative set of user-visible states, such as scanning, pairing, connecting, connected camera controls, preset selection, or a relevant error state.
2. Preserve unrelated worktree changes. Stage only intentional screenshot, README, and PR-description files.
3. Build with `./gradlew :app:assembleDebug` and install the debug APK on the `Karoo` AVD. This profile is 480×800.
4. Use isolated emulator app data. Do not pair a personal camera or expose a real camera name, Bluetooth address, media, or account data. For connected states, use a non-personal test camera and clearly non-sensitive sample values.
5. Navigate to each state with ADB and take a raw screenshot. Use `scripts/capture-app-only.sh` to remove the emulator's 48 px status area and 96 px navigation area. The resulting asset must be 480×656.
6. Inspect every final image visually. Check that no Android system bar, keyboard, dialog outside the intended state, placeholder typo, or partial transition appears.
7. The README currently has no screenshot gallery. Add one only when a user-visible feature is worth showcasing or the user asks for it. Place it before `## Installation`; use sequential images without a table or captions.
8. For a PR that changes visible UI, add a `## Screenshots` section to its description. Link only the screenshots that demonstrate the change, using repository-relative Markdown paths when supported by the PR client; otherwise mention their paths explicitly.

## Capture command

Run from the repository root. Supply the emulator serial and a descriptive output path:

```sh
.codex/skills/karoo-screenshots/scripts/capture-app-only.sh emulator-5554 screenshots/emulator-karoo-480x800/camera-connected.png
```

The script requires macOS `sips` and validates the 480×800 capture before cropping it to a 480×656 app-only PNG. Use `adb -s <serial> shell wm size` to verify the profile first.

## Asset rules

- Use stable, state-based lowercase names such as `camera-scanning.png`, `camera-pairing.png`, and `camera-connected.png`.
- Replace an existing image rather than accumulating obsolete variants.
- Prefer populated, realistic camera controls as the lead README image. Do not use an empty or scanning state as a product hero when a connected state is available.
- Do not change saved camera connections, pairing data, or real camera settings merely to create screenshots.
- Before committing, run `git diff --check` and confirm each PNG is tracked intentionally.
