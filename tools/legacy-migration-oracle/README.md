# Legacy migration oracle

This standalone oracle is the smallest executable model of the legacy migration boundary. It is
not a production Room migration, canonical activity contract, protobuf schema, or general ETL
framework.

The oracle consumes two independent trust roots:

1. the committed API 26/API 36 evidence under
   [`../android-formatter-evidence/`](../android-formatter-evidence/); and
2. a passing real-engine report plus verified source archives from
   [`../sqlite-compat/`](../sqlite-compat/).

It never imports Python's host `sqlite3` module. Every source database is created and read with a
fresh exact SQLite binary built by the merged harness from a pinned official archive.

## Acceptance run

Run from the repository root:

```bash
python3 tools/android-formatter-evidence/validate_evidence.py
python3 -m unittest discover \
  -s tools/android-formatter-evidence -p 'test_*.py' -v
python3 tools/android-formatter-evidence/run_probe.py --validate-only

python3 -m unittest discover \
  -s tools/sqlite-compat/tests -p 'test_*.py' -v
python3 -m unittest discover \
  -s tools/legacy-migration-oracle/tests -p 'test_*.py' -v

rm -rf \
  tools/sqlite-compat/.cache \
  tools/sqlite-compat/.work \
  tools/sqlite-compat/out \
  tools/legacy-migration-oracle/.work \
  tools/legacy-migration-oracle/out
python3 tools/sqlite-compat/sqlite_compat.py run
python3 tools/sqlite-compat/sqlite_compat.py run --offline

python3 tools/legacy-migration-oracle/oracle.py validate
python3 tools/legacy-migration-oracle/oracle.py run --offline
```

The cold run downloads only the four pinned official SQLite archives. The offline run re-verifies
those archive bytes and rebuilds all engines without a request. The oracle then rebuilds fresh exact
engines from that verified cache. The default and acceptance large gate is exactly 100,000 points;
the CLI rejects a smaller count.

The final disposable reports are:

- `tools/sqlite-compat/out/report.json`
- `tools/sqlite-compat/out/report.txt`
- `tools/legacy-migration-oracle/out/report.json`
- `tools/legacy-migration-oracle/out/report.txt`

All output and work directories are ignored. Reports contain no host path, serial, wall-clock
timestamp, credential, database, or source row.

## Android evidence regeneration

The committed formatter evidence must be reproduced separately with the exact pinned SDK, JDK, and
software-emulated images:

```bash
python3 tools/android-formatter-evidence/run_probe.py \
  --compare \
  --repeat 2 \
  --sdk-root /path/to/pinned/android-sdk \
  --java-home /path/to/temurin-17.0.20
```

That runner rebuilds the probe and boots API 26 then API 36 sequentially with `-accel off`. It
byte-compares two complete sanitized regenerations and the committed evidence.

## Source and timestamp contract

The source schema is the `ACTIVITY` and `GPS_POINTS` schema loaded directly from
`tools/sqlite-compat/legacy_schema.sql`, which is derived from `SqlLogger.java`. Before reading rows,
the oracle verifies the exact column order, declared types, nullability, and primary keys through
the runtime-compatible `sqlite_master` and `table_info` path.

The legacy writer contract is pinned to:

```java
new SimpleDateFormat("yyyyMMddHHmmss")
```

The parser:

- iterates Unicode scalar values rather than UTF-16 code units;
- accepts only complete decimal definitions verified by the Android candidate evidence;
- covers all 37 blocks actually emitted by `java.text.SimpleDateFormat`;
- also handles the verified supplementary-plane definitions that API 36 advertises but formats as
  strict ASCII fallback, without claiming those blocks were emitted;
- requires all 14 digits to come from one verified block;
- performs a strict Gregorian UTC date construction; and
- preserves the exact original localized string beside the normalized UTC value.

`GMTEND` may be `NULL`; activity starts and point timestamps may not.

## Deterministic quarantine gates

Every detector has one hashed scenario file under `scenarios/`. `scenario-manifest.json` must match
the exact disk file count and SHA-256 of every scenario. The acceptance run creates a real flawed
SQLite candidate for each detector, executes it twice, and requires the same outcome:

- unknown ten-code-point private-use digit block (standing in for an unverified future/vendor block);
- mixed verified decimal blocks;
- invalid Unicode control;
- invalid non-decimal character;
- invalid code-point length;
- invalid calendar date;
- missing required timestamp;
- schema drift;
- corrupt SQLite bytes;
- missing migration receipt;
- manifest count mismatch;
- manifest hash mismatch; and
- source mutation.

Input defects produce canonical quarantine records containing the original value and its code-point
sequence. Source/schema/output-integrity defects quarantine the candidate as a whole. No detector is
credited from a scenario name, expected set subtraction, simulated SQLite version, or mocked engine.

## Output consumer contract

Each successful candidate produces four canonical UTF-8 NDJSON files:

- `activities.ndjson`
- `points.ndjson`
- `quarantine.ndjson`
- `receipts.ndjson`

`manifest.json` records the exact row count and SHA-256 of every file. A receipt is required for
every source `ACTIVITY` and `GPS_POINTS` row, including quarantined rows. The source database and
all existing journal/WAL sidecars must have identical hashes before and after the read.

The full gate runs the all-verified-decimal-block baseline on SQLite 3.18.2, 3.26.0, 3.32.0, and 3.33.0.
The oldest exact engine independently runs every flawed candidate twice and migrates the same
100,000-point source twice with byte-identical manifests and exact `1 / 100000 / 0 / 100001`
activity, point, quarantine, and receipt counts.

The report preserves the distinction between Android formatter behavior and the API-26-era SQLite
compatibility path:

- Android API 36 evidence must retain every observed ASCII numbering fallback, including all
  supplementary-plane decimal definitions; and
- SQLite 3.18.2 must select `sqlite_master` and `table_info` in the exact-harness report, proving
  the API-26-compatible schema fallback.
