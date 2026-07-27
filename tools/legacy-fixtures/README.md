# Legacy SQLite fixtures

This directory contains only deterministic synthetic data. No fixture is
copied from a user, device, backup, or production database.

## Commands

From the repository root:

```bash
# Validate the committed Temurin 17 formatter matrix.
"$JAVA_HOME/bin/java" tools/legacy-fixtures/LocaleTimestampProbe.java

# Recreate fixtures/, expected/, and manifest.json.
python3 tools/legacy-fixtures/legacy_fixtures.py generate

# Verify schema, rows, logical checksums, expected outputs, idempotency, and
# all 57 declared defect detectors. Version- or build-specific detectors that
# cannot run on the connected SQLite engine are reported as not applicable.
python3 tools/legacy-fixtures/legacy_fixtures.py verify

# Prove all supported SQLite capability paths make equivalent decisions.
python3 tools/legacy-fixtures/legacy_fixtures.py verify-legacy-schema-path

# Regenerate twice. SQLite artifacts use their declared logical/canonical
# comparison; generated JSON is compared byte-for-byte.
python3 tools/legacy-fixtures/legacy_fixtures.py verify-determinism

# Run the standard-library unit tests.
python3 -m unittest discover -s tools/legacy-fixtures -p 'test_*.py' -v
```

The tool uses only Python's standard-library `sqlite3`, `json`, `hashlib`,
`uuid`, and test modules. Android, Gradle, Room, and third-party packages are
not required, and no SQLite engine is bundled. The locale-evidence probe uses
JDK source-file mode.

Current counted reality is 20 fixtures and 44 regeneration artifacts: 22
exact-byte UTF-8/LF text artifacts, 16 logical standard databases, and 6
canonical non-standard SQLite artifacts. The verifier defines 57 defect
detectors and reports separate applicable and not-applicable counts for the
connected SQLite runtime. The standard-library suite contains 24 tests.

## Layout

- `fixtures/*.db` — 20 SQLite inputs using Android's platform metadata table
  plus the source-reachable application schema state.
- `fixtures/active_wal_snapshot.db-{wal,shm}` — the required sidecars for the
  active-WAL case. Its committed rows exist only in the WAL snapshot.
- `expected/*.json` — canonical test outputs. These classify every source row
  as a session, attached point, orphan, rejected row, or retained calendar
  quarantine row, and distinguish valid empty data from blocked migration.
- `manifest.json` — expected counts, timestamp ranges, representative values,
  idempotency results, storage-comparison modes, and logical checksums.
- `LocaleTimestampProbe.expected.tsv` — pinned Temurin `17.0.20+8` formatter
  matrix, including all 1,017 available locales, 11 unique
  digit/calendar/output signatures, and 9 unique decimal digit blocks.
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

Schema validation probes and caches `PRAGMA table_xinfo` support and the
`sqlite_schema` catalog alias independently for each connection. Automatic
validation prefers the universally compatible `sqlite_master` catalog. Pure
unit tests cover the complete selection matrix, while real-connection tests run
only the host profile and capability subsets the host can safely emulate:

| SQLite profile | Column metadata | Catalog paths | Generated-column detector |
|---|---|---|---|
| Android API 26 / 3.18.2 | `table_info` | `sqlite_master` | N/A |
| 3.26 | `table_xinfo` | `sqlite_master` | N/A |
| 3.32 | `table_xinfo` | `sqlite_master` | Runs after a side-effect-free support probe |
| 3.33+ | `table_xinfo` | `sqlite_master`, plus probed `sqlite_schema` equivalence | Runs after a side-effect-free support probe |

The executable end-to-end profile count is derived from the probed host:
3.18-class hosts run two canonical/malformed decisions, 3.26–3.32 hosts run
four, and 3.33+ hosts run six. A host may disable capabilities to exercise a
subset, but the harness never enables or assumes a capability the connection
does not expose.

The representative build matrix used by the unit contract is:

| Runtime/build capabilities | Schema cases | Real profile decisions | Applicable/N/A detectors |
|---|---:|---:|---:|
| 3.18.2 / API26-style FTS4, no FTS5 | 9 | 2 | 55 / 2 |
| 3.26.0 / FTS4, no FTS5 | 9 | 4 | 55 / 2 |
| 3.32.0 / FTS4 + FTS5 | 11 | 4 | 57 / 0 |
| 3.33+ / FTS4 + FTS5 | 11 | 6 | 57 / 0 |

Those are capability-profile expectations, not assumptions about arbitrary
desktop builds. For example, a 3.32 build without FTS5 reports that one
detector as N/A and derives the lower executable case count automatically.

Generated-column SQL is never parsed below SQLite 3.31. The universal
virtual/shadow substitution uses API26-compatible FTS4 after an actual module
probe. A separate FTS5 detector runs only when `ENABLE_FTS5` is reported and the
module probe succeeds. Missing generated-column, FTS4, or FTS5 support is
reported by name as N/A rather than passed or fatal. A catalog-only unit fixture
still proves virtual-table records with root page `0` are rejected when no
virtual-table module is available.

The all-path validator and corpus verifier execute only paths supported by the
connection. Corpus diagnostics are path-neutral, and every supported path
rejects hidden/generated columns, virtual or shadow substitutions, extra
constraints/defaults/types/order, foreign keys, and unexpected tables, indexes,
views, or triggers. Only the literal `sqlite_` prefix is internal, and the
canonical schema explicitly permits only SQLite's AUTOINCREMENT-owned
`sqlite_sequence`. User objects named `sqliteX...` remain visible and fail.

## Locale-sensitive timestamps

Legacy `Config.TimestampFormat` constructs `SimpleDateFormat("yyyyMMddHHmmss")`
without a locale, so Android uses the device's default format locale for both
decimal digits **and calendar**. The probe must run with pinned Eclipse Temurin
`17.0.20+8`; it sorts all available locales, hashes every locale observation,
and compares the result byte-for-byte with the committed matrix:

```bash
"$JAVA_HOME/bin/java" tools/legacy-fixtures/LocaleTimestampProbe.java
```

The pinned runtime exposes these unique decimal zero digits:

```text
U+0030 U+0660 U+06F0 U+0966 U+09E6 U+0E50 U+0F20 U+1040 U+1C50
```

`formatter_digit_blocks` commits one activity and point for every block:
ASCII, Arabic-Indic, Extended Arabic-Indic, Devanagari, Bengali, Thai, Tibetan,
Myanmar, and Ol Chiki. It does not add arbitrary Unicode blocks the pinned
formatter matrix cannot emit. The omission detector repeatedly accepts eight
blocks and rejects the ninth, proving that omitting any represented block fails.
The matrix also records the available Japanese-calendar signature, whose ASCII
output is not 14 digits and therefore remains invalid under the legacy contract.

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
that Buddhist `๒๕๖๗...` and Gregorian `๒๐๒๔...` Thai-digit values map to 2024
only because their calendars are known. It also stores identical ASCII
`2567...` source text under evidenced Buddhist and Gregorian calendars: one
maps to 2024, while the valid Gregorian year remains 2567. The reference oracle
uses the pinned calendar observations, not a year threshold or a fixed
subtraction. `localized_timestamps` models current `ar_EG` metadata plus
durably recorded historical `bn-BD` and `en-US` evidence.

`calendar_ambiguous` has current Thai metadata but no durable per-activity
calendar evidence. Its two activities—one Thai-digit and one ASCII-digit
Buddhist-year value—are quarantined as one migration unit. The oracle reads only
to classify and preserve raw rows, performs zero target writes, writes no
receipt, and requires the original database/text to remain recoverable. A future
user-assisted flow may persist verified per-activity calendar evidence and
rerun migration; it must never guess an era offset.

`calendar_mixed_evidence` combines one reliably evidenced activity with one
ambiguous activity. The approved database/migration-unit policy quarantines
both, preserves diagnostics and raw rows for each, performs zero target writes,
and writes no receipt. A detector constructs the unsafe row-scoped candidate
that migrates the evidenced row and quarantines only its ambiguous peer; it
must fail.

After a calendar is evidenced, validation maps Unicode `Nd` digits to ASCII only
in a temporary parse buffer, requires exactly 14 digits from one numbering
system, and applies strict fields in that calendar. Mixed blocks, separators,
bidi/format controls, non-`Nd` lookalikes, impossible dates, and invalid times
remain rejected.

Detectors prove that Gregorian-only parsing, digit-shape inference,
year-magnitude/fixed-offset conversion, row-scoped mixed-evidence migration,
ASCII-only/finite-block parsing, current-metadata coupling, mixed-block
acceptance, and format-control stripping all fail.

## Deterministic regeneration

All 22 SQLite artifacts are marked `exact_bytes_required: false`; none is a
host-generated byte-for-byte contract. The 16 standard databases compare
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

The 20 expected JSON files, `manifest.json`, and the pinned formatter TSV are
the 22 true exact-byte artifacts. `write_json()` emits UTF-8 bytes with LF and
one terminal newline on every host, while `.gitattributes` enforces LF for
fixture text. Determinism tests prove CRLF drift fails and harmless
writer-version-only variation passes while illegal header semantics, page,
schema, payload, WAL, formatter-matrix, and corruption changes fail.

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
