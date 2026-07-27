# Legacy watch database contract

This document is the source-backed contract for extracting the legacy watch
database. It describes the database that exists today; it does not define a
Room schema or production ETL architecture. The committed corpus under
`tools/legacy-fixtures/` is synthetic and is the executable oracle for this
contract.

## Database identity, location, and lifecycle

- **Name:** `GPSLOGGERDB_LONG2KNOW`
  (`utilities/.../data_access/SqlLogger.java:27`).
- **Owner:** the Wear application, whose application ID is
  `com.long2know.sportlogger` (`wear/build.gradle:6`).
- **Location:** `Context.openOrCreateDatabase()` places the file in the Wear
  app's private databases directory. A typical device path is
  `/data/user/0/com.long2know.sportlogger/databases/GPSLOGGERDB_LONG2KNOW`;
  code must use `Context.getDatabasePath()` rather than hard-code that physical
  path.
- **Context:** the Wear `SportLoggerService` assigns `Config.context = this`
  before constructing the listeners (`wear/.../SportLoggerService.java:46-65`).
  The phone listener only deserializes and broadcasts an activity in memory; it
  does not persist this database (`mobile/.../ListenerService.java:42-76`).
- **Versioning:** there is no `SQLiteOpenHelper`, Room schema, upgrade callback,
  schema identity, or assigned `PRAGMA user_version`. Runtime SQLite therefore
  reports `user_version = 0`.
- **Android platform metadata:** each writable framework open registers localized
  collators, creates `android_metadata (locale TEXT)` when needed, and leaves one
  row containing the device locale. This table is platform-managed metadata, not
  legacy business data. The source opens normally (without
  `NO_LOCALIZED_COLLATORS`), so validation must require the exact table shape and
  exactly one non-empty, locale-shaped TEXT row while excluding it
  from activity/point counts and logical data checksums. Missing, empty,
  non-TEXT, invalid-value, and multi-row metadata states are malformed and never
  migration-ready. See AOSP
  Android 9
  [`SQLiteConnection.setLocaleFromConfiguration()`](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r46/core/java/android/database/sqlite/SQLiteConnection.java#403).
- **Initialization:** `initDatabase()` issues two independent `CREATE TABLE IF
  NOT EXISTS` statements and closes that one handle
  (`SqlLogger.java:106-119`). There is no transaction around the pair and no
  validation that an already-existing table has the expected shape.
- **Recording start:** constructing the scheduled `SqlLogger` first opens or
  creates the database (`SqlLogger.java:53-56`), then the service schedules it,
  initializes the two tables, creates the activity row, and publishes its ID
  (`SportLoggerService.java:162-170`). Deterministic source-reachable snapshots
  therefore include: only `android_metadata`, `android_metadata` plus
  `ACTIVITY`, and a complete schema containing a `GPS_POINTS` row whose
  `ACTIVITYID == 0`.
- **During recording:** a scheduled writer snapshots the latest shared
  location/heart-rate values once per second
  (`SportLoggerService.java:164-165`, `SqlLogger.java:65-103`). It is not one
  row per location callback.
- **Activity creation/finalization:** the live path inserts only `GMTSTART`
  (`SqlLogger.java:121-159`). `stopActivity()` stops scheduling but never calls
  `updateSportActivity()`, so `GMTEND`, `NAME`, `DESCRIPTION`, `DISTANCE`,
  `TIME`, and `PACE` normally remain `NULL`
  (`SportLoggerService.java:175-195`).
- **Stop snapshot:** scheduler shutdown runs asynchronously, while
  `MainActivity` immediately queries and serializes the activity
  (`SportLoggerService.java:175-186`, `wear/.../MainActivity.java:237-255`).
  The final point set is therefore not synchronized with stop.
- **Deletion:** discard deletes points and then the activity with two separate,
  non-transactional statements (`SqlLogger.java:297-310`). No cascade or
  foreign-key enforcement exists.

Room cannot auto-migrate this unversioned raw file. Migration must run on the
watch, open this file read-only, and write a separately named target database.
Preflight must report `no_business_tables` and `partial_business_schema` as
blocked schema states. A complete schema with zero rows is instead valid empty
data and must report ready.

Before page-count multiplication or file-length modulus, structural preflight
must validate the two-byte SQLite page-size encoding. The only legal database
header encodings are `1` (65536 bytes) and powers of two from 512 through
32768. Zero and every other encoding are corrupt input: integrity/schema reads,
source reads, target writes, and receipt writes must not run.

### Active WAL snapshot handling

Android SQLite databases can be observed while using the standard WAL format,
regardless of whether the current legacy code explicitly enabled it. A
filesystem copier must therefore not assume the main database file is the
complete committed state. The committed `active_wal_snapshot` fixture is a
captured 4096-byte-page Android-compatible SQLite snapshot containing:

- a complete, empty main database file;
- one committed activity and two committed points resident only in
  `active_wal_snapshot.db-wal`; and
- the matching `active_wal_snapshot.db-shm` wal-index captured from the same
  active state.

Opening only the main file returns zero business rows. The fixture verifier
also rejects a missing `-shm` artifact and a WAL truncated at a frame boundary.
A SQLite backup performed from one established read transaction returns the
exact activity/point state and passes `PRAGMA integrity_check`.

Production migration must use one of these boundaries:

1. query the source through one SQLite read transaction for the complete
   extraction; or
2. use the SQLite online-backup API through a connection; or
3. quiesce/close the writer and then copy the main file plus `-wal` and `-shm`
   as one snapshot.

Copying the three live files independently, copying only the main file, or
assuming `-shm` may always be reconstructed is not a supported migration
boundary.

## Runtime application DDL

Before application DDL, Android creates this platform table and maintains its
single locale row:

```sql
CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT);
```

Most committed fixtures use deterministic locale `en_US`; the
`localized_timestamps` fixture uses `ar_EG` to reproduce the locale-sensitive
legacy writer. Production locale text is device-dependent and must not become a
source-database identity or migrated business row.

The generator then executes these source statements verbatim:

```sql
CREATE TABLE IF NOT EXISTS ACTIVITY
(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
 GMTSTART VARCHAR,
 GMTEND VARCHAR,
 NAME VARCHAR,
 DESCRIPTION VARCHAR,
 DISTANCE REAL,
 TIME REAL,
 PACE REAL);

CREATE TABLE IF NOT EXISTS GPS_POINTS
(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
 ACTIVITYID INTEGER,
 GMTTIMESTAMP VARCHAR,
 LATITUDE REAL,
 LONGITUDE REAL,
 ALTITUDE REAL,
 ACCURACY REAL,
 SPEED REAL,
 BEARING REAL,
 HEARTRATE REAL);
```

Evidence: `SqlLogger.java:110-116`.

Only `ID` is `NOT NULL`; every other column has no explicit default and
therefore defaults to SQL `NULL`. These are non-`STRICT` SQLite tables, so type
affinity is not a constraint: for example, text can physically exist in a
`REAL` column. `AUTOINCREMENT` creates SQLite's internal `sqlite_sequence`
metadata, which is not application data. Neither `android_metadata` nor
`sqlite_sequence` is a legacy business table.

### `ACTIVITY`

| Column | Declared type | Actual contract |
|---|---|---|
| `ID` | `INTEGER` | Signed 64-bit SQLite row ID and primary key. Legacy Java narrows it to `int`; ETL must not. |
| `GMTSTART` | `VARCHAR` | Intended UTC-like start text, normally `yyyyMMddHHmmss`. Live creation supplies this one field. |
| `GMTEND` | `VARCHAR` | Intended UTC-like end text; normally `NULL` in the live recording path. |
| `NAME` | `VARCHAR` | Optional text; live recording does not populate it. |
| `DESCRIPTION` | `VARCHAR` | Optional text; live recording does not populate it. |
| `DISTANCE` | `REAL` | Optional summary. The schema/model do not state a unit; TCX code uses metres, but the live path does not populate this column. |
| `TIME` | `REAL` | Optional summary. TCX code uses seconds, but the live path does not populate this column. |
| `PACE` | `REAL` | Optional summary with no source-defined unit or live calculation. Preserve the raw value; do not infer a unit during extraction. |

### `GPS_POINTS`

| Column | Declared type | Actual contract |
|---|---|---|
| `ID` | `INTEGER` | Signed 64-bit SQLite row ID and primary key. |
| `ACTIVITYID` | `INTEGER` | Soft reference to `ACTIVITY.ID`; nullable and unenforced. |
| `GMTTIMESTAMP` | `VARCHAR` | Intended UTC-like sample text, normally `yyyyMMddHHmmss`, with one-second precision. |
| `LATITUDE` | `REAL` | Android `Location.getLatitude()`: decimal degrees. Default shared state can produce `0.0`. |
| `LONGITUDE` | `REAL` | Android `Location.getLongitude()`: decimal degrees. Default shared state can produce `0.0`. |
| `ALTITUDE` | `REAL` | Android location altitude in metres when present; otherwise SQL `NULL`. |
| `ACCURACY` | `REAL` | Android horizontal accuracy in metres when present; otherwise SQL `NULL`. |
| `SPEED` | `REAL` | Android speed in metres/second when present; otherwise SQL `NULL`. |
| `BEARING` | `REAL` | Android bearing in degrees when present; otherwise SQL `NULL`. |
| `HEARTRATE` | `REAL` | Heart-rate sensor value, conventionally beats/minute. Default shared state is `0.0`; fractional values are representable. |

The database stores neither steps nor per-point/cumulative distance, despite
those values existing in `LocationData` (`LocationData.java:13-17`).

## Relationship and ordering assumptions

The only intended join is:

```sql
GPS_POINTS.ACTIVITYID = ACTIVITY.ID
```

There is no declared foreign key, index, uniqueness constraint, cascade, or
check constraint. Orphans are legal SQLite rows and are source-reachable
through the start/discard races. A migration must count and report them rather
than silently attach or drop them. Null `ACTIVITYID` is malformed ownership,
not the same case as a non-null missing parent. The default non-null value `0`
is a distinct, source-reachable missing-parent case covered by the startup
fixture.

Legacy reads specify no `ORDER BY` (`SqlLogger.java:187,233,320`). Extraction
must explicitly order activities and points by their 64-bit `ID`; timestamp
order is not guaranteed and timestamps are not unique. The
`timestamp_ordering` fixture stores point IDs
`2147483648`, `9007199254740992`, and `9007199254740993` in ascending order
with timestamps `...02`, `...01`, and `...02`. An extractor that sorts by
timestamp or collapses equal timestamps fails the oracle.

## Timestamp rules

`Config.TimestampFormat` is a process-global
`SimpleDateFormat("yyyyMMddHHmmss")` using the device's default format locale,
default time zone, and default lenient parsing (`Config.java:15-18`). The
one-argument constructor is locale-sensitive, so numeric fields may be emitted
with the locale's decimal digits rather than ASCII. For example, Java/Android
formatting under `ar_EG` emits Arabic-Indic
`٢٠٢٤٠٧٠٨٠٩١٠١١` for the fields represented by ASCII
`20240708091011`. The checked expected string was reproduced with Java 21
`SimpleDateFormat("yyyyMMddHHmmss", Locale.forLanguageTag("ar-EG"))`; Android
documents that the one-argument constructor uses the default `FORMAT` locale,
and its `DecimalFormatSymbols.getZeroDigit()` contract explicitly varies for
Arabic. See Android's
[`SimpleDateFormat(String)`](https://developer.android.com/reference/java/text/SimpleDateFormat#SimpleDateFormat(java.lang.String))
and
[`DecimalFormatSymbols.getZeroDigit()`](https://developer.android.com/reference/java/text/DecimalFormatSymbols#getZeroDigit())
documentation. Writers obtain local time,
subtract the current zone offset, then format with that still-local formatter
(`SqlLogger.java:65-73,121-129`). Consequences:

- values have second precision and no offset/zone marker;
- field names say GMT/UTC, but the implementation is a manual UTC conversion;
- lenient/default-zone parsing can normalize invalid dates or drift values;
- the shared `DateFormat` is not thread-safe;
- parse failures are swallowed and become an unset Java `Date`
  (`SqlLogger.java:205-211,253-259,344-346`).

The fixture oracle treats only a strictly valid 14-`Nd`-digit Gregorian
timestamp as migratable and otherwise records a rejection. For validation and
parsing only, ETL must map Unicode decimal digits to their ASCII decimal values,
require all 14 characters to come from one numbering-system block, and then
apply strict Gregorian field validation. The original legacy text must remain
unchanged in canonical source fields, diagnostics, representative values, and
checksums. Separators, formatting controls, non-`Nd` lookalikes, mixed
numbering systems, impossible dates, and invalid times remain rejected. Valid
values parse as UTC without applying the device's current offset again.

## Source-backed defects that migration must not reproduce

| Defect | Evidence | Migration consequence |
|---|---|---|
| Both activity queries omit `GMTEND` from the projection but call `getString()` for its `-1` column index. | `SqlLogger.java:184-210,229-259` | Do not reuse either activity loader; select every column explicitly. |
| Activity queries project `ID` but never assign it to `SportActivity.Id`. | `SqlLogger.java:184,197-216,229,249-265` | Model-based migration collapses every activity to default ID `0`. |
| Point loader reads `LONGITUDE`, `ALTITUDE`, `ACCURACY`, `SPEED`, `BEARING`, and `HEARTRATE` with `getInt()`. | `SqlLogger.java:348-354` | Fractional values are truncated; use typed SQL `REAL` reads. |
| Nullable numeric columns are loaded into Java primitives without checking `Cursor.isNull()`. | `SqlLogger.java:213-215,348-354`; model fields are primitive doubles | SQL `NULL` presence is lost. Read nullability before numeric conversion. |
| Parse errors are ignored. | `SqlLogger.java:205-211,253-259,344-346` | Invalid timestamps can silently become null model dates; reject/report them explicitly. |
| Reads have no stable ordering. | `SqlLogger.java:187,233,320` | Checksums and ETL iteration must use explicit `ORDER BY ID`. |
| SQLite 64-bit IDs are read/cast to Java `int`. | `SqlLogger.java:151,173-174,341-342,376-377` | Use 64-bit legacy keys. The precision fixture includes exact integer `9007199254740993`, above both Java `int` and JSON's interoperable `2^53` safe range. |
| The database is opened and the writer is scheduled before schema/activity creation. | `SqlLogger.java:53-56`; `SportLoggerService.java:162-170` | Distinguish Android-metadata-only, partial application schema, valid empty complete schema, and points whose owner is `0`. |
| Stop does not finalize the activity summary. | `SportLoggerService.java:175-195` | A row with only `GMTSTART` is valid source-reachable partial data, not corruption. |
| Stop asynchronously shuts down the writer while the UI immediately queries the database. | `SportLoggerService.java:175-186`; `wear/.../MainActivity.java:237-255` | Extract from a quiescent/copied database or a consistent read transaction; do not treat the transmitted model as authoritative. |
| Discard is asynchronous and its two deletes are not transactional. | `SportLoggerService.java:226-244`; `SqlLogger.java:297-310` | Count/report orphans and make target writes idempotent. |
| Object-based activity creation inserts the parent and loops over point inserts without one transaction. | `SqlLogger.java:161-177,367-378` | A process interruption can leave a parent with only a prefix of its points. |
| Location distance math multiplies angular distance by kilometres, then divides by `1000` while claiming to convert to metres. | `GpsListener.java:275-300` | Do not trust the live distance calculation as a recoverable metre value. |
| `LocationData(location, distance, totalDistance)` is called with cumulative and segment values reversed. | `LocationData.java:26-40`; `GpsListener.java:173-176` | Displayed/shared distance semantics are inverted, and neither value is persisted. |
| `CREATE TABLE IF NOT EXISTS` is the only schema management. | `SqlLogger.java:106-116` | Validate actual columns/types before extraction; do not assume the file matches current source. |
| SQLite type affinity is permissive and there are no checks. | DDL above | Detect text-in-`REAL`, invalid ranges, null ownership, and malformed timestamps before insertion into a typed target. |

## Executable fixture contract

`tools/legacy-fixtures/manifest.json` contains physical row counts, canonical
counts, per-activity point counts, orphan counts, strict timestamp ranges,
representative exact/epsilon values, schema checksums, and logical data
checksums. Logical checksums do **not** hash SQLite file bytes. They hash tables in
`ACTIVITY`, `GPS_POINTS` order, rows by `ID`, and type-tagged column values;
`REAL` values use exact IEEE-754 `float.hex()` representations. Platform
metadata is verified separately and excluded from those business-data
checksums. Standard database artifacts are marked `exact_bytes_required: false`;
deterministic regeneration compares their integrity, schema, platform metadata,
and type-tagged rows logically, so harmless SQLite read/write-version header
variation does not fail CI while logical drift does. Exact artifact hashes are
recorded only where bytes are part of the test contract: the active WAL trio and
the malformed, truncated, and corrupt negative files.

The committed cases are:

| Fixture | Purpose |
|---|---|
| `empty.db` | Complete schema with no rows: valid empty data, ready for migration. |
| `startup_no_business_tables.db` | Android metadata only, before either application table exists; migration is blocked. |
| `startup_activity_only.db` | Snapshot between the two independent application `CREATE TABLE` statements; migration is blocked as partial schema. |
| `startup_activity_id_zero.db` | Complete schema with a startup point owned by default `ACTIVITYID=0`; reported as an orphan. |
| `start_only_zero_points.db` | Complete schema with one valid activity containing only `GMTSTART` and zero points; point-driven/inner-join ETL must preserve the session. |
| `active_wal_snapshot.db` plus `-wal`/`-shm` | Committed rows exist only in the captured active WAL; main-only, missing-sidecar, and torn-copy handling is verified. |
| `representative.db` | Multi-activity data, multiple points, optional nulls, zero/default coordinates, and a normal partial live row. |
| `timestamp_ordering.db` | Duplicate and non-monotonic timestamps in required ascending 64-bit point-ID order. |
| `precision.db` | Fractional coordinates/altitude/accuracy/speed/bearing/heart rate/distance, leap day, and exact 64-bit ID `9007199254740993`. |
| `localized_timestamps.db` | `ar_EG` source metadata, valid Arabic-Indic timestamps preserved losslessly, and strict invalid-date/time, separator, non-`Nd`, and mixed-numbering controls. |
| `orphan.db` | One valid orphan alongside a valid parent/point control. |
| `malformed_null_partial.db` | Strictly invalid dates, text in `REAL` columns, invalid ranges, null ownership, and a source-reachable partial row. |
| `malformed_schema.db` | Integrity-valid file whose `ACTIVITY.TIME` declaration is incompatible; exact schema preflight blocks before row reads. |
| `truncated.db` | File is one declared page short; structural preflight blocks before SQLite integrity/schema reads. |
| `corrupt.db` | Header and file length are valid but the `GPS_POINTS` b-tree page is damaged; `PRAGMA integrity_check` blocks migration. |
| `interrupted_idempotency.db` | Actual insert-attempt accounting for interruption after a session row and after a point prefix, full replay, same-run duplicates, prevented duplicate attempts, computed final duplicate-row counts, and exact final equality. |

`expected/*.json` is a test interchange oracle, not a proposed production
schema. Every physical row is accounted for as a session, point, orphan, or
rejected row. Fixture deterministic IDs are UUIDv5 values derived from the
manifest's explicit database identity, table name, and 64-bit legacy ID.
Canonical JSON compares integer fields as exact integers (including values above
`2^53`). Floating-point fields compare by exact parsed IEEE-754 double bits.
Equivalent decimal spellings that round-trip to the same double compare equal,
while narrowing `1.000000000000001` to `1.0` fails. Explicit epsilon
representative probes are used only when a probe declares that mode.

Every blocked preflight output states `source_rows_read = 0`,
`target_write_attempted = false`, `target_rows_written = 0`,
`receipt_write_attempted = false`, and `receipt_written = false`. Preflight
order is file structure, then `PRAGMA integrity_check`, then exact table
metadata/schema validation. A failed layer prevents all later reads and writes.

Each interrupted-rerun scenario requires the rerun record sequence to exactly
match all nine canonical target records. Attempted session/point counts must
cover that full set, and duplicate counts must exactly match the committed
prefix; removing a row that committed before interruption is rejected even when
the final target checksum would otherwise remain equal. The oracle also models
a receipt gap: all nine canonical target rows commit, the receipt is absent, and
a replay attempts all nine rows, inserts zero, counts nine duplicates, preserves
the exact target checksum, and then completes the receipt.

The legacy file has no intrinsic database UUID. **Tank must define the stable
production source-database/install identity before production ETL is frozen**;
using the row ID alone can collide across watches or restored databases. This
gate remains intentionally open; the fixture-only synthetic identities and
source-backed filename do not invent a production install identity.

Run from the repository root:

```bash
python3 tools/legacy-fixtures/legacy_fixtures.py generate
python3 tools/legacy-fixtures/legacy_fixtures.py verify
python3 tools/legacy-fixtures/legacy_fixtures.py verify-determinism
python3 -m unittest discover -s tools/legacy-fixtures -p 'test_*.py' -v
```

The verifier runs 34 detectors covering integer and double narrowing,
swapped/missing fields, timestamp drift/sorting/deduplication, duplicate
deterministic IDs, orphan handling, start-only/point-driven loss, active-WAL
sidecar omission and torn snapshots, interrupted-rerun drift/loss (including
committed-prefix omission), receipt-gap completion, Android metadata readiness,
illegal page-size encodings, logical database drift, ASCII-only localized
timestamp loss, destructive timestamp text normalization, mixed-numbering
acceptance, and malformed/truncated/corrupt preflight side-effect prevention.
See
`tools/legacy-fixtures/README.md` for candidate-output and large-fixture
commands.
