# Karoo Camera Control

Karoo Camera Control is an Android extension that lets a Hammerhead Karoo bike
computer control sports action cameras. GoPro is the currently supported
camera integration.

## Project overview

The project is built using:
- Language: Kotlin
- UI Framework: Jetpack Compose (Material3)
- Build System: Gradle (Kotlin DSL)

Do not update any dependencies if not asked or allowed directly.

## Docs

Before specification, planning, or implementation, read `docs` folders to
identify conventions, product boundaries, and other information relevant to
the change.

After adding a new feature, add a Markdown document explaining it in the
`docs` folders. When changing behaviour, UI, or another part of the app,
update the appropriate document in the `docs` folder.

## Git & GitHub

### Branches

Do not use an `agent/` prefix for branch names.

### PR

When creating a PR, use `.github/PULL_REQUEST_TEMPLATE.md` and check every
item on its checklist. Always add a `version:` label. When a PR changes the UI, include screenshots of only the changed screens (use the `karoo-screenshots` skill).

### Human judgment

If a review comment or another issue genuinely requires human judgment, ask
the user for a decision. Do not resolve the comment or make that decision
yourself.

## Hammerhead Karoo and camera integration

When implementing Hammerhead Karoo functionality, follow the guidelines and
code samples in the official Karoo SDK repository:
https://github.com/hammerheadnav/karoo-ext. In particular, use the extension
and data-source examples at:
https://github.com/hammerheadnav/karoo-ext/tree/master/app/src/main/kotlin/io/hammerhead/sampleext/extension.

For GoPro support, follow the Open GoPro BLE specification and Kotlin tutorial:
https://gopro.github.io/OpenGoPro/ble/index.html and
https://github.com/gopro/OpenGoPro/tree/main/demos/kotlin/tutorial.

When searching for examples of Karoo apps, check:
https://github.com/timklge/awesome-karoo.
