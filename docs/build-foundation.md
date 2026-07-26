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
which is the distinct Wear OS requirement for that deadline and preserves the legacy
`BODY_SENSORS` runtime contract. Targeting Wear API 36 would opt the recorder into granular health
permissions that still require physical Wear OS 6 permission, foreground-service, screen-off, and
sensor validation. The manifest declares the future `READ_HEART_RATE` permission, but runtime code
continues requesting `BODY_SENSORS` while target SDK is 35. The permission helper and tests define
the target-36 transition without claiming that unperformed device validation.

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
7. Added the API 36 `READ_HEART_RATE` manifest foundation while retaining `BODY_SENSORS` runtime
   behavior for the selected Wear target 35. Permission selection is target-aware, and
   heart-rate and location permissions are granted before the recorder service starts.
8. Replaced the removed `wearApp` packaging configuration. `mobile` and `wear` remain independently
   installable APKs with the unchanged application ID `com.long2know.sportlogger`.
9. Adapted the legacy resource-ID switch for AGP's non-final resource IDs, restored colors formerly
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
./gradlew clean assembleDebug test lint --no-daemon
```

The final clean local run completed successfully with 201 actionable tasks. Six unit-test reports
contained 10 tests with zero failures, errors, or skips. Lint completed with zero errors and 83
unsuppressed warnings (6 mobile, 72 Wear, and 5 utilities).

The clean build produces:

- `mobile/build/outputs/apk/debug/mobile-debug.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk`
- `utilities/build/outputs/aar/utilities-debug.aar`

The same tasks run in `.github/workflows/android.yml`, which installs API 36/build tools 36.0.0,
uses Temurin 17.0.20+8, and validates the checked-in wrapper before building.

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
- [Wear Health Services API 36 permission migration](https://developer.android.com/health-and-fitness/health-services/permissions)
- [Android 14 foreground-service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [AndroidX AppCompat releases](https://developer.android.com/jetpack/androidx/releases/appcompat)
- [AndroidX Core releases](https://developer.android.com/jetpack/androidx/releases/core)
- [AndroidX Fragment releases](https://developer.android.com/jetpack/androidx/releases/fragment)
- [AndroidX Wear releases](https://developer.android.com/jetpack/androidx/releases/wear)
- [Google Maven Play Services Wearable metadata](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-wearable/maven-metadata.xml)
