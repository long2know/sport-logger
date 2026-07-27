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

Recording now has an explicit single-generation state machine behind a dedicated lifecycle executor.
Start, pause, resume, stop, discard, permission-loss, startup recovery, and explicit recovery return
immediately from the binder call. An accepted request carries an operation token; duplicate clicks
return the existing pending token, and invalid cross-state actions are rejected without entering a
second fence. The Wear activity disables start/resume/stop/discard/retry controls while that token is
in flight, including after activity reconnection. Until completion is delivered, status reconciliation
keeps the previous stable screen; navigation changes only from the asynchronous completion callback.
The service publishes nonterminal completion or failure into its reconnect-safe pending slot before
releasing the operation token, so no later click can enter the pipeline between state commit and
result delivery. Successful stop uses the separate durable handoff described below.

Each operation captures the service generation, operation token, activity ID, and writer generation
under a short service lock. Writer/listener fences, listener readiness waits, synchronous recovery
metadata commits, SQLite creation/deletion, and export preparation run after that lock is released.
The service reacquires the lock only to verify that the same token and generation still own the
operation and to commit the state transition. No service, state-machine, listener-registry, or
recovery-state monitor is held across a bounded wait or persistence call.
Writer and listener replacement fences revalidate that claim while holding their coordinator lock;
a stale service that passed an earlier check therefore cannot fence whichever generation a newer
service has installed in the meantime.

A writer generation captures its activity ID before scheduling, and every SQL insert uses that
immutable ID instead of consulting the mutable shared `ActivityId`. Start and resume first allocate a
strictly positive process-wide generation and synchronously commit the exact
activity/generation/recovery tuple. Only a successfully durable tuple may reach writer `SqlLogger`,
task, or scheduler construction; zero or negative generations are rejected before ownership. If the
initial tuple commit or later construction fails, the retained activity can still be stopped/exported
or discarded directly from recovery controls. The writer coordinator then reserves the
generation/activity slot under its short monitor, publishes the generation claim, and creates the
scheduler task and opens `SqlLogger` only after releasing that monitor. It revalidates the same
reservation before publishing or scheduling. Permission loss or destruction can therefore invalidate
a slow reservation immediately instead of waiting behind SQLite initialization; a task that finishes
construction after its reservation was fenced is never scheduled and is closed with its scheduler.
A process-wide writer coordinator permits at most one generation and fences
cancellation plus an in-flight write before pause, stop/export, discard/delete, permission-loss
completion, or a later recording. The fence is bounded to two seconds and honors interruption. If it
times out or otherwise fails, the operation returns a typed failure, keeps the activity and database
rows, and does not export or delete as though shutdown succeeded. A generation that terminates
because its write threw is also a failed fence, not successful quiescence. Stop/discard must validate
the exact operation token and commit the state-machine transition before terminal effects are
authorized. A raced write failure therefore retains the exact activity/generation in retry-only
recovery and returns `WRITER_FAILED`.

Recovery metadata is synchronously committed to private `SharedPreferences` as the exact activity
ID, last writer generation, and phase, but the commit itself runs on the lifecycle executor without
holding the recovery-state monitor. Recovery and terminal stores share a fair, process-wide
persistence epoch barrier. Replacement startup reserves a newer epoch on `onCreate()`, then activates
it on the lifecycle executor: activation waits for any predecessor store operation, reloads both
durable records while holding the barrier, and only then publishes replacement state. Operations from
older epochs are rejected before touching disk, so a predecessor cannot land a hidden clear or save
after the replacement reload. A fresh barrier after process death reloads the same durable records;
the in-memory epoch itself does not need to survive a dead process. A failed reload never falls back
to a start-ready idle screen: it leaves persistence unavailable, exposes only retry, and reserves a
newer epoch for the next asynchronous reload attempt.

This metadata is not service ownership: writer and listener ownership remain in the existing fenced
coordinators. Replacement startup validates that the activity row still exists, restores the
writer-generation floor, marks the tuple as recovery required, and fences only that exact prior
generation. Paused controls appear only after the writer is quiescent and the replacement listener
group acknowledges startup. The same activity ID can then be resumed with a newer generation,
stopped/exported, or discarded. Recovery UI also exposes stop and discard directly; those operations
repeat the exact bounded fences instead of pretending recovery already succeeded. An uncaught
scheduled-write failure is surfaced as `WRITER_FAILED`, closes writer and listener ownership, pauses
the stopwatch, and remains recovery-owned.

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
Validation and in-memory retention are separate from durable-store success: if a
`RECOVERY_REQUIRED` commit fails, the service still fences the writer and listeners, pauses the
stopwatch, retains the exact tuple in memory, and renders the retry screen. The result is typed
`RECOVERY_PERSISTENCE_FAILED` with `CURRENT_PROCESS_ONLY` retention, and the screen explicitly warns
that process-death recovery is not guaranteed until a later retry commits successfully.

Sensor and GPS loopers are owned by one service-instance listener group with a distinct per-group
active gate and listener-generation number. Replacement first invalidates the old generation,
unregisters both listener sets, requests safe looper quit, and waits for both termination
acknowledgements within the same two-second bound. Listener registry locks are released before those
waits. A timed-out owner remains registered as the owner, so no replacement starts beside it; a
stale service release cannot clear a newer owner. Queued sensor messages and permission callbacks
also verify the listener generation, so an old group cannot update shared samples, tear down a
replacement, or notify its UI after replacement. A group being started is tracked separately from
the established owner, so destruction or permission loss closes its active gate even when shutdown
races the listener-readiness handshake. The service client is cleared by identity,
preventing an old activity instance from disconnecting its replacement.

A start or resume starts the service stopwatch inside the same final service-monitor commit that
revalidates the operation token, service generation, activity ID, writer generation, permission gate,
and recording state. Permission loss, writer failure, stop/discard, or destruction therefore either
wins before that commit and makes startup a no-op, or wins afterward and pauses the already-started
timer. Cleanup repeats an idempotent final pause (and reset where terminal) after its writer/listener
fences so a dequeued stale completion cannot leave timing active. Stopwatch duration derives from
`SystemClock.elapsedRealtime()` rather than callback count, updates at a bounded 500 ms cadence, never
changes main-thread priority, and removes the exact generation callback during cleanup.

A detected revocation invalidates any active operation token and closes listener callback production
immediately, then queues bounded writer and listener fences on the same lifecycle executor. This
preemption does not wait for a stop/discard fence while holding the service monitor, so permission
loss cannot lock-invert or self-timeout against a terminal operation. Only after both fences succeed
does it reset the stopwatch, notify the activity, and stop the service. An owned activity's recovery
tuple remains paused so a service created after a permission regrant can restore its controls. Reset
cancels the stopwatch callback, clears the shared duration to `00:00:00`, and invalidates any stale
callback that was already dequeued. Once teardown begins, that service instance rejects start and resume.
A timeout is surfaced through `RecordingOperationResult` and
retains the activity in the retry-only recovery state rather than presenting permission shutdown as
successful.

`onDestroy()` is nonblocking. It first marks the service closing, clears its client, invalidates the
service/operation and listener generations, and only then shuts down the lifecycle executor. Exact
writer and listener cleanup continues on a bounded destruction executor without updating UI or
clearing recovery ownership or a pending terminal export handoff. A writer/listener callback that
races or follows destruction therefore
cannot submit to the shut-down executor, throw `RejectedExecutionException`, mutate a replacement
service, export/delete data, or notify stale UI. Rejected cleanup is logged explicitly and leaves the
database and durable recovery tuple intact; current-process-only metadata is logged as unable to
promise service/process replacement recovery.

`SensorFragment` uses one main-thread handler through a generation-guarded callback loop. Repeated
resume/start calls cannot create parallel chains, and pause, permission-loss state, view destruction,
or fragment destruction removes the callback through the same handler and prevents stale callbacks
from rescheduling. Activity failure/status rendering is also retained when fragment transactions are
unsafe after state save. `onStart`, `onResume`, and service reconnection reconcile the authoritative
service or retained recovery status, then render the pending idle, recording, paused, or retry screen
once the `FragmentManager` can safely commit. Stop export database reads and serialization run on a
separate activity executor and are entered only from a successful stop completion; failed or stale
terminal operations retain data and never take the export/delete/navigation success path. The
production stopped-activity query includes `GMTEND`, strictly rejects null, malformed, leniently
normalized, or trailing timestamp values, and closes its activity/track-point cursors and logger
database on success or failure.

Before a fenced stop clears active recording ownership or reports success, it synchronously persists a
minimal record containing a monotonic terminal operation ID, activity ID, writer generation, result,
and `STOP_EXPORT` type. This single-slot legacy terminal export handoff survives activity recreation,
service destruction/restart, and unrelated permission loss. The service replays it until the activity
reports a successful Data Layer handoff acknowledgment from `putDataItem`; task submission alone,
failure, cancellation, a missing callback, or process death leaves it pending for retry. Duplicate
delivery and acknowledgment are idempotent, and discard checks both submission and deletion paths so
it cannot delete the protected stopped activity. A failed terminal-record commit leaves the source
activity in explicit recovery instead of returning stop success. A failed or cancelled Data Layer
attempt immediately enables a visible in-session retry action. Each retry has a distinct in-memory
attempt token behind a process-wide attempt fence and executor that survive activity teardown. Only a
task failure, cancellation, or completed acknowledgment releases the fence, so the non-cancellable
Data Layer task cannot overlap a timeout- or recreation-triggered replacement. An attempt with no
callback remains durably pending and in flight until process death reloads it; this deliberately
prefers retained data and single delivery over an unsafe concurrent retry. Retries are explicit and
bounded by one in-flight operation rather than a zero-delay or unbounded automatic loop. Successful
handoff asks the service to acknowledge asynchronously on the serialized lifecycle executor. Recovery
and terminal preference commits therefore never run on the UI thread, and a failed or stale
acknowledgment releases the attempt while leaving the durable handoff replayable.

This compatibility bridge is intentionally not the future canonical Data Layer outbox. It stores only
one pending stopped activity, blocks another recording/discard while that slot is occupied, retains the
SQLite source rows, and treats Data Layer acceptance as handoff rather than proof that the phone
consumed the workout. A later sync milestone must replace it with a bounded multi-item outbox,
receiver acknowledgment, retry policy, and lifecycle-independent worker.

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
| `org.robolectric:robolectric` | `4.16.1` | wear, utilities tests |
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

The final clean local run completed successfully with 202 actionable tasks. Forty-eight unit-test
reports contained 282 tests with zero failures, errors, or skips. Lint completed with zero errors and
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
- Watch-to-phone transfer still uses the existing Java-serialized Data Item/Asset path. The bounded
  single-slot handoff acknowledges only successful Data Layer acceptance, not phone consumption; it is
  not a general sync outbox.
- Persistence remains the existing raw SQLite implementation; Room and legacy-data ETL are later
  work.
- The phone still uses deprecated in-process `LocalBroadcastManager` behavior to preserve the
  current event flow.
- Lint has no baseline and no issue suppression. It reports non-blocking legacy warnings including
  static context retention, locale-unspecified machine timestamps, Wear accessibility/localization
  debt, and launcher-icon modernization.
- The installed SDK emits a non-fatal SDK XML version warning with this older supported AGP family.
- Robolectric executes the production SQLite schema and stopped-activity query, including invalid end
  times and close paths. No emulator, physical Wear OS device, Play Console submission, sensor
  accuracy, screen-off recording, or real Data Layer delivery test is claimed by this build-only
  foundation.

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
