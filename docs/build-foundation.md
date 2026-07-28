# Supported Android build foundation

Issue #4 restores a supported Android Studio baseline without changing recording, export, recovery,
Data Layer, or database semantics. The project remains Java, Groovy, and three modules:
`mobile`, `wear`, and `utilities`.

## Supported tuple

Validated on 2026-07-27:

| Component | Exact selection |
|---|---|
| JDK | Eclipse Temurin `17.0.20+8` |
| Gradle | `8.11.1` |
| Gradle distribution SHA-256 | `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6` |
| Android Gradle Plugin | `8.10.1` |
| Compile SDK | API `36` |
| Build tools | `36.0.0` |
| Phone target SDK | API `36` |
| Wear target SDK | API `35` |
| Phone/Wear minimum SDK | API `26` |
| Utilities minimum SDK | API `23` |
| Java source/bytecode target | Java `17` |

AGP 8.10 is the supported pre-AGP-9 family whose compatibility table reaches API 36 and pairs with
Gradle 8.11.1 and JDK 17. Google Maven metadata publishes 8.10.1 as that family's final patch.
Issue #16 owns the later AGP 9.3.1/Gradle 9.5 migration.

AndroidX Core 1.19.0 is current, but Core 1.18.0 and newer compile against API 36.1. This baseline
therefore uses Core 1.17.0, the newest stable Core line compatible with the selected API-36/AGP-8.10
tuple. Other direct AndroidX and Google dependencies use their current compatible stable releases:

| Dependency | Version |
|---|---:|
| `androidx.appcompat:appcompat` | `1.7.1` |
| `androidx.constraintlayout:constraintlayout` | `2.2.1` |
| `androidx.core:core` | `1.17.0` |
| `androidx.fragment:fragment` | `1.8.9` |
| `androidx.localbroadcastmanager:localbroadcastmanager` | `1.1.0` |
| `androidx.wear:wear` | `1.4.0` |
| `com.google.android.gms:play-services-wearable` | `20.0.1` |
| `junit:junit` | `4.13.2` |
| AndroidX Test runner / JUnit / Espresso | `1.7.0` / `1.3.0` / `3.7.0` |

All versions are exact. `jcenter()`, dynamic dependencies, support-library artifacts, and the removed
`wearApp` embedding configuration are absent.

## AndroidX and AGP adaptations

- Module namespaces are explicit and manifest `package` attributes are removed.
- `android.useAndroidX=true`; Jetifier is explicitly disabled because no legacy support-library
  binary remains.
- Java/XML support-library references use AndroidX.
- BuildConfig generation is disabled because no module uses it.
- The one resource-ID switch is now an `if`, compatible with AGP non-final resource IDs.
- Wear colors previously supplied transitively by the old support library are local resources.
- API-31 launcher components declare `android:exported`.
- The recorder service is app-private and declares `health|location` foreground-service types.
- `Context.MODE_PRIVATE` replaces a mismatched SQLite flag whose integer value was also zero.
  `SqlLoggerOpenModeTest` proves the compile-only adaptation preserves the prior value.

No `SportLoggerService` recording operation, `StopWatch`, STOP/export path, recovery state,
listener ownership, Data Layer delivery, or database query/transaction behavior is redesigned.
The service changes are limited to the AndroidX `NotificationCompat` signature, a local log tag,
and the immutable `PendingIntent` flag required by current targets.

## Independent publication versioning

Phone and Wear APKs retain the same package name and product version name (`1.0`) but publish from
separate monotonically increasing version-code ranges:

| Artifact | Formula | Current code | Reserved range |
|---|---:|---:|---:|
| Phone | `1,000,000 + phoneReleaseSequence` | `1,000,001` | `1,000,001`–`1,999,999` |
| Wear | `2,000,000 + wearReleaseSequence` | `2,000,001` | `2,000,001`–`2,999,999` |

Increment only the sequence for the artifact being published. Never reuse or decrease a code.
`verifyPublishedVersioning` fails builds for package/version-name mismatch, reused lanes, invalid
sequences, overlap ordering, or Google Play's `2,100,000,000` maximum. Application `preBuild`
tasks depend on this verification.

## Permission and foreground-service policy

The existing recorder reads heart rate, steps, and precise location, and continues while its
activity is no longer visible. Runtime requests are ordered so foreground permissions are resolved
before background sensor access and before the foreground service starts.

| Runtime/device branch | Required recording permissions |
|---|---|
| API 26–28 | `BODY_SENSORS`, coarse location, fine location |
| API 29–32 | prior permissions plus `ACTIVITY_RECOGNITION` |
| API 33–35, target 35 | prior permissions, then `BODY_SENSORS_BACKGROUND` separately |
| API 36, current Wear target 35 | legacy body-sensor pair remains target-policy compatible |
| API 36, future Wear target 36 | `READ_HEART_RATE`, then `READ_HEALTH_DATA_IN_BACKGROUND` |

`POST_NOTIFICATIONS` is requested on API 33+, but denial does not block recording. The UI explains
that Android may show the foreground service only in Active apps. Required-permission denial leaves
the recorder unstarted and displays a specific message; it is never treated as success.

Both legacy and API-36 health permissions are declared. Because the shipped Wear artifact still
targets 35, the legacy declarations are intentionally not capped with `maxSdkVersion=35`; an API-36
Wear OS 6 emulator confirmed that target-35 compatibility maps the legacy requests to the health
permission controller. Issue #16 must add those caps when it changes the Wear target to 36.

The manifest also declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, and
`FOREGROUND_SERVICE_LOCATION`. The existing two-argument `startForeground()` call inherits the
matching `health|location` types from the service manifest, as defined by the platform API.

`RecordingPermissionsTest` deterministically covers API 26, API 33–35, API 36 with target 35,
API 36 with target 36, separate background requests, optional notification denial, and required
permission denial. The permission planner performs no I/O.

### Location-provider degraded mode

The foreground service may start while Location is disabled. `GpsListener` now treats that as a
nonfatal no-route state: heart-rate, step, timer, and persistence work continue, while the listener
logs a location-disabled status distinct from missing runtime permission. The legacy service/UI has
no route-availability callback, so this correction uses its existing listener logging convention
rather than widening the service or activity contract.

Registration resolves a non-null, currently enabled provider before requesting updates. A provider
that disappears during last-location lookup or registration is handled through the documented
`IllegalArgumentException` boundary; permission revocation is handled through `SecurityException`.
Candidate listeners are removed after either race, repeated starts do not register a second
listener, and repeated stops are harmless. Provider-mode broadcasts retry resolution, so enabling
Location recovers the route listener without restarting the recording service. Other runtime
failures are not caught.

`LocationRegistrationTest` uses the existing local JUnit stack to deterministically cover no
providers, a null best provider, a disabled provider, provider disappearance during registration,
permission revocation during registration, successful registration, repeated start/stop,
re-enable/retry, duplicate-callback prevention, and propagation of unexpected failures.

## Command-line build

Install JDK 17, Android SDK Platform 36, and Build Tools 36.0.0, then set:

```bash
export JAVA_HOME=/path/to/temurin-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

Verify and build both variants:

```bash
./gradlew --version --no-daemon
./gradlew clean verifyPublishedVersioning \
  assembleDebug assembleRelease test lint \
  --no-daemon --stacktrace
```

Outputs:

- `mobile/build/outputs/apk/debug/mobile-debug.apk`
- `mobile/build/outputs/apk/release/mobile-release-unsigned.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk`
- `wear/build/outputs/apk/release/wear-release-unsigned.apk`
- `utilities/build/outputs/aar/{debug,release}/`

Release APKs are intentionally unsigned; release signing remains an external publication concern.
The same command runs in `.github/workflows/android.yml` with pinned action commits.

## Android Studio import and run

1. Open the repository root containing `settings.gradle`, not an individual module.
2. Set **Gradle JDK** to a JDK 17 installation.
3. In SDK Manager, install Android SDK Platform 36 and Build Tools 36.0.0.
4. Sync the project. Android Studio must use the checked-in Gradle wrapper.
5. Run `mobile` on an API-36 phone image.
6. Run `wear` on a Wear OS 6/API-36 image.

Phone and Wear intentionally share `com.long2know.sportlogger`, so use separate devices/AVDs. Do
not install both APKs onto one emulator.

## Emulator install/launch smoke

Start one emulator at a time and obtain its serial with `adb devices`. The checked-in helper
installs the APK, cold-launches `MainActivity`, waits five seconds, verifies that the process
survives, and scans its log for a fatal exception:

```bash
ADB="$ANDROID_HOME/platform-tools/adb" \
  ./scripts/android-emulator-smoke.sh emulator-5554 \
  mobile/build/outputs/apk/debug/mobile-debug.apk

ADB="$ANDROID_HOME/platform-tools/adb" \
  ./scripts/android-emulator-smoke.sh emulator-5556 \
  wear/build/outputs/apk/debug/wear-debug.apk
```

On a fresh Wear install, grant the foreground group first, then choose **All the time** for
background fitness data. To confirm the recorder service after grants:

```bash
adb -s emulator-5556 shell dumpsys activity services \
  com.long2know.sportlogger
```

## Validation evidence

Local software-emulator/build validation on 2026-07-27:

- `./gradlew clean assembleDebug assembleRelease test lint --no-daemon --stacktrace`: **passed**;
  the `preBuild` dependency also ran `verifyPublishedVersioning`. The run completed 273 actionable
  tasks (262 executed, 11 up-to-date).
- Unit tests: 10 XML reports, **42 executions** (21 unique test methods across debug/release),
  0 failures, 0 errors, 0 skipped.
- Lint: **0 errors**, 81 unsuppressed legacy warnings (mobile 6, Wear 70, utilities 5); no baseline
  or suppression was added.
- API-36 phone emulator: debug APK installed; cold launch reported `Status: ok`; process remained
  alive with no fatal app log.
- Wear OS 6/API-36 emulator: with recording permissions granted and Location disabled, cold launch
  reported `Status: ok`; `SportLoggerService` remained foreground with inherited types `0x108`
  (`health|location`), and `GpsListener` reported the nonfatal no-route state. Starting a recording
  continued one-second persistence without a fatal or null-provider exception. Enabling Location
  kept the same process, transitioned the route status to available, and produced exactly one
  active app location listener in `dumpsys location`. Phone and Wear emulators were run
  sequentially and stopped after validation.

Generated build outputs and temporary AVD files are not committed.

## Primary evidence

- [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes)
- [Gradle 8.11.1 release notes](https://docs.gradle.org/8.11.1/release-notes.html)
- [Gradle 8.11.1 checksum](https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256)
- [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Wear OS health permissions](https://developer.android.com/health-and-fitness/health-services/permissions)
- [Foreground-service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Wear OS packaging](https://developer.android.com/training/wearables/packaging)
- [Android app versioning](https://developer.android.com/studio/publish/versioning)
- [AndroidX Core releases](https://developer.android.com/jetpack/androidx/releases/core)
