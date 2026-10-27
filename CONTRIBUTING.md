# Contributing

Issues and focused pull requests are welcome. For bugs, include the Android version, provider/model, reproduction steps, and a redacted error message. Do not include API keys, personal conversations, or private project files.

Read [AGENTS.md](AGENTS.md) for Compose and Material 3 conventions, and [Building](docs/BUILDING.md) for local setup. Keep changes focused, preserve light/dark themes and large-font usability, and run the checks relevant to your change. The standard checks are:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
python3 scripts/check-contrast.py
```

Use demo data for documentation screenshots. Keep local SDK paths, signing material, build outputs, and credentials out of commits. Include the problem, resulting behavior, and validation in a pull request.
