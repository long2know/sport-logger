# Modernization Roadmap — Independent Verification and Devil's Advocate Review

**Reviewer:** Fact Checker
**Review timestamp:** `2026-07-25T21:02:06.284-07:00`
**Artifact reviewed:** `docs/modernization-roadmap.md`
**Gate verdict:** **REJECT**
**Blocking correction groups:** **14**

This is an independent source check. Repository source, released artifacts, official
documentation, and public upstream repositories were treated as evidence. A separate
research report was not treated as proof.

## Rating definitions

- **Verified** — primary evidence directly supports the claim.
- **Unverified** — no adequate independent primary evidence was found; do not freeze it
  into a contract.
- **Contradicted** — primary evidence or this repository directly conflicts with the claim.
- **Needs Investigation** — the claim depends on device, server, legal, release, or
  integration testing that documentation alone cannot settle.

## Gate conclusion

The roadmap has a sound destination in broad terms—supported Android tooling, typed
persistence, durable watch-to-phone transfer, and optional outbound connectors—but the
current draft is not safe to implement. It freezes nonexistent API names, an unsupported
toolchain combination, an invalid legacy auto-migration premise, incomplete permission and
foreground-service requirements, a connector contract Endurain does not provide, and a
dependency graph that cannot wire its own implementations.

The blocking rule applies: contradicted empirical claims below must be revised before any
implementation wave begins.

---

## 1. Blocking contradictions

| ID | Rating | Exact roadmap reference | Blocking finding | Required correction |
|---|---|---|---|---|
| B-01 | **Contradicted** | §3.1 lines 168-179; §3.2 lines 183-188; W0-01 | AGP 8.7/Gradle 8.9/JDK 17 is valid in isolation, but Kotlin 2.0.21 is outside its documented Gradle and AGP support range. AGP 8.7 is no longer “latest stable,” supports only API 35, and target 35 misses the phone update requirement taking effect August 31, 2026. Upgrading the wrapper first breaks current AGP 3.3.1. | Select and pin one documented API-36-capable tuple; separate phone and Wear target requirements; upgrade AGP and Gradle as an atomic compatible step; do not combine that risk with Kotlin DSL and convention-plugin conversion. |
| B-02 | **Contradicted** | §4.2 lines 200-221; ADR-001 | `DataType.HEART_RATE_VARIABILITY` does not exist in the released public Health Services API. `PACE` is milliseconds/kilometre, `FLOORS` supports fractional `Double` values, and `ELEVATION_GAIN/LOSS` is not one symbol. SpO2 is not a public Health Services `DataType`. | Replace the catalog with released names and exact units. Mark HRV and public Health Services SpO2 unavailable for core v1. Treat vendor data as an optional extension. |
| B-03 | **Contradicted** | §4.2-§4.3; §12 permissions row | The permission mapping is incomplete or wrong for pace, speed, elevation, calories, floors, Samsung data, foreground health/location services, and API 36 transitions. | Add a versioned manifest/runtime matrix including `BODY_SENSORS`/`BODY_SENSORS_BACKGROUND` max SDK 35, API 36 health permissions, FGS permissions/types, location, activity recognition, Bluetooth, and vendor permissions. |
| B-04 | **Contradicted** | §5.3 lines 309-320; §8.2 lines 423-433 | `TotalCaloriesRecord` does not exist; the class is `TotalCaloriesBurnedRecord`. Route, speed, and elevation write permissions are omitted. `ACCESS_FINE_LOCATION` is not the Health Connect route-write permission. | Correct every record name and request all permissions generated from written record classes plus `HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE`. Keep collection permissions separate from publication permissions. |
| B-05 | **Contradicted** | §6.3 lines 373-377; §8.1-§8.3 | Health Connect identity and lifecycle semantics are unsafe: one session UUID is insufficient for all batched records, `RECORDING_METHOD_AUTOMATICALLY_RECORDED` is wrong for a user-started workout, `Device` has no app-version field, and update/delete behavior for routes and child metrics is unspecified. | Define deterministic per-record IDs, monotonic `clientRecordVersion`, actively-recorded metadata, route-preserving updates, and explicit deletion of every inserted record. Gate API 26-27 and historical/route reads correctly. |
| B-06 | **Contradicted** | §13.1 lines 581-588; §13.2; W1-01/W1-02 | Room cannot auto-generate a migration from this unversioned raw SQLite database. There is no Room schema history or “schema v1.” The database is watch-local, and existing readers already contain projection and numeric-conversion defects. | Replace “auto-migration from schema v1” with an explicit, idempotent, on-watch extract-transform-insert migration into a new Room file, validated against copied legacy databases. |
| B-07 | **Contradicted** | §6.1 lines 326-352; W2-01; W4-03 | `ChannelClient` does not provide application-level resume, persistence, ACK, deduplication, or retry. `MessageClient` success does not guarantee delivery. WorkManager network constraints do not mean a paired Data Layer node is reachable. Whole-session watch priority conflicts with phone-owned metadata. | Specify outbox/inbox state, transfer IDs, lengths/hashes, commit ACKs, duplicate handling, retry triggers, tombstone lifetime, and field-level authority. Disclose that any Data Layer client may use Google’s cloud intermediary. |
| B-08 | **Contradicted** | §2.3 serialization choice; §13.2 line 595; ADR-002 | The roadmap selects `kotlinx.serialization` while claiming proto3 unknown fields are preserved. The open kotlinx issue shows that preservation is not currently guaranteed. Google protobuf preserves unknown binary fields only when the parsed message is retained and reserialized without JSON or field-by-field rebuilding. | Pin Google protobuf plugin/compiler/runtime versions, reserve deleted numbers, preserve binary messages, and add old→new→old tests. Alternatively remove the preservation claim. |
| B-09 | **Contradicted** | §6.3 Endurain external-ID claim; §9.1-§9.3; W2-03 | Endurain has an authenticated file-upload endpoint, but no generic native-JSON activity creation contract or client UUID/external-ID field was found. Activity IDs are integers. Edit changes metadata, not route/stream payloads. JWT access tokens are short-lived and refresh tokens rotate. Duplicate start times create another hidden activity. | Define the exact upload-only v1 contract and authentication mode. If update/delete remain in scope, model integer IDs, access/refresh/session state, route replacement strategy, and uncertain-response reconciliation. |
| B-10 | **Contradicted** | §12 lines 566-574; §13.1 | Blanket certificate pinning is not a viable default for arbitrary user-configured servers. DataStore is not encrypted merely because it is Proto DataStore. Android Keystore is key storage, not a substitute for encrypting a Room database. SQLCipher plaintext conversion is not `PRAGMA rekey`. | Use platform TLS/hostname verification by default, explicit host-scoped custom trust when requested, and optional controlled pinning with backup/recovery. Specify Tink/DataStore and SQLCipher key, backup, conversion, and loss behavior. |
| B-11 | **Contradicted** | §4.2 Samsung rows; ADR-001 reversibility; W4-05 | Samsung SpO2 and skin-temperature production access requires supported devices, Samsung SDK integration, partner approval, and registered release identity. Developer mode is not a production entitlement. | Exclude vendor-only measurements from the core-v1 contract until approval is evidenced. Keep them capability-gated optional plugins with API-36 vendor permissions. |
| B-12 | **Contradicted** | §2.1-§2.2; §6.2; §10.1 | `data-* → core-*` cannot implement interfaces owned by `domain-*`; `domain-sync` cannot orchestrate connector APIs while domain may depend only on core; app shells depending only on features cannot compose data/connector Hilt bindings. Two incompatible `Connector` interfaces are defined. | Redraw the compile-time dependency graph, nominate a composition root, define one connector port, and show every implementation on each app’s runtime classpath. |
| B-13 | **Contradicted** | §15.1 lines 641-702 versus `.squad/routing.md` | 17 of 26 work items have the wrong primary owner. Required partners are also omitted, and multiple dependencies permit competing implementations or removal of migration inputs before migration. | Reassign work using routing, split mixed-domain items, and rebuild dependencies around one runnable vertical slice. Research and verification must precede affected implementation. |
| B-14 | **Contradicted** | §13.3 lines 597-600; §15; §18 | Simultaneously introducing about 23 modules, Kotlin DSL, convention plugins, Hilt, Compose, Room, DataStore, Ktor, Protobuf, Health Connect, encryption, and connectors is not incremental. UI features are not wired until Wave 4, so earlier waves do not demonstrate installable vertical value. | Use a strangler migration that preserves the three existing modules, adds at most one shared contract seam initially, and completes record→persist→transfer→persist→display before broad module/framework expansion. |

---

## 2. Toolchain, versions, and Play requirements

### Repository baseline

| Claim | Rating | Evidence |
|---|---|---|
| The project currently has three modules: `mobile`, `wear`, and `utilities`. | **Verified** | `settings.gradle`; module build files. |
| Current AGP is 3.3.1 and current Gradle is 4.10.1. | **Verified** | `build.gradle`; `gradle/wrapper/gradle-wrapper.properties`. |
| Current compile/target SDK is 28; phone and Wear min SDK are 26. | **Verified** | `mobile/build.gradle`, `wear/build.gradle`. |

### Roadmap target claims

| Roadmap claim | Rating | Verification |
|---|---|---|
| AGP 8.7 requires Gradle 8.9 and JDK 17. | **Verified** | AGP 8.7 release notes. JDK 17 is the build-runtime requirement. |
| AGP 8.7 is “latest stable.” | **Contradicted** | It was a 2024 release and is not current at the review timestamp. |
| AGP 8.7 is a suitable API-36 phone baseline. | **Contradicted** | Its documented maximum API level is 35. |
| Kotlin 2.0.21+ is compatible with the proposed tuple. | **Contradicted** | Kotlin 2.0.20-2.0.21 is fully supported only through Gradle 8.8 and AGP 8.5 in the published compatibility table. A `+` does not establish compatibility. |
| “Gradle wrapper → 8.9” requires no code changes. | **Contradicted** | Current AGP 3.3.1 requires Gradle 4.10.1. The wrapper-only intermediate state is broken. |
| “Java target 17” is an AGP 8+ requirement. | **Contradicted** | AGP 8 requires JDK 17 to run; it does not require every module’s Java bytecode target to be 17. Choosing target 17 is separate. |
| Compose BOM `2024.12.01` exists. | **Verified** | Google Maven metadata. |
| Wear Compose 1.4 is managed by/matches the Compose BOM. | **Contradicted** | `androidx.wear.compose` is versioned separately and is not in the Compose BOM mapping. |
| Horologist `0.6+` is a reproducible dependency. | **Unverified** | The project exists and is active, but `0.6+` is not an exact tested version and is stale relative to available releases. |
| Target SDK 35 is the current Play requirement. | **Verified** | It is the pre-August-31, 2026 baseline. |
| Target SDK 35 is a safe roadmap target for a phone release after this modernization. | **Contradicted** | Starting August 31, 2026, phone app updates must target API 36; Wear OS remains on API 35 for that deadline. |
| Wear min SDK 30 represents Wear OS 3+. | **Verified** | Wear OS 3 is based on Android 11/API 30. |
| Phone min SDK 26 can support Health Connect uniformly. | **Contradicted** | Health Connect requires Android 9/API 28 or higher. API 26-27 must receive an unavailable state. |

**Required baseline correction:** use exact, tested versions. An evidence-backed API-36
floor is AGP 8.10 with Gradle 8.11.1 and JDK 17; Tank must still select an exact Kotlin
version whose published range covers the selected AGP and Gradle. Compile with API 36;
target phone API 36 for the imminent deadline and decide Wear target 35 versus 36
explicitly. Do not convert Groovy→Kotlin DSL or introduce convention plugins in the same
toolchain recovery step.

---

## 3. Wear Health Services and measurement contract

Primary API evidence is the released
`androidx.health:health-services-client:1.1.0-rc02` source artifact.

### Every named Health Services `DataType`

| Roadmap expression | Rating | Public API result |
|---|---|---|
| `DataType.HEART_RATE_BPM` | **Verified** | `DeltaDataType<Double, SampleDataPoint<Double>>`; beats/minute. |
| `DataType.HEART_RATE_VARIABILITY` | **Contradicted** | No such public constant exists in the released source. |
| Health Services SpO2 | **Contradicted** | No public Health Services SpO2 `DataType` exists. |
| `DataType.STEPS` | **Verified** | `Long` interval count. |
| `DataType.STEPS_PER_MINUTE` | **Verified** | `Long` sample in steps/minute. |
| `DataType.DISTANCE` | **Verified** | `Double` interval delta in metres. |
| `DataType.PACE` | **Verified** | The constant exists as a `Double` sample. The roadmap unit claim is checked separately below. |
| `DataType.SPEED` | **Verified** | `Double` sample in metres/second. |
| `DataType.LOCATION` | **Verified** | `LocationData` sample with latitude, longitude, and optional altitude/bearing. |
| `DataType.ELEVATION_GAIN/LOSS` | **Contradicted** | There is no slash-combined symbol. The compilable constants are `ELEVATION_GAIN` and `ELEVATION_LOSS`. |
| `DataType.CALORIES_TOTAL` | **Verified** | `Double` cumulative aggregate. |
| `DataType.FLOORS` | **Verified** | The constant exists, but its values are `Double`, including partial floors. |
| `DataType.VO2_MAX` | **Verified** | `Double` sample; actual device/exercise support remains capability-dependent. |
| `DataType.SWIMMING_STROKES` | **Verified** | `Long` interval count. |
| `DataType.REP_COUNT` | **Verified** | `Long` interval count. |

### Units, provenance, availability, and non-Health-Services rows

| Claim | Rating | Finding |
|---|---|---|
| `PACE` is min/km. | **Contradicted** | Health Services emits milliseconds/kilometre when moving and zero when stopped. A named conversion is required. |
| Floors are whole counts. | **Contradicted** | The API explicitly supports partial floors as `Double`; do not round into a whole-count contract. |
| Every measurement in §4.2 is gated through `ExerciseClient.getCapabilitiesAsync()`. | **Contradicted** | Samsung SDK, `SensorManager.TYPE_PRESSURE`, and a separate location provider are outside `ExerciseCapabilities`. |
| Capability discovery reports permission state. | **Contradicted** | Exercise capabilities report exercise types/data types and feature support, not runtime permission grants. |
| Capability discovery reports generic GPS/barometer/HR-sensor booleans. | **Contradicted** | Those booleans are not fields of `ExerciseCapabilities`; separate platform/vendor probes are needed. |
| `AVAILABLE`, `PERMISSION_DENIED`, `UNAVAILABLE`, and `TEMPORARILY_UNAVAILABLE` are Health Services availability values. | **Contradicted** | They are a proposed domain enum, not the platform enum. General availability includes `UNKNOWN`, `AVAILABLE`, `ACQUIRING`, `UNAVAILABLE`, and `UNAVAILABLE_DEVICE_OFF_BODY`; location uses a separate set including `NO_GNSS` and tethered/untethered acquired states. |
| Barometric pressure can use `SensorManager.TYPE_PRESSURE`. | **Verified** | It is a public Android sensor type, but requires separate hardware discovery and lifecycle handling. |
| Bearing can be sourced from location. | **Verified** | Bearing is optional location data and must carry presence/accuracy, not a guaranteed value. |
| Samsung SpO2 and skin temperature are generally available on the named watch generations. | **Needs Investigation** | Hardware/API support exists on supported models, but production SDK access, approval, package/signature registration, and runtime support must be proven. |
| “Universal” or “most Wear OS 3+” availability labels are safe contracts. | **Unverified** | Public APIs require runtime capability discovery; no primary source guarantees those blanket device labels. |

### Permission and service matrix

The Health Services permissions table maps `CALORIES`, `DISTANCE`, `ELEVATION_GAIN`,
`ELEVATION_LOSS`, `FLOORS`, `PACE`, `REP_COUNT`, `SPEED`, `STEPS`,
`STEPS_PER_MINUTE`, and `SWIMMING_STROKES` to `ACTIVITY_RECOGNITION`.
`HEART_RATE_BPM` maps to the heart-rate permission, and `LOCATION` /
`ABSOLUTE_ELEVATION` map to `ACCESS_FINE_LOCATION`.

| §4.2 permission claim | Rating | Correction |
|---|---|---|
| Heart rate: `BODY_SENSORS` → API-36 heart-rate permission | **Verified** | Use the full `android.permission.health.READ_HEART_RATE` name and max-SDK declarations described below. |
| HRV: same as heart rate | **Contradicted** | The named public HRV data type does not exist, so this cannot be a Health Services permission contract. |
| Samsung SpO2/skin temperature: `BODY_SENSORS` | **Contradicted** | That applies only to the older target regime; API 36 uses Samsung’s oxygen-saturation/skin-temperature health permissions and production access policy. |
| Steps/cadence/distance: `ACTIVITY_RECOGNITION` | **Verified** | Current Health Services permission table supports this mapping. |
| Pace: no permission | **Contradicted** | Current Health Services table maps `PACE` to `ACTIVITY_RECOGNITION`; GNSS configuration can additionally require location. |
| Speed: only `ACCESS_FINE_LOCATION` | **Contradicted** | `SPEED` maps to `ACTIVITY_RECOGNITION`; GNSS-derived operation can additionally require location. |
| Location/bearing: `ACCESS_FINE_LOCATION` | **Verified** | Bearing is optional location content, not a guaranteed separate sensor. |
| Elevation gain/loss: only `ACCESS_FINE_LOCATION` | **Contradicted** | Gain/loss map to `ACTIVITY_RECOGNITION`; `ABSOLUTE_ELEVATION`/location map to fine location. |
| Pressure: no runtime permission | **Verified** | Hardware presence and sensor lifecycle still require a separate platform check. |
| Calories: `BODY_SENSORS` | **Contradicted** | `CALORIES_TOTAL` maps to `ACTIVITY_RECOGNITION`. |
| Floors: no permission | **Contradicted** | `FLOORS` maps to `ACTIVITY_RECOGNITION`. |
| VO2 max: `BODY_SENSORS` | **Unverified** | The current official permission table does not publish that mapping; do not freeze it without a supported-device test and source. |
| Swimming strokes/rep count: `ACTIVITY_RECOGNITION` | **Verified** | Current Health Services permission table supports this mapping. |

Roadmap corrections:

1. Declare `android.permission.BODY_SENSORS` with `maxSdkVersion="35"`.
2. On API 36+, declare/request `android.permission.health.READ_HEART_RATE`.
3. If background body data is used, cap `BODY_SENSORS_BACKGROUND` at 35 and use
   `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` on API 36+.
4. Add `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_HEALTH`; declare service type
   `health`.
5. When GNSS/location is active, also add `FOREGROUND_SERVICE_LOCATION`, service type
   `location`, and `ACCESS_FINE_LOCATION`.
6. Correct the catalog: pace, speed, elevation gain/loss, calories, floors, strokes, and
   reps require `ACTIVITY_RECOGNITION`; location may be additionally required when GNSS
   contributes to distance/speed/pace.
7. Direct BLE support on API 31+ requires `BLUETOOTH_SCAN` and/or
   `BLUETOOTH_CONNECT`, plus max-SDK legacy Bluetooth declarations.
8. Samsung API-36 integrations use vendor permissions such as
   `HealthPermissions.READ_OXYGEN_SATURATION` and
   `HealthPermissions.READ_SKIN_TEMPERATURE`, not merely `BODY_SENSORS`.

Until this matrix is in the roadmap, long-running exercise capture can compile yet fail at
runtime when the app backgrounds, the screen turns off, or the target SDK changes.

---

## 4. Health Connect

### Every named record

| Roadmap record | Rating | Result |
|---|---|---|
| `ExerciseSessionRecord` | **Verified** | Public record. |
| `HeartRateRecord` | **Verified** | Public record. |
| `StepsRecord` | **Verified** | Public record. |
| `DistanceRecord` | **Verified** | Public record. |
| `TotalCaloriesRecord` | **Contradicted** | No such public record. Use `TotalCaloriesBurnedRecord`. |
| `ElevationGainedRecord` | **Verified** | Public record. |
| `SpeedRecord` | **Verified** | Public record. |
| `ExerciseRoute` | **Verified** | Public route type used with an exercise session; it is not a standalone `Record`. |

### Every named permission

| Roadmap permission | Rating | Result |
|---|---|---|
| `WRITE_EXERCISE` | **Verified** | Generated by `HealthPermission.getWritePermission(ExerciseSessionRecord::class)`. |
| `WRITE_HEART_RATE` | **Verified** | Generated from `HeartRateRecord`. |
| `WRITE_STEPS` | **Verified** | Generated from `StepsRecord`. |
| `WRITE_DISTANCE` | **Verified** | Generated from `DistanceRecord`. |
| `WRITE_TOTAL_CALORIES_BURNED` | **Verified** | Valid permission, but it belongs to `TotalCaloriesBurnedRecord`, not the nonexistent class in the table. |
| `READ_EXERCISE` | **Verified** | Generated from `ExerciseSessionRecord`; it does not authorize all associated metric records. |
| `ACCESS_FINE_LOCATION` as route-publication permission | **Contradicted** | It authorizes location collection. Publishing a route requires `HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE`. |

Missing from the roadmap:

- write permission for `ElevationGainedRecord`
- write permission for `SpeedRecord`
- `HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE`
- per-record read permissions for imported HR, steps, distance, calories, elevation, and
  speed
- `PERMISSION_READ_HEALTH_DATA_HISTORY` if the import must exceed the default historical
  window
- route-specific user consent for reading routes created by other apps

### Identity, metadata, update, and delete semantics

| Claim or implied contract | Rating | Finding |
|---|---|---|
| Health Connect works on every phone at min SDK 26. | **Contradicted** | Availability begins at API 28. |
| A completed user-started workout is `RECORDING_METHOD_AUTOMATICALLY_RECORDED`. | **Contradicted** | Use `Metadata.activelyRecorded(...)`. Automatically recorded is for passive capture without an explicit user start. |
| Health Connect `Device` can store watch model and app version. | **Contradicted** | `Device` has type, manufacturer, and model. It has no app-version or firmware field. |
| One `clientRecordId = session UUID` fully solves idempotency. | **Needs Investigation** | IDs are unique per record type, but multiple batched records of one type need deterministic suffixes. Every retry must reuse the same IDs. |
| Timestamp comparison is enough for Health Connect conflict resolution. | **Contradicted** | `clientRecordVersion` is a monotonic `Long`; equal or lower versions are ignored. |
| Deleting an exercise session deletes all metric records written with it. | **Contradicted** | Metrics are separate records and must be tracked/deleted explicitly. |
| Updating a session while omitting its route is harmless. | **Contradicted** | When route permission is held, an update with no route can remove the previous route. The connector must preserve or deliberately clear it. |
| Historical import needs only `READ_EXERCISE`. | **Contradicted** | Associated record types require their own read permissions, older history may require the history permission, and routes have additional consent behavior. |

The connector therefore needs a local ledger for every inserted Health Connect record:
internal session ID, record type, deterministic client record ID, client version, Health
Connect ID when returned, and deletion state.

---

## 5. Legacy SQLite and Room migration

### Existence and schema check

The source defines this exact database:

`GPSLOGGERDB_LONG2KNOW`

| Table | Columns found in source |
|---|---|
| `ACTIVITY` | `ID`, `GMTSTART`, `GMTEND`, `NAME`, `DESCRIPTION`, `DISTANCE`, `TIME`, `PACE` |
| `GPS_POINTS` | `ID`, `ACTIVITYID`, `GMTTIMESTAMP`, `LATITUDE`, `LONGITUDE`, `ALTITUDE`, `ACCURACY`, `SPEED`, `BEARING`, `HEARTRATE` |

Evidence: `utilities/src/main/java/com/long2know/utilities/data_access/SqlLogger.java:27-49,106-116`.

| Claim | Rating | Finding |
|---|---|---|
| The existing database has a Room “schema v1.” | **Contradicted** | No Room schema export, `RoomDatabase`, `SQLiteOpenHelper`, `user_version`, or upgrade path exists. |
| Room auto-migration can generate the legacy conversion. | **Contradicted** | AutoMigration compares exported Room schemas. It cannot infer transforms from this raw database. |
| §13.1’s read→map→insert approach is feasible. | **Verified** | It is feasible as an explicit ETL into a new database, not as W1-01 auto-migration. |
| The migration can run solely on the phone. | **Contradicted** | `Config.context` is assigned by the Wear service before SQLite access; the recorded legacy database is watch-local. |
| Existing getters are safe input for migration. | **Contradicted** | Activity projections omit `GMTEND` but read it. Track-point loaders read several `REAL` columns with integer getters; longitude, altitude, accuracy, speed, and bearing can be truncated. |
| Count equality alone proves lossless migration. | **Contradicted** | Counts do not detect truncation, wrong timestamps, swapped ownership, or omitted end times. |
| Room automatically eliminates a demonstrated SQL injection. | **Unverified** | Room encourages bound queries and compile-time checking, but injection safety still depends on query construction. Current concatenated inserts shown here use internal numeric/timestamp values; an exploitable user-input path was not established. |
| The normalized Room schema remains automatically downgrade-compatible with raw SQLite. | **Contradicted** | Room adds schema identity/version expectations and the proposed data model changes shape. Rollback compatibility requires an explicit strategy. |

### Required migration contract

1. Run on the watch before legacy readers are removed.
2. Open the legacy file read-only and create a separately named Room database.
3. Read columns with direct typed SQL and explicit projection aliases; do not reuse the
   defective model loaders.
4. Generate deterministic UUIDs from stable legacy identifiers and database identity so a
   retry cannot duplicate sessions.
5. Convert UTC strings and `REAL` values without integer truncation.
6. Validate row counts, orphan counts, min/max timestamps, representative coordinate
   precision, per-activity point counts, and deterministic checksums.
7. Test using copied databases representing empty, partial, large, malformed, and
   interrupted migrations.
8. Commit the “migration complete” marker only after Room transaction commit and
   verification.
9. Retain the legacy file until at least one successful release/backup cycle; define
   rollback and low-storage behavior.

---

## 6. Wear Data Layer and Protocol Buffers

### Transport behavior

| Claim | Rating | Finding |
|---|---|---|
| `DataClient`/DataItems persist while devices are disconnected. | **Verified** | Data is buffered and synchronized after reconnection; Assets are intended for larger persistent payloads. |
| `MessageClient` is suitable for small ephemeral messages. | **Verified** | It targets connected nodes and has a roughly 100 KB guidance limit. |
| A successful `sendMessage` result guarantees receiver delivery. | **Contradicted** | Official reference explicitly says success does not guarantee delivery. |
| `ChannelClient` supports large streaming/file payloads. | **Verified** | It exposes streams and `sendFile`, including offset/length operations. |
| `ChannelClient` is inherently resumable and durable. | **Contradicted** | The application must persist offsets/state, reopen channels, verify content, and retry. |
| A WorkManager `CONNECTED` constraint means the paired phone is reachable. | **Contradicted** | It describes network connectivity, not Data Layer node reachability. |
| Wear Data Layer is necessarily direct Bluetooth transport. | **Contradicted** | Official documentation says all Data Layer clients may transfer over Bluetooth or through a Google-owned cloud intermediary, with end-to-end encryption on the cloud path. |
| Whole-session watch-priority last-writer-wins is consistent with phone metadata ownership. | **Contradicted** | A later watch resend can overwrite phone edits unless fields have separate versions/owners. |
| A seven-day watch retention and unspecified phone tombstone retention prevent resurrection. | **Unverified** | An offline or restored watch can outlive the tombstone unless a durable deletion/version rule is specified. |

The current implementation reinforces the need for a real protocol:

- Wear sends a Java-serialized Asset at one static DataItem path and leaves
  `TODO: mark the transmission as complete`
  (`wear/.../MainActivity.java:244-255`).
- Phone deserializes and broadcasts in memory; it does not durably persist or acknowledge
  (`mobile/.../ListenerService.java:37-74`).

### Minimum viable transfer contract

For v1, whole-file retry is simpler and safer than claiming chunk-level resume:

1. Watch writes an immutable protobuf envelope and durable outbox row.
2. Envelope carries transfer ID, session ID, schema version, byte length, SHA-256, and
   monotonic session revision.
3. Channel sends the file. Disconnect means “unknown/not committed,” not success.
4. Phone verifies length/hash, parses, and transactionally upserts the session plus inbox
   receipt.
5. Phone sends a commit ACK containing transfer ID and stored revision.
6. Watch marks synced only after receiving a matching ACK. Lost ACK causes resend; phone
   receipt makes resend harmless.
7. Trigger immediately from Data Layer node/connectivity callbacks, with scheduled work only
   as a fallback.
8. Add chunk offsets only after measured session sizes justify the additional state machine.

If “never traverse Google infrastructure” is a requirement, it is incompatible with a
guarantee made by any Data Layer client and needs a separate architecture decision.

### Protobuf claim check

| Claim | Rating | Finding |
|---|---|---|
| Protobuf binary encoding is compact and type-safe. | **Verified** | Google code generation provides typed Java/Kotlin APIs. |
| Google-generated Kotlin is independent of Java generation/runtime. | **Contradicted** | Kotlin code builds on generated Java message classes. |
| Proto3 unknown fields can survive old-client parse and binary reserialization. | **Verified** | Google protobuf preserves unknown fields in the message and serializes them again. |
| Unknown fields survive JSON conversion or rebuilding a new domain/message object field by field. | **Contradicted** | Official guidance lists both as ways unknown fields are lost. |
| `kotlinx.serialization` ProtoBuf currently guarantees unknown-field preservation. | **Contradicted** | Its public issue for this capability remains open. |
| `schemaVersion` alone creates forward compatibility. | **Contradicted** | Field-number discipline, reserved deleted numbers, runtime behavior, and old/new round-trip tests are also required. |

ADR-002 must choose one implementation. The evidence-backed choice for its stated
preservation property is Google protobuf lite with exact matching plugin, `protoc`, and
runtime versions.

---

## 7. Endurain connector contract

Current upstream source was checked at the review timestamp.

### Endpoint and authentication existence

| Capability | Rating | Current public contract |
|---|---|---|
| Upload an activity file | **Verified** | `POST /api/v1/activities/create/upload`; accepts GPX, FIT, TCX, and GZ. |
| Upload authentication | **Verified** | JWT or `X-API-Key`, requiring `activities:upload`. API-key query parameters are disabled by default. |
| Native JSON activity creation | **Contradicted** | No generic native-JSON create endpoint was found. |
| Edit activity | **Verified** | `PUT /api/v1/activities/edit`; JWT plus `activities:write`. |
| Replace route/streams through edit | **Contradicted** | `ActivityEdit` contains metadata/privacy fields, not route or stream replacement. |
| Delete activity | **Verified** | `DELETE /api/v1/activities/{activity_id}/delete`; integer ID, JWT plus `activities:write`. |
| Delete is idempotent success when already absent | **Contradicted** | Missing activity returns 404. Client policy must interpret or reconcile this. |
| Activity identifier is a client UUID/external ID | **Contradicted** | The model primary key is an auto-increment integer; no general client UUID field was found. |
| Upload retry is naturally idempotent | **Contradicted** | A duplicate start time creates another hidden activity and a duplicate notification. |
| API key can perform edit/delete | **Contradicted** | API-key scopes are restricted to `activities:upload`; write operations use JWT. |
| JWT can be stored as one opaque long-lived token | **Contradicted** | Default access lifetime is 15 minutes; refresh lifetime is seven days; refresh tokens rotate and reuse is detected. |
| Server rate limits are known | **Unverified** | The roadmap correctly marks this for research; no stable connector contract should assume values. |

### Required v1 decision

The smallest reliable v1 is **upload-only**:

- use the scoped API key in `X-API-Key`
- upload TCX (or another explicitly supported file)
- persist a local upload state and returned integer Endurain ID
- never blindly retry an upload after an unknown response without reconciliation

If edit/delete is mandatory, the connector additionally needs username/password login or
another supported session bootstrap, access token, rotating refresh token, expiries,
session/CSRF behavior as applicable, integer remote ID mapping, refresh concurrency control,
and a defined delete/re-upload strategy for route changes. `encryptedToken: ByteArray` is
not an adequate model.

---

## 8. Security claims for arbitrary self-hosted servers

| Claim | Rating | Finding |
|---|---|---|
| HTTPS-only is an appropriate default. | **Verified** | Health and location credentials/data must not use cleartext transport. |
| Certificate pinning should be mandatory for every self-hosted Endurain URL. | **Contradicted** | The app cannot ship a static pin for an arbitrary user-owned host. Pinning also breaks CA/key rotation unless backup and recovery paths exist. |
| Platform CA trust plus hostname verification is a secure default for public certificates. | **Verified** | This is the Android TLS default; custom trust-all managers and hostname bypasses are unsafe. |
| Self-signed/private CA servers can be handled by disabling validation. | **Contradicted** | Trust-all behavior enables interception. Trust must be explicitly scoped to the selected host and hostname verification retained. |
| Proto DataStore encrypts its file by default. | **Contradicted** | DataStore provides storage/serialization, not automatic encryption. |
| “Proto DataStore with Tink” is feasible. | **Needs Investigation** | It is feasible with an explicit AEAD serializer/keyset design. The first official `datastore-tink` artifact appears in DataStore 1.3.0 alpha; stable/version policy and migration must be chosen. |
| Android Keystore alone encrypts a Room database. | **Contradicted** | Keystore protects non-exportable key material and performs crypto operations; a database cipher or explicit field/file encryption is still required. |
| SQLCipher can back Room. | **Verified** | The modern `net.zetetic:sqlcipher-android` artifact provides `SupportOpenHelperFactory`; native `sqlcipher` must be loaded. |
| `PRAGMA rekey` converts the plaintext legacy database. | **Contradicted** | SQLCipher states that `rekey` only changes the key of an already encrypted database. Plaintext conversion requires `sqlcipher_export()` or a copy into a newly encrypted database. |

Required self-hosted trust model:

1. Normalize and validate a user-supplied HTTPS URL; reject embedded credentials and
   unexpected schemes.
2. Use platform trust and hostname verification by default.
3. Support a custom CA or explicit pin only through a deliberate, host-scoped enrollment UI
   with fingerprint display, reset, expiry/rotation, and recovery.
4. Never send authorization or API-key headers across host- or scheme-changing redirects.
5. Keep secrets out of URLs, analytics, exception text, and request logging.
6. Store credentials using an explicit AEAD design; define key generation, keyset storage,
   associated data, migration, and Keystore-loss behavior.
7. For SQLCipher, define passphrase generation/wrapping, plaintext conversion order,
   backup/restore behavior, native ABI/16-KB-page compatibility, and what happens when the
   Keystore entry is lost.
8. Decide whether encrypted databases are excluded from device backup or have a recoverable
   key strategy; restoring ciphertext without its key is data loss.

---

## 9. Open-source projects, licenses, activity, and vendor access

Activity was assessed as observed at `2026-07-25T21:02:06.284-07:00`.

| Item | Rating | Existence/activity/license check | Architectural correction |
|---|---|---|---|
| R-01 OpenTracks | **Contradicted** | Active on Codeberg and Apache-2.0; GitHub mirror is archived. SQLite and BLE support exist. The current Codeberg project is a single root Android module and has no Wear companion/Data Layer module. | Use it for recording/storage/BLE ideas, not as proof of the proposed phone-watch architecture. |
| R-02 SportsTracker | **Verified** | Active Codeberg project, GPL-3.0, separate `mobile` and `wear` modules, OSM, multisport, and encrypted phone-watch sync. | Its transport uses Bluetooth RFCOMM (`CommsBT.kt`), not Wear Data Layer. GPL code adoption has licensing consequences. |
| R-03 Horologist | **Verified** | Active Google project, Apache-2.0, with Compose, Data Layer, Tiles, and Wear helpers. | Pin an exact tested release; `0.6+` is stale and non-reproducible. |
| R-04 Wear OS Samples | **Contradicted** | `android/wear-os-samples` is active/Apache-2.0 and contains the DataLayer sample. Current Health Services exercise examples are in `android/health-samples`, not all in this repository. | Cite the correct sample repository per API. |
| R-05 Endurain | **Verified** | Active, unarchived, AGPL-3.0-or-later, FastAPI/SQLAlchemy/Alembic/PostgreSQL; file imports exist. | Use exact source routes described in §7, not a generic REST assumption. Do not copy AGPL server code without license review. |
| R-06 Garmin FIT SDK | **Needs Investigation** | Official SDK exists; release 21.205.0 was published May 19, 2026. It uses Garmin’s FIT Protocol License, not a standard OSI open-source license. | Legal/package-size/API suitability must be approved before adoption. |
| R-07 Android health samples | **Verified** | Active Google/Android repository, Apache-2.0, containing Health Services and Health Connect examples. | Samples illustrate APIs; they do not replace permission/version/device contract tests. |

### Samsung production-access check

| Claim | Rating | Finding |
|---|---|---|
| Public Samsung APIs expose SpO2 and skin temperature on supported Galaxy Watches. | **Verified** | Samsung documents the data types and API-36 permission names. |
| Any Play-distributed app can use them after requesting `BODY_SENSORS`. | **Contradicted** | Production access requires the Samsung Health Sensor SDK process, partner approval, and registered package/release signature. |
| Developer mode establishes production availability. | **Contradicted** | Developer mode is for development/testing. |
| These metrics can be a core-v1 acceptance criterion before approval. | **Contradicted** | Unpublished approval cannot be assumed. They must degrade to unavailable and remain outside the core contract. |

---

## 10. Architecture consistency and unnecessary complexity

### Compile-time dependency defects

1. `data-* → core-*` conflicts with “data implements domain interfaces.” An implementation
   must compile against the interface it implements.
2. `domain-sync` is forbidden from depending on `connector-api`, yet is tasked with
   orchestrating connectors.
3. `data-connector` cannot implement/use the connector SPI under the stated `data-* →
   core-*` rule.
4. App shells depending on features “only” cannot put Hilt data and connector bindings on the
   application runtime classpath.
5. §6.2 and §10.1 define two incompatible interfaces named `Connector`; the latter adds
   `iconRes`, configuration, connection testing, and supported exercise types.
6. Hilt multibindings assemble compile-time bindings into a collection. They are not
   post-install dynamic plugin discovery.
7. `iconRes: Int` places an Android UI/resource concern in the connector SPI and cannot
   identify resources portably across independently packaged implementations.

### Internal count and acceptance contradictions

| Summary claim | Rating | Actual count/result |
|---|---|---|
| 22 target Gradle modules | **Contradicted** | The architecture tree lists 23 app/library modules, excluding `build-logic`. |
| 29 work items | **Contradicted** | W0-W4 contain 26 items. |
| 16 measurements | **Contradicted** | §4.2 contains 18 measurement rows. |
| APK size per module | **Contradicted** | Library/feature modules normally produce AARs; app modules produce APK/AAB artifacts. |
| Each wave demonstrably yields a buildable, installable APK | **Unverified** | No gate proves this. The wrapper-first intermediate is broken, and new UI features are not composed into app shells until W4-01. |
| Wear OS 3/4/5 coverage is sufficient for release | **Needs Investigation** | Wear OS 6/API 36 permissions are already part of the roadmap’s transition concerns and require coverage. |
| `<2% battery/hour` is an evidenced acceptance threshold | **Unverified** | No baseline device, screen/GNSS state, metric set, or measurement method is defined. |

### Complexity finding

The roadmap asks the first implementation cycle to absorb:

- major AGP/Gradle/JDK/Kotlin change
- Groovy→Kotlin DSL, version catalog, and convention plugins
- AndroidX migration
- Java→Kotlin conversion
- roughly 23 modules
- Hilt, Compose on two form factors, Room, DataStore, Ktor, WorkManager
- Protobuf and a new transfer protocol
- Health Services and API-36 permission migration
- Health Connect, Endurain, TCX, encryption, Tile, and complications

This maximizes simultaneous unknowns and makes failures hard to localize. It also delays the
first end-to-end proof until most foundational choices have already been frozen.

---

## 11. Ownership and dependency audit

### Every work-item owner versus `.squad/routing.md`

**Result:** **17 of 26 items have the wrong primary owner.**

| ID | Roadmap owner | Routing-correct primary and partner(s) | Result |
|---|---|---|---|
| W0-01 | Tank | Tank; Fact Checker verifies versions | Correct primary; required verification absent |
| W0-02 | Tank | Tank | Correct |
| W0-03 | Tank | Tank | Correct |
| W0-04 | Tank | Tank | Correct |
| W0-05 | Tank | Neo; Tank partner | **Misrouted** |
| W1-01 | Neo | Trinity for watch persistence schema; Tank partner for migration architecture | **Misrouted/must split** |
| W1-02 | Neo | Tank; Trinity partner; Mouse migration-quality support | **Misrouted** |
| W1-03 | Neo | Tank for preferences architecture; Neo for sync-state portion | **Misrouted/must split** |
| W1-04 | Switch | Tank | **Misrouted** |
| W1-05 | Switch | Trinity; Tank partner | **Misrouted** |
| W1-06 | Switch | Trinity; Tank partner | **Misrouted** |
| W2-01 | Switch | Neo; Trinity partner | **Misrouted** |
| W2-02 | Neo | Neo; Mouse partner | Correct primary; required partner absent |
| W2-03 | Neo | Neo; Rai for credentials/privacy | Correct primary; security partner absent |
| W2-04 | Switch | Neo; Tank partner | **Misrouted** |
| W2-05 | Tank | Neo; Tank partner | **Misrouted** |
| W3-01 | Trinity | Switch; Trinity partner | **Misrouted** |
| W3-02 | Trinity | Switch; Trinity partner | **Misrouted** |
| W3-03 | Trinity | Switch; Trinity partner | **Misrouted** |
| W3-04 | Trinity | Switch; Trinity partner | **Misrouted** |
| W3-05 | Trinity | Switch; Neo and Rai partners | **Misrouted** |
| W4-01 | Tank | Switch; Tank partner | **Misrouted** |
| W4-02 | Switch | Switch | Correct |
| W4-03 | Neo | Neo; Trinity partner | Correct primary |
| W4-04 | All | Mouse; named domain partners | **No accountable primary** |
| W4-05 | Mouse | Mouse; Fact Checker required partner | Correct primary; required verification absent |

### Dependency and collision errors

1. W0-03 creates canonical `core-model`; W1-04 independently moves/converts models into the
   same target.
2. W1-04 owns all of `utilities/src/` and can change/remove the exact legacy readers W1-02
   still needs.
3. W1-05 can request Health Services `LOCATION` while W1-06 creates a separate fused-location
   authority; no source-selection rule exists.
4. The “no W1 collisions” claim is false because migration and conversion share legacy
   source/model assumptions.
5. W2-05 orchestrates phone→connectors but does not depend on W2-02, W2-03, or W2-04.
6. W3-02 offers share/discard without depending on TCX/export, transfer, or tombstone work.
7. W3-03 and W3-04 cannot show phone history/detail without W2-01 watch→phone transfer.
8. W3-05 cannot configure connectors or show sync state without connector/orchestration
   dependencies.
9. W4-01 app wiring arrives after all UI features, preventing early runnable vertical slices.
10. W4-03 duplicates scheduling responsibility already assigned to W2-01/W2-05.
11. W4-04 depends only on W4-01 despite claiming record→sync→view→export coverage.
12. W4-05 is dependency-free even though its research is supposed to validate toolchain,
    API, license, and vendor choices before implementation.

---

## 12. Strongest counter-architecture

This is the strongest falsification of the 23-module plan: the same product goals can be
tested with materially fewer irreversible changes.

### Stage A — restore a supported green build

1. Keep `mobile`, `wear`, and `utilities`.
2. Capture a current APK/build baseline and copied legacy database fixtures.
3. Upgrade AGP/Gradle/JDK as one compatible change; retain Groovy.
4. Migrate to AndroidX separately.
5. Set API-36 compile/phone target requirements and add manifest/FGS tests.
6. Add Kotlin only where the next vertical seam needs it. Defer convention plugins and
   Kotlin DSL.

### Stage B — add one shared contract seam

Add at most one pure shared contract module, or temporarily place the contract in
`utilities` behind a package boundary:

- canonical session/revision IDs
- Google protobuf lite envelope
- no UI resources, Android types, credentials, or persistence classes

Keep manual composition until actual dependency pressure justifies Hilt.

### Stage C — prove one end-to-end vertical slice

1. Preserve the current recorder initially.
2. On activity completion, adapt one legacy activity into the new contract.
3. Persist it in a new watch Room database while retaining the legacy database.
4. Transfer one immutable file through a durable outbox using whole-file retry and commit
   ACK.
5. Persist transactionally in a phone Room database.
6. Display a minimal phone detail screen.
7. Test process death, disconnection, duplicate delivery, lost ACK, and reinstall/upgrade
   boundaries.

This proves the core architecture before Compose redesign, connector plugins, or mass module
creation.

### Stage D — migrate data and acquisition safely

1. Run explicit on-watch ETL using copied-database tests.
2. During rollback window, import completed legacy sessions into the new store rather than
   deleting the old path.
3. Introduce Health Services behind the existing acquisition boundary using only verified
   data types.
4. Add API-36/FGS/permission instrumentation tests.
5. Keep Samsung-only metrics outside the core build until entitlement is proven.

### Stage E — add connectors one at a time

1. Health Connect behind a feature flag with exact record ledger and permissions.
2. Endurain upload-only using scoped API key and TCX.
3. Add JWT update/delete only after a real-instance contract test proves refresh,
   reconciliation, and route behavior.
4. Add SQLCipher or DataStore encryption as a separately reversible migration with backup
   tests, not in the same change as legacy ETL.

### Stage F — split only under measured pressure

Extract domain, data, feature, and connector modules only when a demonstrated dependency,
build-time, ownership, or reuse problem requires the boundary. Add Compose/Hilt screen by
screen. Keep the app modules as explicit composition roots.

This alternative preserves incremental delivery and makes each architectural claim
falsifiable in a running app.

---

## 13. Thirty-day pre-mortem

Assume the current roadmap starts unchanged and fails within 30 days:

| Window | Likely failure | Early warning | Prevention / owner |
|---|---|---|---|
| Days 1-3 | Wrapper upgrade makes every module unbuildable under AGP 3.3.1. | W0-01 cannot produce a baseline build. | Atomic supported tuple; Tank + Fact Checker. |
| Days 3-7 | Kotlin/Compose/Hilt codegen incompatibilities obscure the original build issue. | Multiple unrelated compiler/plugin failures in one PR. | Separate toolchain, DSL, Kotlin, and DI changes; Tank. |
| Week 1 | Health Services code does not compile because HRV and combined elevation symbols do not exist. | First sensor repository branch adds guessed constants. | Compile against released source artifact and contract tests; Trinity + Fact Checker. |
| Week 1-2 | Workout dies or loses data after screen-off/background transition. | No declared health/location FGS types or API-36 permission tests. | FGS/service/permission matrix and device test; Trinity + Rai. |
| Week 2 | Migration executes on phone and finds no legacy database. | Test fixture exists only in phone instrumentation. | On-watch migration runner and copied watch DB; Tank + Trinity + Mouse. |
| Week 2 | Migrated routes have zero/truncated longitude, altitude, speed, or bearing. | Count checks pass while representative coordinates differ. | Typed SQL plus precision/checksum assertions; Mouse. |
| Week 2-3 | Disconnect or lost ACK produces duplicates or silent watch deletion. | “Channel closed” or successful send is treated as commit. | Durable outbox/inbox, hash, commit ACK, idempotent upsert; Neo + Trinity. |
| Week 3 | Endurain retry creates hidden duplicates. | Timeout occurs after upload and client immediately retries. | Upload ledger and reconciliation; begin with API-key upload-only; Neo. |
| Week 3 | Endurain sync stops after 15 minutes. | One stored access token, no refresh state or rotation lock. | Explicit auth state machine or scoped API key; Neo + Rai. |
| Entire month | Teams create competing location/model/connector implementations. | Parallel PRs touch the same contracts with different owners. | Correct routing and dependency gates; Tank. |
| Entire month | Most effort produces module scaffolding but no recorded activity on the phone. | No runnable vertical-slice demo by end of week 2. | Three-module strangler and vertical-slice milestone; Tank. |
| Week 3-4 | Samsung metrics remain unavailable in release builds. | Only developer-mode testing exists; no partner approval. | Remove from core acceptance criteria; Trinity + Mouse + Fact Checker. |
| Week 3-4 | Self-hosted users with private CAs cannot connect after mandatory pinning. | Test matrix covers only one public-CA server. | Platform trust default and host-scoped custom trust; Neo + Rai. |
| Week 4 | Restored/updated app cannot decrypt its database. | No Keystore-loss, backup, or plaintext-conversion test. | Separate encryption design and destructive recovery policy; Tank + Rai + Mouse. |

---

## 14. Required Tank cycle-2 correction list

**Owner:** Tank, with the named routing partners.
**Due:** before the Cycle 2 architecture gate is resubmitted or any W0 implementation begins.

1. Replace §3 with an exact, documented API-36-capable toolchain tuple and distinct phone/Wear
   target policy.
2. Make the AGP/Gradle change atomic; separate Kotlin DSL, version catalogs, convention
   plugins, AndroidX, Kotlin, Compose, and Hilt into independently green steps.
3. Replace the entire Wear measurement table with released `DataType` names, exact generic
   types/units, and explicit conversion rules.
4. Add the complete API-level permission and foreground-service matrix, including API 36,
   BLE, and Samsung transitions.
5. Move HRV, SpO2, skin temperature, and proprietary running/vendor measurements outside the
   core-v1 contract unless public production access is evidenced.
6. Correct every Health Connect record and permission; add route, speed, elevation, history,
   and per-record read requirements.
7. Define Health Connect availability, actively-recorded metadata, deterministic record IDs,
   monotonic versions, route-safe updates, and explicit child-record deletion.
8. Replace Room “auto-migration from schema v1” with an on-watch, idempotent ETL specification
   and copied-database precision/checksum tests.
9. Specify the Data Layer state machine, cloud-intermediary disclosure, retry triggers,
   ACK/commit boundary, duplicate behavior, field ownership, and tombstone lifetime.
10. Resolve the serialization contradiction by pinning Google protobuf lite or removing the
    unknown-field-preservation guarantee; add old/new/old tests.
11. Rewrite Endurain around exact current endpoints, integer IDs, supported file formats,
    API-key versus JWT scopes, refresh rotation, duplicate behavior, and route-update limits.
12. Replace mandatory self-hosted pinning with a platform-trust/default and explicit
    host-scoped custom-trust model, including redirect and credential rules.
13. Add a concrete encryption ADR covering Tink serializer choice, SQLCipher artifact/factory,
    native loading, plaintext export/copy, key generation, backup, recovery, and Keystore loss.
14. Correct the dependency graph, define one `Connector` interface, and make each app an
    explicit composition root containing feature, data, and connector implementations.
15. Reduce the initial module/framework scope and make record→persist→transfer→persist→display
    the first acceptance milestone.
16. Reassign all 26 work items according to `.squad/routing.md`, with required partners and
    one accountable primary for testing.
17. Repair all dependency/collision errors; make research and Fact Checker verification
    prerequisites for the contracts they validate; correct the module/work-item/measurement
    counts.
18. Return Cycle 2 with source links, tested-version evidence, copied legacy DB fixtures, and a
    rerunnable contract-test matrix for Fact Checker re-verification.

---

## 15. Primary evidence index

### Toolchain and Play

- AGP 8.7 release notes:
  https://developer.android.com/build/releases/agp-8-7-0-release-notes
- AGP 8.10 release notes:
  https://developer.android.com/build/releases/agp-8-10-0-release-notes
- AGP 3.3 release notes:
  https://developer.android.com/build/releases/agp-3-3-0-release-notes
- Kotlin Gradle/AGP compatibility:
  https://kotlinlang.org/docs/gradle-configure-project.html
- Google Play target API requirements:
  https://developer.android.com/google/play/requirements/target-sdk
- Compose BOM mapping:
  https://developer.android.com/develop/ui/compose/bom/bom-mapping
- Google Maven metadata:
  https://dl.google.com/dl/android/maven2/master-index.xml

### Wear Health Services and Android permissions

- Released Health Services source artifact:
  https://dl.google.com/dl/android/maven2/androidx/health/health-services-client/1.1.0-rc02/health-services-client-1.1.0-rc02-sources.jar
- Health Services permissions/API-36 migration:
  https://developer.android.com/health-and-fitness/health-services/permissions
- Android 14 foreground-service type requirements:
  https://developer.android.com/about/versions/14/changes/fgs-types-required
- Samsung Health Sensor permission guide:
  https://developer.samsung.com/health/sensor/guide/permission-request.html
- Samsung app verification and developer mode:
  https://developer.samsung.com/health/sensor/guide/app-verification.html
  https://developer.samsung.com/health/sensor/guide/developer-mode.html

### Health Connect

- Released Health Connect client source artifact:
  https://dl.google.com/dl/android/maven2/androidx/health/connect/connect-client/1.1.0/connect-client-1.1.0-sources.jar
- Availability:
  https://developer.android.com/health-and-fitness/health-connect/availability
- Write data and metadata:
  https://developer.android.com/health-and-fitness/health-connect/write-data
- Exercise routes:
  https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes
- Read data/history:
  https://developer.android.com/health-and-fitness/health-connect/read-data
- Delete data:
  https://developer.android.com/health-and-fitness/health-connect/delete-data

### Room, Data Layer, and Protobuf

- Room migrations:
  https://developer.android.com/training/data-storage/room/migrating-db-versions
- Migrate an existing SQLite app to Room:
  https://developer.android.com/training/data-storage/room/sqlite-room-migration
- Data Layer overview and cloud behavior:
  https://developer.android.com/training/wearables/data/overview
- DataItems/offline buffering:
  https://developer.android.com/training/wearables/data/data-items
- MessageClient reference:
  https://developers.google.com/android/reference/com/google/android/gms/wearable/MessageClient
- ChannelClient reference:
  https://developers.google.com/android/reference/com/google/android/gms/wearable/ChannelClient
- Proto3 unknown fields:
  https://protobuf.dev/programming-guides/proto3/#unknowns
- Google protobuf Kotlin generation:
  https://protobuf.dev/reference/kotlin/kotlin-generated/
- `kotlinx.serialization` unknown-field request:
  https://github.com/Kotlin/kotlinx.serialization/issues/2655

### Endurain

- Upstream repository/license/activity:
  https://github.com/endurain-project/endurain
- Activity routes:
  https://github.com/endurain-project/endurain/blob/master/backend/app/activities/activity/router.py
- Activity edit schema/model:
  https://github.com/endurain-project/endurain/blob/master/backend/app/activities/activity/schema.py
  https://github.com/endurain-project/endurain/blob/master/backend/app/activities/activity/models.py
- Authentication dependencies:
  https://github.com/endurain-project/endurain/blob/master/backend/app/auth/internal_dependencies.py
- API-key scopes:
  https://github.com/endurain-project/endurain/blob/master/backend/app/auth/api_keys/utils.py
- Token lifetimes/refresh flow:
  https://github.com/endurain-project/endurain/blob/master/backend/app/auth/constants.py
  https://github.com/endurain-project/endurain/blob/master/backend/app/auth/router.py

### Security and encryption

- Android Network Security Configuration:
  https://developer.android.com/privacy-and-security/security-config
- Unsafe TrustManager guidance:
  https://developer.android.com/privacy-and-security/risks/unsafe-trustmanager
- Unsafe hostname verification guidance:
  https://developer.android.com/privacy-and-security/risks/unsafe-hostname
- DataStore releases and `datastore-tink`:
  https://developer.android.com/jetpack/androidx/releases/datastore
- SQLCipher for Android:
  https://www.zetetic.net/sqlcipher/sqlcipher-for-android/
- SQLCipher `rekey`/`sqlcipher_export()` behavior:
  https://www.zetetic.net/sqlcipher/sqlcipher-api/#rekey

### Open-source and vendor projects

- OpenTracks Codeberg:
  https://codeberg.org/OpenTracksApp/OpenTracks
- OpenTracks archived GitHub mirror:
  https://github.com/OpenTracksApp/OpenTracks
- SportsTracker Codeberg:
  https://codeberg.org/windkracht8/SportsTracker
- Horologist:
  https://github.com/google/horologist
- Wear OS samples:
  https://github.com/android/wear-os-samples
- Android health samples:
  https://github.com/android/health-samples
- Garmin FIT SDK:
  https://developer.garmin.com/fit/
  https://github.com/garmin/fit-java-sdk
- Garmin FIT license:
  https://github.com/garmin/fit-java-sdk/blob/main/LICENSE.txt

---

# Binary gate verdict

## **REJECT**

Fourteen blocking correction groups remain. The roadmap must not enter implementation until
Tank’s Cycle 2 revision resolves the numbered corrections above and Fact Checker re-verifies
the empirical contracts.
