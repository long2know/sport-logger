# Install SportLogger on a phone and watch

This guide covers manually installing the matching `sideload` APK pair. The sideload
variant uses release behavior and production manifests, is non-debuggable, and uses the
existing Gradle debug signing configuration so Android can install it. That certificate
is a development identity for manual installation, not a production or Play signing
identity.

The final watch Compose source and packaging are approved. Its final corrected-head API 36
device gate is still pending: do not report the separate 12-test debug QA component lane or
the sideload user-flow lane as passed until their final results are available.

The debug APKs remain QA artifacts. In particular, the phone debug APK intentionally
includes verification-only Health Connect `READ_*` permissions and a debug consent-test
activity. The phone sideload APK excludes those additions while keeping the production
Health Connect write permissions. The watch sideload APK keeps its genuine production
health, activity-recognition, body-sensor, and location permissions.

## Requirements

| Device | App minimum | Target API | APK |
| --- | --- | --- | --- |
| Android phone | Android 8 / API 26 | 36 | `mobile-sideload.apk` |
| Wear OS watch | Wear OS 3 / API 30 | 35 | `wear-sideload.apk` |

Use a phone/watch combination supported by the watch manufacturer's companion app. Pair
the watch with the phone through that companion before testing SportLogger synchronization.
The app's minimum SDK does not override the companion's requirements.

## Validated phone sideload boundary

On October 21, 2026, the actual non-debuggable phone sideload APK from immutable integration
head `6cba705594efc07b3b522d9d8365c0f068618133` installed normally with `adb install -r` on
API 36. Its installed public manifest had zero Health Connect `READ_*` permissions, all eight
production `WRITE_*` permissions, and no debug consent activity.

The existing main Compose device suite passed 7/7 against that sideload app. A separate
focused main-UI check passed 1/1 while clicking Health Connect publish and owned-delete
actions against the real provider: provider IDs were returned, persisted receipts advanced
from `UPSERTED` to `DELETED`, and the granted read-permission set stayed empty. The temporary
test-only probe was removed afterward; no production source or permission change was needed.

This proves the phone user variant, not only a debug harness. It does not approve the current
watch device gate or replace the external Play/signing and physical-device gates.

## Watch source and device boundary

Immutable watch source head `fb503a4a03eca9698fba9bd2b19fff8281e8abd6` is formally
approved. It corrects optional-permission start behavior, permanent-denial recovery,
nullable live-value presence/provenance/quality, missing active-duration presentation,
ambient active/elapsed minute updates, and raw-fallback live rendering.

Historical sideload evidence from its accepted predecessor covered ordinary and partial/
zero-health permission paths, settings recovery, start, lap, pause/resume, direct paused
finish, and offline summary/retry. That evidence is not the final corrected-head device
gate. The final matrix remains:

- **Debug QA component lane:** 12 tests, result pending.
- **Sideload user-flow lane:** result pending.

Generic `ComponentActivity` fixtures belong to the debug-only test host and are intentionally
absent from the sideload user app. Attempting those fixtures in the sideload lane is an
invalid test configuration, not evidence of a product crash, and does not replace the real
sideload flows.

Watch-to-phone transfer uses Google Play services. Both apps must have the same package
name and signing certificate; SportLogger intentionally uses `com.long2know.sportlogger`
on both devices. Install both APKs from one build, not unrelated CI runs or signing keys.
See Android's [Data Layer security requirements](https://developer.android.com/training/wearables/data/overview).

## Get a matching APK pair

Follow the [build setup](../README.md#get-started), then build both apps in one checkout:

```bash
./gradlew :mobile:assembleSideload :wear:assembleSideload \
  --no-daemon --max-workers=2
```

The resulting files are:

```text
mobile/build/outputs/apk/sideload/mobile-sideload.apk
wear/build/outputs/apk/sideload/wear-sideload.apk
```

Successful Android CI runs also publish a `sport-logger-apks` artifact. Keep the phone and
watch sideload APKs from the same artifact together. Files ending in
`release-unsigned.apk` remain unsigned for the external release-signing process and cannot
be installed as produced. Use the sideload pair for these instructions rather than the
QA debug pair.

The existing Gradle debug signing identity can differ between development computers and
CI runners, and CI identities can differ between runs. A matching phone/watch pair from
one build has the same certificate, but that does not make it compatible with an app
installed from a different identity. **Every future in-place upgrade must use the same
signing identity as the installed app.** A retained CI artifact lets you reproduce or
reinstall that exact build; it does not make a newer build from a different CI identity
upgrade-compatible.

You can inspect and compare only the public certificates without opening private key
material:

```bash
APKSIGNER="$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner"
"$APKSIGNER" verify --print-certs \
  mobile/build/outputs/apk/sideload/mobile-sideload.apk
"$APKSIGNER" verify --print-certs \
  wear/build/outputs/apk/sideload/wear-sideload.apk
```

The reported signer certificate SHA-256 digest must match for the pair. Private-key
generation, storage, secure external backup, and recovery are outside this repository.
Never put a private key, keystore, key password, endpoint credential, or device pairing
code in the repository, build logs, CI artifacts, issues, or pull requests.

## Connect the installation computer

Enable developer options and authorize USB debugging on the phone. Use supported USB
debugging or wireless debugging on the watch.

On watches that offer **Wireless debugging > Pair new device**, connect the computer and
watch to the same network, enter the displayed pairing code, then connect to the separate
debugging port:

```bash
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:DEBUG_PORT
adb devices -l
```

Replace the placeholders with the values displayed by the watch. The pairing port and
connection port are not interchangeable. Older watch debugging screens can differ; follow
Android's [Wear OS debugging guide](https://developer.android.com/training/wearables/get-started/debugging)
and [wireless debugging instructions](https://developer.android.com/training/wearables/get-started/debug-wifi)
for the device's supported method.

Confirm that each intended device appears as `device`, not `unauthorized` or `offline`.
Use its exact serial in every installation command, especially when other devices or
emulators are connected.

## Install without discarding existing data

From the repository root:

```bash
adb -s PHONE_SERIAL install -r mobile/build/outputs/apk/sideload/mobile-sideload.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/sideload/wear-sideload.apk
```

Install only the phone APK on the phone and only the Wear APK on the watch. Do not install
the instrumentation-test APK as the application.

If installation reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the existing app has a
different signing identity. Produce the newer APK through the original trusted signing
environment; an older retained artifact can only reinstall its own build. **Do not make
uninstalling the automatic fix for a signing mismatch:** uninstalling removes app-private
recordings and migration sources. Export or back up important data before any deliberate
removal. The migration's raw-source retention cannot protect files after an OS-level
uninstall.

An older-SDK error means the selected APK is not supported by that device. Do not use a
manifest override to force Health Services onto an unsupported watch.

## First use and transfer state

Open SportLogger on both devices. Grant only the permissions needed for the measurements
and optional integrations you choose. Either heart-rate or activity-recognition access can
satisfy the health foreground prerequisite. Location and notifications remain optional;
permanently denied required access is surfaced with app-settings recovery.

Record a short disposable workout before relying on a new installation. Pause, resume,
save a manual lap, and finish from active or paused state, then review the canonical watch
summary and the phone history/detail/status screens. Live values should appear only after
usable samples exist and should retain source/availability truth, including actual zeroes.
A local completion, queued file, or successful send is not proof that the phone committed
the activity. Canonical synchronization becomes committed only after the phone stores the
verified activity and the watch accepts the matching commit acknowledgement.

Keep queued workouts on the watch while the phone is unavailable. Health Connect permission
and publishing are separate from watch pairing. An Endurain connection also needs its own
HTTPS endpoint and scoped API key; a successful watch transfer does not publish to that server.

## Privacy and proof boundaries

Google Play services may relay Data Layer traffic through Google Cloud when a direct
connection is unavailable; Android documents that relay as end-to-end encrypted. It is not
accurate to describe watch-to-phone synchronization as a guaranteed local-network-only path.
See the [Data Layer overview](https://developer.android.com/training/wearables/data/overview).

Use only synthetic data for emulator demonstrations. A synthetic heart rate or injected
location verifies application behavior, not physical sensor accuracy, off-body handling,
battery life, or successful pairing with a particular watch model.

Release signing, Play declarations, and physical-device acceptance are separate from
building and manually installing these development APKs. API 36 emulator evidence does
not prove physical heart-rate or GNSS accuracy, battery life, square-watch hardware,
live paired-device acknowledgement, third-party production service acceptance, or the
still-pending final corrected-head watch device matrix.
