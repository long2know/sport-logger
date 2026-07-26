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
- **Initialization:** `initDatabase()` issues two independent `CREATE TABLE IF
  NOT EXISTS` statements and closes that one handle
  (`SqlLogger.java:106-119`). There is no transaction around the pair and no
  validation that an already-existing table has the expected shape.
- **Recording start:** the service schedules a `SqlLogger` immediately, then
  initializes the tables, then creates the activity row and publishes its ID
  (`SportLoggerService.java:162-170`). The zero-delay writer can therefore race
  table creation or write with the default `ActivityId == 0`.
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

## Runtime application DDL

The generator executes these source statements verbatim:

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
metadata, which is not application data.

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
not the same case as a non-null missing parent.

Legacy reads specify no `ORDER BY` (`SqlLogger.java:187,233,320`). Extraction
must explicitly order activities and points by their 64-bit `ID`; timestamp
order is not guaranteed and timestamps are not unique.

## Timestamp rules

`Config.TimestampFormat` is a process-global
`SimpleDateFormat("yyyyMMddHHmmss")` using the device's default time zone and
default lenient parsing (`Config.java:15-18`). Writers obtain local time,
subtract the current zone offset, then format with that still-local formatter
(`SqlLogger.java:65-73,121-129`). Consequences:

- values have second precision and no offset/zone marker;
- field names say GMT/UTC, but the implementation is a manual UTC conversion;
- lenient/default-zone parsing can normalize invalid dates or drift values;
- the shared `DateFormat` is not thread-safe;
- parse failures are swallowed and become an unset Java `Date`
  (`SqlLogger.java:205-211,253-259,344-346`).

The fixture oracle treats only a strictly valid 14-digit Gregorian timestamp as
migratable and otherwise records a rejection. ETL must preserve the raw text
for diagnostics and parse valid values as UTC without applying the device's
current offset again.

## Source-backed defects that migration must not reproduce

| Defect | Evidence | Migration consequence |
|---|---|---|
| Both activity queries omit `GMTEND` from the projection but call `getString()` for its `-1` column index. | `SqlLogger.java:184-210,229-259` | Do not reuse either activity loader; select every column explicitly. |
| Activity queries project `ID` but never assign it to `SportActivity.Id`. | `SqlLogger.java:184,197-216,229,249-265` | Model-based migration collapses every activity to default ID `0`. |
| Point loader reads `LONGITUDE`, `ALTITUDE`, `ACCURACY`, `SPEED`, `BEARING`, and `HEARTRATE` with `getInt()`. | `SqlLogger.java:348-354` | Fractional values are truncated; use typed SQL `REAL` reads. |
| Nullable numeric columns are loaded into Java primitives without checking `Cursor.isNull()`. | `SqlLogger.java:213-215,348-354`; model fields are primitive doubles | SQL `NULL` presence is lost. Read nullability before numeric conversion. |
| Parse errors are ignored. | `SqlLogger.java:205-211,253-259,344-346` | Invalid timestamps can silently become null model dates; reject/report them explicitly. |
| Reads have no stable ordering. | `SqlLogger.java:187,233,320` | Checksums and ETL iteration must use explicit `ORDER BY ID`. |
| SQLite 64-bit IDs are read/cast to Java `int`. | `SqlLogger.java:151,173-174,341-342,376-377` | Use 64-bit legacy keys. The precision fixture includes `2147483648`. |
| The writer is scheduled before schema/activity creation. | `SportLoggerService.java:162-170` | Handle missing/partial schema and points whose owner is `0` or absent. |
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
checksums. Checksums do **not** hash SQLite file bytes. They hash tables in
`ACTIVITY`, `GPS_POINTS` order, rows by `ID`, and type-tagged column values;
`REAL` values use exact IEEE-754 `float.hex()` representations.

The committed cases are:

| Fixture | Purpose |
|---|---|
| `empty.db` | Exact schema with no rows. |
| `representative.db` | Multi-activity data, multiple points, optional nulls, zero/default coordinates, and a normal partial live row. |
| `precision.db` | Fractional coordinates/altitude/accuracy/speed/bearing/heart rate/distance, leap day, and 64-bit IDs. |
| `orphan.db` | One valid orphan alongside a valid parent/point control. |
| `malformed_null_partial.db` | Strictly invalid dates, text in `REAL` columns, invalid ranges, null ownership, and a source-reachable partial row. |
| `interrupted_idempotency.db` | Partial first-pass plus deterministic duplicate-free rerun simulation. |

`expected/*.json` is a test interchange oracle, not a proposed production
schema. Every physical row is accounted for as a session, point, orphan, or
rejected row. Fixture deterministic IDs are UUIDv5 values derived from the
manifest's explicit database identity, table name, and 64-bit legacy ID.

The legacy file has no intrinsic database UUID. **Tank must define the stable
production source-database/install identity before production ETL is frozen**;
using the row ID alone can collide across watches or restored databases.

Run from the repository root:

```bash
python3 tools/legacy-fixtures/legacy_fixtures.py generate
python3 tools/legacy-fixtures/legacy_fixtures.py verify
python3 -m unittest discover -s tools/legacy-fixtures -p 'test_*.py' -v
```

The verifier fault-injects integer truncation, swapped/missing fields,
timestamp drift, duplicate deterministic IDs, orphan mishandling, and
interrupted-rerun identity drift. See
`tools/legacy-fixtures/README.md` for candidate-output and large-fixture
commands.
