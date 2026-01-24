# How to Contribute

Contributions of all kinds are welcome!

## Prerequisites

Before you begin, ensure you have the following installed:

- **JDK 17**: Required for building the project (AGP 8+ requirement).

## Reporting Issues

- **Search first**: Check existing [Issues](https://github.com/michalchecinski/karoo-camera-control/issues) to avoid duplicates.
- **Be clear**: Describe the bug or feature request in detail.
- **Provide context**: Include steps to reproduce, device info (Karoo model, Android version), and logs if possible.

## Pull Request Process

1. **Fork the repository**: Create your own copy of the project.
2. **Create a branch**: Use a descriptive name (e.g., `fix/connection-bug`).
3. **Commit changes**: Use clear, concise commit messages.
4. **Test**: Ensure the app builds and runs on a real device.
5. **Submit a PR**: Open a Pull Request against the `main` branch. Provide a clear description of your changes and reference any related issues.

## Coding Standards

Please adhere to the coding standards in the repository:

- **Code Splitting**:
    - Keep screens (UI) and logic separate.
    - Place reusable UI components in the `components` directory.
    - Put camera communication code in `integrations/<camera-brand>`.
    - Put Karoo extension integration code in `extension`.
- **Testing**: Test changes on an actual Karoo device and with at least one supported action cam. Include regression testing.
- **Formatting**: Adhere to code formatting standards (see "Operating the Project" section of the document).
- **Documentation**: Update `NOTICES` and `README.md` if adding new integrations, features, or libraries.
- **AI Usage**: If using AI coding assistants, or LLMs double-check their output.

## Integrations

For details about the current Bluetooth Low Energy (BLE) implementation and future OpenGoPro SDK migration plans used in this project, see the GoPro integration README in this repository.

## Operating the Project

To operate the project locally, use the following commands:

**Build the project:**

```bash
./gradlew build
```

**Install Debug version to connected device:**

```bash
./gradlew installDebug
```

**Format the code:**

```bash
./gradlew ktlintFormat
```

### Using Task

This project also supports [Task](https://taskfile.dev).

**Build the project:**

```bash
task build
```

**Install Debug version to connected device:**

```bash
task installDebug
```

**Format the code:**

```bash
task fmt
```
