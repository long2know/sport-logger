# Legacy SQLite fixtures

This directory contains only deterministic synthetic data. No fixture is
copied from a user, device, backup, or production database.

## Commands

From the repository root:

```bash
# Recreate fixtures/, expected/, and manifest.json.
python3 tools/legacy-fixtures/legacy_fixtures.py generate

# Verify schema, rows, logical checksums, expected outputs, idempotency, and
# all 49 defect detectors.
python3 tools/legacy-fixtures/legacy_fixtures.py verify

# Regenerate twice. SQLite artifacts use their declared logical/canonical
# comparison; generated JSON is compared byte-for-byte.
python3 tools/legacy-fixtures/legacy_fixtures.py verify-determinism

# Run the standard-library unit tests.
python3 -m unittest discover -s tools/legacy-fixtures -p 'test_*.py' -v
```

The tool uses only Python's standard-library `sqlite3`, `json`, `hashlib`,
`uuid`, and test modules. Android, Gradle, Room, and third-party packages are
not required. The optional locale-evidence probe uses JDK source-file mode.

Current counted reality is 18 fixtures and 39 regeneration artifacts: 19
exact-byte UTF-8/LF JSON artifacts, 14 logical standard databases, and 6
canonical non-standard SQLite artifacts. The verifier runs 49 defect detectors,
and the standard-library suite contains 22 tests.

## Layout

- `fixtures/*.db` — 18 SQLite inputs using Android's platform metadata table
  plus the source-reachable application schema state.
- `fixtures/active_wal_snapshot.db-{wal,shm}` — the required sidecars for the
  active-WAL case. Its committed rows exist only in the WAL snapshot.
- `expected/*.json` — canonical test outputs. These classify every source row
  as a session, attached point, orphan, rejected row, or retained calendar
  quarantine row, and distinguish valid empty data from blocked migration.
- `manifest.json` — expected counts, timestamp ranges, representative values,
  idempotency results, storage-comparison modes, and logical checksums.
- `test_legacy_fixtures.py` — regression and fault-injection tests.
- `LocaleTimestampProbe.java` — checked source-mode JDK probe for the exact
  locale digit and calendar semantics committed in the timestamp fixtures.

The JSON output is a test interchange format, not a production Room schema.
Integer comparison is exact above `2^53`. Canonical JSON floating-point values
compare by their parsed IEEE-754 double bits, so alternate decimal spellings
that round-trip to the same double are stable while `1.000000000000001` changed
to `1.0` fails. Explicit epsilon probes remain available only where a test
declares that weaker contract. The idempotency oracle counts every attempted,
inserted, and prevented duplicate insert, computes final duplicate rows,
requires every interrupted rerun to replay the exact full canonical record set,
requires exact final-state equality, and covers a target-commit/receipt-gap
replay that inserts zero rows before completing the receipt.
To validate an ETL test export, emit matching files named `<fixture>.json` and
run:

```bash
python3 tools/legacy-fixtures/legacy_fixtures.py verify \
  --candidate-dir path/to/candidate-json
```

Schema readiness also requires the exact `android_metadata (locale TEXT)` table
shape and exactly one non-empty, locale-shaped TEXT row. Missing, empty,
non-TEXT, invalid-value, and multi-row metadata states are explicit
`malformed_schema` diagnostics and block migration before source reads or
target/receipt writes.

Schema validation uses `PRAGMA table_xinfo`, not only `table_info`, and compares
canonicalized `sqlite_schema` table SQL. It rejects hidden/generated columns,
virtual or shadow-table substitutions, extra indexes/views/triggers, changed
constraints/defaults/order/types, foreign keys, and non-table b-tree records.
Keyword case, identifier quoting, comments, and whitespace are treated as
formatting only.

## Locale-sensitive timestamps

Legacy `Config.TimestampFormat` constructs `SimpleDateFormat("yyyyMMddHHmmss")`
without a locale, so Android uses the device's default format locale for both
decimal digits **and calendar**. The checked probe reproduces Arabic-Indic,
Bengali, ASCII, Thai Buddhist, explicit Thai-digit Gregorian, and Thai Buddhist
with Latin digits:

```bash
java tools/legacy-fixtures/LocaleTimestampProbe.java
```

OpenJDK `21.0.11+10-1-24.04.2-Ubuntu` reported:

```text
ar-EG zero=U+0660 calendar=gregory formatted=٢٠٢٤٠٧٠٨٠٩١٠١١
bn-BD zero=U+09E6 calendar=gregory formatted=২০২৪০৭০৮০৯১০১১
en-US zero=U+0030 calendar=gregory formatted=20240708091011
th-TH-u-nu-thai zero=U+0E50 calendar=buddhist formatted=๒๕๖๗๐๗๐๘๐๙๑๐๑๑
th-TH-u-ca-gregory-nu-thai zero=U+0E50 calendar=gregory formatted=๒๐๒๔๐๗๐๘๐๙๑๐๑๑
th-TH-u-nu-latn zero=U+0030 calendar=buddhist formatted=25670708091011
```

Android documents the same locale-sensitive constructor/default-symbol
contract. The checked strings are deterministic and do not depend on the
Python host locale. See the Android
[`SimpleDateFormat(String)`](https://developer.android.com/reference/java/text/SimpleDateFormat#SimpleDateFormat(java.lang.String))
and
[`DecimalFormatSymbols.getZeroDigit()`](https://developer.android.com/reference/java/text/DecimalFormatSymbols#getZeroDigit())
contracts. Source text is preserved exactly in canonical rows, quarantine
records, rejected-row diagnostics, representative values, and checksums.

The migration oracle never infers a calendar from digit shape or the current
`android_metadata` locale. Current metadata can change when Android reopens the
database and is not durable row-level evidence. The synthetic ready fixtures
carry explicit per-activity generation evidence. `calendar_semantics` proves
that Buddhist `๒๕๖๗...` and Gregorian `๒๐๒๔...` Thai-digit values both map to
2024 only because their calendars are explicitly known. `localized_timestamps`
models current `ar_EG` metadata plus durably recorded historical `bn-BD` and
`en-US` evidence.

`calendar_ambiguous` has current Thai metadata but no durable per-activity
calendar evidence. Its two activities—one Thai-digit and one ASCII-digit
Buddhist-year value—are quarantined as one migration unit. The oracle reads only
to classify and preserve raw rows, performs zero target writes, writes no
receipt, and requires the original database/text to remain recoverable. A future
user-assisted flow may persist verified per-activity calendar evidence and
rerun migration; it must never guess a 543-year adjustment.

After a calendar is evidenced, validation maps Unicode `Nd` digits to ASCII only
in a temporary parse buffer, requires exactly 14 digits from one numbering
system, and applies strict fields in that calendar. Mixed blocks, separators,
bidi/format controls, non-`Nd` lookalikes, impossible dates, and invalid times
remain rejected.

Detectors prove that Gregorian-only parsing, automatic Buddhist conversion from
Thai digit shape, migration of ambiguous rows, ASCII-only/limited-block parsing,
current-metadata coupling, mixed-block acceptance, and format-control stripping
all fail.

## Deterministic regeneration

All 20 SQLite artifacts are marked `exact_bytes_required: false`; none is a
host-generated byte-for-byte contract. The 14 standard databases compare
integrity, schema, platform metadata, and type-tagged business rows logically.
The active-WAL trio compares main-only/full logical state, WAL frame shape, and
required sidecar semantics. The malformed schema compares its exact schema
defect plus seed rows. Truncated/corrupt files compare documented corruption
shape plus a canonical byte digest that zeroes only SQLite header byte ranges
`[18,20)`, `[24,28)`, and `[92,100)` before hashing. Before any normalization,
the oracle requires read/write versions `(1,1)` for rollback files or `(2,2)`
for WAL, and requires the change counter to equal version-valid-for. WAL
transactions need not increment the main-file counter, but the two stored
header values must still agree. Invalid `0`/`255` versions, mixed modes, and
counter mismatches are corruption. The SQLite writer-version field alone is
informational and may vary; structure, schema, page payload, and damage bytes
remain contractual.

The 18 expected JSON files and `manifest.json` are the 19 true exact-byte
artifacts. `write_json()` emits UTF-8 bytes with LF and one terminal newline on
every host, while `.gitattributes` enforces LF for fixture text. Determinism
tests prove CRLF drift fails and harmless writer-version-only variation passes
while illegal header semantics, page, schema, payload, WAL, and corruption
changes fail.

## Active WAL snapshot

`active_wal_snapshot.db`, `active_wal_snapshot.db-wal`, and
`active_wal_snapshot.db-shm` are one captured Android-compatible SQLite WAL
snapshot. The main file has a complete schema but zero business rows; one
activity and two committed points are resident only in the WAL. The verifier
proves that opening only the main file loses those rows, that omitting `-shm`
is rejected as an incomplete file-copy snapshot, that a torn WAL is rejected,
and that a SQLite backup taken from one read transaction contains the exact
state.

Production extraction must either read the live database through one
consistent SQLite read transaction/backup connection, or quiesce/close the
writer before copying the main file and both sidecars together. Never copy the
three live files independently, ignore a sidecar, or use an immutable/main-only
open as the migration source.

## Blocked preflight fixtures

`malformed_schema.db`, `truncated.db`, and `corrupt.db` exercise different
preflight layers:

- malformed schema: file structure and `PRAGMA integrity_check` pass, then
  exact schema validation fails;
- truncated file: declared page count exceeds file length, so integrity and
  schema reads are not attempted;
- corrupt file: file length/header pass, then `PRAGMA integrity_check` fails;
- illegal page-size header: every encoding other than `1` (65536 bytes) or a
  power of two from 512 through 32768 is classified as corrupt before modulus,
  integrity, schema, source-read, target-write, or receipt logic.
- illegal read/write versions or mismatched change-counter/version-valid-for
  values are classified as corrupt before canonicalization, integrity, schema,
  source-read, target-write, or receipt logic.

Each expected output requires a blocked migration, zero source rows read, no
target write attempt, zero target rows, and no receipt attempt or receipt.

## Large mode

Large databases are generated locally and are intentionally not committed:

```bash
python3 tools/legacy-fixtures/legacy_fixtures.py large \
  --activities 100 \
  --points-per-activity 1000

python3 tools/legacy-fixtures/legacy_fixtures.py verify-large
```

The default output is `tools/legacy-fixtures/generated/large.db` with a
logical-checksum sidecar. Raw SQLite file hashes are deliberately not used.
