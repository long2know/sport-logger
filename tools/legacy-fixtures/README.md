# Legacy SQLite fixtures

This directory contains only deterministic synthetic data. No fixture is
copied from a user, device, backup, or production database.

## Commands

From the repository root:

```bash
# Recreate fixtures/, expected/, and manifest.json.
python3 tools/legacy-fixtures/legacy_fixtures.py generate

# Verify schema, rows, logical checksums, expected outputs, idempotency, and
# all 34 defect detectors.
python3 tools/legacy-fixtures/legacy_fixtures.py verify

# Regenerate twice. Standard databases are compared logically; artifacts whose
# bytes are contractual are compared byte-for-byte.
python3 tools/legacy-fixtures/legacy_fixtures.py verify-determinism

# Run the standard-library unit tests.
python3 -m unittest discover -s tools/legacy-fixtures -p 'test_*.py' -v
```

The tool uses only Python's standard-library `sqlite3`, `json`, `hashlib`,
`uuid`, and test modules. Android, Gradle, Room, and third-party packages are
not required.

## Layout

- `fixtures/*.db` — 16 SQLite inputs using Android's platform metadata table
  plus the source-reachable application schema state.
- `fixtures/active_wal_snapshot.db-{wal,shm}` — the required sidecars for the
  active-WAL case. Its committed rows exist only in the WAL snapshot.
- `expected/*.json` — canonical test outputs. These classify every source row
  as a session, attached point, orphan, or rejected row, and distinguish valid
  empty data from blocked missing/partial schema.
- `manifest.json` — expected counts, timestamp ranges, representative values,
  idempotency results, and logical checksums.
- `test_legacy_fixtures.py` — regression and fault-injection tests.

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
`localized_timestamps` fixture records `android_metadata = ar_EG` and uses the
Arabic-Indic text `٢٠٢٤٠٧٠٨٠٩١٠١١`, reproduced with Java 21
`SimpleDateFormat("yyyyMMddHHmmss", Locale.forLanguageTag("ar-EG"))` for the
same fields as ASCII `20240708091011`. Android documents the same
locale-sensitive constructor/default-symbol contract, including a zero digit
that differs for Arabic; the checked fixture text is deterministic and does not
depend on the Python host locale. See the Android
[`SimpleDateFormat(String)`](https://developer.android.com/reference/java/text/SimpleDateFormat#SimpleDateFormat(java.lang.String))
and
[`DecimalFormatSymbols.getZeroDigit()`](https://developer.android.com/reference/java/text/DecimalFormatSymbols#getZeroDigit())
contracts. This source text is preserved exactly in canonical rows,
rejected-row diagnostics, representative values, and checksums.

Validation converts each Unicode `Nd` decimal digit to its ASCII value only in a
temporary parse buffer. It requires exactly 14 digits from one numbering-system
block, then applies strict Gregorian date/time parsing. It still rejects mixed
ASCII/Arabic-Indic or mixed Arabic digit sets, separators, direction marks,
superscript and other non-`Nd` lookalikes, impossible dates, and invalid times.
Defect detectors prove both that an ASCII-only ETL drops the valid localized
rows and that a permissive per-character digit conversion wrongly accepts mixed
numbering systems.

## Deterministic regeneration

Every standard SQLite artifact is marked `exact_bytes_required: false`.
Regeneration compares its integrity, schema, platform metadata, and type-tagged
business rows logically. Harmless SQLite read/write-version header differences
therefore pass, while any schema, metadata, type, or row-value change fails.
Artifacts marked `exact_bytes_required: true` remain byte-for-byte contracts,
and canonical JSON outputs remain byte-stable. Manifest comparison ignores only
non-contractual byte lengths for standard database artifacts.

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
