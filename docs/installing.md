# Install SportLogger on a phone and watch

This guide covers manually installing the matching `sideload` APK pair. The sideload
variant uses release behavior and production manifests, is non-debuggable, and uses the
existing Gradle debug signing configuration so Android can install it. That certificate
is a development identity for manual installation, not a production or Play signing
identity.

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
installed from a different identity. For repeat upgrades, build in the same trusted
environment that produced the installed pair or retain the matching CI artifact.

You can inspect and compare only the public certificates without opening private key
material:

```bash
APKSIGNER="$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner"
"$APKSIGNER" verify --print-certs \
  mobile/build/outputs/apk/sideload/mobile-sideload.apk
"$APKSIGNER" verify --print-certs \
  wear/build/outputs/apk/sideload/wear-sideload.apk
```

The reported signer certificate SHA-256 digest must match for the pair. Do not commit,
copy, inspect, or share private keystores, key passwords, endpoint credentials, or device
pairing codes.

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
different signing identity. Rebuild in the original trusted environment or recover a
matching artifact before upgrading. **Do not make uninstalling the automatic fix for a
signing mismatch:** uninstalling removes app-private recordings and migration sources.
Export or back up important data before any deliberate removal. The migration's raw-source
retention cannot protect files after an OS-level uninstall.

An older-SDK error means the selected APK is not supported by that device. Do not use a
manifest override to force Health Services onto an unsupported watch.

## First use and transfer state

Open SportLogger on both devices. Grant only the permissions needed for the measurements
and optional integrations you choose. Location is not required for an indoor workout;
missing location, sensor capability, and denied permissions are different states.

Record a short disposable workout before relying on a new installation. Pause, resume,
and stop it, then check the phone and watch together. A local completion, queued file, or
successful send is not proof that the phone committed the activity. Canonical synchronization
becomes committed only after the phone stores the verified activity and the watch accepts
the matching commit acknowledgement.

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
building and manually installing these development APKs. Successful packaging also does
not establish that prototype phone or watch UI work is finished.
