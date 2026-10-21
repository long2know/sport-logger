# Install SportLogger on a phone and watch

This guide covers manually installing the development APKs. It does not turn an unsigned
release APK into a signed app or imply that a Play Store release has been configured.

## Requirements

| Device | App minimum | APK |
| --- | --- | --- |
| Android phone | Android 8 / API 26 | `mobile-debug.apk` |
| Wear OS watch | Wear OS 3 / API 30 | `wear-debug.apk` |

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
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

The resulting files are:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
wear/build/outputs/apk/debug/wear-debug.apk
```

Successful Android CI runs also publish a `sport-logger-apks` artifact. Keep the phone and
watch APKs from the same artifact together. Files ending in `release-unsigned.apk` cannot
be installed until they are signed; use the debug pair for these instructions.

Debug signing identities can differ between CI runs. For repeat installations, use the
same local keystore or a configured stable release-signing process. Do not commit a
keystore, key password, endpoint credential, or device pairing code.

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
adb -s PHONE_SERIAL install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Install only the phone APK on the phone and only the Wear APK on the watch. Do not install
the instrumentation-test APK as the application.

If installation reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the existing app has a
different signing identity. Rebuild with the original key or resolve the signing mismatch
before upgrading. **Do not uninstall an existing app just to bypass the error:** uninstalling
removes its app-private recordings and migration sources. Export or back up important data
before deliberately removing an installation. The migration's raw-source retention cannot
protect files after an OS-level uninstall.

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
building and manually installing these development APKs.
