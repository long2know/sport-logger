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

## Stable identifiers

`CanonicalIds` provides:

- random UUIDs for newly recorded session and transfer identities;
- RFC 4122 version-5 deterministic session IDs from legacy source identity plus activity ID;
- RFC 4122 version-5 deterministic transfer IDs from source identity, activity ID, and revision.

The watch integration must generate and durably journal a session UUID once before calling the
canonical start API. The current legacy integer activity ID remains a separate migration identity.

## Watch database and writer API

`sportlogger_watch_v1.db` is a new Room version-1 database. It does not claim to migrate or replace
`GPSLOGGERDB_LONG2KNOW`. Its schema stores:

- active/completed session state, lease, boot/monotonic anchors, accumulated active duration, and
  exact canonical payload;
- exact per-sample protobuf bytes;
- immutable activity outbox files and retry state;
- legacy migration receipts and typed quarantine rows;
- tombstones, tombstone outbox, and exact tombstone receipts.

The compiled integration boundary is `WatchActivityRepository`:

```text
startSession(StartRequest)
writeSample(sessionId, leaseToken, MeasurementSample)
transition(sessionId, leaseToken, lifecycle fields...)
finishSession(sessionId, leaseToken, ActivityRecord, transferId, completedAt)
discardSession(sessionId, leaseToken, TombstoneRecord, createdAt)
readyOutbox(limit)
transitionOutbox(transferId, expectedState, nextState, attempt/failure fields...)
```

Writes return `CompletableFuture` on the caller-supplied executor. Session/sample/lifecycle writes
are fenced by the persisted session UUID and non-zero recording lease. A stale callback cannot
append or transition. `finishSession` writes the completed revision, final samples, and immutable
outbox bytes in one Room transaction. `discardSession` writes the tombstone/outbox before ending
ownership.

Trinity owns adapting the existing controller/journal to persist the UUID and call this API. This
change does not alter `PersistentRecording`, recording services, `SqlLogger`, or legacy models.

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
detail(sessionId, acceptedRevision)
pointPage(sessionId, revision, afterSequence, limit, CancellationSignal)
metadata(sessionId) / setMetadata(...)
observeChanges(Executor, Runnable)
```

Summaries are paged and sorted without materializing point graphs. Details are immutable
session/revision snapshots. Point queries are cancellable, copy ordered protobuf samples into an
immutable page, and close the SQLite cursor before returning.

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

## Validation tiers and rollback

- Pure JVM: protobuf binary compatibility, IDs, validation, and reserved-source checks.
- Robolectric/API 26: actual Room schema creation, transactions, fencing, revisions, metadata,
  point paging, connector ledgers, SQLite fixture ETL, interruption retry, and serialized inbox
  backfill.
- Android instrumentation: phone and Wear version-1 database tests compile into their debug test
  APKs. Device execution is a separate recorded gate and is not implied by host tests.

Both legacy stores remain the rollback source. The canonical databases are separately named and may
be deleted to return to the legacy application path while this migration is staged. Automatic
legacy deletion is not approved.

Issue #5 intentionally keeps the merged Wear min SDK 26 baseline. The separately authorized
Health Services 1.1 / Wear OS 3 decision raises Wear to API 30 in issue #7, not in this storage PR.
