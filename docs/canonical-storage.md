# Canonical activity contract and storage

## Selected Java-first stack

The first canonical seam keeps the existing Groovy build and three modules:

| Component | Version and role |
| --- | --- |
| Protobuf Gradle plugin | `0.10.0`, with explicit Android Java `lite` generation |
| `protoc` | `4.36.2` |
| Runtime | `protobuf-javalite:4.36.2`; no full protobuf or ProtoJSON runtime |
| Room | plugin/runtime/compiler/testing `2.8.5` |
| Annotation processing | Java `annotationProcessor`; no KSP, KAPT, or Kotlin source |
| Host Android proof | Robolectric `4.17`, Android API 26 configuration |

The public wire schema is
`utilities/src/main/proto/sportlogger/core/v1/activity.proto`. Generated Java types use
`com.long2know.utilities.contract.proto`. `CanonicalPayload` retains the exact input bytes for
hashing/storage and separately exposes protobuf binary reserialization so unknown fields survive.
JSON is not a wire or forwarding format.

Checked-in binary fixtures prove:

- old writer to current reader;
- current writer using old fields to old reader;
- future unknown field through current parse/reserialize to future reader;
- optional presence, including valid latitude/longitude `0,0`;
- reserved field-number/name discipline.

## Core-v1 semantics

`ActivityRecord` is an immutable completed revision identified by a canonical UUID and monotonically
increasing watch revision. It contains:

- sport/activity type, UTC start/end, elapsed and optional active duration;
- optional time-zone ID and UTC offset at start;
- optional summaries for distance, calories, steps, elevation, floors, and heart rate;
- optional laps and active intervals;
- ordered measurement samples;
- source/tracker metadata and legacy identity;
- recorded legacy name/description, separate from phone-owned title/notes/tags.

Laps keep calories, intensity, and trigger method explicitly optional. A strict TCX writer must
report an unsupported/incomplete export when required TCX values were never recorded; it must not
invent zero calories, active intensity, or a manual trigger.

Each measurement has a bounded core-v1 identifier/unit/aggregation from
`CanonicalMeasurements.Metric`, plus separate:

- exercise capability;
- runtime permission;
- raw-hardware capability;
- vendor capability/entitlement;
- live availability, including acquiring, off-body, no-GNSS, and permission unavailable;
- provenance and tracker metadata;
- quality and optional platform accuracy.

Samples retain wall-clock time, optional monotonic time and boot ID, optional session elapsed/active
duration, and explicit delta versus cumulative fields. Fractional floors remain `double`. Message
presence—not numeric zero—represents availability. Position message presence preserves valid
`0,0`; altitude, accuracy, speed, bearing, and heart rate have independent presence.
Swimming lap delta and `SWIMMING_LAP_COUNT_TOTAL` cumulative values are distinct fields/metrics.

## Stable identifiers

`CanonicalIds` provides:

- random UUIDs for newly recorded session and transfer identities;
- RFC 4122 version-5 deterministic session IDs from legacy source identity plus activity ID;
- RFC 4122 version-5 deterministic transfer IDs from source identity, activity ID, and revision.

The watch integration must generate and durably journal a session UUID once before calling the
canonical start API. The current legacy integer activity ID remains a separate migration identity.

## Watch database and writer API

`sportlogger_watch_v1.db` is a separately named Room database. Schema version 2 adds durable laps
through an explicit validated v1→v2 migration; it does not claim to migrate or replace
`GPSLOGGERDB_LONG2KNOW`. Its schema stores:

- active/completed session state, lease, boot/monotonic anchors, accumulated active duration, and
  exact canonical payload;
- exact per-sample protobuf bytes;
- ordered open/closed lap boundaries with UUID, wall, elapsed, and active timing;
- immutable activity outbox files and retry state;
- legacy migration receipts and typed quarantine rows;
- tombstones, tombstone outbox, and exact tombstone receipts.

The compiled integration boundary is `WatchActivityRepository`:

```text
startSession(StartRequest)
writeSample(sessionId, leaseToken, MeasurementSample)
transition(sessionId, leaseToken, lifecycle fields...)
recordManualLap(sessionId, leaseToken, wall, elapsed, active)
closeFinalLap(sessionId, leaseToken, wall, elapsed, active)
laps(sessionId) / currentLap(sessionId)
reserveCompletion(sessionId, leaseToken, revision, transferId, updatedAt)
samples(sessionId)
finishSessionFromPersistedSamples(sessionId, leaseToken, finalMetadata, completedAt)
finishSession(sessionId, leaseToken, ActivityRecord, transferId, completedAt)
discardSession(sessionId, leaseToken, TombstoneRecord, createdAt)
readyOutbox(limit)
outbox(transferId) / pendingOutbox(limit) / reclaimPendingOutbox(limit, now)
transitionOutbox(transferId, expectedState, nextState, attempt/failure fields...)
acceptCommitAck(exactAckBytes, committedAt)
pendingTombstoneOutbox / transitionTombstoneOutbox / acceptTombstoneCommitAck
latestCompleted() / recentCompleted(limit)
observeChanges(Executor, Runnable)
```

Writes return `CompletableFuture` on the caller-supplied executor. Session/sample/lifecycle writes
are fenced by the persisted session UUID and non-zero recording lease. A stale callback cannot
append or transition. `finishSession` writes the completed revision, final samples, and immutable
outbox bytes in one Room transaction. `discardSession` writes the tombstone/outbox before ending
ownership. Process-start recovery reclaims interrupted `SENDING` rows and returns
`READY`/`RETRYABLE`/`AWAITING_COMMIT_ACK` rows for exact-byte resend. Only a protobuf ACK whose
transfer/session/revision/hash matches the immutable outbox can mark it `COMMITTED`; duplicate exact
ACKs are harmless. Tombstone outbox and ACK handling use the same persisted semantics.

The preferred STOP sequence durably calls `reserveCompletion`, appends the final platform sample,
then calls `finishSessionFromPersistedSamples`. The repository rebuilds the final activity from
ordered exact sample rows; it does not depend on an in-memory list. A replacement process can read
the reserved transfer UUID/revision from `SessionSnapshot`, call `samples`, and recover the same
outbox through `outboxForSessionRevision`.

`startSession` is replay-safe across the insert/observer crash window. Repeating the same session
UUID, lease, and immutable start metadata returns the existing current snapshot; any conflicting
replay is rejected. A supported, non-unspecified `ActivityType` is required and is returned from
`SessionSnapshot` after recovery.

The repository creates the first open lap atomically with session start. A manual lap is accepted
only while recording, closes the current lap, and opens the next in one transaction. Repeating the
same boundary returns the exact existing lap UUIDs. Pause never creates a lap. STOP closes the final
partial lap idempotently before canonical completion; `finishSessionFromPersistedSamples` also
enforces that close and injects every persisted closed lap into `ActivityRecord`. DISCARD removes
all open/completed lap rows in the tombstone transaction. `SessionSnapshot` exposes current lap
index plus last completed elapsed/active duration for service callbacks after rebind.

`latestCompleted` and `recentCompleted` return the exact payload-backed completed `ActivityRecord`
joined to its matching outbox across READY, SENDING, AWAITING_COMMIT_ACK, RETRYABLE, and COMMITTED.
The existing Room invalidation observer now covers sessions, laps, and outboxes so UI integration
does not need DAO access or polling.

Trinity owns service/controller actions, valid-state command results, selected activity type
journaling, and callback/snapshot mapping. Switch consumes only those service callbacks and the
read-only completed presentation API; UI does not own clocks, lap counters, or direct Room access.
The compiled service boundary is `SportLoggerService.recordLap()`, which returns a
`CompletableFuture<ManualLapResult>` with explicit accepted, not-recording, busy,
legacy-authority, or failed status. `RecordingSnapshot` authoritatively exposes current lap index
and last completed elapsed/active lap duration after rebind. Completed presentation uses
`onLatestCompletedChanged(CompletedRecordingSnapshot)` plus asynchronous
`recentCompleted(limit)`; each result contains the immutable `ActivityRecord` and matching outbox
identity/state, including COMMITTED.

## Phone database and read/receipt API

`sportlogger_phone_v1.db` is also a new Room version-1 database. It stores:

- immutable activity revisions and ordered sample payloads;
- the accepted revision head and transfer/import health state;
- phone-owned metadata in a separate table;
- exact inbox receipt and ACK bytes;
- tombstones and reverse outbox;
- connector delivery ledgers and append-only scoped remote IDs;
- legacy serialized-inbox backfill receipts.

`PhoneActivityRepository.acceptActivityTransfer` verifies the transfer UUID, session/revision,
declared byte length, SHA-256, protobuf parse, and tombstone coverage. One transaction inserts a
higher immutable revision, updates the accepted head without overwriting phone metadata, and stores
the exact ACK receipt. Duplicate transfer IDs with the same bytes return the stored ACK; conflicting
bytes fail. Lower revisions are acknowledged as stale. Covered revisions are acknowledged as
tombstoned and cannot resurrect a session.

The phone consumer API is:

```text
summaries(offset, limit)
summaryCount()
detail(sessionId, acceptedRevision)
pointPage(sessionId, revision, afterSequence, limit, CancellationSignal)
metadata(sessionId) / setMetadata(...)
observeChanges(Executor, Runnable)
deleteLocal(sessionId, expectedAcceptedRevision, deletedAt)
```

Summaries are paged and sorted without materializing point graphs. Details are immutable
session/revision snapshots. Point queries are cancellable, copy ordered protobuf samples into an
immutable page, and close the SQLite cursor before returning.

The three-argument `deleteLocal` is the only UI deletion boundary. In one Room transaction it:

- compares the caller's expected accepted revision with the current head;
- covers the actual accepted head revision;
- allocates one delete revision above the unsigned maximum of that head and the winning tombstone;
- generates one transfer UUID only for a newly committed operation;
- persists the exact phone-origin tombstone, outbox, and deleted head; and
- returns `LocalDeleteResult` containing those exact persisted values.

If completion was ambiguous, replay with the same expected head returns the existing phone-origin
tombstone/outbox with `isCreated=false`; it does not allocate another revision or UUID. A newer head
fails with `DELETE_HEAD_CHANGED`, unsigned overflow fails with `DELETE_REVISION_EXHAUSTED`, and a
watch-origin deletion is reported as already deleted rather than misclassified as local replay.
The deprecated five-argument method remains only for protocol fixtures and validates head coverage
and delete ordering.

Production repository and backfill calls are synchronous Room/file I/O and belong on a background
executor. A repository opened with `PhoneActivityRepository.open(context)` may be retained by a
ViewModel and closed from `onCleared`. Phone metadata limits are 200 title characters, 10,000 notes
characters, 50 unique tags, and 64 characters per non-empty single-line tag.

Connector ledger identity is the complete tuple:

```text
(session UUID, revision, connector ID, normalized instance URL, connection identity)
```

The ledger records only a credential reference, plus deterministic artifact length/SHA-256 before
network I/O. Lease/CAS transitions cover pending, delivered, unknown delivery, duplicate review,
and definitive failure. Positive integer remote IDs are append-only and retained after local
tombstoning; delete-unsupported is explicit.

Neo owns transport, ACK delivery, connector networking, and scheduling. Switch owns replacing the
legacy phone adapter with these read methods after integration.

Phone-origin tombstones expose `tombstoneOutbox`, pending/reclaim/transition methods, and exact
commit-ACK acceptance. Both repositories also expose `acceptTombstoneTransfer`: incoming bytes are
validated, deterministically ordered by delete revision/origin/transfer UUID, written with an exact
receipt/ACK in one transaction, and applied only through their covered session revision. Activity
outboxes covered on the watch are suppressed; phone heads covered by a winning tombstone are hidden.
Exact duplicate transfers repeat the stored ACK. Issue #6 therefore transports tombstones in both
directions rather than implementing a watch-only delete path.

## Health Connect owned-record ledger

Health Connect record ownership is separate from the Endurain positive-integer remote-ID ledger.
`HealthConnectLedgerRepository` stores opaque provider record ID strings and a deterministic
descriptor:

```text
(session UUID, revision, record type, chunk index,
 deterministic client record ID, monotonic client record version)
```

Its API allocates monotonic versions, prepares only named records without deleting omitted
route/child data, records `PUBLISH`/`UPDATE`/`DELETE` operation state, persists a `BEFORE` receipt
before provider I/O, persists success/failure receipts after provider I/O, retains every opaque
provider record ID, and exposes deletion progress/receipts across all revisions. Changed chunk
counts therefore leave prior owned records discoverable for explicit deletion instead of orphaning
them. Partial delete failures remain `DELETE_PENDING` across process death.

This is only the canonical ledger contract. The Health Connect owner supplies record mapping,
permissions, provider calls, route-safe update policy, and retry scheduling.

## Read-only legacy ETL

`LegacyWatchMigrator` opens `GPSLOGGERDB_LONG2KNOW` with Android SQLite read-only flags and never
calls the legacy projection reader. It:

1. hashes the database and any existing journal/WAL sidecars;
2. validates the legacy table/column contract;
3. selects explicitly aliased columns with typed cursor access;
4. parses the verified 14-digit Android decimal blocks into strict Gregorian UTC;
5. preserves SQLite `REAL` precision and null presence;
6. generates deterministic UUIDs and revision-1 protobuf payloads;
7. imports each completed activity idempotently;
8. records malformed rows, orphan points, missing tables, and corrupt sources in quarantine;
9. verifies source hashes after closing the read-only database;
10. writes a receipt only after count/timestamp/checksum evidence is complete.

An interruption can leave idempotent session/outbox rows but no receipt. Rerunning uses the same
session and transfer IDs, verifies identical bytes, finishes remaining rows, and writes one receipt.
The source database is retained. No timed deletion, destructive Room fallback, or Room auto-migration
claim is used for the raw legacy conversion.

Checked-in sanitized SQLite fixtures cover empty, representative precision, malformed rows, missing
tables/incomplete activities, orphans, corrupt bytes, interruption retry, and 1,000 ordered points.
`fixture-manifest.properties` pins each database SHA-256.

`LegacyPhoneInboxBackfill` similarly scans existing app-private `.ser` receipts read-only, retains
the original bytes, generates deterministic canonical IDs, stores typed health receipts, and
preserves existing phone-owned metadata. Legacy zero-valued fields whose serialized format cannot
prove presence remain absent, except latitude/longitude where the legacy model always carried both
values and `0,0` must remain valid.

Neither migration auto-starts from the repository. After integration, Trinity owns one
background-dispatcher call to `LegacyWatchMigrator.migrateDefault(context, now)` from the watch
startup/recovery path. Switch owns one background-dispatcher call to
`LegacyPhoneInboxBackfill.backfillDefault(context, now)` from phone app startup before canonical
history is displayed. Both calls are idempotent and retain raw sources, receipts, and quarantine.

## Validation tiers and rollback

- Pure JVM: protobuf binary compatibility, IDs, validation, and reserved-source checks.
- Robolectric/API 26: actual Room schema creation, transactions, fencing, revisions, metadata,
  point paging, connector ledgers, SQLite fixture ETL, interruption retry, and serialized inbox
  backfill.
- Android instrumentation: phone and Wear version-1 database tests compile into their debug test
  APKs. `PhoneDatabaseInstrumentedTest` passed 1/1 on API 36; the Wear instrumentation test remains
  compile-only until its owner provides the Wear lane. No sensor, UI, or cross-device claim follows
  from either storage test.

Both legacy stores remain the rollback source. The canonical databases are separately named and may
be deleted to return to the legacy application path while this migration is staged. Automatic
legacy deletion is not approved.

Issue #5 intentionally keeps the merged Wear min SDK 26 baseline. The separately authorized
Health Services 1.1 / Wear OS 3 decision raises Wear to API 30 in issue #7, not in this storage PR.
