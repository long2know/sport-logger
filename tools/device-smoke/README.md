# Owned-emulator pairing

This bounded CLI follows the published Android Studio Wear pairing protocol without requiring
the IDE UI. It does **not** install companion APKs, sign in to Google, modify private GMS
preferences, clear app data, or fabricate a connected peer.

Only explicitly selected, caller-owned emulator serials are accepted. The test phone must
already have the official companion selected by the watch installed and configured. A
64-bit-only x86 phone cannot use the legacy Wear companion. Missing prerequisites produce
an explicit error rather than a successful-looking fallback.

```bash
python3 -m unittest discover -s tools/device-smoke -p 'test_*.py' -v

python3 tools/device-smoke/pair_emulators.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --phone emulator-5592 --watch emulator-5594 --check

python3 tools/device-smoke/pair_emulators.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --phone emulator-5592 --watch emulator-5594 \
  --watch-avd owned-watch --timeout 60
```

The tool creates a no-rebind phone forward and watch reverse, requests the supported
emulator-connection refresh, and starts the first-party companion's `EmulatorActivity`.
Success requires both devices to report the expected enabled, connected peer. Companion
consent/setup may still require interaction; the CLI never automates account authentication.
On failure it removes only mappings created by that invocation. A successful bridge remains
available for the subsequent application tests.

Remove only the matching bridge after testing:

```bash
python3 tools/device-smoke/pair_emulators.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --phone emulator-5592 --watch emulator-5594 --disconnect
```

Removing an ADB bridge is **not proof of an offline Data Layer**: Google-owned cloud relay
may still be available. Disconnect/retry tests must independently observe application peer
reachability and durable outbox state. Pairing success likewise does not prove activity
commit, matching ACK delivery, or process-death recovery; those are separate app tests.

The implementation and ABI restrictions are based on the first-party Android Studio sources:

- [Device connection protocol](https://github.com/JetBrains/android/blob/master/wear-pairing/src/com/android/tools/idea/wearpairing/DeviceConnection.kt)
- [Non-interactive companion entry point](https://github.com/JetBrains/android/blob/master/wear-pairing/src/com/android/tools/idea/wearpairing/NonInteractivePairing.kt)
- [Companion feature/ABI requirements](https://github.com/JetBrains/android/blob/master/wear-pairing/src/com/android/tools/idea/wearpairing/PairingFeature.kt)
