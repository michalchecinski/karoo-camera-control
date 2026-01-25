# Karoo Camera Control

## Project Overview

This project is an Android application designed as an extension for the Hammerhead Karoo cycling computer. Its primary purpose is to control a sports camera. In first version it supports the GoPro integration only.

The project is built using:
-   **Language:** Kotlin
-   **UI Framework:** Jetpack Compose (Material3)
-   **Build System:** Gradle (Kotlin DSL)
-   **Key Library:** `io.hammerhead:karoo-ext` (Hammerhead Karoo Extension SDK)

### GoPro integration

When implementing GoPro functionality please follow guidelines and code samples from the following official GoPro repository: https://github.com/gopro/OpenGoPro/tree/main/demos/kotlin/tutorial. This is the most critical requirement for implementing GoPro integration.

The possibilities of the Open GoPro API can be found here: https://gopro.github.io/OpenGoPro/ble/index.html. Check it before implementing.

For detailed information on the GoPro integration, refer to @app/src/main/kotlin/com/karoocameracontrol/integrations/gopro/README.md.

### Hammerhead Karoo integration

When implementing Hammerhead Karoo functionality please follow guidelines and code samples from the following official Karoo sdk repository: https://github.com/hammerheadnav/karoo-ext. Especially different data sources, types and extensions are available here: https://github.com/hammerheadnav/karoo-ext/tree/master/app/src/main/kotlin/io/hammerhead/sampleext/extension.

## Building and Running

### Build Commands

**Build the project:**

    ```bash
    ./gradlew build
    ```

## Project Structure

- **`app/`**: The main application module.
  - **`src/main/kotlin/com/karoocameracontrol/extension/KarooCameraControlExtension.kt`**: The core Service class that extends `KarooExtension`. This is the entry entry point for the Karoo system interactions.
  - **`src/main/kotlin/com/karoocameracontrol/MainActivity.kt`**: The main activity, likely used for configuration or standalone app UI.
  - **`src/main/kotlin/com/karoocameracontrol/screens/`**: Contains the Jetpack Compose UI screens (e.g. `MainScreen`, `ScanningScreen`, `ConnectedScreen`, `PresetSelectionScreen`) and `MainViewModel`.
  - **`src/main/kotlin/com/karoocameracontrol/integrations/`**: Contains camera integration logic.
    - **`gopro/`**: Specific implementation for GoPro cameras, including `GoProManager`, `GoProCommands`, and data parsing logic.
  - **`src/main/res/xml/extension_info.xml`**: Metadata definition for the extension (display name, icon, capabilities).
  - **`src/main/AndroidManifest.xml`**: Declares the `KarooCameraControlExtension` service and required permissions.
- **`gradle/libs.versions.toml`**: Version catalog managing dependencies and plugin versions.
- **`settings.gradle.kts`**: Project settings, including the repository configuration for `hammerheadnav/karoo-ext`.

## Development Conventions

- **Jetpack Compose:** The UI is built using Jetpack Compose.
- **Kotlin DSL:** Gradle build scripts are written in Kotlin (`.kts`).
- **Namespace:** `com.karoocameracontrol`
- Keeping project aligned with DRY and clean code rules.
- Separate the business logic with app screens.
- Keep the code logically separated, e.g. Hammerhead Karoo integration should be separate of GoPro integration. Each functionality should have it's own class (e.g. for connecting with camera and for controlling it).

## Additional rules

- Do not commit the code unless stated clearly to do so.
- Plan first, ask for approval and then implement.
