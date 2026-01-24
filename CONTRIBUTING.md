# How to Contribute

Contributions of all kinds are welcome!

## Coding standards

Please, adhere to the coding standards in the repository, including:

- Integrating your code using Fork and Pull Request.
- Split the code accordingly. This includes, but is not limited to:
  - screens (UI) and logic separate;
  - reusable UI components in the `components` directory;
  - code used for communication with a camera in the `integrations/<camera-brand>` folder;
  - code for integrations with the bike computer in the `extension` directory.
- Testing the changes on actual Karoo device and with at least one supported action cam. Those tests should also include regression testing to ensure backwards compatibility.
- Adhere to code formatting standards. See "Operating the Project" section for details on how to format the code.
- If you add any new integration, feature, component or library, please add it to the `NOTICES` and `README.md` files, including links and license information.
- If using any AI coding assistants or LLMs to support you development, please double check it.

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
