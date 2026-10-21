# Android foundation and recording service

## Supported build baseline

The supported baseline is the existing three-module Groovy build:

| Component | Selected value |
| --- | --- |
| JDK | Eclipse Temurin 17 (`17.0.20+8` used for the clean validation) |
| Gradle | 9.5.0, binary distribution SHA-256 pinned in the wrapper |
| Android Gradle Plugin | 9.3.3 |
| Android SDK / build tools | compile SDK 36 / build tools 36.0.0 |
| Kotlin | AGP built-in Kotlin 2.2.10 where enabled; Java Room code uses `annotationProcessor` |
| Mobile | min SDK 26 / target SDK 36 |
| Wear | min SDK 30 / target SDK 35 |
| Utilities | min SDK 23 |
| Java source and bytecode | 17 |

Dependencies resolve from Google Maven and Maven Central. AndroidX is enabled, Jetifier is
disabled, and no `jcenter()` or `com.android.support.*` dependency remains.

The Wear app requires Wear OS 3 / API 30 or newer because the verified
`androidx.health:health-services-client:1.1.0` AAR declares min SDK 30. The app does not use
`tools:overrideLibrary` to claim unsupported Wear OS 2 compatibility. Raw sensor and location
fallbacks remain available when Health Services or an individual metric is unavailable on a
supported OS; they are not a promise of support for pre-API-30 watches. Raising the app minimum
does not delete or rewrite the existing legacy watch database or serialized activity payloads.
AGP 9's public DSL is used without `android.newDsl=false` or another compatibility opt-out.

AGP 9.3.1 was not retained because the official 9.3 release notes record a JDK 17 lint crash that
was fixed in 9.3.2. The selected 9.3.3 patch keeps the
documented Gradle 9.5.0 / JDK 17 / build tools 36.0.0 compatibility tuple. Wrapper integrity is
pinned and checked in CI:

- Gradle 9.5.0 binary distribution SHA-256:
  `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746`
- Gradle 9.5.0 wrapper JAR SHA-256:
  `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`

## Build and install

Set `JAVA_HOME` to a JDK 17 installation and make SDK 36 available through
`ANDROID_SDK_ROOT` or an uncommitted `local.properties` file.

```bash
./gradlew :mobile:assembleSideload :wear:assembleSideload \
  --no-daemon --max-workers=2
```

The recommended manual-install APKs are:

- `mobile/build/outputs/apk/sideload/mobile-sideload.apk`
- `wear/build/outputs/apk/sideload/wear-sideload.apk`

Install the phone and watch APKs on their corresponding devices:

```bash
adb -s <phone-serial> install -r mobile/build/outputs/apk/sideload/mobile-sideload.apk
adb -s <wear-serial> install -r wear/build/outputs/apk/sideload/wear-sideload.apk
```

The `sideload` variants inherit release behavior, are non-debuggable, use production
manifests, and reference the existing Gradle development signing identity. They are not
production/Play-signed releases. The phone debug variant retains QA-only Health Connect
read permissions and its consent-test activity; release APKs remain unsigned for the
external signing process.

The pinned CI workflow is `.github/workflows/android-build.yml`. It runs for pull requests
and pushes targeting both `dev` and `main`, validates the public sideload manifest and
certificate properties, and uploads debug, sideload, and unsigned-release APKs.

For device pairing, signing compatibility, and non-destructive upgrade instructions, see
the [installation guide](installing.md).

### Phone sideload device checkpoint

On October 21, 2026, immutable integration head
`6cba705594efc07b3b522d9d8365c0f068618133` passed an actual API 36 phone user-variant
checkpoint:

- normal `adb install -r`;
- non-debuggable installed APK with zero Health Connect reads, eight production writes,
  no debug consent activity, and the expected matching public certificate;
- 7/7 existing `MainActivityComposeTest` cases against the sideload app;
- 1/1 focused real-main-UI Health Connect publish and owned-delete flow with provider IDs,
  persisted `UPSERTED` → `DELETED` receipts, and an empty granted-read set.

The focused instrumentation probe was temporary and removed after capture. Production
sources and manifests remained unchanged. This checkpoint proves the phone sideload path;
the final watch device matrix and combined pair retain their separate acceptance gates.

### Final watch source and device matrix

Immutable watch source head `fb503a4a03eca9698fba9bd2b19fff8281e8abd6` is formally
approved. It adds no new module, dependency, signing configuration, version, or application
identity. Its final corrections are limited to the watch source and tests:

- either heart-rate or activity-recognition access satisfies the health foreground
  prerequisite; denied route/notification access is optional and permanent required denial
  is exposed for settings recovery;
- the public live snapshot uses nullable samples with provenance and quality, preserves real
  zero values, does not fabricate missing zeroes, and hides unreliable or explicitly blocked
  values;
- missing canonical active duration remains absent instead of falling back to elapsed time;
- ambient refresh compares both active and elapsed minute buckets, including pause offsets;
- raw fallback values use the same presence, quality, and readiness gates.

The final device lanes remain separate:

| Lane | Current evidence |
| --- | --- |
| Phone sideload user flow | Passed: 7 existing main Compose tests plus 1 write-only Health Connect main-UI publish/delete test. |
| Watch source/host tests | Passed: 164 Wear JVM tests in the final integrated build. |
| Historical predecessor sideload flows | Passed ordinary, partial/zero-health, settings, start/lap/pause/resume/direct-finish, offline-summary, and retry paths; not the final corrected-head gate. |
| Final watch debug QA components | 12-test result pending; do not claim passed. |
| Final watch sideload user flows | Result pending; do not claim passed. |

Generic `ComponentActivity` fixtures are part of the debug-only test host and are
intentionally absent from the sideload app. Their launch failure in that invalid lane is
not a product crash and is not a substitute for the final sideload user-flow result.

The exact full CI command completed successfully after source alignment. JUnit XML totals
were:

| Module | Suites | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| Mobile | 30 | 132 | 0 | 0 | 0 |
| Wear | 36 | 164 | 0 | 0 | 0 |
| Utilities | 4 | 22 | 0 | 0 | 0 |
| **Total** | **70** | **318** | **0** | **0** | **0** |

Debug lint XML contained 70 mobile, 17 Wear, and 3 utilities warnings, with no error or
fatal findings.

The same build produced and validated all six expected application APKs:

| App | Debug QA | Manual sideload | Unsigned release |
| --- | --- | --- | --- |
| Phone | `mobile/build/outputs/apk/debug/mobile-debug.apk` | `mobile/build/outputs/apk/sideload/mobile-sideload.apk` | `mobile/build/outputs/apk/release/mobile-release-unsigned.apk` |
| Watch | `wear/build/outputs/apk/debug/wear-debug.apk` | `wear/build/outputs/apk/sideload/wear-sideload.apk` | `wear/build/outputs/apk/release/wear-release-unsigned.apk` |

The exact CI public-APK validation block passed against those files: both sideload APKs
were non-debuggable and shared package/version/signing identity; phone min/target remained
26/36 and watch 30/35; the phone sideload had zero Health Connect reads, eight writes, and
no debug consent activity; watch production sensor/health permissions remained present;
debug QA declarations remained present; and both release APKs remained unsigned.

## Canonical and legacy recording boundaries

The current canonical contracts, Room repositories, and commit acknowledgements are
documented in [canonical storage](canonical-storage.md) and [durable sync](durable-sync.md).
Those documents describe the primary activity identity and transfer protocol. The retained
Java controller and legacy compatibility path below must not be mistaken for the canonical
phone commit boundary.

`SportLoggerService` delegates durable recording transitions to a serial
`RecordingController`. START, PAUSE, RESUME, STOP, DISCARD, sampling, and finalization are
ordered on its worker. Durable state is committed before STOP or DISCARD work proceeds, and a
replacement service resumes STOPPING, DISCARDING, EXPORT_PENDING, or EXPORT_QUEUED work after
recreation. New canonical recordings enter EXPORT_QUEUED only after the Room completion and
immutable outbox commit; `WatchSyncEvents` then owns truthful queued/transferring/failed/synced
presentation. A recovered pre-cutover legacy recording retains its matching serialized export and
ACK path.

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

The legacy SQLite table names, columns, timestamp format, Java `SportActivity` serialization,
and `/sportactivity` receiver remain unchanged for migration, rollback, and recovery of a
pre-cutover pending recording. Those legacy paths retain their existing receipt and matching
ACK behavior; they are not the outbound authority for a new canonical recording.

For supported upgrades, legacy-only `GPSLOGGERDB_LONG2KNOW` and phone serialized inbox data
remain readable and migrate idempotently into canonical storage. New watch recordings are
canonical-authority only: they allocate a non-SQL local recording token, write Room/protobuf
samples and outbox state, and never emit the legacy serialized `/sportactivity` mirror. Legacy
pending recordings recovered from a pre-cutover journal retain the legacy store/export path.
The unreleased development build that briefly dual-wrote canonical and legacy identities is not
a supported persisted upgrade source; acceptance devices using that build must reset app data
rather than merge identities heuristically. Matching current watch and phone versions are
required for new canonical transfers.

## Toolchain-upgrade validation checkpoint

The counts in this section describe the original Java-only toolchain upgrade, before the
canonical storage, Health Services, and provider work. They are historical evidence, not
the current product test count or a paired-device acceptance claim.

The clean host validation command is:

```bash
./gradlew clean \
  assembleDebug assembleRelease test lint \
  :mobile:assembleDebugAndroidTest \
  :wear:assembleDebugAndroidTest \
  :utilities:assembleDebugAndroidTest \
  --no-daemon --max-workers=2 --warning-mode all --stacktrace
```

AGP 9 creates unit-test components only for the tested build type by default. The same 32 unique
unit-test methods therefore execute once under `testDebugUnitTest`, rather than being duplicated
across debug and release reports for 64 executions. No test method is skipped. Android-test APKs
compile for all three modules; device execution remains a separate gate.

The upgrade validation has zero lint errors and adds no lint baseline or suppression. The current
reports contain 73 warnings (mobile 7, Wear 64, utilities 2), including out-of-scope API 37/newer
Gradle availability notices and existing application/resource quality findings. `--warning-mode
all` reports no Gradle deprecation after removing the obsolete `android.nonFinalResIds=false`
project option. Javac still emits four advisory notes for deprecated APIs in unchanged application
sources; application code is intentionally outside this toolchain-only change.

Deterministic unit tests inject blocked cleanup, registration and cleanup failures, stale and
duplicate callbacks, missing providers, permission denial and revocation, two consecutive
offline workouts, durable phone restart/ack retry, service recreation, persistence failures,
and export retries. The cleanup and finalization timing tests each hold production workers
for at least 5,000 monotonic milliseconds while asserting stop/close calls return in under
100 milliseconds, then prove eventual worker termination. Android test APKs also compile
for all three modules, including an activity recreation test that verifies the restored
visible sensor fragment receives live updates. Device and emulator behavior is not claimed
by these host tests.
