# SportLogger

**A watch-first workout recorder. A foundation for a better Android companion.**

SportLogger is an open-source Wear OS workout recorder with an Android companion prototype.
It brings together heart-rate, step, and optional GPS recording, watch-to-phone activity
transfer, and shared activity/TCX-oriented utilities.

[![Android build](https://github.com/long2know/sport-logger/actions/workflows/android-build.yml/badge.svg?branch=main)](https://github.com/long2know/sport-logger/actions/workflows/android-build.yml)
[![SQLite compatibility](https://github.com/long2know/sport-logger/actions/workflows/sqlite-exact-compat.yml/badge.svg?branch=main)](https://github.com/long2know/sport-logger/actions/workflows/sqlite-exact-compat.yml)
[![Migration oracle](https://github.com/long2know/sport-logger/actions/workflows/legacy-migration-oracle.yml/badge.svg?branch=main)](https://github.com/long2know/sport-logger/actions/workflows/legacy-migration-oracle.yml)

[Get started](#get-started) | [Build and run](#build-and-run) |
[Project layout](#project-layout) | [Roadmap](#roadmap) | [Contributing](#contributing)

> **Developer preview:** SportLogger is being modernized incrementally from its original
> Java Android/Wear OS application. The existing three-module structure and activity formats
> are retained while build, recording, and migration foundations are improved. Planned
> features below are not advertised as shipped integrations. The phone still has a minimal
> developer UI: received activities are stored privately, not exposed in an activity browser.
> **User-facing TCX export is not implemented yet.** Real paired-device end-to-end acceptance
> remains a release gate; this development baseline is not a release-ready app.

## What it does

| Capability | Details |
|---|---|
| Watch recording | Heart rate, steps, and location where the device and granted permissions support them. |
| Indoor workouts | Track workouts without requiring a GPS route. |
| Phone receiver | Persist activities received from a paired watch in app-private storage; no history/browser UI yet. |
| TCX-oriented utilities | Shared models and serializers exist, but the apps do not yet build or save TCX through the UI. |
| Migration verification | Standalone Android formatter and exact-SQLite tools support reproducible legacy-data migration work. |

SportLogger started as a way to record indoor and treadmill workouts without a cumbersome
post-processing workflow. Keeping recorded data usable and portable remains the goal.

## Get started

### Prerequisites

- **JDK 17**, selected both for your terminal and Android Studio's Gradle JDK.
- **Android SDK command-line tools**, platform tools, Android SDK Platform 36, and Build Tools 36.0.0.
- An Android phone and a paired Wear OS watch, both running Android API 26 or newer,
  for real recording and transfer checks.
  Emulators are useful for development but do not prove physical sensor behavior.
- Network access for the first Gradle/dependency download.

The project uses its checked-in Gradle wrapper; a separate Gradle installation is not required.

| Build component | Project baseline |
|---|---|
| Java | 17 |
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.10.1 |
| Compile SDK | 36 |
| Phone target SDK | 36 |
| Wear target SDK | 35 |

These versions are one project build tuple, not a claim that each component is the newest
available release. See the [Android foundation guide](docs/android-foundation.md) for the
implemented lifecycle, transfer, compatibility, and build details.

The app's API minimum does not override the phone OS requirements of your watch's companion app.
For emulator-based end-to-end testing, follow the
[official phone/watch pairing instructions](https://developer.android.com/training/wearables/get-started/connect-phone).
Pairing and first-party companion-app setup are separate from building the APKs and may require
interactive Play Store setup. Never put account credentials in the repository.

### Clone and configure

```bash
git clone https://github.com/long2know/sport-logger.git
cd sport-logger
```

Set `JAVA_HOME` to your JDK 17 installation and `ANDROID_HOME` to your Android SDK installation.
Add their tools to your shell path, substituting your own absolute paths:

```bash
export JAVA_HOME=/absolute/path/to/jdk-17
export ANDROID_HOME=/absolute/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
./gradlew --version
```

Review and accept the SDK licenses when prompted. Alternatively, install these SDK components
through Android Studio's SDK Manager, open the repository as a Gradle project, and set its
Gradle JDK to 17. Keep SDK paths and other machine-specific settings in untracked local
configuration, not committed source files.

Windows users can use `gradlew.bat` in place of `./gradlew` and set the same environment
variables through their shell or Android Studio.

## Build and run

Build both debug apps:

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

| App | Debug APK |
|---|---|
| Android phone | `mobile/build/outputs/apk/debug/mobile-debug.apk` |
| Wear OS watch | `wear/build/outputs/apk/debug/wear-debug.apk` |

Install the matching app on each device from Android Studio, or use ADB with the device
serials shown by `adb devices`:

```bash
adb devices
adb -s PHONE_SERIAL install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Pair the phone and watch, open the apps, and grant the permissions requested for the
measurements you want to record. Heart rate and step availability depend on hardware;
location is optional for indoor workouts. Check recording, stopping, and transfer on your own
devices before relying on a development build for an important workout.

The current phone screen is a developer placeholder. Receiving an activity does not create a
user-browsable history entry or a downloadable TCX file; those experiences remain roadmap work.

Debug APKs are development builds. Real paired phone/watch transfer and acknowledgement/restart
acceptance, release signing, physical-device acceptance, and store submission requirements remain
separate release gates. A passing build or host test suite is not a claim that these gates passed.

### Checks

Run the Android build, unit tests, and lint:

```bash
./gradlew clean assembleDebug assembleRelease test lint --no-daemon
```

The standalone migration-verification tools have their own prerequisites and commands:

- [Exact SQLite compatibility harness](tools/sqlite-compat/README.md): builds pinned upstream
  SQLite engines and checks real engine behavior, including a 100,000-point gate.
- [Android legacy formatter evidence](tools/android-formatter-evidence/README.md): API 26/API 36
  instrumentation probes, sanitized evidence, and reproducibility checks.
- [Legacy migration oracle](tools/legacy-migration-oracle/README.md): read-only migration checks
  against exact SQLite engines, verified Android numbering definitions, real flawed candidates,
  and deterministic 100,000-point inputs.

Do not commit SDK installations, emulator data, generated databases, build output, or local
activity files.

## Project layout

```text
mobile/       Android phone companion
wear/         Wear OS recorder
utilities/    Shared models, data access, and activity-file support
tools/        Standalone compatibility and migration-verification tools
docs/         Modernization roadmap and research
.github/      CI and contributor automation
```

## Roadmap

The [modernization roadmap](docs/modernization-roadmap.md) describes the staged architecture
and its acceptance gates. It is a dated design document; use the checked-in build files and
tool guides for the implemented build and verification commands.

Future milestones include canonical activity contracts and storage, the broader durable-sync
architecture, capability-aware Health Services recording, refreshed phone/watch interfaces,
phone activity browsing and TCX export, Health Connect, and Endurain integration. These are
roadmap items, not promises that the current app implements them.

See the [open issues](https://github.com/long2know/sport-logger/issues) for the active backlog
and [platform research](docs/research/open-source-and-platform-research.md) for the evidence
behind the design.

## Contributing

`main` is the reviewed baseline; `dev` is the integration branch. Start focused changes
from `dev` on a `stlong/` branch, include regression coverage with behavior changes, and
open a PR targeting `dev`. Reviewed integration changes are promoted to `main` through
a separate PR.

Keep recording behavior, existing activity data, and transfer compatibility intact unless
a change explicitly documents and tests a migration. Never include personal workout data,
credentials, or machine-specific configuration in a PR.

## License

SportLogger is licensed under the [Apache License 2.0](LICENSE).
