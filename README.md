# SportLogger

**A watch-first workout recorder with a durable Android companion.**

SportLogger records workouts on Wear OS, preserves them in canonical local storage, transfers
them durably to an Android phone, and provides phone history, export, and optional publishing
workflows. The approved recording backend treats routes as optional and canonical storage
preserves optional measurement presence; the final watch UI paths for degraded permissions and
live missing-versus-zero presentation are still under correction.

[![Android build](https://github.com/long2know/sport-logger/actions/workflows/android-build.yml/badge.svg?branch=main)](https://github.com/long2know/sport-logger/actions/workflows/android-build.yml)
[![SQLite compatibility](https://github.com/long2know/sport-logger/actions/workflows/sqlite-exact-compat.yml/badge.svg?branch=main)](https://github.com/long2know/sport-logger/actions/workflows/sqlite-exact-compat.yml)
[![Migration oracle](https://github.com/long2know/sport-logger/actions/workflows/legacy-migration-oracle.yml/badge.svg?branch=main)](https://github.com/long2know/sport-logger/actions/workflows/legacy-migration-oracle.yml)

[Features](#features) | [Use SportLogger](#use-sportlogger) |
[Get started](#get-started) | [Build and install](#build-and-install) |
[Project layout](#project-layout) | [Roadmap](#roadmap) | [Contributing](#contributing)

> **Current acceptance status:** the phone companion UI and actual non-debuggable phone
> `sideload` user variant are approved with API 36 device and host-test evidence, and the
> canonical watch recording/storage/sync backend remains approved. The current watch Compose
> revision is **not ship-approved**; an independent replacement must correct degraded
> optional-permission handling, live measurement presence, unavailable active duration
> presentation, and ambient minute timing. The `sideload` packaging is unchanged, but the final
> distributable pair must use the corrected watch UI. Production/Play signing and declarations,
> physical sensors and battery, square-watch hardware, and an actual paired-device commit
> acknowledgement remain separate release gates.

## Features

| Capability | Current behavior |
|---|---|
| Watch recording core | The approved backend supports Running, Walking, Cycling, Hiking, Strength, Pool swimming, and Other; durable start/pause/resume/lap/finish/discard transitions; offscreen foreground-service continuity; and canonical summaries. |
| Watch UI acceptance | Final claims about optional-permission degradation, permanent-denial presentation, live missing-versus-zero values, unavailable active duration, and ambient minute updates are held until the replacement Compose head passes independent review. |
| Measurement truth | Canonical records and saved summaries preserve optional-value presence. The current live watch UI revision is not approved as proof that every missing measurement is distinguished from a recorded zero. |
| Durable transfer | Canonical protobuf records, Room storage, and an outbox expose queued, transferring, acknowledged, and retryable states. Saved or queued on the watch is not labeled synced until the phone commit acknowledgement is accepted. |
| Phone companion | Material 3 Compose history, activity detail, phone-owned title/notes, measurement availability, bounded route preview, laps, 12-week trends, storage/recovery status, and phone-to-watch deletion retry state. |
| TCX portability | Stream a selected canonical activity to a user-chosen document through Android's Storage Access Framework, or share only the generated TCX file. Export warnings identify missing required values; measured data is not fabricated. |
| Health Connect | User-initiated, optional publication supports partial permission grants and explicit publish, update, retry, and app-owned delete behavior. Local deletion does not silently delete Health Connect data. Production phone manifests request the eight required `WRITE_*` permissions; verification-only `READ_*` permissions remain debug-only. An actual phone sideload run exercised main-UI publish and owned delete with no read grant. |
| Endurain | Configure an HTTPS endpoint and scoped API key, prepare and queue canonical TCX, retry eligible failures, repair artifacts, reconcile unknown delivery with retained positive remote IDs, or explicitly confirm a duplicate-risk retry. Remote edit and delete are truthfully unsupported in API-key TCX mode. |
| Legacy recovery | Existing watch data and the immutable phone legacy inbox remain available for idempotent migration and recovery; corrupt or partial inputs are surfaced without rewriting their source payloads. |

## Approved phone screens

These synthetic API 36 emulator results are from the approved immutable phone UI. Watch
screens are intentionally withheld until the replacement Compose head is independently
accepted.

| Persisted phone history | Phone activity detail and bounded route preview |
|---|---|
| <img alt="Synthetic Material 3 persisted activity history" src="https://github.com/user-attachments/assets/7efd99bc-f1c0-4a30-953f-fff78e3ae998" width="360"> | <img alt="Synthetic activity detail with recorded metrics and bounded route preview" src="https://github.com/user-attachments/assets/47be4f4e-bec6-4a05-badd-3b945f7d5300" width="360"> |

### Actual phone sideload acceptance

The immutable integration build at
`6cba705594efc07b3b522d9d8365c0f068618133` was exercised as the real phone user variant
on API 36:

- normal `adb install -r` succeeded;
- the installed APK was non-debuggable, declared zero Health Connect `READ_*` permissions,
  retained all eight production `WRITE_*` permissions, excluded the debug consent activity,
  and used the matching development certificate;
- the existing `MainActivityComposeTest` suite passed **7/7** against the sideload app;
- a focused **1/1** main-UI test clicked publish and owned-delete actions against the real
  Health Connect provider, received provider IDs, and persisted operation/record receipts from
  `UPSERTED` through `DELETED`;
- the granted read-permission set remained empty, with no permission loop, debug consent
  activity, fake provider, or shell permission grant.

The focused test-only probe was removed after evidence capture; production sources remained
unchanged.

## Use SportLogger

### Watch workflow status

The approved recording backend supports the listed activity types, start, pause/resume,
durable manual laps, finish/discard, canonical summary storage, and durable phone transfer. Final
end-user watch instructions are held until the replacement UI corrects the four bounded
presentation and permission defects listed above. Existing watch screenshots and the prior
Compose artifact must not be treated as ship approval.

### Review and export on the phone

- Browse persisted history, open activity details, edit the phone-owned title and notes, and
  review trends and recovery status.
- Save TCX through the system document picker or share the generated TCX file with temporary
  read access.
- Local deletion creates a durable phone/watch tombstone. Health Connect and Endurain copies
  remain separate explicit provider actions.

### Optional integrations

- **Health Connect:** choose an action before SportLogger requests access. Partial grants are
  preserved rather than treated as all-or-nothing. SportLogger writes and deletes its own
  records; it does not use production permissions to browse unrelated health history.
- **Endurain:** provide an HTTPS base URL and appropriately scoped API key, then explicitly
  queue publication. An unknown response requires remote-ID reconciliation or an explicit
  duplicate-risk retry. API-key TCX remote edit/delete is unsupported and does not discard
  known remote IDs.

Google Play services may use an end-to-end encrypted cloud relay for Wear Data Layer traffic
when a direct connection is unavailable. Synchronization is therefore not guaranteed to stay
on the local network. See the [installation and privacy guide](docs/installing.md).

## Get started

### Prerequisites

- **JDK 17**, selected both for your terminal and Android Studio's Gradle JDK.
- **Android SDK command-line tools**, platform tools, Android SDK Platform 36, and Build Tools
  36.0.0.
- An Android phone running Android 8 / API 26 or newer.
- A paired Wear OS 3 / API 30 or newer watch for real recording and transfer checks.
- Network access for the first Gradle/dependency download.

Emulators are useful for deterministic UI, storage, migration, and transfer-state tests, but
do not prove physical heart-rate quality, outdoor GNSS, off-body behavior, battery life, or
manufacturer companion compatibility.

| Build component | Project baseline |
|---|---|
| Java | 17 |
| Gradle wrapper | 9.5.0 |
| Android Gradle Plugin | 9.3.3 |
| Compile SDK / Build Tools | 36 / 36.0.0 |
| Phone min / target SDK | 26 / 36 |
| Wear min / target SDK | 30 / 35 |

These versions are one verified project tuple, not a claim that each component is the newest
available release. See the [Android foundation guide](docs/android-foundation.md) for recording,
storage, transfer, compatibility, and build details.

### Clone and configure

```bash
git clone https://github.com/long2know/sport-logger.git
cd sport-logger
```

Set `JAVA_HOME` to your JDK 17 installation and `ANDROID_HOME` or `ANDROID_SDK_ROOT` to your
Android SDK installation. Add their tools to your shell path, substituting your own paths:

```bash
export JAVA_HOME=/absolute/path/to/jdk-17
export ANDROID_HOME=/absolute/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
./gradlew --version
```

Alternatively, install the SDK components through Android Studio's SDK Manager, open the
repository as a Gradle project, and select JDK 17 as the Gradle JDK. Keep SDK paths and other
machine-specific settings in untracked local configuration. Windows users can use
`gradlew.bat` in place of `./gradlew`.

## Build and install

Build the recommended matching manual-install pair:

```bash
./gradlew :mobile:assembleSideload :wear:assembleSideload \
  --no-daemon --max-workers=2
```

| App | Minimum OS | Sideload APK |
|---|---|---|
| Android phone | Android 8 / API 26 | `mobile/build/outputs/apk/sideload/mobile-sideload.apk` |
| Wear OS watch | Wear OS 3 / API 30 | `wear/build/outputs/apk/sideload/wear-sideload.apk` |

Install each APK only on its corresponding device:

```bash
adb devices
adb -s PHONE_SERIAL install -r mobile/build/outputs/apk/sideload/mobile-sideload.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/sideload/wear-sideload.apk
```

The `sideload` build type inherits release behavior and production manifests, is
non-debuggable, and uses the existing Gradle development signing identity so it can be
installed manually. It is **not** Play/production signing. The phone debug APK retains
verification-only Health Connect read permissions and its consent-test activity for QA;
release APKs remain unsigned for the external release-signing process.

Both apps intentionally use `com.long2know.sportlogger` and must come from the same signing
identity. Every future in-place upgrade must use that same identity. Keeping an older CI
artifact can reproduce or reinstall that exact build; it cannot make a newer build signed by
a different CI identity upgrade-compatible. Do not uninstall automatically to bypass a
signature mismatch because uninstalling removes app-private recordings and migration sources.

See [Install SportLogger on a phone and watch](docs/installing.md) for wireless debugging,
public-certificate comparison, backup-safe upgrade guidance, and privacy boundaries.

### Development checks

Build all public APK variants, run host tests and lint, and assemble instrumentation APKs:

```bash
./gradlew clean \
  assembleDebug assembleRelease \
  :mobile:assembleSideload :wear:assembleSideload \
  test lint \
  :mobile:assembleDebugAndroidTest \
  :wear:assembleDebugAndroidTest \
  :utilities:assembleDebugAndroidTest \
  --no-daemon --max-workers=2
```

The standalone migration-verification tools have their own prerequisites and commands:

- [Exact SQLite compatibility harness](tools/sqlite-compat/README.md): pinned upstream SQLite
  engines and exact behavior checks, including a 100,000-point gate.
- [Android legacy formatter evidence](tools/android-formatter-evidence/README.md): API 26/API 36
  instrumentation probes and reproducible sanitized evidence.
- [Legacy migration oracle](tools/legacy-migration-oracle/README.md): read-only migration checks
  against exact SQLite engines and deterministic legacy candidates.

Do not commit SDK installations, emulator data, generated databases, build output, private
signing material, credentials, or local activity files.

## Project layout

```text
mobile/       Android phone companion, canonical history, export, and integrations
wear/         Wear OS recording, summaries, and durable transfer
utilities/    Shared protobuf contracts, models, validation, and TCX support
tools/        Standalone compatibility and migration-verification tools
docs/         Architecture, operating guides, roadmap, and research
.github/      CI and contributor automation
```

## Roadmap

The [Cycle 3 modernization roadmap](docs/modernization-roadmap.md) is the historical staged
design and acceptance plan. Its core implementation milestones now exist: the JDK 17/API 36
toolchain, canonical protobuf and Room storage, capability-aware recording, durable Data Layer
transfer and commit acknowledgement, the approved Compose phone experience, TCX save/share,
Health Connect publication, and the Endurain TCX publishing workflow. The watch Compose
integration exists but is not yet a ship-approved product surface.

Remaining release acceptance is deliberately narrower and evidence-based:

- independently correct and approve the watch UI's optional-permission, live-presence,
  unavailable-active-duration, and ambient-minute behavior;
- exercise a real paired phone/watch commit-acknowledgement path;
- validate physical heart-rate, GNSS, off-body, battery, and square-watch behavior;
- validate against a disposable supported Endurain deployment;
- complete external production signing, Play declarations, and store review.

These gates do not erase the host and API 36 emulator behavior already implemented, but those
results must not be presented as physical-device, live-service, or store-release proof. See
the [open issues](https://github.com/long2know/sport-logger/issues) for the active backlog and
[platform research](docs/research/open-source-and-platform-research.md) for design evidence.

## Contributing

`main` is the reviewed baseline; `dev` is the integration branch. Start focused changes from
`dev`, include regression coverage with behavior changes, and open a PR targeting `dev`.
Reviewed integration changes are promoted to `main` separately.

Keep recording behavior, existing activity data, and transfer compatibility intact unless a
change explicitly documents and tests a migration. Never include personal workout data,
credentials, signing material, or machine-specific configuration in a PR or its logs.

## License

SportLogger is licensed under the [Apache License 2.0](LICENSE).
