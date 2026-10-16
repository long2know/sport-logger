# Open-source and platform research

**Research timestamp:** `2026-07-25T21:02:06.284-07:00`
**Owner:** Mouse, Research & Quality Engineer
**Scope:** Public primary documentation, release metadata, repository source, and released
artifacts. No physical-watch measurements, Play Console submission, or live Endurain instance
calls were performed.

## Evidence rules

- **Verified** means the claim is present in a current first-party document, released source
  artifact, release page, or canonical repository at the timestamp above.
- **Static inspection** means source establishes the intended behavior, but this report did not
  execute it.
- **Not found** means the named public surface was absent from the released source inspected; it is
  not a claim that no private or future API can exist.
- Repository stars and forks are a volatile snapshot, included only because the roadmap asks for
  them. Maintenance dates, releases, tests, and architecture are stronger adoption signals.

## Executive findings: corrections that block contract freeze

1. `DataType.HEART_RATE_VARIABILITY` does not exist in the public Health Services
   `1.1.0-rc02` API. Public Health Services also has no SpO2 or skin-temperature data type.
   Building those into the cross-device Health Services contract would create non-existent APIs.
2. Health Services is an exercise/fused-metric API, not a replacement for every
   `SensorManager` path. Raw pressure, accelerometer, gyroscope, and magnetometer data still need a
   raw sensor path; SpO2 and skin temperature require a vendor path.
3. The roadmap's toolchain is obsolete. Current stable releases include AGP `9.3.1`, Gradle
   `9.6.1`, Kotlin `2.4.10`, Compose BOM `2026.06.01`, and Wear Compose `1.6.2`, but those
   independently latest values are not one proven tuple. AGP 9 uses built-in Kotlin, while
   Kotlin's published compatibility table only fully supports KGP `2.4.10` through AGP `9.1.0`
   and Gradle `9.5.0`. A build spike must select the tuple.
4. Android 17/API 37 remains a preview SDK. The production baseline should stay on stable API 36
   unless the team explicitly accepts preview risk. Starting 2026-08-31, Play requires phone
   submissions to target API 36+, while Wear OS submissions must target API 35+.
5. Health Connect stable `1.1.0` has `PERMISSION_WRITE_EXERCISE_ROUTE` and
   `ExerciseRouteRequestContract`, but not
   `HealthPermission.PERMISSION_READ_EXERCISE_ROUTES`. That constant is present in
   `1.2.0-alpha04`; code and acceptance tests must match the selected dependency.
6. The correct Health Connect calorie record is `TotalCaloriesBurnedRecord`, not
   `TotalCaloriesRecord`. A user-started workout is `Metadata.activelyRecorded`, not
   automatically recorded. `Device` metadata has type/manufacturer/model, not an app-version
   field.
7. Endurain does not expose a native JSON activity-create API in release `v0.19.0`. Its usable
   connector surface is multipart GPX/TCX/FIT/GZ upload. Upload is not idempotent, its returned
   IDs are instance-local integers, and API keys can upload but cannot read, update, or delete.
8. OpenTracks has no first-party Wear OS module or Data Layer companion. It is a strong Apache-2.0
   phone-tracker reference, not evidence for watch-phone architecture.
9. Garmin's FIT Java SDK uses a custom FIT Protocol License Agreement, not an OSI open-source
   license. Do not ship that SDK without legal review; TCX is the lower-risk Endurain MVP format.

---

## 1. Repository baseline

The checked-in project is materially older than the proposed foundation:

| Item | Current repository evidence |
|---|---|
| Build plugin | AGP `3.3.1` and `jcenter()` in [`build.gradle`](../../build.gradle) |
| Wrapper | Gradle `4.10.1` in [`gradle-wrapper.properties`](../../gradle/wrapper/gradle-wrapper.properties) |
| Phone | compile/target API 28, support libraries, single `mobile` app module in [`mobile/build.gradle`](../../mobile/build.gradle) |
| Watch | compile/target API 28, support libraries, single `wear` app module in [`wear/build.gradle`](../../wear/build.gradle) |

This is not a wrapper-only update. AGP, Gradle, repositories, AndroidX, Java/Kotlin compilation,
manifest behavior, tests, and packaging all change across this jump.

---

## 2. Maintained open-source tracker survey

### Classification

| Class | Projects |
|---|---|
| Full tracker with a first-party Wear OS and phone app | SportsTracker; RunnerUp; Elevate Fitness |
| Full tracker, phone-only | OpenTracks; FitoTrack |
| Official samples, not full trackers | Android Health Samples; Wear OS Samples |
| Library/toolkit, not a tracker | Horologist |
| Server/connector target, not a device tracker | Endurain |

### 2.1 SportsTracker — full watch-first tracker

- **Canonical repository:** [Codeberg: windkracht8/SportsTracker](https://codeberg.org/windkracht8/SportsTracker)
- **License:** `GPL-3.0-or-later`; see [LICENSE](https://codeberg.org/windkracht8/SportsTracker/src/branch/main/LICENSE)
  and the explicit “version 3 or later” source headers.
- **Maintenance:** latest checked commit
  [`15b0658`](https://codeberg.org/windkracht8/SportsTracker/commit/15b065848c04d202de9304289beba5a9c1a68ba4)
  on 2026-07-25. No tagged release was found through the canonical repository at the check time.
  Snapshot: 0 stars / 0 forks from the
  [Codeberg API](https://codeberg.org/api/v1/repos/windkracht8/SportsTracker).
- **Languages/modules:** Kotlin; `:mobile` and `:wear` are declared in
  [`settings.gradle.kts`](https://codeberg.org/windkracht8/SportsTracker/src/branch/main/settings.gradle.kts).
- **Watch-phone architecture:** the watch declares itself standalone and owns exercise recording.
  [`RecordActivity.kt`](https://codeberg.org/windkracht8/SportsTracker/src/branch/main/wear/src/main/java/ui/record/RecordActivity.kt)
  uses Health Services `ExerciseClient`, exercise capabilities, location, heart rate, totals, and
  availability callbacks. Watch and phone synchronize through custom Bluetooth RFCOMM, not Wear
  Data Layer; [`CommsBT.kt`](https://codeberg.org/windkracht8/SportsTracker/src/branch/main/mobile/src/main/java/CommsBT.kt)
  uses RSA-OAEP plus AES-GCM.
- **Reusable patterns:** watch-owned completed sessions; explicit exercise-type/data-type
  capability checks; local watch history; phone-side import/review flow.
- **Adoption risks:** very young project, no verified release, no community adoption signal, and
  GPL is incompatible with copying source into this Apache-2.0 project without changing licensing.
  The custom transport and cryptography should be studied, not copied. Static inspection also
  found a target-36 watch manifest still declaring legacy `BODY_SENSORS` without the API-36
  heart-rate permission split, so this project is not a normative permissions reference.

### 2.2 RunnerUp — full phone tracker with dependent Wear companion

- **Canonical repository:** [GitHub: jonasoreland/runnerup](https://github.com/jonasoreland/runnerup)
- **License:** first-party source headers state `GPL-3.0-or-later`; see the
  [Wear manifest](https://github.com/jonasoreland/runnerup/blob/master/wear/src/main/AndroidManifest.xml).
  The [README](https://github.com/jonasoreland/runnerup#license) says GNU GPL v3 and points to a
  `LICENSE` file that is absent at the checked commit; use
  [CREDITS](https://github.com/jonasoreland/runnerup/blob/master/CREDITS.md) for differently
  licensed components.
- **Maintenance:** latest checked commit
  [`396f9c8`](https://github.com/jonasoreland/runnerup/commit/396f9c83b727b503f3c466b70a6ec18e162a7f03)
  on 2026-07-17; latest release
  [`v2.11.0.1`](https://github.com/jonasoreland/runnerup/releases/tag/v2.11.0.1_free) on
  2026-02-21. Snapshot: 941 stars / 309 forks.
- **Languages/modules:** Java-dominant Gradle project with `:app`, `:common`, `:hrdevice`, and
  `:wear`; see [`settings.gradle`](https://github.com/jonasoreland/runnerup/blob/master/settings.gradle).
- **Watch-phone architecture:** the phone records the activity. The watch manifest explicitly sets
  `com.google.android.wearable.standalone=false`; it presents controls/status and listens for phone
  state. [`WearableClient.java`](https://github.com/jonasoreland/runnerup/blob/master/common/src/wear/java/org/runnerup/wear/WearableClient.java)
  uses `DataClient`, `DataMap`, versioned paths, reads, puts, and deletes.
- **Reusable patterns:** persistent Data Layer state snapshots; listener-service recovery after
  process restart; shared protocol constants/models; explicit “connect to phone” states.
- **Adoption risks:** not a standalone-watch architecture, no modern Health Services exercise
  acquisition, Java/View-era UI, and GPL source cannot be copied into this Apache-2.0 project.
  Reuse the protocol/test ideas, not implementation.

### 2.3 Elevate Fitness / PerfectGymCoach — modern gym tracker with Wear companion

- **Canonical repository:** [GitHub: alessioGalatolo/PerfectGymCoach](https://github.com/alessioGalatolo/PerfectGymCoach)
- **License:** project-declared GPL v3 (`GPL-3.0`); see
  [README](https://github.com/alessioGalatolo/PerfectGymCoach#license) and
  [LICENSE](https://github.com/alessioGalatolo/PerfectGymCoach/blob/main/LICENSE).
- **Maintenance:** latest checked commit
  [`bf65190`](https://github.com/alessioGalatolo/PerfectGymCoach/commit/bf65190b422f918230b62f9979d7a28168c2578b)
  on 2026-06-25. No GitHub release was published at the check time. Snapshot: 58 stars / 10 forks.
- **Languages/modules:** Kotlin and protobuf; `:app`, `:wear`, and `:shared` in
  [`settings.gradle.kts`](https://github.com/alessioGalatolo/PerfectGymCoach/blob/main/settings.gradle.kts).
  The version catalog shows Compose, Room, Health Services, Health Connect, Horologist, and gRPC.
- **Watch-phone architecture:** typed bidirectional services over Horologist's Wear Data Layer gRPC
  support. The watch
  [`DataLayerModule.kt`](https://github.com/alessioGalatolo/PerfectGymCoach/blob/main/wear/src/main/java/agdesigns/elevatefitness/di/DataLayerModule.kt)
  targets the paired phone; the phone exposes reciprocal services. Watch acquisition uses Health
  Services; phone export uses
  [`HealthConnectRepository.kt`](https://github.com/alessioGalatolo/PerfectGymCoach/blob/main/app/src/main/java/agdesigns/elevatefitness/data/HealthConnectRepository.kt).
- **Reusable patterns:** shared protobuf contracts; typed command/query services; phone/watch app
  helpers; separating watch acquisition from phone Health Connect publication.
- **Adoption risks:** gym-oriented rather than GPS endurance-oriented; no stable app release;
  `Health Connect 1.2.0-alpha04` and `Horologist 0.8.3-alpha` are preview dependencies; Horologist
  APIs used are annotated experimental; GPL source is pattern-study only.

### 2.4 OpenTracks — full phone tracker, no first-party Wear app

- **Canonical repository:** [Codeberg: OpenTracksApp/OpenTracks](https://codeberg.org/OpenTracksApp/OpenTracks)
- **License:** `Apache-2.0`; see
  [LICENSE](https://codeberg.org/OpenTracksApp/OpenTracks/src/branch/main/LICENSE).
- **Maintenance:** latest checked commit
  [`47fea69`](https://codeberg.org/OpenTracksApp/OpenTracks/commit/47fea690c91910b482419b77e124cd6f3ea560a2)
  on 2026-07-24; latest release
  [`v4.28.0`](https://codeberg.org/OpenTracksApp/OpenTracks/releases/tag/v4.28.0) on
  2026-07-06. Snapshot: 119 stars / 21 forks from the
  [Codeberg API](https://codeberg.org/api/v1/repos/OpenTracksApp/OpenTracks).
- **Languages/modules:** Java-dominant, single Android application rooted at
  [`build.gradle`](https://codeberg.org/OpenTracksApp/OpenTracks/src/branch/main/build.gradle).
  The complete checked tree had no Wear module or Wear source set.
- **Watch-phone architecture:** none. The phone records GPS/sensors. The
  [README](https://codeberg.org/OpenTracksApp/OpenTracks/src/branch/main/README.md) describes
  optional statistics on watches through Gadgetbridge, not a first-party Wear OS companion.
- **Reusable patterns:** BLE sensor abstraction; GPX/KML/KMZ import/export; stable
  `opentracks:trackid`; recording-service hardening; WorkManager export. Its
  [`HealthConnectWorker`](https://codeberg.org/OpenTracksApp/OpenTracks/src/branch/main/src/main/java/de/dennisguse/opentracks/io/healthconnect/exporter/HealthConnectWorker.java)
  uses the track UUID as `clientRecordId`, `Metadata.activelyRecorded`, and an
  `ExerciseSessionRecord` with route.
- **Adoption risks:** no watch/Data Layer evidence and a Java/single-module architecture. Unlike
  the GPL projects, Apache-2.0 source can be considered for reuse if notices and attribution are
  preserved, but it still requires review and adaptation.

### 2.5 FitoTrack — full phone tracker, no Wear OS module

- **Canonical repository:** [Codeberg: jannis/FitoTrack](https://codeberg.org/jannis/FitoTrack)
- **License:** `GPL-3.0-or-later`; see
  [README](https://codeberg.org/jannis/FitoTrack/src/branch/master/README.md#license) and
  [LICENSE.txt](https://codeberg.org/jannis/FitoTrack/src/branch/master/LICENSE.txt).
- **Maintenance:** latest checked commit
  [`eeb94f0`](https://codeberg.org/jannis/FitoTrack/commit/eeb94f01c005c711a1667ac36fd00a403a72763f)
  on 2026-07-09; latest release
  [`v16.2`](https://codeberg.org/jannis/FitoTrack/releases/tag/v16.2) on 2026-06-28.
  Snapshot: 404 stars / 88 forks from the
  [Codeberg API](https://codeberg.org/api/v1/repos/jannis/FitoTrack).
- **Languages/modules:** mixed Kotlin/Java, one `:app` module in
  [`settings.gradle`](https://codeberg.org/jannis/FitoTrack/src/branch/master/settings.gradle);
  Compose, Room, WorkManager, and BLE dependencies are visible in
  [`app/build.gradle`](https://codeberg.org/jannis/FitoTrack/src/branch/master/app/build.gradle).
- **Watch-phone architecture:** no first-party Wear OS module or Wear Data Layer. The repository
  contains a legacy Pebble integration and phone-side Bluetooth devices.
- **Reusable patterns:** incremental Java-to-Kotlin/Compose migration inside a mature recorder;
  Room-backed workout history; background export/work; BLE reconnect and denial UX.
- **Adoption risks:** does not validate Wear OS architecture; mixed legacy/modern implementation;
  GPL source is pattern-study only for this Apache-2.0 repository.

### 2.6 Official references: use for API shape, not product completeness

| Reference | Classification | Verified use |
|---|---|---|
| [android/health-samples](https://github.com/android/health-samples) | Official Apache-2.0 samples | Contains separate Health Services and Health Connect projects. This is the current first-party exercise/health API reference. |
| [android/wear-os-samples](https://github.com/android/wear-os-samples) | Official Apache-2.0 samples | `DataLayer`, `ComposeStarter`, OAuth, widgets, and other focused Wear examples. It is not a complete sports tracker, and current health tracking samples live in `health-samples`. |
| [google/horologist](https://github.com/google/horologist) | Google-maintained Apache-2.0 libraries/samples | Helpful Data Layer, UI, media, tiles, and testing utilities. Its [compatibility table](https://github.com/google/horologist#releases) says `0.7.x` is a maintenance branch on Wear Compose 1.5.x; `0.8.x`/`main` tracks Wear Compose 1.6.x and may remove or change APIs. Latest checked tag: [`v0.8.3-alpha`](https://github.com/google/horologist/releases/tag/v0.8.3-alpha). |

### 2.7 Garmin FIT SDK is not an open-source reference

Garmin's official [FIT Java SDK](https://github.com/garmin/fit-java-sdk) is public source, but its
[FIT Protocol License Agreement](https://github.com/garmin/fit-java-sdk/blob/main/LICENSE.txt) is
a custom, non-transferable/non-sublicensable license with distribution restrictions. It is not an
OSI open-source license. Treat the roadmap's “license TBD” as resolved to **legal review required
before embedding or redistributing the SDK**. Endurain accepts TCX, so FIT is not required for the
first connector.

---

## 3. Current Android and Wear OS toolchain evidence

### 3.1 Published versions and obligations

| Component | Verified state at research time | Recommendation / constraint |
|---|---|---|
| Android Gradle Plugin | [Google Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml) lists `9.3.1` as latest stable and `9.4.0-alpha06` as latest preview. [AGP 9.3 compatibility](https://developer.android.com/build/releases/agp-9-3-0-release-notes) supports through API 37, requires Gradle 9.5+, and runs on JDK 17. | `9.3.1` is the current stable candidate, not roadmap `8.7.x`. |
| Gradle | The official [current-version endpoint](https://services.gradle.org/versions/current) returns `9.6.1`. | Candidate with AGP 9.3.1; verify plugins and configuration cache in the spike. |
| Kotlin | `2.4.10` is current stable in [Maven metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml). Kotlin's [KGP compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin) lists full support for KGP 2.4.0–2.4.10 through Gradle 9.5.0 and AGP 9.1.0. | Do not combine KGP 2.4.10, AGP 9.3.1, and Gradle 9.6.1 merely because each is latest. |
| AGP built-in Kotlin | [AGP 9 migration guidance](https://developer.android.com/build/migrate-to-built-in-kotlin) says AGP 9 enables built-in Kotlin, removes the need for `org.jetbrains.kotlin.android`, and recommends KSP over incompatible `kapt`. AGP 9.3.1's [published POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.1/gradle-9.3.1.pom) depends on Kotlin/KGP `2.2.10`. | Prefer the built-in-Kotlin lane; prove Compose compiler, serialization, Hilt/KSP, and Room in one representative module before freezing versions. |
| Stable Android SDK | Android 16/API 36 is the production SDK. [Android 17 setup](https://developer.android.com/about/versions/17/setup-sdk) still labels API 37 “Cinnamon Bun Preview.” | Use `compileSdk=36` for the production foundation unless a separately tested preview lane is requested. |
| Play target deadline | [Google Play target requirements](https://developer.android.com/google/play/requirements/target-sdk) require phone new apps/updates to target API 36+ and Wear OS submissions API 35+ starting 2026-08-31. | Phone target 36 is mandatory for the next release after the deadline. Wear target 35 is the minimum; target 36 is preferable only after the permission/FGS transition passes device tests. |
| JDK / bytecode | AGP 9.3 requires JDK 17. | Keep toolchain and Java/Kotlin JVM targets aligned at 17 unless a dependency proves otherwise. |
| Compose BOM | [Google Maven metadata](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml) publishes stable `2026.06.01`; the official [BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) was updated 2026-07-14. | Pin the BOM exactly. Do not use `+`. |
| Wear Compose | [Release notes](https://developer.android.com/jetpack/androidx/releases/wear-compose) list stable `1.6.2` and preview `1.7.0-alpha06`. | Pin `1.6.2` independently; Wear Compose is not versioned by the regular Compose BOM. |
| Health Services | [AndroidX Health releases](https://developer.android.com/jetpack/androidx/releases/health) list stable `1.0.0` and candidate `1.1.0-rc02`. | Default to stable 1.0.0. Select RC02 only if its advanced running/event APIs are required and explicitly accepted as pre-stable. |
| Health Connect | [Release notes](https://developer.android.com/jetpack/androidx/releases/health-connect) list stable `1.1.0` and preview `1.2.0-alpha04`. | Use 1.1.0 for production; do not code against 1.2-only route permission constants accidentally. |
| Horologist | The [project matrix](https://github.com/google/horologist#releases) describes 0.7.x as maintenance/stable API behavior and 0.8.x/main as actively changing; latest tag is `0.8.3-alpha`. | There is no current stable Horologist line aligned to Wear Compose 1.6.x. Either isolate a pinned alpha behind project-owned wrappers or defer it. |

### 3.2 Foundation acceptance gate

Before W0-01 can freeze dependency versions, Tank should produce a reproducible build report for
one phone module and one Wear module that proves:

1. AGP/Gradle/JDK/SDK versions and the exact Kotlin/Compose compiler path.
2. Kotlin + Compose + serialization + Hilt/KSP + Room code generation in the same build.
3. `compileSdk=36`; phone `targetSdk=36`; Wear `targetSdk=35` and `36` test variants if feasible.
4. Unit tests, lint, a debug APK for both devices, and no dynamic dependency versions.
5. A documented rollback tuple. The separately current versions above are evidence inputs, not a
   pre-approved dependency catalog.

---

## 4. Wear Health Services: actual public contract

Identifiers below were checked against the released
[`health-services-client:1.1.0-rc02` source artifact](https://dl.google.com/dl/android/maven2/androidx/health/health-services-client/1.1.0-rc02/health-services-client-1.1.0-rc02-sources.jar)
and the current [`DataType` reference](https://developer.android.com/reference/kotlin/androidx/health/services/client/data/DataType).
Presence in the library does **not** mean every watch or exercise type supports the data.

### 4.1 Capability APIs that must be kept distinct

| Question | Public surface |
|---|---|
| Which exercises can this watch run? | `ExerciseCapabilities.supportedExerciseTypes` |
| Which data can this exercise type produce? | `getExerciseTypeCapabilities(type).supportedDataTypes` |
| Which one-off live measurements are supported? | `MeasureCapabilities.supportedDataTypesMeasure` |
| Which passive data/goals are supported? | `PassiveMonitoringCapabilities.supportedDataTypesPassiveMonitoring` and `supportedDataTypesPassiveGoals` |
| Is a requested stream currently producing data? | `DataTypeAvailability`: `UNKNOWN`, `AVAILABLE`, `ACQUIRING`, `UNAVAILABLE`, `UNAVAILABLE_DEVICE_OFF_BODY` |
| Is permission granted? | Android runtime permission check; not an Exercise capability |
| Does raw hardware exist? | `SensorManager.getDefaultSensor(...)` / feature check; not an Exercise capability |

[`MeasureClient`](https://developer.android.com/reference/kotlin/androidx/health/services/client/MeasureClient)
explicitly says it is for one-off measurements and must not be used for background capture or
workout tracking.

### 4.2 Measurement and capability table

The permission column follows the current
[Health Services permission table](https://developer.android.com/health-and-fitness/health-services/permissions).
“Capability required” means no universal-availability claim is valid.

| Requested measurement | Verified public identifier/source | Acquisition class | Permission / important semantics | Verdict |
|---|---|---|---|---|
| Heart rate | `DataType.HEART_RATE_BPM`; exercise stats `HEART_RATE_BPM_STATS` | Health Services exercise; measure/passive only when the corresponding capability set includes it | API <=35 `BODY_SENSORS`; API 36+ `android.permission.health.READ_HEART_RATE`; accuracy can be `HeartRateAccuracy` | Supported, capability required |
| HRV / RMSSD | No public Health Services data type named `HEART_RATE_VARIABILITY`, HRV, RMSSD, or IBI | Derived from suitable IBI data; Samsung vendor path can expose IBI | Requires a validated artifact/window algorithm and vendor permissions | **Unavailable through cross-device Health Services** |
| Steps | `STEPS`, `STEPS_TOTAL`; passive daily `STEPS_DAILY`; walking/running variants also exist | Exercise plus passive daily aggregate | `ACTIVITY_RECOGNITION` | Supported, not universal |
| Cadence | `STEPS_PER_MINUTE`, `STEPS_PER_MINUTE_STATS` | Exercise | `ACTIVITY_RECOGNITION` | Supported, capability required |
| Distance | `DISTANCE`, `DISTANCE_TOTAL`; passive `DISTANCE_DAILY`; incline/decline/flat variants also exist | Exercise/fused plus passive daily | Current Health Services table maps distance to `ACTIVITY_RECOGNITION`, not fine location | Supported, capability required |
| Pace | `PACE`, `PACE_STATS` | Exercise, platform-derived/fused | `ACTIVITY_RECOGNITION`; public value is milliseconds per kilometer and needs display conversion | Supported, capability required |
| Speed | `SPEED`, `SPEED_STATS` | Exercise, platform-derived/fused | `ACTIVITY_RECOGNITION` in the current table, not fine location | Supported, capability required |
| Location | `LOCATION` containing latitude, longitude, optional altitude, and optional bearing | Exercise location samples | `ACCESS_FINE_LOCATION`; handle location disabled and approximate-only grants | Supported on capable/configured exercises |
| Bearing | `LocationData.bearing`; there is no standalone `DataType.BEARING` | Field of Health Services location or locally derived from location/raw orientation | Fine location for Health Services location | Correct the model to a location field/derived value |
| Absolute elevation | `ABSOLUTE_ELEVATION`, `ABSOLUTE_ELEVATION_STATS` | Exercise | `ACCESS_FINE_LOCATION` in the current table | Supported, capability required |
| Elevation gain/loss | `ELEVATION_GAIN`, `ELEVATION_GAIN_TOTAL`, `ELEVATION_LOSS`, `ELEVATION_LOSS_TOTAL`; passive `ELEVATION_GAIN_DAILY` | Exercise/fused; gain also passive daily | Current table maps gain/loss to `ACTIVITY_RECOGNITION` | Do not claim barometer source or universal support |
| Barometric pressure | `SensorManager.TYPE_PRESSURE` | Raw `SensorManager` only | No dangerous runtime permission; hardware check and accuracy callbacks required | Not a Health Services measurement |
| Calories | `CALORIES`, `CALORIES_TOTAL`; passive `CALORIES_DAILY` | Exercise/fused plus passive daily | `ACTIVITY_RECOGNITION`; Health Services defines it as calories including basal rate and activity | Supported; maps semantically to Health Connect total calories |
| Floors | `FLOORS`, `FLOORS_TOTAL`; passive `FLOORS_DAILY` | Exercise/fused plus passive daily | `ACTIVITY_RECOGNITION` | Supported, capability required |
| VO2 max | `VO2_MAX`, `VO2_MAX_STATS` | Health Services exercise | The current permissions table does not assign the roadmap's claimed `BODY_SENSORS` permission; verify capability and real-device behavior | Public identifier exists; availability is selective |
| Swimming | `SWIMMING_STROKES`, `SWIMMING_STROKES_TOTAL`, `SWIMMING_LAP_COUNT`, `SWIMMING_LAP_COUNT_TOTAL` | Exercise | `ACTIVITY_RECOGNITION` | Supported only for matching capable exercise types |
| Repetitions | `REP_COUNT`, `REP_COUNT_TOTAL` | Exercise | `ACTIVITY_RECOGNITION` | Supported only where capability reports it |
| Golf shots | `GOLF_SHOT_COUNT`, `GOLF_SHOT_COUNT_TOTAL`; 1.1 also has exercise-event support | Exercise | `ACTIVITY_RECOGNITION` | Supported only where capability reports it |
| Advanced running | `GROUND_CONTACT_TIME`, `VERTICAL_OSCILLATION`, `VERTICAL_RATIO`, `STRIDE_LENGTH` and matching `_STATS` | Exercise; introduced on the 1.1 line | No separate permission row is documented; capability and RC dependency required | Do not put in a 1.0.0-only contract |
| Raw accelerometer/gyro/magnetometer | No corresponding public Health Services `DataType` | Raw `SensorManager`; Samsung also has a vendor accelerometer tracker | Sensor-specific lifecycle/rate policy | Health Services cannot replace this path |

### 4.3 Samsung-only measurements

Samsung's current [Sensor SDK overview](https://developer.samsung.com/health/sensor/overview.html)
and [v1.4.1 release note](https://developer.samsung.com/health/sensor/release-note.html) establish:

- Version `1.4.1`, released 2025-08-20; Galaxy Watch4 series and later; no emulator.
- Continuous trackers include accelerometer, EDA, heart rate with IBI, PPG, and skin temperature.
- On-demand trackers include BIA, MF-BIA, ECG, PPG, skin temperature, and SpO2.
- [`SPO2_ON_DEMAND`](https://developer.samsung.com/health/sensor/guide/data-specifications.html)
  is not a continuous workout stream. On-demand trackers are foreground-only, one at a time, and
  intended for roughly 30-second measurement.
- `SKIN_TEMPERATURE_CONTINUOUS` reports skin and ambient temperature, explicitly not body
  temperature, and is available on Watch5+.
- For apps targeting API 36+, tracker-specific permissions include
  `READ_HEART_RATE`, `READ_OXYGEN_SATURATION`, and `READ_SKIN_TEMPERATURE`; several raw trackers use
  Samsung's `READ_ADDITIONAL_HEALTH_DATA`. A blanket `BODY_SENSORS` row is no longer correct.
- Production use is gated. Samsung's
  [distribution process](https://developer.samsung.com/health/sensor/process.html) requires partner
  approval and registration of package name plus release signature. Otherwise the SDK only works
  with [developer mode](https://developer.samsung.com/health/sensor/guide/developer-mode.html),
  which must not be delegated to end users.

Therefore Samsung data belongs behind an optional vendor implementation, with an explicit
“not distributable until partner approval” release gate. RMSSD may be derived from Samsung IBI,
but this report does not validate the signal-cleaning or RMSSD algorithm.

---

## 5. Runtime permissions and API-level transitions

| Concern | Verified rule | Required implementation behavior |
|---|---|---|
| Activity-derived metrics | [`ACTIVITY_RECOGNITION`](https://developer.android.com/reference/android/Manifest.permission#ACTIVITY_RECOGNITION) is a runtime permission from API 29, and the Health Services table maps steps, cadence, distance, pace, speed, calories, floors, elevation gain/loss, reps, and swim counts to it. | Request in context before configuring those data types; remove denied data types rather than failing the entire exercise. |
| Heart rate through API 35 | Use `BODY_SENSORS`. | Declare with `android:maxSdkVersion="35"` when adding the API-36 permission. |
| Background/passive body sensors, API 33–35 | [Android's background body-sensor guide](https://developer.android.com/health-and-fitness/health-services/background-body-sensors) requires `BODY_SENSORS` then a separate `BODY_SENSORS_BACKGROUND` grant; requesting both together is ignored. | Only request for a genuine passive/background feature. A user-started active workout should start its foreground service while UI-visible instead of demanding “all the time” by default. |
| Heart rate, target API 36+ | Health Services migration guidance uses `android.permission.health.READ_HEART_RATE`. | Branch permission requests by platform/target behavior and keep legacy permission `maxSdkVersion=35`. |
| Passive/background health, target API 36+ | `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` replaces the legacy background body-sensor permission for Health Services passive monitoring. | Feature-gate and request only for passive monitoring. |
| Samsung SpO2/temperature, target API 36+ | Samsung 1.4.1 maps its trackers to `READ_OXYGEN_SATURATION` and `READ_SKIN_TEMPERATURE`. | Keep vendor permission logic out of the generic Health Services implementation. |
| Location | Health Services `LOCATION` and `ABSOLUTE_ELEVATION` require fine location. Android users can grant approximate only. | Continue without route/absolute elevation when precise location is unavailable; expose a clear degraded state. |
| Screen-off workout location | Android's [location permission guide](https://developer.android.com/develop/sensors-and-location/location/permissions) treats a running foreground service as foreground access and retains access after Home/screen-off. | Do not request `ACCESS_BACKGROUND_LOCATION` solely for a user-started foreground workout. It is needed for access outside visible UI/FGS conditions or background starts. |
| Foreground services, target API 34+ | [FGS type rules](https://developer.android.com/about/versions/14/changes/fgs-types-required) require declared types and type permissions. Fitness tracking uses `health`; route tracking uses `location`. | Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, and when applicable `FOREGROUND_SERVICE_LOCATION`; declare `health|location` on the service and pass used types to `ServiceCompat.startForeground`. Obtain runtime permissions before starting. |
| Temporary availability | Health Services can report `ACQUIRING`, generic unavailable, or off-body unavailable after start. | Model these separately from unsupported hardware and permission denial; test transition/recovery. |

The roadmap's four-value `MeasurementAvailability` enum is not sufficient as the source of truth.
Persist or expose separate dimensions for **API capability**, **permission**, **raw hardware/vendor
capability**, and **live availability**. A presentation layer can combine them, but discovery code
must not.

### Wear acceptance matrix

Before Trinity freezes measurement contracts:

1. Compile every referenced identifier against the selected Health Services artifact.
2. Unit-test mapping from capabilities + permissions + live availability into domain state.
3. On at least one non-Samsung Wear OS device and one supported Samsung device when available,
   record a user-started workout with screen on/off, pause/resume, off-body HR, location disabled,
   approximate-only location, and permission revocation.
4. Run API 35 and API 36 permission manifests/requests, including denial and “don't ask again.”
5. Treat HRV, SpO2, and skin temperature as disabled until their vendor implementation,
   distribution approval, and measurement-specific acceptance tests pass.

---

## 6. Health Connect exercise/session integration

### 6.1 Version and record mapping

Use stable `androidx.health.connect:connect-client:1.1.0` unless a reviewed preview feature is
required.

| Internal data | Correct Health Connect type | Notes |
|---|---|---|
| Session | `ExerciseSessionRecord` | Route, segments, and laps are session subtype data. |
| Heart rate samples | `HeartRateRecord` | Chunk deterministically if record-size limits require it. |
| Steps | `StepsRecord` | Session interval aggregate. |
| Distance | `DistanceRecord` | Session interval aggregate. |
| Calories | `TotalCaloriesBurnedRecord` | Correct semantic match because Health Services calories include basal + activity. `TotalCaloriesRecord` does not exist. |
| Elevation | `ElevationGainedRecord` | Write only when measured/derived under a documented policy. |
| Speed samples | `SpeedRecord` | Series of speed samples. |
| Route | nested `ExerciseRoute` | Route locations carry timestamp, latitude/longitude, and optional accuracy/altitude. |

The current [exercise route guide](https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes)
also demonstrates `Metadata.activelyRecorded` for a user-started workout and explicitly shows that
deleting an exercise session does not automatically delete associated distance/metric records.

### 6.2 Permissions and availability

- Generate normal data permissions with
  `HealthPermission.getWritePermission(RecordClass::class)` and
  `getReadPermission(...)`. This yields manifest/runtime permissions such as
  `WRITE_EXERCISE`, `WRITE_HEART_RATE`, `WRITE_STEPS`, `WRITE_DISTANCE`,
  `WRITE_TOTAL_CALORIES_BURNED`, `WRITE_ELEVATION_GAINED`, and `WRITE_SPEED`.
- A route write additionally requires
  `HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE`
  (`android.permission.health.WRITE_EXERCISE_ROUTE`). `ACCESS_FINE_LOCATION` is needed to acquire
  watch GPS, not to publish an already collected route from the phone.
- Stable `1.1.0` contains `ExerciseRouteRequestContract` for per-session user consent but does not
  contain `HealthPermission.PERMISSION_READ_EXERCISE_ROUTES`. The latter appears in
  `1.2.0-alpha04` and has special Settings/route-dialog grant semantics. This was verified by
  comparing the released
  [1.1.0 sources](https://dl.google.com/dl/android/maven2/androidx/health/connect/connect-client/1.1.0/connect-client-1.1.0-sources.jar)
  and
  [1.2.0-alpha04 sources](https://dl.google.com/dl/android/maven2/androidx/health/connect/connect-client/1.2.0-alpha04/connect-client-1.2.0-alpha04-sources.jar).
- Historical reads older than the normal 30-day window require
  `READ_HEALTH_DATA_HISTORY`; background reads require `READ_HEALTH_DATA_IN_BACKGROUND`. Both must
  first pass `HealthConnectFeatures` availability checks. See the
  [data-type permission guide](https://developer.android.com/health-and-fitness/health-connect/data-types).
- Health Connect is a framework module on API 34+ and a separately installed provider on API 33
  and lower; always check `HealthConnectClient.getSdkStatus()`.

### 6.3 Idempotency, updates, deletion, and metadata

- `insertRecords` uses `Metadata.clientRecordId` for deduplication and the record with the higher
  `clientRecordVersion` takes precedence. Use a deterministic ID per
  `session UUID / record type / chunk`, not one undifferentiated ID for every record.
- Increment `clientRecordVersion` monotonically whenever a published record changes. Rerunning the
  same version must be a no-op from the app's perspective.
- `clientRecordId` is not a relationship/cascade key. Deletion must enumerate the session,
  heart-rate chunks, distance, steps, calories, elevation, and speed records owned by the app.
- Use `Metadata.activelyRecorded(...)` for the watch workout initiated by the user.
  “Automatically recorded” means the app detected/recorded without explicit user initiation.
- [`Device`](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/metadata/Device)
  stores device type, manufacturer, and model. It has no arbitrary app-version field; retain app
  version in the app's own database. Health Connect records already carry data origin.
- Health Connect access is also a release-policy surface. The
  [publish guide](https://developer.android.com/health-and-fitness/health-connect/publish) requires
  Play Console Health Apps declarations, matching minimum permissions, Data Safety answers, and a
  privacy policy/rationale activity.

### 6.4 Health Connect acceptance gate

Neo should not mark this connector complete until tests prove:

1. A second insert of the same record IDs/version creates no duplicates.
2. A higher version replaces every changed record/chunk.
3. Session deletion also deletes every app-owned associated metric record.
4. Partial write grants publish only allowed records and report a structured partial result.
5. Stable-1.1 route write and route-read-consent flows compile and run without a 1.2-only symbol.
6. API 33 provider-missing/update-required, API 34+ framework, history unavailable, background
   unavailable, and permission-revoked states all have deterministic results.

---

## 7. Endurain `v0.19.0`: released connector surface

### 7.1 Repository and release

- **Canonical:** [Codeberg: endurain-project/endurain](https://codeberg.org/endurain-project/endurain).
  Its [README](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/README.md) states that
  GitHub is a read-only mirror.
- **License:** `AGPL-3.0-or-later`; the released API metadata declares that SPDX identifier in
  [`core/config.py`](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/core/config.py).
- **Latest stable checked:** [`v0.19.0`](https://codeberg.org/endurain-project/endurain/releases/tag/v0.19.0),
  released 2026-07-22.
- **API root:** `/api/v1`; unauthenticated version discovery is
  `GET /api/v1/about`. Swagger is `/api/v1/docs`, but
  [`main.py`](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/main.py)
  enables docs only in development or demo, not normal production.

### 7.2 Endpoint/authentication contract

| Surface | Verified behavior in released source | Connector impact |
|---|---|---|
| Upload | `POST /api/v1/activities/create/upload`; multipart field `file`; HTTP 201; response is a list of activity objects. See [`activity/router.py`](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/activities/activity/router.py). | Always parse a list. A FIT file can produce multiple activities. |
| Formats | `.gpx`, `.tcx`, `.fit`, and `.gz`; gzip must wrap a supported activity file. See [`activity/utils.py`](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/activities/activity/utils.py). | TCX is a realistic MVP and preserves route/HR/cadence without adding the FIT SDK license risk. |
| API-key auth | `X-API-Key` or JWT is accepted by upload. API keys support only `activities:upload`; query-string `?api_key=` is disabled by default through `ALLOW_API_KEY_QUERY_PARAM=false`. See [`api_keys/utils.py`](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/auth/api_keys/utils.py) and [`auth/internal_dependencies.py`](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/auth/internal_dependencies.py). | Preferred upload-only MVP. API-key upload does not require `X-Client-Type`. Never put the key in the URL. |
| JWT mobile auth | `/api/v1/auth/login`, `/mfa/verify`, `/refresh`, `/logout`; bearer access token, mobile refresh token, `X-Client-Type: mobile`, rotating refresh tokens, optional MFA and PKCE/SSO flows. Defaults are 15-minute access and 7-day refresh in [`auth/constants.py`](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/auth/constants.py). | Required only if read/update/delete are product requirements. This is substantially more than storing one static token. |
| Read | `GET /api/v1/activities/{id}`; JWT `activities:read`. | API keys cannot reconcile an uncertain upload. |
| Update | `PUT /api/v1/activities/edit`; JWT `activities:write`; body `ActivityEdit` carries the ID. | Updates mutable metadata/privacy/gear fields. No released endpoint was found to replace the uploaded route/streams/file. |
| Delete | `DELETE /api/v1/activities/{id}/delete`; JWT `activities:write`. | The roadmap's generic delete SPI cannot promise Endurain delete in API-key mode. |
| Bulk import | `POST /api/v1/activities/create/bulkimport` is JWT-only and processes files available to the server. | It is not a mobile multipart “sync all” endpoint. Historical sync must queue ordinary uploads. |
| IDs | `Activity.id` is an autoincrement integer. | Store `(normalized base URL, connection/user identity, Endurain activity ID)`. It is not globally stable and is not the sport-logger UUID. |
| Duplicate handling | Before insert, Endurain checks same user/start time; if found, it still inserts the new activity as `is_hidden=true` and creates a duplicate notification. See [`activity/crud.py`](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/activities/activity/crud.py). | Upload has no exactly-once/idempotency key. A blind retry after a lost 201 response can create a duplicate. |
| Rate limit | Default is `120/minute`; 429 responses receive rate-limit and `Retry-After` headers in [`core/rate_limit.py`](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/core/rate_limit.py). | Honor `Retry-After`; rate-limit retry is safe only when the server rejected before processing. |

### 7.3 Realistic connector path

**Recommended MVP: API-key, upload-only TCX connector**

1. User supplies normalized HTTPS base URL and an Endurain API key with
   `activities:upload`.
2. Probe `GET /api/v1/about`; require a recognized API root/version response.
3. Generate one deterministic TCX file from a completed local session.
4. Persist an outbox row before network I/O, then multipart upload with `X-API-Key`.
5. On 201, transactionally persist every returned activity ID plus server identity and mark the
   outbox delivered.
6. On a definitive pre-processing error (validation, 401/403, 413, or rate-limit 429), record the
   typed error. On timeout/connection loss after request transmission, mark **delivery unknown**;
   do not auto-retry because the first upload may have committed.
7. Surface update/delete as unsupported in API-key mode.

**Full-control mode:** only add mobile JWT authentication if the product accepts secure refresh
token storage, rotation/replay behavior, MFA/PKCE/SSO states, and JWT-only read/update/delete.
Even then, current update changes metadata rather than replacing activity streams. No public
external UUID/idempotency field or native JSON create endpoint was found in `v0.19.0`.

### 7.4 Endurain acceptance gate

Against a disposable real `v0.19.0` instance, Neo should contract-test:

1. API-key TCX upload returns 201/list and creates the expected route, HR, cadence, and summary.
2. Wrong scope/key, unsupported type, corrupt gzip, oversized file, 429/`Retry-After`, and server
   outage map to stable connector results.
3. A deliberately dropped response leaves `UNKNOWN_DELIVERY` and does not trigger an automatic
   duplicate.
4. Reuploading the same start time demonstrates Endurain's hidden-duplicate behavior.
5. JWT mode, if retained, proves refresh rotation, read, allowed metadata edit, and delete.

---

## 8. Roadmap corrections by exact location

| Roadmap location | Current claim | Required correction before implementation |
|---|---|---|
| `3.1`, lines 170–179 | AGP 8.7, Gradle 8.9, Kotlin 2.0.21, API 35, old Compose/Horologist described as current | Replace with the verified version table above and label stable vs RC/alpha. Freeze only a tested coherent tuple. |
| `3.2`, lines 183–184 | Gradle 8.9 is “no code changes required” | Remove that assurance. A Gradle 4.10.1/AGP 3.3.1 to 9.x migration is a coordinated build/plugin/JDK change. |
| `4.1`, line 196 | Replace direct `SensorManager`/`LocationManager` with Health Services | Narrow to exercise/fused metrics. Preserve explicit raw-sensor and vendor implementations for pressure, raw motion/orientation, SpO2, temperature, and optional IBI-derived HRV. |
| `4.2`, line 200 | Every measurement is gated by `ExerciseClient.getCapabilitiesAsync()` | Split exercise, measure, passive, raw hardware, Samsung vendor, permission, and live availability checks. |
| `4.2`, line 205 | `DataType.HEART_RATE_VARIABILITY` | Delete; no such public identifier exists. Model HRV as unsupported cross-device or derived from a separately validated vendor IBI source. |
| `4.2`, lines 206–207 | SpO2 partly from Health Services and blanket `BODY_SENSORS`; temperature similar | Health Services exposes neither. Use Samsung on-demand SpO2 and Samsung skin-temperature trackers, API-36 tracker-specific health permissions, Watch/device gates, and partner-approval gate. |
| `4.2`, lines 208–218 | Several data types are “Universal” | Replace every universal claim with runtime exercise/type capability evidence. |
| `4.2`, lines 210–221 | Fine location/body-sensor/blank permission mappings | Use the current Health Services table: activity recognition covers distance, pace, speed, calories, floors, gain/loss, steps, reps, and swim counts; fine location covers `LOCATION` and `ABSOLUTE_ELEVATION`; HR uses the sensor/health transition. Mark VO2 permission as not established by that table. |
| `4.3`, lines 227–242 | One enum combines capability, permission, and temporary state | Preserve separate evidence dimensions. Include `ACQUIRING` and off-body state; do not infer raw hardware from Health Services. |
| `5.3`, line 317 | `TotalCaloriesRecord` | Rename to `TotalCaloriesBurnedRecord`. |
| `6.1`, line 350 | Soft delete issues delete to Health Connect/Endurain generically | Health Connect needs explicit deletion of session plus associated metric records. Endurain delete is JWT-only and unavailable to API-key MVP. |
| `6.3`, line 376 | One session UUID as Health Connect dedup key | Namespace deterministic `clientRecordId` by record type/chunk and use monotonic `clientRecordVersion`. |
| `6.3`, line 377 | Endurain uses activity UUID as external ID | Remove. Released Endurain exposes an instance-local integer ID and no external UUID/idempotency field. |
| `8.1`, lines 418–420 | First-install historical/background reads are unconditional | Add provider availability, record permissions, 30-day history boundary, `READ_HEALTH_DATA_HISTORY`, background-read feature/permission, and partial-grant behavior. |
| `8.2`, lines 427–433 | Route permission is `ACCESS_FINE_LOCATION`; record list is incomplete | Add `WRITE_EXERCISE_ROUTE`; separate acquisition location from Health Connect publication; include elevation/speed permissions when written; document stable route-consent vs alpha persistent-read APIs. |
| `8.3`, line 437 | Every Health Connect record uses the same session UUID | Use type/chunk-specific IDs and versions. |
| `8.3`, line 438 | `RECORDING_METHOD_AUTOMATICALLY_RECORDED` | Use `Metadata.activelyRecorded` for user-started workouts. |
| `8.3`, line 439 | Device metadata includes app version | Remove app version; retain it in sport-logger storage. |
| `9.1`, lines 448–452 | JWT-only design, possible native JSON, generic retries, stored ID supports update/delete | Prefer API-key upload-only TCX MVP. No native JSON create was found. JWT is required for read/update/delete; update does not replace streams. Add unknown-delivery state and composite server ID. |
| `9.2`, lines 457–462 | Username plus encrypted token in DataStore is sufficient | Distinguish API-key config from mobile JWT session state. [DataStore](https://developer.android.com/jetpack/androidx/releases/datastore) is not itself a key-management primitive; secrets require a separately specified design such as [Android Keystore](https://developer.android.com/privacy-and-security/keystore) backed encryption. |
| `9.3`, lines 468–471 | Auto retry/bulk sync without API semantics | Loop normal uploads under 120/minute, honor `Retry-After`, and do not blind-retry ambiguous transmitted requests. The server-local bulk-import endpoint is not a connector upload API. |
| `14.2`, line 623 | OpenTracks is multi-module with Wear/Data Layer | Correct to a single phone application with no first-party Wear module. Gadgetbridge integration is not Wear Data Layer. |
| `14.2`, line 624 | SportsTracker activity described as 2024–2025 | Current canonical activity is 2026-07-25; mark it active but young/no verified release. |
| `14.2`, lines 625–626 | Horologist and Wear samples treated as direct exercise references | Classify Horologist as a potentially changing library and Wear OS Samples as focused UI/Data Layer samples. Health Services exercise samples are in `android/health-samples`. |
| `14.2`, line 627 | Endurain GitHub/Codeberg generalized behavior | Name Codeberg canonical `v0.19.0`, upload/auth/ID/update/delete limitations, and production docs behavior. |
| `14.2`, line 628 | FIT SDK license TBD | Mark custom FIT Protocol License, not open source; require legal approval or use TCX. |
| `15.1`, line 645 | W0-01 accepts AGP 8.7/Gradle 8.9 | Replace with the toolchain-spike gate; do not bake stale numbers into acceptance criteria. |
| `ADR-001`, lines 745–749 | Health Services capability discovery replaces individual sensor checks | Keep the ADR for Health Services metrics, but explicitly retain raw `SensorManager` and Samsung capability/permission checks behind the repository boundary. |

---

## 9. Named follow-up recommendations

| Owner | Action | Due gate |
|---|---|---|
| **Tank** | Replace roadmap version numbers and W0-01 acceptance with a tested toolchain tuple; amend ADR-001 so Health Services is primary but not exclusive; add GPL/FIT license reuse gates. | Before W0-01 is accepted |
| **Trinity** | Produce the exercise/passive/raw/vendor capability matrix and API 35/36 permission state machine; compile all identifiers against the chosen artifact and run the physical-watch acceptance matrix. | Before watch measurement interfaces freeze |
| **Neo** | Use type/chunk Health Connect IDs and explicit cascade deletion; design Endurain API-key TCX upload-only MVP with `UNKNOWN_DELIVERY`, composite server IDs, and no blind retry. | Before connector/database schemas freeze |
| **Switch** | Design user-visible states for unsupported vs denied vs acquiring/off-body, approximate/no-location recording, partial Health Connect grants, route consent, Samsung unavailability, and Endurain unknown delivery/duplicate review. | Before watch/connector UX implementation starts |
| **Fact Checker** | Independently re-fetch version metadata and verify the two highest-risk claims: stable-vs-alpha Health Connect route APIs and Endurain `v0.19.0` auth/idempotency/update surfaces. Report any drift before roadmap edits merge. | Before the modernization roadmap is approved |

No production architecture or implementation is approved by this report. It supplies evidence and
acceptance gates; the owning engineers must validate the selected paths in builds, emulators where
supported, physical watches, Health Connect, and a disposable Endurain instance.
