# Building Agentic Hydra

## Requirements

- JDK 17 or 21 for local Gradle builds. The CI entry point uses JDK 17.
- Android SDK platform 35 and build tools 35.0.0.
- Android Studio, or Android command-line tools with SDK licenses accepted.

The wrapper pins Gradle 8.10.2 and its distribution checksum. The project uses Android Gradle Plugin 8.7.3 and Kotlin 2.0.21, with JVM target 17.

## Local build

```sh
git clone https://github.com/theonlyasdk/AgenticHydra.git
cd AgenticHydra
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
./gradlew assembleDebug
```

Set `ANDROID_HOME` to the SDK directory, or let Android Studio create an ignored `local.properties` containing `sdk.dir`. On Windows, use `.\gradlew.bat` in place of `./gradlew`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Debug builds use Android's debug signing key. They cannot update an official release installation with the same application ID. Use a separate device/emulator for development when you need to keep your release installation and its data.

## Checks

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
python3 scripts/check-contrast.py
```

The suite includes JVM logic tests and Robolectric native-graphics UI tests. Native UI tests run in isolated forks. This makes the full suite slower than a small unit-test suite; it does not need a connected device.

## Release signing

Official signing material is kept outside Git. To build your own signed APK, create a private keystore with alias `hydra` and provide:

```sh
export HYDRA_KEYSTORE=/absolute/path/to/your-release.keystore
export HYDRA_STORE_PASSWORD='your-private-password'
./gradlew assembleRelease
```

Keep the password out of shell history on shared machines; a local secret manager or CI secret is preferable. Check `app/build.gradle.kts` for the signing configuration. Preserve the same key for updates to your distribution. The APK is `app/build/outputs/apk/release/app-release.apk`.

The [Android CI workflow](../.github/workflows/android.yml) runs `scripts/ci-build.sh` on JDK 17. It installs the SDK components, runs release tests and lint, builds an APK, checks contrast, and verifies APK signing. Without a supplied key, it generates a disposable **preview** key. Its downloadable artifact cannot update official signed releases.

## Documentation screenshots

`ShowcaseUiTest.kt` renders the actual Compose screens with local fixture data. It uses no live API key or personal history. Regenerate the images with:

```sh
./gradlew testDebugUnitTest --tests 'app.agentichydra.Showcase*UiTest'
```

Outputs are in `app/build/reports/screenshots/showcase-*.png`. Review them before replacing the four curated images in `docs/screenshots/`. The banner is vector artwork; `scripts/render-banner.py` regenerates it from the bundled Instrument fonts using Python's `fontTools` package.

## Project map

| Path | Contents |
| :-- | :-- |
| `app/src/main/java/app/agentichydra/` | Compose screens, provider client, state, storage, and Work harness |
| `app/src/main/res/` | Fonts, provider artwork, launcher and splash assets |
| `app/src/test/` | Logic and native UI regression tests |
| `scripts/` | CI, contrast checks, and documentation artwork |
| `docs/` | Setup, tools, credits, and screenshots |

Earlier design and API research lives in [DESIGN.md](../DESIGN.md), [PROVIDER-NOTES.md](../PROVIDER-NOTES.md), and [UI-REFERENCES.md](../UI-REFERENCES.md). These are historical notes; use the current source and setup guide for behavior.
