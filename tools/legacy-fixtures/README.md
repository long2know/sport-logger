# Legacy SQLite fixtures

This directory contains only deterministic synthetic data. No fixture is
copied from a user, device, backup, or production database.

## Commands

From the repository root:

```bash
# Validate the exact Android instrumentation source and API26/API36 evidence.
python3 tools/legacy-fixtures/legacy_fixtures.py verify-formatter-evidence

# Recreate fixtures/, expected/, and manifest.json.
python3 tools/legacy-fixtures/legacy_fixtures.py generate

# Verify schema, rows, logical checksums, expected outputs, idempotency, and
# all 56 declared defect detectors. Version- or build-specific detectors that
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

The fixture tool uses only Python's standard-library `sqlite3`, `json`,
`hashlib`, `uuid`, and test modules. Android is required only to reproduce the
committed formatter evidence; normal generation and verification consume the
sanitized TSVs and bundle no SQLite engine.

Current counted reality is 18 fixtures and 42 regeneration artifacts: 22
exact-byte UTF-8/LF text artifacts, 14 logical standard databases, and 6
canonical non-standard SQLite artifacts. The verifier defines 56 defect
detectors and reports separate applicable and not-applicable counts for the
connected SQLite runtime. The standard-library suite contains 24 tests.

## Layout

- `fixtures/*.db` — 18 SQLite inputs using Android's platform metadata table
  plus the source-reachable application schema state.
- `fixtures/active_wal_snapshot.db-{wal,shm}` — the required sidecars for the
  active-WAL case. Its committed rows exist only in the WAL snapshot.
- `expected/*.json` — canonical test outputs. These classify every source row
  as a session, attached point, orphan, or rejected row, and distinguish valid
  empty data from blocked migration.
- `manifest.json` — expected counts, timestamp ranges, representative values,
  idempotency results, storage-comparison modes, and logical checksums.
- `AndroidLocaleTimestampProbe.api26.tsv` and `.api36.tsv` — sanitized,
  byte-pinned Android formatter observations for API 26 / Android 8.0.0 and
  API 36 / Android 16.
- `test_legacy_fixtures.py` — regression and fault-injection tests.
- `AndroidLocaleTimestampProbeTest.java` — exact instrumentation source that
  records fixed instants, calendar class/type, numbering output, default
  constructor behavior, and strict parse round-trips.

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
| 3.18.2 / API26-style FTS4, no FTS5 | 9 | 2 | 54 / 2 |
| 3.26.0 / FTS4, no FTS5 | 9 | 4 | 54 / 2 |
| 3.32.0 / FTS4 + FTS5 | 11 | 4 | 56 / 0 |
| 3.33+ / FTS4 + FTS5 | 11 | 6 | 56 / 0 |

Those are capability-profile expectations, not assumptions about arbitrary
desktop builds. For example, a 3.32 build without FTS5 reports that one
detector as N/A and derives the lower executable case count automatically.

Generated-column SQL is never parsed below SQLite 3.31. The universal
virtual/shadow substitution uses API26-compatible FTS4 after an actual module
probe. A separate FTS5 detector uses compile options only when diagnostics are
present and complete. Empty/missing diagnostics or
`OMIT_COMPILEOPTION_DIAGS` mean **unknown**, so the harness safely creates and
drops a uniquely named temporary FTS5 table to determine support. A known
diagnostic set without `ENABLE_FTS5` may short-circuit as unsupported. Missing
generated-column, FTS4, or FTS5 support is reported by name as N/A rather than
passed or fatal, and every probe verifies that no temporary main or shadow
object remains. A catalog-only unit fixture still proves virtual-table records
with root page `0` are rejected when no virtual-table module is available.

The all-path validator and corpus verifier execute only paths supported by the
connection. Corpus diagnostics are path-neutral, and every supported path
rejects hidden/generated columns, virtual or shadow substitutions, extra
constraints/defaults/types/order, foreign keys, and unexpected tables, indexes,
views, or triggers. Only the literal `sqlite_` prefix is internal, and the
canonical schema explicitly permits only SQLite's AUTOINCREMENT-owned
`sqlite_sequence`. User objects named `sqliteX...` remain visible and fail.

## Locale-sensitive timestamps

Legacy `Config.TimestampFormat` constructs `SimpleDateFormat("yyyyMMddHHmmss")`
without a locale, so Android uses the device's default `FORMAT` locale. The
oracle is grounded in Android rather than desktop Java:

```bash
python3 tools/legacy-fixtures/legacy_fixtures.py verify-formatter-evidence
```

`AndroidLocaleTimestampProbeTest.java` enumerates every available locale and
uses Android
`SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", locale)` plus the legacy
`yyyyMMddHHmmss` pattern at four fixed UTC instants (years 2000, 2024, 2032,
and 2567). It records calendar class/type, localized decimal output,
`1234567890`, the default-constructor result, and strict parse round-trip epoch.
The committed TSVs were reproduced byte-for-byte on the software-emulated AVDs
`SportLoggerPhoneApi26` and `SportLoggerPhoneApi36`, started sequentially with
`-accel off`; their SHA-256 values are pinned in the generator. The files contain
no serial, AVD name, host path, username, or wall-clock value.

To reproduce, copy the probe source unchanged into a disposable AndroidX
instrumentation app, start one AVD at a time with:

```bash
ANDROID_AVD_HOME=/data/android-avd/sport-logger \
  /data/android-sdk/emulator/emulator \
  -avd SportLoggerPhoneApi26 -no-window -no-audio -no-boot-anim \
  -no-snapshot-load -no-snapshot-save -accel off

# Run only AndroidLocaleTimestampProbeTest, then preserve the exact bytes:
adb -s <serial> exec-out run-as <target-package> \
  cat files/android-locale-timestamp-probe.tsv \
  > tools/legacy-fixtures/AndroidLocaleTimestampProbe.api26.tsv
```

Repeat with `SportLoggerPhoneApi36` and the `.api36.tsv` destination, stop the
first emulator before starting the second, then run the formatter-evidence
gate. Disposable apps, APKs, keystores, and build output are not retained.

API 26 exposes seven available-locale digit blocks; API 36 adds N'Ko and Ol
Chiki; explicit Thai numbering controls add Thai digits on both. The conservative
union is:

```text
U+0030 U+0660 U+06F0 U+07C0 U+0966
U+09E6 U+0E50 U+0F20 U+1040 U+1C50
```

`formatter_digit_blocks` commits one activity and point for every union member:
ASCII, Arabic-Indic, Extended Arabic-Indic, N'Ko, Devanagari, Bengali, Thai,
Tibetan, Myanmar, and Ol Chiki. Arbitrary Unicode `Nd` blocks such as fullwidth
digits remain unsupported because neither probed Android platform emits them.
The omission detector removes each represented block in turn and requires every
candidate to fail.

Every API26/API36 available-locale signature and every Thai control—including
locale tags requesting `ca-buddhist`—uses
`java.util.GregorianCalendar` / `gregory`. Android ignores those calendar
extensions for this `java.text` formatter. There is therefore no source-reachable
Buddhist ambiguity, era conversion, or database-wide calendar quarantine in the
oracle. `android_thai_gregorian` covers ordinary and Thai digits across years
2000, 2024, 2032, and 2567; Gregorian 2567 remains 2567.

Android documents the locale-sensitive constructor/default-symbol contract.
See
[`SimpleDateFormat(String)`](https://developer.android.com/reference/java/text/SimpleDateFormat#SimpleDateFormat(java.lang.String))
and
[`DecimalFormatSymbols.getZeroDigit()`](https://developer.android.com/reference/java/text/DecimalFormatSymbols#getZeroDigit())
contracts. Current `android_metadata` can change on reopen and is not used to
select a row's digit block. Validation preserves source text, maps only the
finite Android-emittable block to ASCII in a temporary buffer, requires exactly
14 digits from one block, and applies strict Gregorian fields. Mixed blocks,
separators, bidi/format controls, non-`Nd` lookalikes, impossible dates, invalid
times, and unsupported `Nd` blocks remain rejected.

Candidate detectors prove desktop-JDK Buddhist assumptions, year
magnitude/fixed-offset conversion, a one-year lookup, omission of any Android
block, current-metadata coupling, mixed-block acceptance, unsupported-block
acceptance, and format-control stripping all fail.

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

The 18 expected JSON files, `manifest.json`, the probe source, and both Android
evidence TSVs are the 22 true exact-byte artifacts. `write_json()` emits UTF-8
bytes with LF and one terminal newline on every host, while `.gitattributes`
enforces LF for fixture text. Determinism tests prove CRLF drift fails and
harmless writer-version-only variation passes while illegal header semantics,
page, schema, payload, WAL, formatter evidence, and corruption changes fail.

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
