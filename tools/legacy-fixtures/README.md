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
# all 60 declared defect detectors. Version- or build-specific detectors that
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

Current counted reality is 18 fixtures and 43 regeneration artifacts: 23
exact-byte UTF-8/LF text artifacts, 14 logical standard databases, and 6
canonical non-standard SQLite artifacts. The verifier defines 60 defect
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
- `run_android_formatter_probe.py` — byte-pinned, repository-relative builder
  and sequential software-emulator runner for regenerating or comparing both
  evidence files.

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
| 3.18.2 / API26-style FTS4, no FTS5 | 9 | 2 | 58 / 2 |
| 3.26.0 / FTS4, no FTS5 | 9 | 4 | 58 / 2 |
| 3.32.0 / FTS4 + FTS5 | 11 | 4 | 60 / 0 |
| 3.33+ / FTS4 + FTS5 | 11 | 6 | 60 / 0 |

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

`AndroidLocaleTimestampProbeTest.java` enumerates every available locale and the
complete sorted result of
`android.icu.text.NumberingSystem.getAvailableNames()`. Every numbering
candidate is probed in its own instrumentation process, with one retry for an
otherwise empty infrastructure result. APK installation also gets one bounded
retry. An API26 native ICU crash or a transient software-emulator operation
therefore cannot silently truncate the matrix. The test uses Android
`SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", locale)` plus the legacy
`yyyyMMddHHmmss` pattern at fixed UTC instants in years 2000, 2024, 2032, and
2567. It records the candidate definition, calendar class/type, localized
decimal output, `1234567890`, default-constructor behavior, and strict
full-consumption parse round-trip epoch.

Pinned provenance:

| API | System image package / revision | Build fingerprint | ABI | ICU / Unicode / CLDR |
|---|---|---|---|---|
| 26 | `system-images;android-26;google_apis;x86_64` / `16.0.0` | `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys` | `x86_64` | `58.2.0.0` / `9.0.0.0` / `30.0.3.0` |
| 36 | `system-images;android-36;google_apis;x86_64` / `7.0.0` | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` | `x86_64` | `76.1.0.0` / `16.0.0.0` / `46.0.0.0` |

Both runs use emulator package revision `36.6.11` (reported version
`36.6.11.0`), compile SDK `platforms;android-36` revision `2.0.0`, build tools
`36.0.0`, UTC, and software emulation. The TSV metadata also pins supported
ABIs, Java VM/runtime versions, patterns, fixed instants, candidate-set count
and hash, available-locale count and hash, and the exact repository command.

`run_android_formatter_probe.py` is the byte-pinned command sequence. It
verifies every SDK revision, requires exactly one AVD for each pinned image,
builds and signs disposable target/test APKs without Gradle, boots API26 then
API36 sequentially with `-read-only -accel off`, installs and runs the probe,
compares exact TSV bytes, uninstalls the apps, stops each emulator, and removes
all generated build material. No AVD name, serial, host path, wall-clock capture
time, or device credential is written to the TSVs, and no APK, keystore,
userdata, or emulator log is retained.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_AVD_HOME=/path/to/avd-home
export JAVA_HOME=/path/to/jdk-17-or-newer

# Rebuild/install/run and fail if either committed TSV differs.
python3 tools/legacy-fixtures/run_android_formatter_probe.py --compare

# Maintainer-only regeneration; verify and review the resulting bytes.
python3 tools/legacy-fixtures/run_android_formatter_probe.py --update
python3 tools/legacy-fixtures/legacy_fixtures.py verify-formatter-evidence
```

API26 reports 77 candidates: 37 successful observations and 40 isolated
failures, including 18 native process crashes. API36 reports 96 candidates: 76
successful observations and 20 caught failures. Both platforms actually emit
the same conservative union of 37 contiguous decimal blocks:

```text
U+0030 U+0660 U+06F0 U+07C0 U+0966 U+09E6 U+0A66 U+0AE6 U+0B66 U+0BE6
U+0C66 U+0CE6 U+0D66 U+0DE6 U+0E50 U+0ED0 U+0F20 U+1040 U+1090 U+17E0
U+1810 U+1946 U+19D0 U+1A80 U+1A90 U+1B50 U+1BB0 U+1C40 U+1C50 U+A620
U+A8D0 U+A900 U+A9D0 U+A9F0 U+AA50 U+ABF0 U+FF10
```

This includes fullwidth digits. API36 defines 39 supplementary-plane decimal
blocks, including Osmanya, but this `java.text` formatter falls back to ASCII
for them; no supplementary block is source-emitted on either pinned platform.
The parser is nevertheless code-point-aware and its synthetic detector proves
that a future evidenced supplementary block is handled without splitting
surrogate pairs. Digit values come from evidenced contiguous ranges rather than
the host Python Unicode database, so Unicode 16 definitions remain testable on
a Unicode 15 host. `formatter_digit_blocks` commits one activity and point for
each emitted block. A separate Osmanya row proves that a defined-but-not-emitted
block remains unsupported.

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
select a row's digit block. Validation preserves source text, maps only one
known contiguous source-emitted block to ASCII in a temporary buffer, requires
exactly 14 digit code points, permits only source-proven format-control layouts
(none are emitted by the pinned matrix), and applies strict Gregorian fields.
Mixed blocks, separators, unproven bidi/format controls, digit-like non-decimal
characters, impossible dates, invalid times, and unsupported `Nd` blocks remain
rejected.

Candidate detectors prove desktop-JDK Buddhist assumptions, year
magnitude/fixed-offset conversion, a one-year lookup, the old hardcoded
10-block oracle, fullwidth omission, UTF-16-character/BMP-only candidate
handling, supplementary-definition omission, omission of any emitted Android
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

The 18 expected JSON files, `manifest.json`, the probe source, runner, and both
Android evidence TSVs are the 23 true exact-byte artifacts. `write_json()` emits
UTF-8 bytes with LF and one terminal newline on every host, while
`.gitattributes` enforces LF for fixture text. Determinism tests prove CRLF drift
fails and harmless writer-version-only variation passes while illegal header
semantics, page, schema, payload, WAL, formatter evidence, and corruption
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
