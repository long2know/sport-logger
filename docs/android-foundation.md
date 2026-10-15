# Android foundation and recording service

## Supported build baseline

The supported baseline is the existing three-module Groovy build:

| Component | Selected value |
| --- | --- |
| JDK | Eclipse Temurin 17 (`17.0.20.1+1` used for the clean validation) |
| Gradle | 8.11.1, binary distribution SHA-256 pinned in the wrapper |
| Android Gradle Plugin | 8.10.1 |
| Android SDK / build tools | compile SDK 36 / build tools 36.0.0 |
| Kotlin | Not applied; modules remain Java with Groovy Gradle scripts |
| Mobile | min SDK 26 / target SDK 36 |
| Wear | min SDK 26 / target SDK 35 |
| Utilities | min SDK 23 |
| Java source and bytecode | 17 |

Dependencies resolve from Google Maven and Maven Central. AndroidX is enabled, Jetifier is
disabled, and no `jcenter()` or `com.android.support.*` dependency remains.

## Build and install

Set `JAVA_HOME` to a JDK 17 installation and make SDK 36 available through
`ANDROID_SDK_ROOT` or an uncommitted `local.properties` file.

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

The debug APKs are:

- `mobile/build/outputs/apk/debug/mobile-debug.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk`

Install the phone and watch APKs on their corresponding devices:

```bash
adb -s <phone-serial> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <wear-serial> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

The pinned CI workflow is `.github/workflows/android-build.yml`. It runs for pull requests
and pushes targeting both `dev` and `main`.

## Recording ownership

`SportLoggerService` delegates durable recording transitions to a serial
`RecordingController`. START, PAUSE, RESUME, STOP, DISCARD, database sampling, finalization,
and Data Layer export are ordered on its worker. Durable state is committed before STOP or
DISCARD work proceeds, and a replacement service resumes STOPPING, DISCARDING, or
EXPORT_PENDING work after recreation.

Location and sensor resources have independent `ResourceOwner` workers. Ownership is
recorded only after successful registration, partial registration is rolled back, callbacks
are generation-fenced and deduplicated, and retries cannot resurrect a stopped owner.
Lifecycle callbacks request asynchronous cleanup and never join a worker or wait on a
latch. A delayed platform unregister therefore retains its worker only until that platform
call returns, while the main thread remains free.

The SQLite table names, columns, timestamps, Java-serialized activity payload, Data Layer
path (`/sportactivity`), and asset key (`sportActivity`) remain compatible with the legacy
phone transfer.

## Validation

The clean host validation command is:

```bash
./gradlew clean \
  :mobile:assembleDebug :mobile:assembleRelease \
  :wear:assembleDebug :wear:assembleRelease \
  test lint --no-daemon --stacktrace
```

Deterministic unit tests inject blocked cleanup, registration and cleanup failures, stale and
duplicate callbacks, missing providers, permission denial and revocation, rapid
stop/restart, service recreation, persistence failures, and export retries. Android test
APKs also compile for all three modules. Device and emulator behavior is not claimed by
these host tests.
