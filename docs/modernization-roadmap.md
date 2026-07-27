# Sport-Logger Modernization Roadmap — Cycle 3

> **Original author:** Tank, Mobile Architect / Lead
> **Replacement revision owner:** Neo, Sync & Integrations Engineer
> **Revision date:** 2026-07-26
> **Status:** **Approved for implementation — Cycle 3**
> **Requested by:** Stephen Long
> **Supersedes:** the Cycle 2 artifact rejected by Fact Checker

## 0. Approval boundary

This replacement revision preserves every accepted Cycle 2 correction to the 14 blocking groups
and 18 required corrections in [`roadmap-verification.md`](research/roadmap-verification.md), and
resolves the four remaining deltas in
[`roadmap-cycle2-verification.md`](research/roadmap-cycle2-verification.md). Neo owns this revision
under reviewer lockout; Tank did not participate in revising the rejected artifact. Approval is
deliberately narrow:

- Work proceeds through the 30 PR-sized items in §13 and their dependency gates.
- `mobile`, `wear`, and `utilities` remain the only Gradle modules through the first runnable
  vertical slice.
- Groovy build scripts remain in place during build recovery.
- The exact API-36-capable dependency tuple is selected by a reproducible spike, recorded, and
  independently checked before it is applied. Independently current versions are evidence inputs,
  not an assumed compatible tuple.
- Physical hardware, Samsung production entitlement, Play declarations, and a disposable Endurain
  instance are release gates. They do not block compile-valid local development.
- Claims that cannot yet be proved are removed or explicitly deferred.

The first implementation milestone is:

> **existing watch recorder → canonical protobuf contract → new watch database/outbox → durable
> Data Layer transfer and commit ACK → phone database/inbox → minimal phone display**

No Compose redesign, Hilt rollout, connector framework, database encryption, or broad module
extraction precedes that milestone.

---

## 1. Verified legacy baseline and invariants

### 1.1 Repository baseline

| Concern | Current state |
|---|---|
| Modules | `mobile`, `wear`, `utilities` |
| Build | Groovy, AGP `3.3.1`, Gradle `4.10.1`, `jcenter()` |
| SDK | compile/target 28; phone and Wear app min SDK 26 |
| Watch recorder | Java service using raw sensors/location and `GPSLOGGERDB_LONG2KNOW` |
| Transfer | Java serialization in a Data Layer Asset; no durable ACK |
| Phone | in-memory receive/broadcast; no durable activity store |

### 1.2 Migration invariants

1. Existing recording remains usable until its replacement passes the same device gate.
2. `GPSLOGGERDB_LONG2KNOW` is never modified by the new migration path.
3. Every intermediate merged change produces both phone and Wear debug APKs.
4. Build recovery, AndroidX, Kotlin, Compose, Hilt/DI, and module extraction are separate green
   changes.
5. The app modules are composition roots. They may depend on `utilities`; `utilities` never depends
   on either app.
6. No automatic deletion of legacy data, synced sessions, or tombstones is permitted in core v1.

---

## 2. Recovery architecture: a three-module strangler

```text
wear application and composition root
  ├─ existing recorder, adapted rather than rewritten first
  ├─ watch Room database, migration runner, and durable outbox
  ├─ Data Layer sender/ACK receiver
  └─ depends on utilities

mobile application and composition root
  ├─ Data Layer receiver/commit-ACK sender
  ├─ phone Room database and durable inbox
  ├─ minimal legacy-view display, then optional Compose screens
  ├─ phone-only connector implementations
  └─ depends on utilities

utilities Android library
  ├─ existing legacy models/readers and TCX code, retained during migration
  ├─ one canonical protobuf contract seam
  └─ one platform-neutral Connector port when connector work begins
```

### 2.1 Dependency rules

- `wear -> utilities` and `mobile -> utilities`; no app-to-app source dependency.
- The legacy `wearApp project(':wear')` packaging relationship may remain until packaging is
  modernized; it is not a domain dependency.
- Contract messages contain no Android UI resources, credentials, DAOs, Room entities, or service
  objects.
- Connector implementations are phone-only and are assembled explicitly by `mobile`.
- Hilt multibindings, if adopted later, are compile-time composition. They are not dynamic plugins.
- A new Gradle module requires the `M-04` extraction gate in §13.5. Package boundaries are sufficient
  until measured pressure proves otherwise.

### 2.2 Field authority

| Field family | Authority | Merge rule |
|---|---|---|
| Recorded timestamps, route, sensor samples, watch summaries | Watch | Upsert only when `watch_revision` increases |
| Phone title, notes, tags, local display state | Phone | Stored separately; never overwritten by a watch resend |
| Connector credentials and delivery ledgers | Phone | Never sent to the watch |
| Deletion/tombstone | Phone after import; watch for never-imported discard | Highest deterministic tombstone order key wins |
| Contract/schema interpretation | Shared contract | Reject unsupported required semantics; preserve unknown binary fields |

Whole-session last-writer-wins is prohibited.

---

## 3. Foundation: exact tuple selected by evidence

### 3.1 Fixed policy, variable versions

The spike must select one exact tuple satisfying all of these constraints:

| Component | Selection constraint |
|---|---|
| Android SDK | stable `compileSdk=36`; no API 37 preview dependency |
| Phone target | `targetSdk=36` |
| Wear target | first recovery build targets 35; the same tuple must compile and test a target-36 Wear profile before promotion |
| Minimum SDK | unchanged during recovery; Health Connect reports unavailable on phone API 26-27 |
| JDK | JDK 17 build runtime; bytecode target recorded separately |
| AGP/Gradle | exact pair supported by the selected AGP and capable of compiling API 36 |
| Kotlin path | exact compatible built-in-Kotlin or Kotlin plugin path; no `+` versions |
| Code generation | protobuf, Room, future KSP/Hilt, and Compose compiler compatibility demonstrated in the spike |
| UI libraries | regular Compose BOM and Wear Compose selected independently |
| Preview dependencies | excluded unless a named required feature has no stable alternative and Fact Checker approves the exception |

Research observed AGP `9.3.1`, Gradle `9.6.1`, and Kotlin `2.4.10` as separately current, but their
published support tables do not prove that combination. The spike may select different exact
versions. Its result, not this observation, becomes the repository contract.

### 3.2 Required spike record

`F-02` records:

1. Exact AGP, Gradle, JDK, SDK, Kotlin/compiler path, protobuf plugin/`protoc`/lite runtime, Room,
   Compose, Wear Compose, Health Services, and Health Connect versions.
2. Primary compatibility links for every version.
3. Exact commands, environment, resolved dependency output, and artifact checksums where available.
4. Phone and Wear debug APK builds, unit tests, lint, protobuf generation, Room generation, a
   minimal Kotlin compile, a minimal Compose compile, and a minimal Hilt/KSP compile in the
   disposable spike.
5. Wear target-35 and target-36 results.
6. A rollback tuple and the reason any preview artifact was accepted.

The spike may use temporary worktree changes, but only the reproducible report is merged. `F-03`
then applies the selected tuple atomically to the real Groovy build.

### 3.3 Independently green build sequence

1. Select the tuple in a disposable spike.
2. Upgrade AGP, Gradle, repositories, JDK runtime, and required AGP syntax atomically while retaining
   Groovy and the three modules.
3. Migrate support libraries to AndroidX in a separate change.
4. Apply API 36 compile/phone-target policy and the explicit Wear target decision separately.
5. Correct Wear foreground-service and runtime permissions separately.
6. Add protobuf-lite and Room only for the vertical slice.
7. Add Kotlin later without converting build scripts.
8. Add Compose later, screen by screen.
9. Add Hilt only after manual composition becomes an evidenced maintenance problem.
10. Consider version catalogs, Kotlin DSL, convention plugins, and module extraction as separate
    later PRs; none is part of recovery.

---

## 4. Canonical contract seam

### 4.1 Serialization choice

Core v1 uses **Google Protocol Buffers lite**, not `kotlinx.serialization` ProtoBuf.

- The exact protobuf Gradle plugin, `protoc`, generated-code, and lite runtime versions come from
  `F-02`.
- Initial generated Java lite messages are acceptable so the existing Java recorder can cross the
  seam without a Kotlin rewrite. Generated Kotlin extensions may be added only after `M-01`.
- Wire payloads are never converted through protobuf JSON.
- A message carrying unknown fields is retained and reserialized as that message. A field-by-field
  domain rebuild is not an unknown-field-preserving forwarder.
- Removed field numbers and names are declared `reserved` and are never reused.
- Enum zero values are `*_UNSPECIFIED`; unknown numeric enum values are not coerced into a valid
  business value.

### 4.2 Core-v1 payload

Core v1 contains only data needed to preserve the existing completed activity:

| Value | Canonical representation |
|---|---|
| Session ID | UUID string generated once on the watch |
| Watch revision | Monotonic unsigned integer |
| Start/end | UTC epoch milliseconds |
| Duration | milliseconds |
| Distance | metres as `double` |
| Route latitude/longitude | WGS84 degrees as `double` |
| Altitude and accuracy | optional metres as `double` |
| Speed | optional metres/second as `double` |
| Bearing | optional degrees as `double` |
| Heart rate | optional beats/minute as `double` |
| Legacy identity | watch-local migration metadata; not a connector identifier |
| Source metadata | source kind, manufacturer/model where known, and app contract version |

Optional numeric values use protobuf presence; zero is not used as “missing.” Pace is derived for
display from duration/distance in the first slice rather than copied from a potentially stale
legacy value.

### 4.3 Binary compatibility gate

Contract tests must include checked-in binary fixtures:

1. old writer → new reader;
2. new writer using only old fields → old reader;
3. new writer with an added unknown field → old parse and binary reserialize → new reader, proving
   the unknown field survived;
4. reserved-field compilation checks;
5. deterministic semantic comparison of all known values, including optional presence and
   coordinate precision.

A `schema_version` field is informative; it does not replace field-number discipline or binary
tests.

---

## 5. Watch measurements and permissions

### 5.1 Stable core Health Services identifiers and units

No row is “universal.” Each exercise type must report the requested data type in
`getExerciseTypeCapabilities(type).supportedDataTypes`.
Core v1 intentionally uses only identifiers verified in stable
`androidx.health:health-services-client:1.0.0`.

| Measurement | Stable identifier/source | Public value and unit | Core policy |
|---|---|---|---|
| Heart rate | `DataType.HEART_RATE_BPM` | `Double`, beats/minute | capability and permission gated |
| Steps | `STEPS`, `STEPS_TOTAL` | `Long`, interval/cumulative count | capability gated |
| Cadence | `STEPS_PER_MINUTE` | `Long`, steps/minute | capability gated |
| Distance | `DISTANCE`, `DISTANCE_TOTAL` | `Double`, metres | capability gated |
| Pace | `PACE` | `Double`, milliseconds/kilometre; zero when stopped | named display conversion required |
| Speed | `SPEED` | `Double`, metres/second | capability gated |
| Location | `LOCATION` | `LocationData`: degrees plus optional altitude/bearing | fine-location and live-state gated |
| Absolute elevation | `ABSOLUTE_ELEVATION` | `Double`, metres | fine-location and capability gated |
| Elevation change | `ELEVATION_GAIN`, `ELEVATION_LOSS` and total variants | `Double`, metres | activity permission and capability gated |
| Calories | `CALORIES`, `CALORIES_TOTAL` | `Double`, kilocalories including basal plus activity | capability gated |
| Floors | `FLOORS`, `FLOORS_TOTAL` | `Double`, fractional floors permitted | never round in storage |
| VO2 max | `VO2_MAX` | `Double`, mL/kg/min | optional public metric; permission/device behavior must be proven |
| Swimming | `SWIMMING_STROKES`, `SWIMMING_STROKES_TOTAL`, `SWIMMING_LAP_COUNT` | `Long`, counts | matching exercises only |
| Repetitions | `REP_COUNT`, `REP_COUNT_TOTAL` | `Long`, counts | matching exercises only |
| Pressure | `SensorManager.TYPE_PRESSURE` | hPa | raw-sensor path, not Health Services |

Advanced running metrics available only on a pre-stable or later Health Services line are not part
of core v1 unless the selected stable artifact exposes them and Fact Checker re-verifies them.

### 5.2 Separate discovery dimensions

The source of truth keeps these dimensions separate:

| Dimension | Evidence/API |
|---|---|
| Exercise capability | supported exercise types and per-exercise supported data types |
| One-off measure capability | `MeasureCapabilities.supportedDataTypesMeasure` |
| Passive capability | passive data and goal capability sets |
| Runtime permission | Android permission result, including “do not ask again” |
| Raw hardware | `SensorManager`/platform feature probe |
| Vendor capability | vendor SDK availability, device support, entitlement, and tracker support |
| Live availability | `UNKNOWN`, `AVAILABLE`, `ACQUIRING`, `UNAVAILABLE`, `UNAVAILABLE_DEVICE_OFF_BODY`; preserve location-specific states such as no GNSS |
| Provenance/quality | Health Services, raw platform, vendor, or derived; platform accuracy when supplied |

UI may derive a presentation state, but persistence and acquisition code must not collapse these
dimensions into one enum.

### 5.3 Target-35/36 permission and foreground-service matrix

| Concern | Target 35 or lower | Target 36+ | Required behavior |
|---|---|---|---|
| Steps, cadence, distance, pace, speed, calories, floors, elevation gain/loss, repetitions, and swim counts | `ACTIVITY_RECOGNITION` on API 29+ | same | remove denied data types instead of failing the workout; location may also be needed when GNSS contributes |
| Active heart rate | `BODY_SENSORS`, declared with `maxSdkVersion=35` | `android.permission.health.READ_HEART_RATE` | branch requests by target/platform |
| Passive/background heart data | `BODY_SENSORS_BACKGROUND` on API 33-35, requested after foreground sensor permission | `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` | only for an explicit passive feature |
| Active workout FGS | `FOREGROUND_SERVICE` plus `FOREGROUND_SERVICE_HEALTH` for target 34+ | same | service declares and starts with type `health` |
| Route/location FGS | `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, service type `location` | same | continue without route/absolute elevation if precise location is unavailable |
| Background location | not required solely for a user-started active workout FGS | same | request only for a separately approved background feature |
| Direct BLE sensors | `BLUETOOTH_SCAN` and/or `BLUETOOTH_CONNECT` whenever targeting API 31+, including Wear target 35; legacy Bluetooth declarations are capped at API 30 | the same API-31+ permissions, including Wear target 36 | applies to direct BLE support, not automatically to Data Layer |
| Samsung SpO2 | `BODY_SENSORS` for the relevant Samsung tracker at target 35 or lower | `HealthPermissions.READ_OXYGEN_SATURATION` | vendor-only and entitlement gated |
| Samsung skin temperature | `BODY_SENSORS` for the relevant Samsung tracker at target 35 or lower | `HealthPermissions.READ_SKIN_TEMPERATURE` | vendor-only and entitlement gated |
| Samsung raw/additional data | `BODY_SENSORS` for relevant Samsung trackers at target 35 or lower | `HealthPermissions.READ_ADDITIONAL_HEALTH_DATA` where required | never placed in generic Health Services code |

The active workout starts while the app is user-visible and only after the required runtime
permissions are granted. Screen-off and Home transitions are tested; the app does not request broad
background access as a shortcut.

### 5.4 Optional vendor extensions

HRV/RMSSD, SpO2, skin temperature, and proprietary running metrics are outside core v1.

- Public Health Services has no `HEART_RATE_VARIABILITY`, SpO2, or skin-temperature data type.
- RMSSD requires a separately validated IBI source, artifact/window algorithm, and quality policy.
- Samsung SpO2 is an on-demand measurement, not a continuous workout stream.
- Samsung skin temperature is skin/ambient temperature, not body temperature.
- Production code remains disabled until supported hardware, exact SDK tests, partner approval, and
  package/release-signature registration are evidenced.
- Developer mode is not production entitlement.

---

## 6. Persistence and legacy ETL

### 6.1 New databases

| Device | New database | Role |
|---|---|---|
| Watch | `sportlogger_watch_v1.db` | canonical sessions, samples/points, outbox, migration receipts, tombstones |
| Phone | `sportlogger_phone_v1.db` | canonical sessions, phone metadata, inbox receipts, connector ledgers, tombstones, and the phone tombstone outbox |

Room schema version 1 describes these new databases only. It is not a claimed version of
`GPSLOGGERDB_LONG2KNOW`.

### 6.2 On-watch legacy ETL

The migration is explicit, on-watch, idempotent ETL:

1. Copy sanitized legacy databases into test assets before changing legacy readers.
2. Open `GPSLOGGERDB_LONG2KNOW` read-only.
3. Read explicit columns using typed SQL and aliases; do not reuse the defective legacy projection
   and integer getters.
4. Generate a deterministic session UUID from legacy database identity plus legacy activity ID.
5. Convert UTC text and every SQLite `REAL` without integer truncation.
6. Insert into `sportlogger_watch_v1.db` in a transaction.
7. Validate activity/point/orphan counts, min/max timestamps, per-activity point counts,
   representative coordinate precision, and deterministic checksums.
8. Commit the migration receipt only after the Room transaction and verification succeed.
9. On interruption, rerun to the same IDs without duplicates.
10. Retain the legacy database through at least one successful release and backup cycle. Low-storage
    or rollback behavior must be explicit; no timed deletion is approved.

Fixtures cover empty, partial, large, malformed, precision-sensitive, orphaned, and interrupted
imports. Count equality alone is not acceptance.

Room auto-migration is used only for future exported Room schemas when appropriate. It is not used
for the raw legacy conversion.

---

## 7. Durable watch-to-phone protocol

### 7.1 Transport disclosure

Wear Data Layer clients may transfer over Bluetooth or through a Google-owned cloud intermediary;
the cloud path is documented as end-to-end encrypted by the platform. The product must disclose
that behavior. “Never traverses Google infrastructure” cannot be promised by this design and would
require a separate transport ADR.

### 7.2 Transfer identity and durable state

For each immutable session revision, the watch persists:

- `transfer_id`: UUID generated once and reused for every retry of that payload;
- `session_id`;
- `session_revision`;
- payload byte length;
- SHA-256 of the exact protobuf file;
- creation time, last attempt, attempt count, and state;
- last typed failure.

Watch outbox states are `READY`, `SENDING`, `AWAITING_COMMIT_ACK`, `COMMITTED`, and
`RETRYABLE`. A disconnect or successful channel write is not commit.

The phone inbox receipt stores `transfer_id`, `session_id`, accepted revision, hash, commit time, and
the exact ACK body in the same transaction as the canonical session upsert.

### 7.3 Whole-file v1 state machine

1. Watch finishes the existing recording and adapts it to the canonical protobuf payload.
2. Watch transactionally stores the session and durable outbox row before transfer.
3. An immediate node/connectivity callback opens a `ChannelClient` whole-file transfer. Scheduled
   work is fallback wake-up only; a generic network constraint does not prove phone reachability.
4. Phone verifies transfer ID, declared length, SHA-256, protobuf parse, and supported semantics.
5. Phone transactionally upserts only watch-owned fields and writes the inbox receipt.
6. Phone sends a small commit ACK containing transfer ID, session ID, accepted revision, and hash.
7. Watch marks `COMMITTED` only after a matching ACK.
8. A lost ACK causes the same transfer ID/file to be resent. The inbox receipt makes the duplicate
   harmless and lets the phone repeat the same ACK.
9. A stale lower revision is not applied; the phone ACK reports the stored revision. A higher
   revision updates watch-owned fields only.
10. Corrupt or unsupported payloads receive a typed rejection when possible and remain retained on
    the watch.

Chunk offsets and resumable streaming are deferred until measured payload sizes prove whole-file
retry inadequate.

### 7.4 Retry triggers

- immediate node connected/reachable callback;
- app/service start;
- new activity completion;
- explicit user retry;
- bounded scheduled fallback.

Backoff uses jitter and persists across process death. `MessageClient` or `ChannelClient` task
success is never treated as receiver commit.

### 7.5 Tombstones and reverse commit protocol

A tombstone covers activity revisions through `covered_session_revision`. Phone deletion marks the
local session deleted and writes the tombstone plus a durable phone outbox row in one transaction.
That row stores:

- immutable `transfer_id`, `session_id`, `delete_revision`, `covered_session_revision`, and
  `origin`;
- deletion time, exact tombstone-protobuf byte length, and SHA-256;
- creation time, last attempt, attempt count, state, and last typed failure.

Phone tombstone outbox states are `READY`, `SENDING`, `AWAITING_COMMIT_ACK`, `COMMITTED`, and
`RETRYABLE`. Reverse phone-to-watch delivery uses this commit protocol:

1. The phone retries the same transfer ID and exact bytes until commit.
2. The watch verifies identity, length, hash, and supported semantics.
3. In one watch transaction, it persists the tombstone and receipt—including the transfer identity,
   winning order key, hash, commit time, and exact ACK body—applies the winning delete order, and
   suppresses any watch activity outbox revision covered by the tombstone.
4. The watch sends a commit ACK containing transfer ID, session ID, delete revision, origin, and
   hash.
5. The phone marks the tombstone `COMMITTED` only after that matching ACK.
6. If the ACK is lost, the phone resends the same transfer. The watch receipt makes the duplicate
   harmless and causes the exact ACK to be repeated.

When the phone receives an activity whose `session_revision` is covered by its local tombstone, it
does not upsert any activity fields. It transactionally records a typed `TOMBSTONED` rejection
receipt and ACKs that activity transfer with the stored tombstone order key; the watch treats that
covered transfer as terminal rather than retrying it into a resurrection loop.

Delete ordering is globally deterministic across phone and watch origins:

- each origin allocates `delete_revision` as one greater than the greatest revision it has observed
  for that session;
- tombstones compare by `(delete_revision, origin_rank, transfer_id)`, with `WATCH=0`, `PHONE=1`,
  and the UUID bytes compared lexicographically as the final tie-break;
- deletion time is audit data only and never breaks ties because device clocks can disagree;
- the higher tuple wins regardless of delivery order; reuse of one transfer ID with a different
  hash is a typed protocol error.

A never-imported watch discard uses the same immutable tombstone schema, ordering, durable
watch-outbox delivery, phone receipt, and matching-ACK rules. Core-v1 tombstones are not age-pruned
automatically. They remain until explicit “erase all local data” or a future compaction ADR proves a
safe node-watermark protocol. Connector deletion follows each connector’s real capability; a local
tombstone does not imply Endurain can delete.

---

## 8. Health Connect connector contract

### 8.1 Availability and version

- Production uses stable `androidx.health.connect:connect-client:1.1.0` unless a later stable
  version is selected and re-verified.
- API 26-27 returns unavailable.
- API 28-33 checks provider presence/update state with `HealthConnectClient.getSdkStatus()`.
- API 34+ uses the framework module but still checks SDK status and feature availability.
- Historical import is optional and separate from v1 publishing.

### 8.2 Records and permissions

| Internal data | Health Connect type | Permission rule |
|---|---|---|
| Session | `ExerciseSessionRecord` | generated write/read exercise permissions |
| Heart-rate chunks | `HeartRateRecord` | generated per-record write/read permission |
| Steps aggregate | `StepsRecord` | generated per-record write/read permission |
| Distance aggregate | `DistanceRecord` | generated per-record write/read permission |
| Total calories | `TotalCaloriesBurnedRecord` | generated per-record write/read permission |
| Elevation gain | `ElevationGainedRecord` | generated per-record write/read permission |
| Speed samples | `SpeedRecord` | generated per-record write/read permission |
| Route | nested `ExerciseRoute` | `HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE`; reads use stable 1.1 `ExerciseRouteRequestContract` consent |

`ACCESS_FINE_LOCATION` is for collecting watch location, not publishing an already collected route.
Stable 1.1 code must not reference 1.2-alpha-only persistent route-read constants.

Reads older than the normal window require
`HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY`; background reads require the feature check
and `HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND`. Import requests every record
permission it actually reads. Partial grants publish/import only allowed types and return a
structured partial result.

### 8.3 Metadata, identity, update, and deletion

- User-started workouts use `Metadata.activelyRecorded(...)`.
- Health Connect `Device` stores supported type/manufacturer/model fields. App version stays in the
  sport-logger database.
- Every inserted record uses a deterministic ID such as
  `sl:<session-id>:<record-kind>:<chunk-index>`.
- Every changed record receives a monotonically increasing `clientRecordVersion`.
- The local ledger stores internal session ID, record kind/chunk, deterministic client ID, client
  version, returned Health Connect ID when available, and deletion state.
- A session update preserves the existing route unless the user explicitly clears it; omitting a
  route must not accidentally erase one.
- Session deletion explicitly deletes the session and every app-owned HR, steps, distance, calories,
  elevation, speed, and route-related record tracked by the ledger. No cascade is assumed.

---

## 9. One Connector port and phone composition

There is one platform-neutral `Connector` port in `utilities`. It exposes semantic operations, not
UI resources or a dynamic-plugin API:

- stable connector identity;
- declared capabilities such as publish, reconcile, and delete;
- publish of a canonical completed session/revision;
- optional delete only when the connector advertises it;
- typed results including success, partial, retryable failure, permanent failure,
  unknown delivery, and unsupported;
- caller-supplied configuration through connector-specific typed configuration outside the port.

`iconRes`, `Map<String,String>` configuration, and runtime classpath discovery are excluded.
`mobile` explicitly constructs the enabled implementations and owns connector scheduling and
ledgers. Hilt may later replace manual construction without changing the port.

---

## 10. Endurain v1

### 10.1 Exact supported surface

Endurain v1 is **upload-only TCX** against canonical Endurain `v0.19.0` behavior:

The released upload route accepts `.gpx`, `.tcx`, `.fit`, and `.gz` containing a supported activity
file. Sport-logger deliberately emits only TCX in v1; FIT remains behind its separate license gate.

1. Normalize an HTTPS base URL and probe `GET /api/v1/about`.
2. Generate deterministic TCX bytes from one completed local session.
3. Persist an upload outbox row before network I/O.
4. `POST /api/v1/activities/create/upload` as multipart field `file`.
5. Authenticate only with `X-API-Key` scoped to `activities:upload`; never use a URL query key.
6. On HTTP 201, parse the response as a list and transactionally store every returned integer
   activity ID.
7. Remote identity is `(normalized base URL, connection identity, integer activity ID)`, never the
   sport-logger UUID alone.

There is no native JSON create contract in scope. API-key mode cannot read, edit, or delete.
JWT login, MFA/PKCE/SSO states, rotating refresh tokens, metadata edit, and delete are deferred to a
later independently tested connector revision. Current edit does not replace route/stream payloads.

### 10.2 Delivery and duplicate reconciliation

| Outcome | State/action |
|---|---|
| Validation/auth/unsupported/oversize rejection before accepted processing | typed permanent or user-action failure |
| 429 with `Retry-After` and a definitive rejection | retry no earlier than the server instruction |
| Connection failure before request transmission | retryable |
| Timeout/drop after transmission may have begun | `UNKNOWN_DELIVERY`; no automatic retry |
| HTTP 201/list parsed and ledger committed | delivered |

Endurain has no client idempotency key. A repeated start time can create another hidden activity.
For `UNKNOWN_DELIVERY`, API-key v1 requires user reconciliation against the Endurain UI:

- mark delivered after confirming the activity;
- or explicitly retry with a duplicate-risk warning;
- if retry returns another ID, retain all IDs and flag duplicate review rather than silently choosing
  one.

“Sync all” queues ordinary uploads under server limits; the server-local bulk-import endpoint is not
a mobile connector API. The `v0.19.0` default rate limit observed in released source is 120/minute,
but clients do not assume that configuration and always honor response headers and `Retry-After`.

---

## 11. Transport security and deferred encryption

### 11.1 Network trust

- Use Android platform CA trust and hostname verification by default.
- Reject cleartext URLs, unexpected schemes, embedded credentials, and malformed hosts.
- Never forward `Authorization` or `X-API-Key` across host- or scheme-changing redirects.
- Keep credentials out of URLs, analytics, exception text, and request logs.
- Private CA or pin support is optional and deferred until an explicit host-scoped enrollment flow
  can show the fingerprint/CA, retain hostname verification, support reset/rotation/expiry, and
  recover from mistakes.
- Trust-all managers and hostname bypasses are prohibited.

Public-CA HTTPS is the Endurain v1 release baseline. Private-CA support is not faked by disabling
validation.

### 11.2 Encryption ADR gate

Database encryption is not bundled with legacy ETL and is not claimed by core v1. Before any
encryption implementation, `C-04` must record an ADR covering:

- exact stable Tink/DataStore serializer approach or an explicit AEAD serializer;
- Android Keystore key generation, alias lifecycle, associated data, and keyset storage;
- credential-file migration and Keystore-loss behavior;
- exact `net.zetetic:sqlcipher-android` artifact and Room `SupportOpenHelperFactory`;
- native library loading, supported ABIs, and 16-KB page-size validation;
- passphrase generation/wrapping;
- plaintext-to-encrypted copy or `sqlcipher_export()` order (`PRAGMA rekey` is not plaintext
  conversion);
- backup inclusion/exclusion, restore, recovery, and destructive-loss policy.

Credential protection required by Endurain is implemented as a separate, reversible change after
this ADR. Full Room/database encryption remains deferred until ETL, backup, and restore have each
passed a release cycle.

---

## 12. Verification gates

### 12.1 Evidence levels

| Gate | Can prove | Cannot prove |
|---|---|---|
| JVM/unit | protobuf compatibility, hashes, revision/authority rules, deterministic IDs, ETL transforms, connector state machines | Android lifecycle, OEM sensors, real transport |
| Phone/Wear emulators | compile/install, Room transactions, process death, permission denial UI, paired-emulator happy path where supported | real sensor quality, off-body behavior, OEM/vendor APIs, battery |
| Physical non-Samsung watch + phone | screen-off FGS, Data Layer disconnect/reconnect, HR/location availability, API 35/36 behavior | Samsung-only measurements |
| Physical supported Samsung watch | vendor tracker/device behavior | production entitlement without Samsung approval |
| Health Connect test devices | provider/framework availability, consent, partial permissions, route-safe update/delete | Play Console approval |
| Disposable Endurain `v0.19.0` | exact upload/list/ID/rate-limit/unknown-response/duplicate behavior | arbitrary future server versions |

### 12.2 First vertical-slice acceptance

The milestone passes only when:

1. Both APKs build from a clean checkout with the recorded tuple.
2. A completed activity survives watch process death before transfer.
3. Disconnect during transfer leaves the outbox uncommitted.
4. Phone process death before commit does not ACK.
5. Phone transaction commit followed by lost ACK causes a harmless duplicate resend.
6. Hash/length mismatch is rejected without deleting watch data.
7. A higher session revision updates watch-owned fields; a stale revision does not.
8. Phone-owned metadata survives watch resend.
9. The phone displays the persisted session after app restart.
10. A phone deletion and its tombstone outbox survive phone process death before reverse delivery.
11. A reverse-transfer disconnect leaves the phone tombstone outbox uncommitted.
12. Watch commit followed by a lost tombstone ACK causes a harmless duplicate resend and repeated
    matching ACK.
13. A covered activity resend receives `TOMBSTONED` rejection/ACK and cannot resurrect the phone
    session.
14. Equal-revision phone/watch tombstones delivered in opposite orders converge on the same winning
    order key.
15. The physical-device gate repeats record, screen-off, disconnect, reconnect, persist, ACK, and
    display before release promotion.

### 12.3 External release gates

- **Physical devices:** at least one non-Samsung Wear device and phone for active workout,
  screen-off, off-body, GNSS-disabled, approximate-location, permission-revocation, and
  disconnect/reconnect tests.
- **Samsung:** supported Watch4+ hardware as applicable, exact SDK tests, partner approval, and
  registered package/release signature. This gates only optional vendor extensions.
- **Health Connect:** Play Health Apps declaration, minimum-permission justification, Data Safety,
  privacy policy, and rationale activity.
- **Endurain:** disposable canonical `v0.19.0` instance for contract tests.
- **Battery:** a named-device/configuration baseline must be recorded before setting a numeric
  regression threshold; the rejected unqualified `<2%/hour` claim is removed.

---

## 13. Work plan — 30 PR-sized items

Every implementation PR includes focused tests. Mouse is the accountable primary for integration,
device, migration-quality, and release gates. Fact Checker re-verifies empirical contracts before
the dependent implementation merges.

### 13.1 Foundation — sequential

| ID | Primary | Required partners | Dependencies | Exclusive file scope | Acceptance |
|---|---|---|---|---|---|
| F-01 Baseline and copied fixtures | Mouse | Tank, Trinity | none | `docs/build-evidence/baseline.md`, `wear/src/androidTest/assets/legacy-db-fixtures/**` | current build result recorded; sanitized empty/partial/large/precision/interrupted fixtures and checksums exist |
| F-02 API-36 tuple spike | Tank | Fact Checker, Mouse | F-01 | `docs/build-evidence/foundation-tuple.md` only in merged result | §3.2 report complete; exact coherent tuple independently verified |
| F-03 Atomic Groovy build recovery | Tank | Fact Checker, Mouse | F-02 | root `build.gradle`, `settings.gradle`, `gradle/wrapper/**`, three module `build.gradle` files | both legacy-UI APKs, tests, and lint pass with exact tuple; no Kotlin DSL/catalog/convention plugin |
| F-04 AndroidX migration | Tank | Mouse | F-03 | support-library imports, manifests/test runners, AndroidX dependencies | no `android.support.*`; clean phone/Wear builds and legacy flow smoke test |
| F-05 Stable SDK and target policy | Tank | Fact Checker, Mouse | F-04 | three module `build.gradle` files and required AGP namespace/config only | compile 36; phone target 36; recorded Wear target-35 decision plus target-36 build profile |
| F-06 Wear FGS/permission baseline | Trinity | Tank, Rai, Mouse, Fact Checker | F-05 | Wear manifest, `SportLoggerService`, permission/manifest tests | §5.3 matrix compiles; active recorder survives screen-off in emulator and is ready for physical gate |
| F-07 Canonical protobuf/Room seam | Tank | Neo, Trinity, Mouse, Fact Checker | F-06 | Groovy dependency additions, `utilities/src/main/proto/**`, generated-contract adapters/tests and binary fixtures | exact protobuf-lite versions; core-v1 contract and old/new/old tests pass; no new Gradle module |

`F-01` is the only immediate start. `F-02` begins after `F-01` acceptance, and every later
foundation item follows its listed predecessor.

After `F-07`, `V-01`, `V-02`, and `V-03` have non-overlapping scopes and may start in parallel.

### 13.2 Milestone 1 — runnable vertical slice

| ID | Primary | Required partners | Dependencies | Exclusive file scope | Acceptance |
|---|---|---|---|---|---|
| V-01 Legacy recorder adapter | Trinity | Tank, Neo, Mouse | F-07 | `wear/src/main/java/com/long2know/sportlogger/contract/**` and matching tests | existing `SportActivity` maps to core-v1 without precision/presence loss |
| V-02 Watch database and outbox | Trinity | Tank, Mouse | F-07 | `wear/src/main/java/com/long2know/sportlogger/persistence/**`, `wear/schemas/**`, matching tests | `sportlogger_watch_v1.db`; atomic session/outbox write; process-death recovery |
| V-03 Phone database and inbox | Neo | Tank, Mouse | F-07 | `mobile/src/main/java/com/long2know/sportlogger/persistence/**`, `mobile/schemas/**`, matching tests | `sportlogger_phone_v1.db`; atomic session/inbox receipt; revision-aware upsert |
| V-04 Completion enqueue hook | Trinity | Neo, Tank | V-01, V-02 | `wear/src/main/java/com/long2know/sportlogger/MainActivity.java` completion method only | stopping current recorder persists canonical payload/outbox before any send |
| V-05 Durable Data Layer transfer | Neo | Trinity, Mouse, Fact Checker | V-02, V-03, V-04 | `wear/src/main/java/com/long2know/sportlogger/sync/**`, `mobile/src/main/java/com/long2know/sportlogger/sync/**`, `mobile/src/main/java/com/long2know/sportlogger/ListenerService.java`; tombstone-only entities/DAOs and schema exports under both persistence packages and `wear/schemas/**` / `mobile/schemas/**`; matching tests | §7 activity and reverse tombstone state machines; tests cover process death, reverse disconnect, matching ACK, lost-ACK duplicates in both directions, covered-activity rejection/ACK, and deterministic cross-origin ordering |
| V-06 Minimal persisted phone display | Switch | Neo, Mouse | V-03 | `mobile/src/main/java/com/long2know/sportlogger/MainActivity.java`, `ActivityListAdapter.java`, `mobile/src/main/res/layout/activity_main.xml`, related strings | app restart displays stored start/end, duration, distance, point count, and transfer state |
| V-07 Vertical-slice integration gate | Mouse | Tank, Trinity, Neo, Switch, Fact Checker | V-05, V-06 | focused test suites and `docs/build-evidence/vertical-slice.md` | all §12.2 emulator tests pass; physical steps/results are recorded or explicitly pending as release gate |

### 13.3 Data preservation and acquisition

| ID | Primary | Required partners | Dependencies | Exclusive file scope | Acceptance |
|---|---|---|---|---|---|
| D-01 On-watch legacy ETL | Tank | Trinity, Mouse | V-02, V-07, F-01 | `wear/src/main/java/com/long2know/sportlogger/migration/**`; legacy readers remain untouched | separately named DB, deterministic IDs, typed SQL, idempotent receipt, no Room auto-migration claim |
| D-02 Migration/rollback quality gate | Mouse | Tank, Trinity | D-01 | migration instrumented tests and evidence | all fixture classes pass counts, orphans, timestamps, precision, per-activity counts, checksums, interruption, low-storage/rollback policy |
| D-03 Health Services capability contract | Trinity | Tank, Fact Checker, Mouse | V-07, F-06 | `wear/src/main/java/com/long2know/sportlogger/acquisition/capability/**` | every identifier compiles; units/conversions and separate capability/permission/raw/vendor/live dimensions tested |
| D-04 Health Services adapter and raw boundary | Trinity | Tank, Mouse | D-03 | `wear/src/main/java/com/long2know/sportlogger/acquisition/healthservices/**`, `services/SensorListener.java`, `services/GpsListener.java` | verified Health Services metrics replace only matching acquisition paths; pressure/raw/vendor paths remain explicit |
| D-05 API 35/36 physical acquisition gate | Mouse | Trinity, Rai, Fact Checker | D-04 | device tests and `docs/build-evidence/wear-device-matrix.md` | denial, do-not-ask, screen-off, off-body, location disabled/approximate, pause/resume, revocation recorded; Samsung rows remain external if unavailable |

### 13.4 Connectors and security

| ID | Primary | Required partners | Dependencies | Exclusive file scope | Acceptance |
|---|---|---|---|---|---|
| C-01 One Connector port and manual composition | Neo | Tank, Mouse | V-07 | `utilities/src/main/java/com/long2know/utilities/connector/**`, `mobile/src/main/java/com/long2know/sportlogger/composition/**` | one port; no `iconRes`, map config, duplicate interface, Hilt, or dynamic-plugin claim |
| C-02 Health Connect implementation | Neo | Mouse, Rai, Fact Checker | C-01, V-03 | `mobile/src/main/java/com/long2know/sportlogger/connector/healthconnect/**`, its ledger schema, phone manifest/dependency changes | §8 availability, records, permissions, partial grants, deterministic IDs/versions, route-safe update, explicit child deletion |
| C-03 Health Connect contract gate | Mouse | Neo, Fact Checker | C-02 | Health Connect tests and evidence | duplicate insert, higher version, partial grants, provider states, history/background gates, route consent, and child deletion pass |
| C-04 Trust/encryption ADR | Tank | Rai, Neo, Mouse, Fact Checker | V-07 | Squad decision state and focused evidence only | §11 decisions recorded; no implementation claims platform trust or DataStore/Keystore database encryption incorrectly |
| C-05 Credential and HTTPS foundation | Neo | Rai, Mouse | C-04 | `mobile/src/main/java/com/long2know/sportlogger/security/**`, `mobile/src/main/java/com/long2know/sportlogger/network/**` | Keystore-backed AEAD credential path per ADR; URL normalization; platform hostname/CA verification; safe redirect/logging rules |
| C-06 Endurain upload-only TCX | Neo | Tank, Rai, Mouse | V-03, C-01, C-05 | `mobile/src/main/java/com/long2know/sportlogger/connector/endurain/**`, bounded adapter code under `utilities/src/main/java/com/long2know/utilities/tcxzpot/**` | deterministic TCX from the persisted canonical phone activity; exact endpoint/header/list response/integer IDs; upload outbox; `UNKNOWN_DELIVERY`; no JWT edit/delete |
| C-07 Endurain real-instance gate | Mouse | Neo, Rai, Fact Checker | C-06 | mock/contract tests and `docs/build-evidence/endurain-v0.19.0.md` | disposable-instance TCX, wrong scope, invalid file, 429, outage, dropped response, and hidden duplicate behavior recorded |

`V-03` is C-06's persisted-activity prerequisite. The bounded deterministic TCX adapter is part of
C-06 itself, so no separate TCX item or Health Connect gate is a dependency.

### 13.5 Later independent modernization

| ID | Primary | Required partners | Dependencies | Exclusive file scope | Acceptance |
|---|---|---|---|---|---|
| M-01 Kotlin language enablement | Tank | Fact Checker, Mouse | D-05, C-07 | Groovy build files plus one bounded seam | exact tested Kotlin path; both APKs green; no DSL conversion or mass Java rewrite |
| M-02 Compose enablement for one screen | Switch | Trinity, Tank, Mouse | M-01 | one selected phone or Wear screen and that app's module build file | legacy navigation remains runnable; the replaced screen has UI/accessibility tests; later screens receive separate items |
| M-03 Hilt/DI adoption, if justified | Tank | Neo, Switch, Mouse | M-02 and documented manual-composition pain | app composition-root files and exact bindings only | app modules still include implementations; no dynamic discovery; both APKs green |
| M-04 Module extraction gate | Tank | affected domain owner, Mouse, Fact Checker | M-03 and measured trigger | one proposed package/module boundary only | dependency/build-time/ownership/reuse evidence names the boundary; one extraction per PR or no extraction |

Version catalog adoption, Groovy-to-Kotlin-DSL conversion, and convention plugins are not scheduled
requirements. If later approved, they occur as three separate green PRs in that order and never in a
module-extraction PR.

---

## 14. Disposition of the prior 26 work items

| Prior item | Cycle 2 disposition and routing-correct owner |
|---|---|
| W0-01 | F-02/F-03, Tank; Fact Checker verifies |
| W0-02 | F-04, Tank |
| W0-03 | F-07, Tank; no new `core-model` module |
| W0-04 | Removed; no speculative `core-common` module |
| W0-05 | C-01, Neo with Tank |
| W1-01 | Split into V-02 Trinity and V-03 Neo |
| W1-02 | D-01 Tank; D-02 Mouse |
| W1-03 | Sync state belongs in V-02/V-03; general preferences are deferred |
| W1-04 | Replaced by M-01, Tank; no pre-migration model move |
| W1-05 | D-03/D-04, Trinity with Tank |
| W1-06 | D-04, Trinity with Tank |
| W2-01 | V-05, Neo with Trinity |
| W2-02 | C-02 Neo; C-03 Mouse |
| W2-03 | C-05/C-06 Neo; C-07 Mouse; Rai partners |
| W2-04 | TCX adapter portion of C-06, Neo with Tank |
| W2-05 | V-05/C-01, Neo with Tank |
| W3-01 | M-02, Switch with Trinity |
| W3-02 | M-02, Switch with Trinity |
| W3-03 | V-06 then M-02, Switch |
| W3-04 | M-02, Switch with Trinity |
| W3-05 | Deferred settings slices route to Switch with Neo/Rai |
| W4-01 | Early composition is explicit in both apps; UI wiring is V-06/M-02, Switch; DI is M-03, Tank |
| W4-02 | M-02, Switch |
| W4-03 | V-05, Neo with Trinity |
| W4-04 | V-07/D-02/D-05/C-03/C-07, Mouse as accountable primary |
| W4-05 | Research prerequisite is complete; F-02/F-07/D-03/C-02/C-07 retain explicit Fact Checker gates |

---

## 15. ADR amendments

The immutable decision ledger is not rewritten. Cycle 3 preserves the prior amendments and records
its correction through Squad state:

| Decision | Cycle 3 status |
|---|---|
| ADR-001 | **Amended:** Health Services is primary for supported exercise/fused metrics, not exclusive; raw and vendor paths remain behind the acquisition boundary |
| ADR-002 | **Replaced:** Google protobuf lite with exact spike-selected versions, reserved field numbers/names, retained binary messages, and old/new/old tests |
| ADR-003 | **Amended:** Room backs separately named new databases; legacy conversion is explicit on-watch ETL, never Room auto-migration |
| ADR-004 | **New:** three-module strangler and app composition roots precede extraction |
| ADR-005 | **Amended:** activity and tombstone transfers use durable outbox/receipt state, matching commit ACKs, duplicate-safe retries, covered-revision rejection, and deterministic cross-origin delete ordering; Data Layer cloud behavior is disclosed |
| ADR-006 | **New:** one Connector port; Endurain v1 is API-key upload-only TCX |
| ADR-007 | **New:** platform TLS/hostname verification is default; custom trust is host-scoped; encryption requires a later reversible ADR/migration |

---

## 16. Primary evidence

### Toolchain and release policy

- [AGP Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)
- [AGP 9.3 compatibility](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
- [Gradle current-version endpoint](https://services.gradle.org/versions/current)
- [Kotlin Gradle/AGP compatibility](https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin)
- [AGP built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Android 17/API 37 preview setup](https://developer.android.com/about/versions/17/setup-sdk)
- [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping)
- [Wear Compose releases](https://developer.android.com/jetpack/androidx/releases/wear-compose)
- [Health Services releases](https://developer.android.com/jetpack/androidx/releases/health)
- [Health Connect releases](https://developer.android.com/jetpack/androidx/releases/health-connect)

### Wear measurements and permissions

- [Health Services `DataType`](https://developer.android.com/reference/kotlin/androidx/health/services/client/data/DataType)
- [Health Services permissions](https://developer.android.com/health-and-fitness/health-services/permissions)
- [Health Services stable 1.0.0 source](https://dl.google.com/dl/android/maven2/androidx/health/health-services-client/1.0.0/health-services-client-1.0.0-sources.jar)
- [Health Services 1.1.0-rc02 source](https://dl.google.com/dl/android/maven2/androidx/health/health-services-client/1.1.0-rc02/health-services-client-1.1.0-rc02-sources.jar)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android foreground-service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Samsung Sensor SDK overview](https://developer.samsung.com/health/sensor/overview.html)
- [Samsung Sensor SDK permissions](https://developer.samsung.com/health/sensor/guide/permission-request.html)
- [Samsung distribution process](https://developer.samsung.com/health/sensor/process.html)
- [Samsung developer mode](https://developer.samsung.com/health/sensor/guide/developer-mode.html)

### Persistence, transfer, and protobuf

- [Migrate an existing SQLite app to Room](https://developer.android.com/training/data-storage/room/sqlite-room-migration)
- [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- [Wear Data Layer overview and cloud behavior](https://developer.android.com/training/wearables/data/overview)
- [Data Items](https://developer.android.com/training/wearables/data/data-items)
- [MessageClient](https://developers.google.com/android/reference/com/google/android/gms/wearable/MessageClient)
- [ChannelClient](https://developers.google.com/android/reference/com/google/android/gms/wearable/ChannelClient)
- [Proto3 unknown fields](https://protobuf.dev/programming-guides/proto3/#unknowns)
- [Google protobuf Kotlin generation](https://protobuf.dev/reference/kotlin/kotlin-generated/)

### Health Connect

- [Health Connect availability](https://developer.android.com/health-and-fitness/health-connect/availability)
- [Write data and metadata](https://developer.android.com/health-and-fitness/health-connect/write-data)
- [Exercise routes](https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes)
- [Read data and history](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [Delete data](https://developer.android.com/health-and-fitness/health-connect/delete-data)
- [Stable 1.1.0 client source](https://dl.google.com/dl/android/maven2/androidx/health/connect/connect-client/1.1.0/connect-client-1.1.0-sources.jar)

### Endurain and security

- [Endurain canonical `v0.19.0`](https://codeberg.org/endurain-project/endurain/releases/tag/v0.19.0)
- [Endurain upload routes](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/activities/activity/router.py)
- [Endurain API-key behavior](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/auth/api_keys/utils.py)
- [Endurain duplicate behavior](https://codeberg.org/endurain-project/endurain/src/tag/v0.19.0/backend/app/activities/activity/crud.py)
- [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)
- [Unsafe TrustManager guidance](https://developer.android.com/privacy-and-security/risks/unsafe-trustmanager)
- [Unsafe hostname verification guidance](https://developer.android.com/privacy-and-security/risks/unsafe-hostname)
- [DataStore releases and `datastore-tink`](https://developer.android.com/jetpack/androidx/releases/datastore)
- [SQLCipher for Android](https://www.zetetic.net/sqlcipher/sqlcipher-for-android/)
- [SQLCipher export/rekey behavior](https://www.zetetic.net/sqlcipher/sqlcipher-api/#rekey)

### Reference-project limits

- [OpenTracks](https://codeberg.org/OpenTracksApp/OpenTracks) — Apache-2.0 phone tracker, no
  first-party Wear/Data Layer app.
- [SportsTracker](https://codeberg.org/windkracht8/SportsTracker) — active young GPL project using
  custom RFCOMM, pattern study only.
- [Android health samples](https://github.com/android/health-samples) — current first-party Health
  Services and Health Connect samples.
- [Wear OS samples](https://github.com/android/wear-os-samples) — focused Data Layer/UI samples, not
  a full tracker.
- [Horologist](https://github.com/google/horologist) — optional changing library, not a required
  foundation dependency.
- [Garmin FIT SDK license](https://github.com/garmin/fit-java-sdk/blob/main/LICENSE.txt) — custom
  license; TCX avoids this v1 legal gate.

---

## 17. Fact Checker resolution map

### 17.1 Fourteen blocking groups

| Blocker | Resolution |
|---|---|
| B-01 toolchain | §3 and F-02/F-05: exact tested API-36 tuple, atomic build change, phone/Wear target split |
| B-02 identifiers/units | §5.1: stable-1.0 core names and exact types/units; no RC-only lap-total, Health Services HRV, or SpO2 |
| B-03 permissions/FGS | §5.3 and F-06/D-05: target-31+ BLE permissions apply to Wear targets 35/36; Samsung uses `BODY_SENSORS` through target 35 and named vendor constants at target 36 |
| B-04 Health Connect records/permissions | §8.2: correct records, route/speed/elevation permissions, collection/publication split |
| B-05 Health Connect identity/lifecycle | §8.3 and C-02/C-03: per-record IDs/versions, active metadata, route-safe update, explicit children |
| B-06 SQLite/Room | §6 and D-01/D-02: on-watch idempotent ETL into a separately named database |
| B-07 Data Layer | §7 and V-02/V-05: durable activity and reverse tombstone state, transactional receipts, matching ACKs, lost-ACK safety, covered-resend rejection, deterministic delete ordering, and cloud disclosure |
| B-08 protobuf contradiction | §4 and F-07: Google protobuf lite, exact versions from spike, reservations, binary old/new/old tests |
| B-09 Endurain | §10 and C-06/C-07: exact upload endpoint, API key, integer IDs, unknown delivery, duplicates, JWT deferred |
| B-10 security/encryption | §11 and C-04/C-05: platform trust, host-scoped custom trust, concrete later encryption ADR |
| B-11 Samsung entitlement | §5.4 and external gates: vendor metrics outside core v1 |
| B-12 dependency graph/Connector | §2 and §9: app composition roots, one port, implementations on mobile runtime classpath |
| B-13 ownership/dependencies | §13-§14 and §18: routing-correct primaries/partners, F-01-before-F-02 start order, V-05 tombstone scope, and Endurain dependencies limited to persisted activity, connector, and security prerequisites |
| B-14 big bang | §0-§3 and Milestone 1: three-module Groovy strangler with runnable vertical value first |

### 17.2 Eighteen original Cycle 2 corrections

| Correction | Resolution |
|---|---|
| T-01 exact API-36 tuple/targets | §3.1-§3.2, F-02/F-05 |
| T-02 atomic AGP/Gradle; separate build changes | §3.3, F-03 through F-07, M-01 through M-04 |
| T-03 released data types/units/conversions | §5.1 stable core table excludes the RC-only lap-total identifier |
| T-04 full permission/FGS/BLE/vendor matrix | §5.3 applies BLE permissions at target 31+ and names both Samsung permission regimes |
| T-05 vendor measurements outside core | §5.4 |
| T-06 Health Connect records/permissions | §8.2 |
| T-07 Health Connect availability/IDs/versions/delete | §8.1-§8.3 |
| T-08 explicit on-watch ETL/copied fixtures | §6, F-01, D-01/D-02 |
| T-09 Data Layer state machine/cloud/ACK/authority/tombstones | §7.5 defines durable reverse outbox/receipt/ACK, rejection of covered resends, and global delete ordering |
| T-10 Google protobuf lite/old-new-old | §4, F-07 |
| T-11 exact Endurain behavior | §10 |
| T-12 platform trust and host-scoped custom trust | §11.1 |
| T-13 concrete encryption ADR | §11.2, C-04; implementation remains a separate migration |
| T-14 dependency graph/one Connector/composition roots | §2, §9 |
| T-15 reduced scope and vertical milestone | §0, §2, §13.2 |
| T-16 routing-correct ownership and accountable tests | §13-§14 |
| T-17 repaired dependencies/scopes/counts | §13 and §18; 30 items, F-01-before-F-02 sequencing, explicit V-05 reverse tombstone scope/tests, and no C-06 dependency on C-03 |
| T-18 source links, version evidence, fixtures, rerunnable tests | §3.2, §12, §16, F-01/F-02/V-07/D-02/D-05/C-03/C-07 |

---

## 18. Final gate statement

The Cycle 1 23-module/big-bang design is withdrawn. This Cycle 3 roadmap approves an incremental
three-module strangler with one canonical protobuf seam and one runnable end-to-end milestone.

`F-01` is the only item that may start immediately. `F-02` starts only after `F-01` is accepted, and
`F-03` cannot merge until Fact Checker accepts the exact spike tuple. Physical-device, Samsung,
Health Connect publication, and Endurain-instance gates block release of their affected
capabilities, not local compilation or unrelated vertical-slice work. Neo owns this replacement
revision, which is ready for independent Cycle 3 re-verification before PR #13 leaves draft.
