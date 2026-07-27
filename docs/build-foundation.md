# Supported Android build foundation

This report records the reproducible build selected for issue #4. The recovery intentionally keeps
the Groovy build scripts and the existing `mobile`, `wear`, and `utilities` modules. It does not add
Kotlin, Compose, Hilt, Room, protobuf, convention plugins, version catalogs, or new product modules.

## Selected tuple

Verified locally on 2026-07-26:

| Component | Exact selection |
|---|---|
| JDK | Eclipse Temurin `17.0.20+8` |
| Gradle | `8.11.1` |
| Gradle distribution SHA-256 | `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6` |
| Android Gradle Plugin | `8.10.1` |
| Android compile SDK | API `36` |
| Android build tools | `36.0.0` |
| Phone target SDK | API `36` |
| Wear target SDK | API `35` |
| Phone/Wear minimum SDK | API `26` |
| Utilities minimum SDK | API `23` |
| Java source/bytecode target | Java `17` |

AGP 8.10 is the lower-risk documented family that supports API 36 and requires Gradle 8.11.1 and
JDK 17. Google Maven metadata publishes `8.10.1` as the final patch in that family. The spike used
that patch instead of combining independently latest AGP, Gradle, and JDK releases.

The phone targets API 36 for the Google Play deadline beginning August 31, 2026. Wear targets API 35,
which is the distinct Wear OS requirement for that deadline and retains the legacy
`BODY_SENSORS`/`BODY_SENSORS_BACKGROUND` contract. On API 33 and newer, `BODY_SENSORS` is
while-in-use only for this target, so the background grant is also required before the recorder can
run beyond the visible activity. Runtime code requests the foreground body-sensor permission first
and makes the background permission a separate runtime request; Android ignores a combined request.

Targeting Wear API 36 would opt the recorder into `READ_HEART_RATE` plus
`READ_HEALTH_DATA_IN_BACKGROUND`. Both modern permissions are declared, and the permission helper
and tests define that target-36 transition without claiming unperformed physical Wear OS 6
permission, foreground-service, screen-off, or sensor validation.

## Play publication versioning

Google's Wear OS packaging documentation says that phone and watch APKs are uploaded and updated
independently, and that a watch version code must be unique across all form factors. Google's
multiple-APK rules additionally require the same package name and signing key, a different version
code for every APK, and a higher code for the preferred APK when device coverage overlaps.

The two application modules therefore share one user-visible product version name, `1.0`, but use
separate monotonically increasing version-code ranges:

| Artifact | Formula | Current sequence | Current code | Reserved codes |
|---|---:|---:|---:|---:|
| Phone | `1,000,000 + phoneReleaseSequence` | `1` | `1,000,001` | `1,000,001`–`1,999,999` |
| Wear | `2,000,000 + wearReleaseSequence` | `1` | `2,000,001` | `2,000,001`–`2,999,999` |

For a release, increment only the sequence for the artifact being published; never reuse or
decrease either sequence. The disjoint ranges allow phone-only or watch-only fixes without
renumbering the other artifact, and every Wear code remains higher than every phone code so a
watch receives the Wear artifact if manifest coverage ever overlaps. A sequence must remain between
`1` and `999,999`; exhausting a range requires a deliberate scheme migration before publishing.

The root `verifyPublishedVersioning` task reads the application modules' configured Gradle metadata
and fails on a package-name mismatch, product-version-name mismatch, equality, wrong range, wrong
formula, reversed overlap preference, or a code above Google Play's `2,100,000,000` limit. Both
application `preBuild` tasks depend on it, so normal assemblies also enforce the scheme.

## Recording permission gate

Android 10 (API 29) introduced the `ACTIVITY_RECOGNITION` runtime permission for physical-activity
data. Android's privacy documentation identifies the step counter and step detector as the built-in
sensors that require it, and the current step-counter guide requires the grant before sensor access.
The Wear manifest now declares that permission and startup follows this matrix:

- API 29 and newer require `ACTIVITY_RECOGNITION`; older devices do not request it.
- Heart rate uses `BODY_SENSORS` unless both the device and target SDK are API 36 or newer, when it
  uses `READ_HEART_RATE`.
- On API 33 and newer with target SDK 33 through 35, background heart rate additionally requires
  `BODY_SENSORS_BACKGROUND`.
- When both device and target SDK are API 36 or newer, background heart rate instead requires
  `READ_HEALTH_DATA_IN_BACKGROUND`.
- Coarse and fine location remain required for recording.
- `POST_NOTIFICATIONS` is requested on API 33 and newer but remains optional for recording.

Foreground sensor, activity-recognition, location, and optional notification permissions may remain
in the existing grouped request. A required background sensor permission is never included in that
array: it is requested only after the foreground recording permissions have been granted. This
ordering preserves older API behavior and satisfies Android's separate-operation requirement.

Because this foreground service deliberately keeps collecting heart rate after the activity leaves
the foreground, recording remains gated when the background sensor grant is denied. The start screen
stays non-recording and an explicit message directs the user to allow all-the-time sensor access in
Settings. The app itself remains usable, but it does not start the recorder service or claim
background heart-rate capture.

`BODY_SENSORS_BACKGROUND` is a hard-restricted permission: the installer of record must allowlist it
before the user can grant it. A sideloaded or otherwise non-allowlisted build therefore remains
explicitly gated rather than silently recording without background heart rate. Installer
allowlisting and the device Settings flow require Play/OEM and physical-device validation.

The service checks the complete permission set before foreground startup, before starting or
resuming a recording, and immediately before every scheduled track-point write. The sensor thread
also handles a permission-race `SecurityException`.

Recording now has an explicit single-generation state machine. Start, pause, resume, stop, discard,
and shutdown transitions are idempotent: duplicate UI actions are no-ops while invalid cross-state
actions are rejected. A writer generation captures its activity ID before scheduling, and every SQL
insert uses that immutable ID instead of consulting the mutable shared `ActivityId`. A process-wide
writer coordinator permits at most one generation and fences cancellation plus an in-flight write
before pause, stop/export, discard/delete, permission-loss completion, or a later recording. The
fence is bounded to two seconds and honors interruption. If it times out or otherwise fails, the
operation returns a typed failure, keeps the activity and database rows, and does not export or
delete as though shutdown succeeded.

Recovery metadata is synchronously committed to private `SharedPreferences` as the exact activity
ID, last writer generation, and phase. This metadata is not service ownership: writer and listener
ownership remain in the existing fenced coordinators. A replacement service first validates that
the activity row still exists, restores the writer-generation floor, marks the tuple as recovery
required, and fences only that exact prior generation. Paused controls appear only after the writer
is quiescent and the replacement listener group acknowledges startup. The same activity ID can then
be resumed with a newer generation, stopped/exported, or discarded. An uncaught scheduled-write
failure is surfaced as `WRITER_FAILED`; once its failed generation is confirmed terminated, the
retained partial activity may be resumed, stopped, or discarded without overlapping writes.

A listener or service-startup failure before an activity row exists remains `IDLE`, clears the
non-authoritative shared UI mirror, and returns to the start screen. A failure with an owned activity
preserves the exact tuple. If either exact-generation or listener termination cannot be proven, the
state becomes `RECOVERY_REQUIRED` and the Wear UI shows a retry-only recovery screen rather than
success-shaped paused controls. Retry repeats the bounded fences; resume, stop, and discard remain
invalid until recovery succeeds.

The retained tuple survives process death, but stopwatch elapsed time and live sensor samples remain
in memory and may restart from their last displayed/default values. A process death in the narrow
legacy interval between SQLite row insertion and the synchronous metadata commit can leave an
unclaimed unfinished row; the app does not guess that row's ownership. Commit or rollback failures
are kept in the explicit recovery state while the current service can still retain the known ID.

Sensor and GPS loopers are owned by one service-instance listener group. Replacement first disables
the old generation, unregisters both listener sets, requests safe looper quit, and waits for both
termination acknowledgements within the same two-second bound. A timed-out owner remains registered
as the owner, so no replacement starts beside it; a stale service release cannot clear a newer
owner. The service client is also cleared by identity, preventing an old activity instance from
disconnecting its replacement.

A detected revocation first pauses callback production, then obtains the bounded writer and listener
fences off the main thread. Only after both succeed does it reset the stopwatch, notify the activity,
and stop the service. An owned activity's recovery tuple remains paused so a service created after a
permission regrant can restore its controls. Reset cancels the stopwatch callback, clears the shared
duration to `00:00:00`, and invalidates any stale callback that was already dequeued. Once
permission-loss teardown begins, that service instance rejects start and resume, so a regrant cannot
race old callbacks or scheduled writes into a later recording. A timeout is surfaced through
`RecordingOperationResult` and retains the activity in the retry-only recovery state rather than
presenting permission shutdown as successful.

`SensorFragment` uses one main-thread handler through a generation-guarded callback loop. Repeated
resume/start calls cannot create parallel chains, and pause, permission-loss state, view destruction,
or fragment destruction removes the callback through the same handler and prevents stale callbacks
from rescheduling.

## Pinned direct dependencies

| Dependency | Version | Modules |
|---|---:|---|
| `androidx.appcompat:appcompat` | `1.7.1` | mobile |
| `androidx.constraintlayout:constraintlayout` | `2.2.1` | mobile |
| `androidx.core:core` | `1.17.0` | mobile, wear |
| `androidx.fragment:fragment` | `1.8.9` | mobile, wear |
| `androidx.localbroadcastmanager:localbroadcastmanager` | `1.1.0` | mobile |
| `androidx.wear:wear` | `1.4.0` | wear |
| `com.google.android.gms:play-services-wearable` | `20.0.1` | mobile, wear |
| `junit:junit` | `4.13.2` | all modules |
| `androidx.test:runner` | `1.7.0` | all modules |
| `androidx.test.ext:junit` | `1.3.0` | all modules |
| `androidx.test.espresso:espresso-core` | `3.7.0` | all modules |

No dependency uses a dynamic version. Explicit Core and Fragment versions also align transitive
Kotlin runtime dependencies used by AndroidX; the project itself applies no Kotlin plugin and
contains no Kotlin source.

## Baseline failure

With the repository's original Gradle 4.10.1 and AGP 3.3.1 files, this command failed after locating
the Android SDK:

```bash
JAVA_HOME=/home/long2know/.local/share/jdks/temurin-17 \
ANDROID_HOME=/home/long2know/.local/share/android-sdk \
./gradlew tasks --stacktrace --no-daemon
```

Configuration of `:mobile` ended in `ExceptionInInitializerError`. AGP's JAXB initialization tried
to reflectively access `ClassLoader.defineClass`, and JDK 17 rejected it with
`InaccessibleObjectException` because `java.base` does not open `java.lang` to the unnamed module.
This confirms that a wrapper-only change or a JDK module-opening workaround would not be a supported
foundation.

## Migration performed

1. Upgraded AGP and the wrapper atomically, regenerated the wrapper JAR/scripts, and pinned the
   Gradle distribution checksum.
2. Replaced `jcenter()` with `google()` and `mavenCentral()`.
3. Added module namespaces, API 36 compilation, pinned build tools, and Java 17 compatibility.
4. Migrated Java imports, XML widget names, instrumentation tests, and dependencies from the Android
   support libraries to AndroidX.
5. Added explicit `android:exported` values required by API 31 and made the recorder service
   app-private.
6. Added notification permission/channel handling, immutable `PendingIntent` use, and explicit
   `health|location` foreground-service permissions, manifest types, and runtime types.
7. Added target/API-aware foreground and background health permissions. Wear target 35 uses
   `BODY_SENSORS` followed by a separate `BODY_SENSORS_BACKGROUND` request on API 33+, while the API
   36 transition uses `READ_HEART_RATE` and `READ_HEALTH_DATA_IN_BACKGROUND`. Heart-rate,
   activity-recognition, location, and required background access are granted before the recorder
   service or its sensors start.
8. Replaced the removed `wearApp` packaging configuration. `mobile` and `wear` remain independently
   installable APKs with the unchanged application ID `com.long2know.sportlogger`.
9. Added disjoint, independently incremented phone/Wear version-code ranges and wired their
   publication invariant check into application builds.
10. Adapted the legacy resource-ID switch for AGP's non-final resource IDs, restored colors formerly
   supplied transitively by the old Wear library, and corrected the SQLite open mode constant that
   blocked modern lint.

Removing `wearApp` means the phone APK no longer embeds a Wear APK. Play-distributed phone and watch
artifacts must be published independently with matching package identity and compatible signing;
the existing Data Layer behavior and application IDs are otherwise retained.

## Reproduction

```bash
export JAVA_HOME=/home/long2know/.local/share/jdks/temurin-17
export ANDROID_HOME=/home/long2know/.local/share/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"

./gradlew --version --no-daemon
./gradlew verifyPublishedVersioning :wear:testDebugUnitTest --no-daemon
./gradlew clean assembleDebug test lint --no-daemon
```

The final clean local run completed successfully with 202 actionable tasks. Twenty-four unit-test
reports contained 96 tests with zero failures, errors, or skips. Lint completed with zero errors and
82 unsuppressed warnings (6 mobile, 71 Wear, and 5 utilities).

The clean build produces:

- `mobile/build/outputs/apk/debug/mobile-debug.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk`
- `utilities/build/outputs/aar/utilities-debug.aar`

The same tasks run in `.github/workflows/android.yml`, which installs API 36/build tools 36.0.0,
uses Temurin 17.0.20+8, and validates the checked-in wrapper before building. Workflow actions are
pinned to the release commits for Checkout 7.0.1, Setup Java 5.6.0, Setup Android 4.0.1, and Gradle
Actions 6.2.0.

## Known behavior intentionally not modernized

- Recording still uses the existing raw `SensorManager` and `LocationManager` implementation.
- Watch-to-phone transfer still uses the existing Java-serialized Data Item/Asset path and has no
  durable acknowledgement protocol.
- Persistence remains the existing raw SQLite implementation; Room and legacy-data ETL are later
  work.
- The phone still uses deprecated in-process `LocalBroadcastManager` behavior to preserve the
  current event flow.
- Lint has no baseline and no issue suppression. It reports non-blocking legacy warnings including
  static context retention, locale-unspecified machine timestamps, Wear accessibility/localization
  debt, and launcher-icon modernization.
- The installed SDK emits a non-fatal SDK XML version warning with this older supported AGP family.
- No emulator, physical Wear OS device, Play Console submission, sensor accuracy, screen-off
  recording, or Data Layer delivery test is claimed by this build-only foundation.

## Primary evidence

- [AGP 8.10 release notes and compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes)
- [Google Maven AGP metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)
- [Gradle 8.11.1 release notes](https://docs.gradle.org/8.11.1/release-notes.html)
- [Gradle 8.11.1 distribution checksum](https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256)
- [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Package and distribute Wear OS apps](https://developer.android.com/training/wearables/packaging)
- [Google Play multiple-APK rules and version-code schemes](https://developer.android.com/google/play/publishing/multiple-apks)
- [Set app version information](https://developer.android.com/studio/publish/versioning#appversioning)
- [Android 10 physical activity recognition](https://developer.android.com/about/versions/10/privacy/changes#physical-activity-recognition)
- [Read step-count data with SensorManager](https://developer.android.com/health-and-fitness/fitness/basic-app/read-step-count-data)
- [Wear Health Services API 36 permission migration](https://developer.android.com/health-and-fitness/health-services/permissions)
- [Request background access to body sensor data](https://developer.android.com/health-and-fitness/health-services/background-body-sensors)
- [`BODY_SENSORS_BACKGROUND` permission reference](https://developer.android.com/reference/android/Manifest.permission#BODY_SENSORS_BACKGROUND)
- [Foreground service type runtime requirements](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 14 foreground-service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [AndroidX AppCompat releases](https://developer.android.com/jetpack/androidx/releases/appcompat)
- [AndroidX Core releases](https://developer.android.com/jetpack/androidx/releases/core)
- [AndroidX Fragment releases](https://developer.android.com/jetpack/androidx/releases/fragment)
- [AndroidX Wear releases](https://developer.android.com/jetpack/androidx/releases/wear)
- [Google Maven Play Services Wearable metadata](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-wearable/maven-metadata.xml)
