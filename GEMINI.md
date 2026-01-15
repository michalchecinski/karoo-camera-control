# Karoo Camera Control

## Project Overview

This project is an Android application designed as an extension for the Hammerhead Karoo cycling computer. Its primary purpose is to control a sports camera. In first version we'll implement the GoPro integration.

The project is built using:
-   **Language:** Kotlin
-   **UI Framework:** Jetpack Compose (Material3)
-   **Build System:** Gradle (Kotlin DSL)
-   **Key Library:** `io.hammerhead:karoo-ext` (Hammerhead Karoo Extension SDK)

## Building and Running

### Build Commands

*   **Build the project:**
    ```bash
    ./gradlew build
    ```

*   **Install Debug version to connected device:**
    ```bash
    ./gradlew installDebug
    ```

*   **Run Unit Tests:**
    ```bash
    ./gradlew test
    ```

## Project Structure

*   **`app/`**: The main application module.
    *   **`src/main/kotlin/com/karoocameracontrol/extension/TemplateExtension.kt`**: The core Service class that extends `KarooExtension`. This is the entry point for the Karoo system interactions.
    *   **`src/main/kotlin/com/karoocameracontrol/MainActivity.kt`**: The main activity, likely used for configuration or standalone app UI.
    *   **`src/main/res/xml/extension_info.xml`**: Metadata definition for the extension (display name, icon, capabilities).
    *   **`src/main/AndroidManifest.xml`**: Declares the `TemplateExtension` service and required permissions.
*   **`gradle/libs.versions.toml`**: Version catalog managing dependencies and plugin versions.
*   **`settings.gradle.kts`**: Project settings, including the repository configuration for `hammerheadnav/karoo-ext`.

## Development Conventions

*   **Jetpack Compose:** The UI is built using Jetpack Compose.
*   **Kotlin DSL:** Gradle build scripts are written in Kotlin (`.kts`).
*   **Namespace:** `com.karoocameracontrol`
*   Keeping project aligned with DRY and clean code rules.
*   Separate the business logic with app screens.
*  Keep the code logically separated, e.g. Hammerhead Karoo integration should be separate of GoPro integration. Each functionality should have it's ow class (e.g. for connectiong with camera and for controlling it).
