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
DISCARD work proceeds, and a replacement service resumes STOPPING, DISCARDING,
EXPORT_PENDING, or EXPORT_QUEUED work after recreation. A local Data Layer write moves the
controller to EXPORT_QUEUED, not IDLE. That state permits another workout while the phone is
offline, while IDLE is reached only after a durable phone receipt is acknowledged.

The recording journal serializes lease acquisition, lease validation, and the complete
SharedPreferences commit under one process-wide lock. Listener delivery is also lease- and
generation-fenced, so a stopped or replaced controller cannot publish state after a newer
service has acquired ownership.

Location and sensor resources have independent `ResourceOwner` workers. Ownership is
recorded only after successful registration, partial registration is rolled back, callbacks
are generation-fenced and deduplicated, and retries cannot resurrect a stopped owner.
Lifecycle callbacks request asynchronous cleanup and never join a worker or wait on a
latch. A delayed platform unregister therefore retains its worker only until that platform
call returns, while the main thread remains free.

On Android 14 and newer, foreground-service types are recomputed from current permissions
and enabled location providers. Location permission with location services disabled selects
health-only promotion when health permission is available; later provider recovery upgrades
the running service without suppressing health measurements. Step-counter values are
converted from since-boot totals into per-session deltas, with pause, counter reset, and
service recreation fencing. A step detector is used only when the counter cannot be
registered.

The SQLite table names, columns, and timestamps remain unchanged. Activity payloads still
use the legacy Java `SportActivity` serialization and `sportActivity` asset. Each completed
workout is additionally written to a unique `/sportactivity/<transfer-id>` Data Item, while
the fixed `/sportactivity` item remains as a legacy-reader alias. The phone accepts both
formats, fsyncs each unique payload into app-private `received-sport-activities` storage,
and keeps a durable pending-ack record until its unique
`/sportactivity-ack/<watch>/<transfer-id>` Data Item is locally queued. Replays are
idempotent and conflicting bytes never overwrite an existing receipt. The watch deletes the
source item only after receiving that phone acknowledgement. No legacy watch database table
or serialized model schema is changed.

## Validation

The clean host validation command is:

```bash
./gradlew clean \
  :mobile:assembleDebug :mobile:assembleRelease \
  :wear:assembleDebug :wear:assembleRelease \
  test lint --no-daemon --stacktrace
```

Deterministic unit tests inject blocked cleanup, registration and cleanup failures, stale and
duplicate callbacks, missing providers, permission denial and revocation, two consecutive
offline workouts, durable phone restart/ack retry, service recreation, persistence failures,
and export retries. The cleanup and finalization timing tests each hold production workers
for at least 5,000 monotonic milliseconds while asserting stop/close calls return in under
100 milliseconds, then prove eventual worker termination. Android test APKs also compile
for all three modules, including an activity recreation test that verifies the restored
visible sensor fragment receives live updates. Device and emulator behavior is not claimed
by these host tests.
