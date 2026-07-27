# Legacy SQLite fixtures

This directory contains only deterministic synthetic data. No fixture is
copied from a user, device, backup, or production database.

## Commands

From the repository root:

```bash
# Recreate fixtures/, expected/, and manifest.json.
python3 tools/legacy-fixtures/legacy_fixtures.py generate

# Verify schema, rows, logical checksums, expected outputs, idempotency, and
# all 38 defect detectors.
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

Current counted reality is 16 fixtures and 35 regeneration artifacts: 17
exact-byte UTF-8/LF JSON artifacts, 12 logical standard databases, and 6
canonical non-standard SQLite artifacts. The verifier runs 38 defect detectors,
and the standard-library suite contains 19 tests.

## Layout

- `fixtures/*.db` — 16 SQLite inputs using Android's platform metadata table
  plus the source-reachable application schema state.
- `fixtures/active_wal_snapshot.db-{wal,shm}` — the required sidecars for the
  active-WAL case. Its committed rows exist only in the WAL snapshot.
- `expected/*.json` — canonical test outputs. These classify every source row
  as a session, attached point, orphan, or rejected row, and distinguish valid
  empty data from blocked missing/partial schema.
- `manifest.json` — expected counts, timestamp ranges, representative values,
  idempotency results, storage-comparison modes, and logical checksums.
- `test_legacy_fixtures.py` — regression and fault-injection tests.
- `LocaleTimestampProbe.java` — optional source-mode JDK probe for the exact
  locale digits committed in `localized_timestamps`.

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

## Locale-sensitive timestamps

Legacy `Config.TimestampFormat` constructs `SimpleDateFormat("yyyyMMddHHmmss")`
without a locale, so Android uses the device's default format locale. The
`localized_timestamps` database currently records `android_metadata = ar_EG`,
but contains valid per-row Arabic-Indic, historical Bengali, and historical
ASCII timestamps. Android updates platform locale metadata when the database is
opened after a locale change; that one current value is not a row-level digit
declaration.

The committed Arabic-Indic `٢٠٢٤٠٧٠٨٠٩١٠١١` and Bengali
`২০২৪০৭০৮০৯১০১১` strings were reproduced by:

```bash
java tools/legacy-fixtures/LocaleTimestampProbe.java
```

OpenJDK `21.0.11+10-1-24.04.2-Ubuntu` reported:

```text
ar-EG zero=U+0660 formatted=٢٠٢٤٠٧٠٨٠٩١٠١١
bn-BD zero=U+09E6 formatted=২০২৪০৭০৮০৯১০১১
en-US zero=U+0030 formatted=20240708091011
```

Android documents the same locale-sensitive constructor/default-symbol
contract. The checked strings are deterministic and do not depend on the
Python host locale. See the Android
[`SimpleDateFormat(String)`](https://developer.android.com/reference/java/text/SimpleDateFormat#SimpleDateFormat(java.lang.String))
and
[`DecimalFormatSymbols.getZeroDigit()`](https://developer.android.com/reference/java/text/DecimalFormatSymbols#getZeroDigit())
contracts. This source text is preserved exactly in canonical rows,
rejected-row diagnostics, representative values, and checksums.

Validation converts each Unicode `Nd` decimal digit to its ASCII value only in a
temporary parse buffer. It requires exactly 14 digits from one numbering-system
block, then applies strict Gregorian date/time parsing. ASCII is a valid block
by itself and can coexist with non-ASCII rows in one database, but ASCII cannot
mix with another block inside one timestamp: one `SimpleDateFormat` instance
uses one `DecimalFormatSymbols` zero digit for all numeric fields. The oracle
also rejects mixed non-ASCII blocks, separators, embedded bidi/direction/format
controls, superscript and other non-`Nd` lookalikes, impossible dates, and
invalid times.

Detectors prove that ASCII-only, ASCII-plus-Arabic-only, current-metadata-
coupled, mixed-block-permissive, and Unicode-format-stripping parsers all fail
the committed candidate corpus.

## Deterministic regeneration

All 18 SQLite artifacts are marked `exact_bytes_required: false`; none is a
host-generated byte-for-byte contract. The 12 standard databases compare
integrity, schema, platform metadata, and type-tagged business rows logically.
The active-WAL trio compares main-only/full logical state, WAL frame shape, and
required sidecar semantics. The malformed schema compares its exact schema
defect plus seed rows. Truncated/corrupt files compare documented corruption
shape plus a canonical byte digest that zeroes only SQLite header byte ranges
`[18,20)`, `[24,28)`, and `[92,100)` before hashing. These fields cover
read/write version and informational change/version metadata; structure,
schema, page payload, and damage bytes remain contractual.

The 16 expected JSON files and `manifest.json` are the 17 true exact-byte
artifacts. `write_json()` emits UTF-8 bytes with LF and one terminal newline on
every host, while `.gitattributes` enforces LF for fixture text. Determinism
tests prove CRLF drift fails and harmless SQLite header variation passes while
page, schema, payload, WAL, and corruption changes fail.

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
