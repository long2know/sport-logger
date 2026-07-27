# Legacy SQLite fixtures

This directory contains only deterministic synthetic data. No fixture is
copied from a user, device, backup, or production database.

## Commands

From the repository root:

```bash
# Recreate fixtures/, expected/, and manifest.json.
python3 tools/legacy-fixtures/legacy_fixtures.py generate

# Verify schema, rows, logical checksums, expected outputs, idempotency, and
# all nine fault-injection detectors.
python3 tools/legacy-fixtures/legacy_fixtures.py verify

# Run the standard-library unit tests.
python3 -m unittest discover -s tools/legacy-fixtures -p 'test_*.py' -v
```

The tool uses only Python's standard-library `sqlite3`, `json`, `hashlib`,
`uuid`, and test modules. Android, Gradle, Room, and third-party packages are
not required.

## Layout

- `fixtures/*.db` — nine SQLite inputs using Android's platform metadata table
  plus the source-reachable application schema state.
- `expected/*.json` — canonical test outputs. These classify every source row
  as a session, attached point, orphan, or rejected row, and distinguish valid
  empty data from blocked missing/partial schema.
- `manifest.json` — expected counts, timestamp ranges, representative values,
  idempotency results, and logical checksums.
- `test_legacy_fixtures.py` — regression and fault-injection tests.

The JSON output is a test interchange format, not a production Room schema.
Integer comparison is exact above `2^53`; epsilon comparison applies only to
JSON floating-point values. The idempotency oracle counts every attempted,
inserted, and prevented duplicate insert, computes final duplicate rows, and
requires exact final-state equality.
To validate an ETL test export, emit matching files named `<fixture>.json` and
run:

```bash
python3 tools/legacy-fixtures/legacy_fixtures.py verify \
  --candidate-dir path/to/candidate-json
```

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
