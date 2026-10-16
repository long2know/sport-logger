#!/usr/bin/env python3
"""Execute the legacy migration oracle with verified Android and exact-SQLite evidence."""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import os
import shutil
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Iterator, Mapping, Sequence


TOOL_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOL_DIR.parents[1]
FORMATTER_DIR = REPOSITORY_ROOT / "tools" / "android-formatter-evidence"
SQLITE_DIR = REPOSITORY_ROOT / "tools" / "sqlite-compat"
SCENARIO_DIR = TOOL_DIR / "scenarios"
SCENARIO_MANIFEST_PATH = TOOL_DIR / "scenario-manifest.json"
DEFAULT_CACHE_DIR = SQLITE_DIR / ".cache"
DEFAULT_EXACT_REPORT = SQLITE_DIR / "out" / "report.json"
DEFAULT_WORK_DIR = TOOL_DIR / ".work"
DEFAULT_OUTPUT_DIR = TOOL_DIR / "out"

sys.path.insert(0, str(FORMATTER_DIR))
sys.path.insert(0, str(SQLITE_DIR))

import evidence_lib as formatter_evidence  # noqa: E402
import sqlite_compat as sqlite_harness  # noqa: E402


ORACLE_REPORT_SCHEMA = "sport-logger.legacy-migration-oracle/v1"
OUTPUT_MANIFEST_SCHEMA = "sport-logger.legacy-migration-output/v1"
SCENARIO_MANIFEST_SCHEMA = "sport-logger.legacy-migration-scenarios/v1"
OUTPUT_DATA_FILES = (
    "activities.ndjson",
    "points.ndjson",
    "quarantine.ndjson",
    "receipts.ndjson",
)
DETECTOR_CODES = frozenset(
    {
        "INVALID_CHARACTER",
        "INVALID_CONTROL",
        "INVALID_DATE",
        "INVALID_LENGTH",
        "MANIFEST_COUNT_MISMATCH",
        "MANIFEST_HASH_MISMATCH",
        "MISSING_TIMESTAMP",
        "MIXED_DIGIT_BLOCKS",
        "RECEIPT_GAP",
        "SCHEMA_DRIFT",
        "SOURCE_CORRUPT",
        "SOURCE_MUTATED",
        "UNKNOWN_DIGIT_BLOCK",
    }
)
EXPECTED_ACTIVITY_SCHEMA = (
    ("ID", "INTEGER", 1, 1),
    ("GMTSTART", "VARCHAR", 0, 0),
    ("GMTEND", "VARCHAR", 0, 0),
    ("NAME", "VARCHAR", 0, 0),
    ("DESCRIPTION", "VARCHAR", 0, 0),
    ("DISTANCE", "REAL", 0, 0),
    ("TIME", "REAL", 0, 0),
    ("PACE", "REAL", 0, 0),
)
EXPECTED_POINT_SCHEMA = (
    ("ID", "INTEGER", 1, 1),
    ("ACTIVITYID", "INTEGER", 0, 0),
    ("GMTTIMESTAMP", "VARCHAR", 0, 0),
    ("LATITUDE", "REAL", 0, 0),
    ("LONGITUDE", "REAL", 0, 0),
    ("ALTITUDE", "REAL", 0, 0),
    ("ACCURACY", "REAL", 0, 0),
    ("SPEED", "REAL", 0, 0),
    ("BEARING", "REAL", 0, 0),
    ("HEARTRATE", "REAL", 0, 0),
)
CORRUPTION_MARKERS = (
    "is not a database",
    "database disk image is malformed",
    "file is encrypted or is not a database",
    "malformed",
)


class OracleError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclasses.dataclass(frozen=True)
class DigitBlock:
    zero_code_point: int
    code_points: tuple[int, ...]
    names: tuple[str, ...]
    api_levels: tuple[int, ...]
    emitted_api_levels: tuple[int, ...]
    fallback_api_levels: tuple[int, ...]


@dataclasses.dataclass(frozen=True)
class DigitCatalog:
    blocks: tuple[DigitBlock, ...]
    digit_map: Mapping[int, tuple[int, int]]
    formatter_fallback_names: tuple[str, ...]
    evidence_hashes: Mapping[str, str]


@dataclasses.dataclass(frozen=True)
class ParsedTimestamp:
    canonical_utc: str
    zero_code_point: int


@dataclasses.dataclass(frozen=True)
class Scenario:
    scenario_id: str
    mutation: str
    expected_status: str
    expected_code: str | None


@dataclasses.dataclass(frozen=True)
class ActivityRow:
    identifier: int
    start: str | None
    end: str | None
    name: str | None
    description: str | None
    distance: str
    duration: str
    pace: str


@dataclasses.dataclass(frozen=True)
class PointRow:
    identifier: int
    activity_id: str
    timestamp: str | None
    latitude: str
    longitude: str
    altitude: str
    accuracy: str
    speed: str
    bearing: str
    heart_rate: str


class CanonicalNdjsonWriter:
    def __init__(self, path: Path):
        self.path = path
        self.partial = path.with_name(path.name + ".partial")
        self.stream = self.partial.open("wb")
        self.digest = hashlib.sha256()
        self.records = 0

    def write(self, value: Mapping[str, object]) -> str:
        record = canonical_record_bytes(value)
        payload = record + b"\n"
        self.stream.write(payload)
        self.digest.update(payload)
        self.records += 1
        return hashlib.sha256(record).hexdigest()

    def close(self) -> dict[str, object]:
        self.stream.flush()
        os.fsync(self.stream.fileno())
        self.stream.close()
        self.partial.replace(self.path)
        return {
            "name": self.path.name,
            "records": self.records,
            "sha256": self.digest.hexdigest(),
        }

    def abort(self) -> None:
        if not self.stream.closed:
            self.stream.close()
        self.partial.unlink(missing_ok=True)


def canonical_record_bytes(value: Mapping[str, object]) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".partial")
    partial.write_bytes(formatter_evidence.canonical_json_bytes(value))
    with partial.open("rb") as stream:
        os.fsync(stream.fileno())
    partial.replace(path)


def atomic_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".partial")
    partial.write_text(value, encoding="utf-8", newline="\n")
    with partial.open("rb") as stream:
        os.fsync(stream.fileno())
    partial.replace(path)


def reset_directory(path: Path) -> None:
    resolved = path.resolve()
    root = TOOL_DIR.resolve()
    if resolved == root or root not in resolved.parents:
        raise OracleError("UNSAFE_WORK_DIRECTORY", f"refusing to reset {path}")
    if path.exists() or path.is_symlink():
        if path.is_symlink() or not path.is_dir():
            raise OracleError("UNSAFE_WORK_DIRECTORY", f"{path} is not a real directory")
        shutil.rmtree(path)
    path.mkdir(parents=True, mode=0o700)


def load_digit_catalog() -> tuple[DigitCatalog, dict[str, object]]:
    validated = formatter_evidence.validate_all(FORMATTER_DIR)
    evidence_by_api = validated["evidence"]
    block_names: dict[tuple[int, ...], set[str]] = {}
    block_apis: dict[tuple[int, ...], set[int]] = {}
    emitted_apis: dict[tuple[int, ...], set[int]] = {}
    fallback_apis: dict[tuple[int, ...], set[int]] = {}
    digit_map: dict[int, tuple[int, int]] = {}

    for api_level in formatter_evidence.API_LEVELS:
        evidence = evidence_by_api[api_level]
        for row in evidence.candidates:
            if (
                row["candidate_kind"] != "decimal"
                or row["category"]
                not in {"supported_decimal_output", "unsupported_fallback"}
            ):
                continue
            points = formatter_evidence.parse_code_points_strict(
                row["description_code_points"]
            )
            if len(points) != 10:
                raise OracleError(
                    "ANDROID_EVIDENCE_INVALID",
                    f"{row['candidate_name']} does not define ten decimal digits",
                )
            block_names.setdefault(points, set()).add(row["candidate_name"])
            block_apis.setdefault(points, set()).add(api_level)
            if row["category"] == "supported_decimal_output":
                emitted_apis.setdefault(points, set()).add(api_level)
            else:
                fallback_apis.setdefault(points, set()).add(api_level)
            for value, code_point in enumerate(points):
                existing = digit_map.get(code_point)
                definition = (value, points[0])
                if existing is not None and existing != definition:
                    raise OracleError(
                        "ANDROID_EVIDENCE_INVALID",
                        f"U+{code_point:04X} has conflicting decimal definitions",
                    )
                digit_map[code_point] = definition

    blocks = tuple(
        DigitBlock(
            zero_code_point=points[0],
            code_points=points,
            names=tuple(sorted(block_names[points])),
            api_levels=tuple(sorted(block_apis[points])),
            emitted_api_levels=tuple(sorted(emitted_apis.get(points, set()))),
            fallback_api_levels=tuple(sorted(fallback_apis.get(points, set()))),
        )
        for points in sorted(block_names, key=lambda item: item[0])
    )
    emitted_zeros = {
        block.zero_code_point for block in blocks if block.emitted_api_levels
    }
    if 0x0030 not in emitted_zeros:
        raise OracleError("ANDROID_EVIDENCE_INVALID", "ASCII decimal output is absent")
    if 0xFF10 not in emitted_zeros:
        raise OracleError("ANDROID_EVIDENCE_INVALID", "fullwidth decimal output is absent")
    if not any(block.zero_code_point > 0xFFFF for block in blocks):
        raise OracleError(
            "ANDROID_EVIDENCE_INVALID",
            "no supplementary-plane decimal definition was captured",
        )

    fallback_names = tuple(
        sorted(
            {
                row["candidate_name"]
                for evidence in evidence_by_api.values()
                for row in evidence.candidates
                if row["category"] == "unsupported_fallback"
                and row["fallback"] == "true"
                and row["observed_output"] == "ascii_fallback"
            }
        )
    )
    if not fallback_names:
        raise OracleError(
            "ANDROID_EVIDENCE_INVALID",
            "Android evidence contains no observed ASCII numbering fallback",
        )

    evidence_hashes = {
        path.name: sha256_file(path)
        for path in sorted((FORMATTER_DIR / "evidence").iterdir())
        if path.is_file()
    }
    return (
        DigitCatalog(
            blocks=blocks,
            digit_map=digit_map,
            formatter_fallback_names=fallback_names,
            evidence_hashes=evidence_hashes,
        ),
        validated,
    )


def parse_localized_timestamp(value: str | None, catalog: DigitCatalog) -> ParsedTimestamp:
    if value is None:
        raise OracleError("MISSING_TIMESTAMP", "required legacy timestamp is NULL")
    for character in value:
        if unicodedata.category(character) in {"Cc", "Cf", "Cs"}:
            raise OracleError(
                "INVALID_CONTROL",
                "legacy timestamp contains a Unicode control character",
            )
    if len(value) != 14:
        raise OracleError(
            "INVALID_LENGTH",
            f"legacy timestamp has {len(value)} code points instead of 14",
        )

    ascii_digits: list[str] = []
    zero_code_points: set[int] = set()
    for character in value:
        code_point = ord(character)
        definition = catalog.digit_map.get(code_point)
        if definition is None:
            if (
                unicodedata.decimal(character, None) is not None
                or unicodedata.category(character) == "Co"
            ):
                raise OracleError(
                    "UNKNOWN_DIGIT_BLOCK",
                    f"U+{code_point:04X} belongs to an unverified digit block",
                )
            raise OracleError(
                "INVALID_CHARACTER",
                f"U+{code_point:04X} is not an accepted decimal digit",
            )
        digit, zero_code_point = definition
        ascii_digits.append(str(digit))
        zero_code_points.add(zero_code_point)
    if len(zero_code_points) != 1:
        raise OracleError(
            "MIXED_DIGIT_BLOCKS",
            "legacy timestamp mixes Android-emitted decimal blocks",
        )

    normalized = "".join(ascii_digits)
    try:
        parsed = datetime(
            int(normalized[0:4]),
            int(normalized[4:6]),
            int(normalized[6:8]),
            int(normalized[8:10]),
            int(normalized[10:12]),
            int(normalized[12:14]),
            tzinfo=timezone.utc,
        )
    except ValueError as error:
        raise OracleError("INVALID_DATE", "legacy timestamp is not a valid UTC date") from error
    return ParsedTimestamp(
        canonical_utc=parsed.strftime("%Y-%m-%dT%H:%M:%S.000Z"),
        zero_code_point=next(iter(zero_code_points)),
    )


def encode_digits(ascii_digits: str, block: DigitBlock) -> str:
    if any(character < "0" or character > "9" for character in ascii_digits):
        raise ValueError("ASCII digits required")
    return "".join(chr(block.code_points[ord(character) - ord("0")]) for character in ascii_digits)


def find_unknown_decimal_block(catalog: DigitCatalog) -> DigitBlock:
    known_zeros = {block.zero_code_point for block in catalog.blocks}
    for zero in range(0x110000 - 9):
        if zero in known_zeros or 0xD800 <= zero <= 0xDFFF:
            continue
        values = tuple(
            unicodedata.decimal(chr(zero + offset), None) for offset in range(10)
        )
        if values == tuple(range(10)):
            return DigitBlock(
                zero_code_point=zero,
                code_points=tuple(range(zero, zero + 10)),
                names=("host-unicode-unknown-to-android",),
                api_levels=(),
                emitted_api_levels=(),
                fallback_api_levels=(),
            )
    return DigitBlock(
        zero_code_point=0xE000,
        code_points=tuple(range(0xE000, 0xE00A)),
        names=("private-use-unknown-block",),
        api_levels=(),
        emitted_api_levels=(),
        fallback_api_levels=(),
    )


def read_canonical_json(path: Path) -> object:
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise OracleError("INVALID_MANIFEST", f"{path.name} is not canonical JSON") from error
    if raw != formatter_evidence.canonical_json_bytes(value):
        raise OracleError("INVALID_MANIFEST", f"{path.name} is not canonical JSON")
    return value


def load_scenarios() -> tuple[Scenario, ...]:
    manifest_value = read_canonical_json(SCENARIO_MANIFEST_PATH)
    if not isinstance(manifest_value, dict):
        raise OracleError("INVALID_MANIFEST", "scenario manifest must be an object")
    if manifest_value.get("schema") != SCENARIO_MANIFEST_SCHEMA:
        raise OracleError("INVALID_MANIFEST", "unsupported scenario manifest schema")
    entries = manifest_value.get("files")
    if not isinstance(entries, list):
        raise OracleError("INVALID_MANIFEST", "scenario manifest files must be a list")

    disk_files = sorted(path.name for path in SCENARIO_DIR.glob("*.json"))
    manifest_files = [entry.get("name") for entry in entries if isinstance(entry, dict)]
    if manifest_value.get("file_count") != len(disk_files):
        raise OracleError("INVALID_MANIFEST", "scenario manifest file count does not match disk")
    if manifest_files != disk_files:
        raise OracleError("INVALID_MANIFEST", "scenario manifest file names do not match disk")

    scenarios: list[Scenario] = []
    seen_ids: set[str] = set()
    observed_codes: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise OracleError("INVALID_MANIFEST", "scenario manifest entry is not an object")
        path = SCENARIO_DIR / str(entry["name"])
        if sha256_file(path) != entry.get("sha256"):
            raise OracleError("INVALID_MANIFEST", f"{path.name} SHA-256 does not match")
        document = read_canonical_json(path)
        if not isinstance(document, dict) or document.get("schema_version") != 1:
            raise OracleError("INVALID_MANIFEST", f"{path.name} has invalid schema")
        scenario = Scenario(
            scenario_id=str(document.get("id", "")),
            mutation=str(document.get("mutation", "")),
            expected_status=str(document.get("expected_status", "")),
            expected_code=(
                None
                if document.get("expected_code") is None
                else str(document.get("expected_code"))
            ),
        )
        if (
            not scenario.scenario_id
            or not scenario.mutation
            or scenario.expected_status not in {"PASS", "QUARANTINED"}
            or scenario.scenario_id in seen_ids
        ):
            raise OracleError("INVALID_MANIFEST", f"{path.name} has invalid scenario fields")
        if entry.get("id") != scenario.scenario_id or entry.get("expected_code") != scenario.expected_code:
            raise OracleError("INVALID_MANIFEST", f"{path.name} disagrees with its manifest entry")
        seen_ids.add(scenario.scenario_id)
        if scenario.expected_code is not None:
            observed_codes.add(scenario.expected_code)
        scenarios.append(scenario)

    declared_codes = [
        scenario.expected_code
        for scenario in scenarios
        if scenario.expected_code is not None
    ]
    if observed_codes != DETECTOR_CODES or len(declared_codes) != len(DETECTOR_CODES):
        missing = sorted(DETECTOR_CODES - observed_codes)
        extra = sorted(observed_codes - DETECTOR_CODES)
        raise OracleError(
            "INVALID_MANIFEST",
            f"detector scenario coverage mismatch; missing={missing}, extra={extra}",
        )
    if sum(scenario.expected_status == "PASS" for scenario in scenarios) != 1:
        raise OracleError("INVALID_MANIFEST", "exactly one passing baseline scenario is required")
    return tuple(scenarios)


def validate_exact_report(path: Path) -> dict[str, object]:
    report = read_canonical_json(path)
    if not isinstance(report, dict):
        raise OracleError("EXACT_REPORT_INVALID", "exact SQLite report is not an object")
    if report.get("schema") != sqlite_harness.REPORT_SCHEMA or report.get("status") != "PASS":
        raise OracleError("EXACT_REPORT_INVALID", "exact SQLite report did not pass")
    if report.get("large_point_count") != 100_000:
        raise OracleError("EXACT_REPORT_INVALID", "exact SQLite report did not use 100,000 points")

    specs = sqlite_harness.load_manifest()
    engines = report.get("engines")
    if not isinstance(engines, list) or len(engines) != len(specs):
        raise OracleError("EXACT_REPORT_INVALID", "exact SQLite report engine count mismatch")
    by_version = {
        engine.get("requested_version"): engine
        for engine in engines
        if isinstance(engine, dict)
    }
    if set(by_version) != {spec.version for spec in specs}:
        raise OracleError("EXACT_REPORT_INVALID", "exact SQLite report versions mismatch")

    for spec in specs:
        engine = by_version[spec.version]
        if (
            engine.get("status") != "PASS"
            or engine.get("observed_version") != spec.version
            or engine.get("observed_source_id") != spec.source_id
        ):
            raise OracleError(
                "EXACT_REPORT_INVALID",
                f"SQLite {spec.version} identity/status is not exact",
            )
        archive = engine.get("archive")
        build = engine.get("build")
        summary = engine.get("gate_summary")
        gates = engine.get("gates")
        if not isinstance(archive, dict) or (
            archive.get("url") != spec.archive_url
            or archive.get("sha256") != spec.archive_sha256
            or archive.get("size") != spec.archive_size
            or archive.get("verified") is not True
        ):
            raise OracleError("EXACT_REPORT_INVALID", f"SQLite {spec.version} archive proof failed")
        if not isinstance(build, dict) or (
            build.get("mode") != "fresh_from_verified_archive"
            or build.get("cache_artifacts_used") is not False
            or build.get("gate_driver_source_sha256")
            != sha256_file(sqlite_harness.GATE_DRIVER_PATH)
        ):
            raise OracleError("EXACT_REPORT_INVALID", f"SQLite {spec.version} build proof failed")
        if not isinstance(summary, dict) or summary.get("fail") != 0:
            raise OracleError("EXACT_REPORT_INVALID", f"SQLite {spec.version} has failed gates")
        if (
            not isinstance(gates, list)
            or len(gates) != len(sqlite_harness.GATE_IDS)
            or {
                gate.get("id") for gate in gates if isinstance(gate, dict)
            }
            != set(sqlite_harness.GATE_IDS)
        ):
            raise OracleError("EXACT_REPORT_INVALID", f"SQLite {spec.version} gate set mismatch")
        gate_status = {
            str(gate["id"]): str(gate["status"])
            for gate in gates
            if isinstance(gate, dict) and "id" in gate and "status" in gate
        }
        mandatory_pass = {
            "api26_schema_fallback",
            "corruption_open_failure",
            "interrupted_wal_receipt",
            "large_point_transaction_query",
            "legacy_schema",
            "readonly_reopen",
            "table_index",
            "wal_snapshot",
        }
        if any(gate_status.get(gate_id) != "PASS" for gate_id in mandatory_pass):
            raise OracleError(
                "EXACT_REPORT_INVALID",
                f"SQLite {spec.version} did not pass every mandatory runtime gate",
            )

    api26_engine = by_version["3.18.2"]
    capabilities = api26_engine.get("runtime_capabilities")
    if not isinstance(capabilities, dict):
        raise OracleError("EXACT_REPORT_INVALID", "API-26-era runtime capabilities are absent")
    catalog = capabilities.get("schema_catalog")
    columns = capabilities.get("column_metadata")
    if (
        not isinstance(catalog, dict)
        or catalog.get("selected") != "sqlite_master"
        or not isinstance(columns, dict)
        or columns.get("selected") != "table_info"
    ):
        raise OracleError(
            "EXACT_REPORT_INVALID",
            "SQLite 3.18.2 did not prove sqlite_master/table_info fallback",
        )
    return report


def query_readonly(
    engine: sqlite_harness.ExactEngine,
    source: Path,
    sql: str,
    *,
    stage: str,
    timeout: int = 180,
) -> str:
    result = engine.readonly_sql(source, sql, timeout=timeout)
    if result.returncode == 0:
        return result.stdout
    diagnostic = (result.stderr + "\n" + result.stdout).lower()
    if any(marker in diagnostic for marker in CORRUPTION_MARKERS):
        raise OracleError("SOURCE_CORRUPT", "exact SQLite rejected corrupt source bytes")
    raise OracleError("SCHEMA_DRIFT", f"exact SQLite query failed during {stage}")


def parse_table_info(output: str) -> tuple[tuple[str, str, int, int], ...]:
    columns: list[tuple[str, str, int, int]] = []
    for line in output.splitlines():
        if not line:
            continue
        fields = line.split("\t")
        if len(fields) != 6:
            raise OracleError("SCHEMA_DRIFT", "PRAGMA table_info output is malformed")
        try:
            cid = int(fields[0])
            not_null = int(fields[3])
            primary_key = int(fields[5])
        except ValueError as error:
            raise OracleError("SCHEMA_DRIFT", "PRAGMA table_info metadata is not numeric") from error
        if cid != len(columns):
            raise OracleError("SCHEMA_DRIFT", "legacy column ordinals are not contiguous")
        columns.append((fields[1], fields[2].upper(), not_null, primary_key))
    return tuple(columns)


def validate_source_schema(engine: sqlite_harness.ExactEngine, source: Path) -> None:
    tables = query_readonly(
        engine,
        source,
        "SELECT name FROM sqlite_master "
        "WHERE type='table' AND name IN ('ACTIVITY','GPS_POINTS') ORDER BY name;",
        stage="table discovery",
    )
    if tables.splitlines() != ["ACTIVITY", "GPS_POINTS"]:
        raise OracleError("SCHEMA_DRIFT", "legacy ACTIVITY/GPS_POINTS tables do not match")
    activity = parse_table_info(
        query_readonly(engine, source, "PRAGMA table_info('ACTIVITY');", stage="ACTIVITY schema")
    )
    points = parse_table_info(
        query_readonly(engine, source, "PRAGMA table_info('GPS_POINTS');", stage="GPS_POINTS schema")
    )
    if activity != EXPECTED_ACTIVITY_SCHEMA or points != EXPECTED_POINT_SCHEMA:
        raise OracleError("SCHEMA_DRIFT", "legacy table columns drifted from SqlLogger")


def encoded_text(column: str) -> str:
    return (
        f"CASE WHEN {column} IS NULL THEN 'N' "
        f"ELSE 'H' || hex(CAST({column} AS BLOB)) END"
    )


def decode_text(value: str) -> str | None:
    if value == "N":
        return None
    if not value.startswith("H"):
        raise OracleError("SOURCE_CORRUPT", "encoded SQLite text marker is invalid")
    try:
        return bytes.fromhex(value[1:]).decode("utf-8")
    except (ValueError, UnicodeDecodeError) as error:
        raise OracleError("INVALID_CHARACTER", "legacy text is not valid UTF-8") from error


def read_activity_rows(
    engine: sqlite_harness.ExactEngine, source: Path
) -> Iterator[ActivityRow]:
    sql = (
        "SELECT ID,"
        f"{encoded_text('GMTSTART')},{encoded_text('GMTEND')},"
        f"{encoded_text('NAME')},{encoded_text('DESCRIPTION')},"
        "quote(DISTANCE),quote(TIME),quote(PACE) "
        "FROM ACTIVITY ORDER BY ID;"
    )
    output = query_readonly(engine, source, sql, stage="ACTIVITY rows", timeout=600)
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) != 8:
            raise OracleError("SOURCE_CORRUPT", "ACTIVITY row shape is invalid")
        try:
            identifier = int(fields[0])
        except ValueError as error:
            raise OracleError("SOURCE_CORRUPT", "ACTIVITY ID is not an integer") from error
        yield ActivityRow(
            identifier=identifier,
            start=decode_text(fields[1]),
            end=decode_text(fields[2]),
            name=decode_text(fields[3]),
            description=decode_text(fields[4]),
            distance=fields[5],
            duration=fields[6],
            pace=fields[7],
        )


def read_point_rows(
    engine: sqlite_harness.ExactEngine, source: Path
) -> Iterator[PointRow]:
    sql = (
        "SELECT ID,quote(ACTIVITYID),"
        f"{encoded_text('GMTTIMESTAMP')},"
        "quote(LATITUDE),quote(LONGITUDE),quote(ALTITUDE),quote(ACCURACY),"
        "quote(SPEED),quote(BEARING),quote(HEARTRATE) "
        "FROM GPS_POINTS ORDER BY ID;"
    )
    output = query_readonly(engine, source, sql, stage="GPS_POINTS rows", timeout=600)
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) != 10:
            raise OracleError("SOURCE_CORRUPT", "GPS_POINTS row shape is invalid")
        try:
            identifier = int(fields[0])
        except ValueError as error:
            raise OracleError("SOURCE_CORRUPT", "GPS_POINTS ID is not an integer") from error
        yield PointRow(
            identifier=identifier,
            activity_id=fields[1],
            timestamp=decode_text(fields[2]),
            latitude=fields[3],
            longitude=fields[4],
            altitude=fields[5],
            accuracy=fields[6],
            speed=fields[7],
            bearing=fields[8],
            heart_rate=fields[9],
        )


def source_state(path: Path) -> dict[str, str]:
    state: dict[str, str] = {}
    for suffix in ("", "-wal", "-shm", "-journal"):
        candidate = Path(str(path) + suffix)
        if candidate.exists():
            if not candidate.is_file() or candidate.is_symlink():
                raise OracleError("SOURCE_MUTATED", "legacy source contains an unsafe sidecar")
            state[suffix or "database"] = sha256_file(candidate)
    return state


def issue_record(
    table: str,
    identifier: int,
    field: str,
    original: str | None,
    error: OracleError,
) -> dict[str, object]:
    return {
        "code": error.code,
        "field": field,
        "original": original,
        "original_code_points": (
            None if original is None else formatter_evidence.code_points(original)
        ),
        "source_id": identifier,
        "source_table": table,
    }


def validate_timestamp_field(
    table: str,
    identifier: int,
    field: str,
    original: str | None,
    catalog: DigitCatalog,
    *,
    optional: bool,
) -> tuple[ParsedTimestamp | None, dict[str, object] | None]:
    if optional and original is None:
        return None, None
    try:
        return parse_localized_timestamp(original, catalog), None
    except OracleError as error:
        if error.code not in DETECTOR_CODES:
            raise
        return None, issue_record(table, identifier, field, original, error)


def write_receipt(
    writer: CanonicalNdjsonWriter,
    table: str,
    identifier: int,
    disposition: str,
    record_hashes: Sequence[str],
) -> None:
    writer.write(
        {
            "disposition": disposition,
            "record_sha256": hashlib.sha256(
                "\n".join(record_hashes).encode("ascii")
            ).hexdigest(),
            "source_id": identifier,
            "source_table": table,
        }
    )


def count_ndjson(path: Path) -> int:
    count = 0
    with path.open("rb") as stream:
        for line in stream:
            if not line.endswith(b"\n"):
                raise OracleError("MANIFEST_HASH_MISMATCH", f"{path.name} is not canonical NDJSON")
            json.loads(line.decode("utf-8"))
            count += 1
    return count


def validate_output_manifest(directory: Path) -> dict[str, object]:
    disk_files = sorted(path.name for path in directory.iterdir() if path.is_file())
    expected_files = sorted((*OUTPUT_DATA_FILES, "manifest.json"))
    if disk_files != expected_files:
        raise OracleError(
            "MANIFEST_HASH_MISMATCH",
            "output manifest file set does not match disk",
        )
    manifest_value = read_canonical_json(directory / "manifest.json")
    if not isinstance(manifest_value, dict) or manifest_value.get("schema") != OUTPUT_MANIFEST_SCHEMA:
        raise OracleError("MANIFEST_HASH_MISMATCH", "output manifest schema is invalid")
    entries = manifest_value.get("files")
    if not isinstance(entries, list):
        raise OracleError("MANIFEST_HASH_MISMATCH", "output manifest files are invalid")
    names = [entry.get("name") for entry in entries if isinstance(entry, dict)]
    if names != list(OUTPUT_DATA_FILES):
        raise OracleError("MANIFEST_HASH_MISMATCH", "output manifest file set is invalid")
    for entry in entries:
        if not isinstance(entry, dict):
            raise OracleError("MANIFEST_HASH_MISMATCH", "output manifest entry is invalid")
        path = directory / str(entry["name"])
        actual_count = count_ndjson(path)
        if entry.get("records") != actual_count:
            raise OracleError(
                "MANIFEST_COUNT_MISMATCH",
                f"{path.name} count does not match disk",
            )
        if entry.get("sha256") != sha256_file(path):
            raise OracleError(
                "MANIFEST_HASH_MISMATCH",
                f"{path.name} SHA-256 does not match disk",
            )
    return manifest_value


def migrate_source(
    engine: sqlite_harness.ExactEngine,
    source: Path,
    output_dir: Path,
    catalog: DigitCatalog,
    *,
    fault: str | None = None,
) -> dict[str, object]:
    output_dir.mkdir(parents=True, exist_ok=False)
    before = source_state(source)
    validate_source_schema(engine, source)

    writers = {
        name: CanonicalNdjsonWriter(output_dir / name)
        for name in OUTPUT_DATA_FILES
    }
    activities = writers["activities.ndjson"]
    points = writers["points.ndjson"]
    quarantine = writers["quarantine.ndjson"]
    receipts = writers["receipts.ndjson"]
    expected_receipts: set[tuple[str, int]] = set()
    actual_receipts: set[tuple[str, int]] = set()
    codes: set[str] = set()
    skipped_receipt = False
    try:
        for row in read_activity_rows(engine, source):
            key = ("ACTIVITY", row.identifier)
            expected_receipts.add(key)
            start, start_issue = validate_timestamp_field(
                "ACTIVITY",
                row.identifier,
                "GMTSTART",
                row.start,
                catalog,
                optional=False,
            )
            end, end_issue = validate_timestamp_field(
                "ACTIVITY",
                row.identifier,
                "GMTEND",
                row.end,
                catalog,
                optional=True,
            )
            issues = [issue for issue in (start_issue, end_issue) if issue is not None]
            hashes: list[str] = []
            if issues:
                for issue in issues:
                    hashes.append(quarantine.write(issue))
                    codes.add(str(issue["code"]))
                disposition = "quarantined"
            else:
                assert start is not None
                record = {
                    "canonical_end_utc": None if end is None else end.canonical_utc,
                    "canonical_start_utc": start.canonical_utc,
                    "description": row.description,
                    "distance_sql": row.distance,
                    "name": row.name,
                    "original_end": row.end,
                    "original_start": row.start,
                    "pace_sql": row.pace,
                    "source_id": row.identifier,
                    "time_sql": row.duration,
                }
                hashes.append(activities.write(record))
                disposition = "migrated"
            write_receipt(receipts, "ACTIVITY", row.identifier, disposition, hashes)
            actual_receipts.add(key)

        for row in read_point_rows(engine, source):
            key = ("GPS_POINTS", row.identifier)
            expected_receipts.add(key)
            timestamp, issue = validate_timestamp_field(
                "GPS_POINTS",
                row.identifier,
                "GMTTIMESTAMP",
                row.timestamp,
                catalog,
                optional=False,
            )
            hashes = []
            if issue is not None:
                hashes.append(quarantine.write(issue))
                codes.add(str(issue["code"]))
                disposition = "quarantined"
            else:
                assert timestamp is not None
                record = {
                    "accuracy_sql": row.accuracy,
                    "activity_id_sql": row.activity_id,
                    "altitude_sql": row.altitude,
                    "bearing_sql": row.bearing,
                    "canonical_timestamp_utc": timestamp.canonical_utc,
                    "heart_rate_sql": row.heart_rate,
                    "latitude_sql": row.latitude,
                    "longitude_sql": row.longitude,
                    "original_timestamp": row.timestamp,
                    "source_id": row.identifier,
                    "speed_sql": row.speed,
                    "zero_code_point": f"U+{timestamp.zero_code_point:04X}",
                }
                hashes.append(points.write(record))
                disposition = "migrated"
            if fault == "receipt_gap" and not skipped_receipt:
                skipped_receipt = True
            else:
                write_receipt(receipts, "GPS_POINTS", row.identifier, disposition, hashes)
                actual_receipts.add(key)
    except BaseException:
        for writer in writers.values():
            writer.abort()
        raise

    file_entries = [writers[name].close() for name in OUTPUT_DATA_FILES]
    if expected_receipts != actual_receipts:
        raise OracleError("RECEIPT_GAP", "migration receipts do not cover every source row")

    manifest: dict[str, object] = {
        "files": file_entries,
        "schema": OUTPUT_MANIFEST_SCHEMA,
        "source_sha256": before.get("database"),
    }
    if fault == "manifest_count":
        file_entries[1]["records"] = int(file_entries[1]["records"]) + 1
    atomic_json(output_dir / "manifest.json", manifest)
    if fault == "manifest_hash":
        points_path = output_dir / "points.ndjson"
        payload = points_path.read_bytes()
        newline = payload.find(b"\n")
        if newline < 0:
            raise OracleError(
                "MANIFEST_HASH_MISMATCH",
                "flawed manifest-hash candidate has no point row",
            )
        with points_path.open("wb") as stream:
            stream.write(payload[:newline] + b" " + payload[newline:])
            stream.flush()
            os.fsync(stream.fileno())
    if fault == "source_mutation":
        with source.open("ab") as stream:
            stream.write(b"source-mutated-by-flawed-candidate\n")
            stream.flush()
            os.fsync(stream.fileno())

    after = source_state(source)
    if before != after:
        raise OracleError("SOURCE_MUTATED", "legacy source bytes or sidecars changed")
    validated_manifest = validate_output_manifest(output_dir)
    return {
        "codes": sorted(codes),
        "counts": {
            str(entry["name"]): int(entry["records"])
            for entry in validated_manifest["files"]  # type: ignore[index]
            if isinstance(entry, dict)
        },
        "files": validated_manifest["files"],
        "output_manifest_sha256": sha256_file(output_dir / "manifest.json"),
        "source_sha256": before.get("database"),
        "source_unchanged": True,
        "status": "PASS" if not codes else "QUARANTINED",
    }


def sql_text(value: str | None) -> str:
    if value is None:
        return "NULL"
    return f"CAST(X'{value.encode('utf-8').hex().upper()}' AS TEXT)"


def require_command_success(
    result: sqlite_harness.CommandResult, code: str, stage: str
) -> None:
    if result.returncode != 0:
        raise OracleError(code, f"exact SQLite failed during {stage}")


def create_fixture_database(
    engine: sqlite_harness.ExactEngine,
    path: Path,
    timestamps: Sequence[str | None],
) -> None:
    sqlite_harness.remove_database(path)
    base = sqlite_harness.LEGACY_SCHEMA_PATH.read_text(encoding="utf-8")
    statements = [
        base,
        "BEGIN IMMEDIATE;",
        "DELETE FROM GPS_POINTS;",
        "DELETE FROM ACTIVITY;",
        "DELETE FROM sqlite_sequence;",
        "INSERT INTO ACTIVITY "
        "(ID,GMTSTART,GMTEND,NAME,DESCRIPTION,DISTANCE,TIME,PACE) VALUES "
        f"(1,{sql_text('20240229235958')},{sql_text('20240301000058')},"
        f"{sql_text('Oracle run')},{sql_text('production representative')},"
        "321.125,90.5,0.2816634241245135);",
    ]
    for index, timestamp in enumerate(timestamps, start=1):
        statements.append(
            "INSERT INTO GPS_POINTS "
            "(ID,ACTIVITYID,GMTTIMESTAMP,LATITUDE,LONGITUDE,ALTITUDE,ACCURACY,"
            "SPEED,BEARING,HEARTRATE) VALUES "
            f"({index},1,{sql_text(timestamp)},"
            f"{47.6205 + index * 0.000001:.6f},"
            f"{-122.3493 - index * 0.000001:.6f},"
            f"{20.0 + index:.1f},3.25,2.75,181.5,142.0);"
        )
    statements.append("COMMIT;")
    require_command_success(
        engine.script(path, "\n".join(statements), timeout=300),
        "SOURCE_CORRUPT",
        "fixture creation",
    )


def create_large_database(
    engine: sqlite_harness.ExactEngine,
    path: Path,
    point_count: int,
) -> None:
    sqlite_harness.remove_database(path)
    base = sqlite_harness.LEGACY_SCHEMA_PATH.read_text(encoding="utf-8")
    script = (
        base
        + "\nBEGIN IMMEDIATE;\n"
        "DELETE FROM GPS_POINTS;\n"
        "DELETE FROM ACTIVITY;\n"
        "DELETE FROM sqlite_sequence;\n"
        "INSERT INTO ACTIVITY "
        "(ID,GMTSTART,GMTEND,NAME,DESCRIPTION,DISTANCE,TIME,PACE) VALUES "
        f"(1,{sql_text('20240229235958')},{sql_text('20240301000058')},"
        f"{sql_text('100000 point run')},{sql_text('determinism gate')},"
        "100000.0,100000.0,1.0);\n"
        "WITH RECURSIVE sequence(n) AS (\n"
        "  SELECT 1\n"
        f"  UNION ALL SELECT n + 1 FROM sequence WHERE n < {point_count}\n"
        ")\n"
        "INSERT INTO GPS_POINTS "
        "(ID,ACTIVITYID,GMTTIMESTAMP,LATITUDE,LONGITUDE,ALTITUDE,ACCURACY,"
        "SPEED,BEARING,HEARTRATE)\n"
        f"SELECT n,1,{sql_text('20240229235958')},"
        "47.0+(n*0.000000001),-122.0-(n*0.000000001),"
        "CASE WHEN n%10=0 THEN NULL ELSE 20.0+(n%100) END,"
        "3.0+(n%5),2.0+(n%7),n%360,100+(n%80) FROM sequence;\n"
        "COMMIT;\n"
    )
    require_command_success(
        engine.script(path, script, timeout=600),
        "SOURCE_CORRUPT",
        f"{point_count}-point fixture creation",
    )


def timestamp_for_mutation(mutation: str, catalog: DigitCatalog) -> str | None:
    baseline = "20240229235958"
    non_ascii = next(
        block for block in catalog.blocks if block.zero_code_point != 0x0030
    )
    if mutation in {
        "none",
        "receipt_gap",
        "manifest_count",
        "manifest_hash",
        "source_mutation",
        "schema_drift",
        "corruption",
        "all_android_decimal_blocks",
    }:
        return baseline
    if mutation == "unknown_digit_block":
        return encode_digits(baseline, find_unknown_decimal_block(catalog))
    if mutation == "mixed_digit_blocks":
        localized = encode_digits(baseline, non_ascii)
        return baseline[:7] + localized[7:]
    if mutation == "invalid_control":
        return baseline[:4] + "\n" + baseline[5:]
    if mutation == "invalid_character":
        return baseline[:4] + "A" + baseline[5:]
    if mutation == "invalid_length":
        return baseline[:-1]
    if mutation == "invalid_date":
        return "20240230030405"
    if mutation == "missing_timestamp":
        return None
    raise OracleError("INVALID_MANIFEST", f"unsupported scenario mutation {mutation}")


def execute_scenario(
    engine: sqlite_harness.ExactEngine,
    scenario: Scenario,
    root: Path,
    catalog: DigitCatalog,
) -> dict[str, object]:
    scenario_root = root / scenario.scenario_id
    scenario_root.mkdir(parents=True, exist_ok=False)
    source = scenario_root / "legacy.db"
    if scenario.mutation == "all_android_decimal_blocks":
        timestamps: Sequence[str | None] = [
            encode_digits("20240229235958", block) for block in catalog.blocks
        ]
    else:
        timestamp = timestamp_for_mutation(scenario.mutation, catalog)
        timestamps = [timestamp, "20240301000058"]
    create_fixture_database(engine, source, timestamps)
    if scenario.mutation == "schema_drift":
        require_command_success(
            engine.script(source, "ALTER TABLE GPS_POINTS ADD COLUMN EXTRA TEXT;"),
            "SCHEMA_DRIFT",
            "schema drift candidate creation",
        )
    elif scenario.mutation == "corruption":
        source.write_bytes((b"not a sqlite database\n" * 32)[:512])

    before = source_state(source)
    fault = scenario.mutation if scenario.mutation in {
        "receipt_gap",
        "manifest_count",
        "manifest_hash",
        "source_mutation",
    } else None
    try:
        outcome = migrate_source(
            engine,
            source,
            scenario_root / "output",
            catalog,
            fault=fault,
        )
    except OracleError as error:
        outcome = {
            "codes": [error.code],
            "counts": {},
            "files": [],
            "output_manifest_sha256": None,
            "source_sha256": before.get("database"),
            "source_unchanged": before == source_state(source),
            "status": "QUARANTINED",
        }
    outcome = {"id": scenario.scenario_id, **outcome}
    expected_codes = [] if scenario.expected_code is None else [scenario.expected_code]
    if outcome["status"] != scenario.expected_status or outcome["codes"] != expected_codes:
        raise OracleError(
            "SCENARIO_EXPECTATION_FAILED",
            f"{scenario.scenario_id} produced {outcome['status']}/{outcome['codes']}",
        )
    if scenario.mutation != "source_mutation" and outcome["source_unchanged"] is not True:
        raise OracleError(
            "SCENARIO_EXPECTATION_FAILED",
            f"{scenario.scenario_id} changed its source database",
        )
    return outcome


def compare_outcomes(first: Mapping[str, object], second: Mapping[str, object]) -> None:
    if canonical_record_bytes(first) != canonical_record_bytes(second):
        raise OracleError(
            "NONDETERMINISTIC_RESULT",
            f"scenario {first.get('id')} did not reproduce byte-identically",
        )


def validate_all_block_output(directory: Path, catalog: DigitCatalog) -> None:
    rows = [
        json.loads(line)
        for line in (directory / "points.ndjson").read_text(
            encoding="utf-8"
        ).splitlines()
    ]
    observed_zeros = {str(row["zero_code_point"]) for row in rows}
    expected_zeros = {
        f"U+{block.zero_code_point:04X}" for block in catalog.blocks
    }
    if len(rows) != len(catalog.blocks) or observed_zeros != expected_zeros:
        raise OracleError(
            "SCENARIO_EXPECTATION_FAILED",
            "all-definition baseline did not preserve every Android decimal block",
        )
    for row in rows:
        original = row.get("original_timestamp")
        if not isinstance(original, str):
            raise OracleError(
                "SCENARIO_EXPECTATION_FAILED",
                "all-definition baseline omitted an original timestamp",
            )
        parsed = parse_localized_timestamp(original, catalog)
        if (
            row.get("canonical_timestamp_utc") != parsed.canonical_utc
            or row.get("zero_code_point")
            != f"U+{parsed.zero_code_point:04X}"
        ):
            raise OracleError(
                "SCENARIO_EXPECTATION_FAILED",
                "all-definition baseline changed an original timestamp",
            )


def run_large_gate(
    engine: sqlite_harness.ExactEngine,
    root: Path,
    catalog: DigitCatalog,
    point_count: int,
) -> dict[str, object]:
    source = root / "large.db"
    create_large_database(engine, source, point_count)
    first = migrate_source(engine, source, root / "first", catalog)
    second = migrate_source(engine, source, root / "second", catalog)
    if first != second:
        raise OracleError("NONDETERMINISTIC_RESULT", "large migration outputs differ")
    expected_counts = {
        "activities.ndjson": 1,
        "points.ndjson": point_count,
        "quarantine.ndjson": 0,
        "receipts.ndjson": point_count + 1,
    }
    if first["status"] != "PASS" or first["counts"] != expected_counts:
        raise OracleError("NONDETERMINISTIC_RESULT", "large migration counts are incorrect")
    return {
        "manifest_sha256": first["output_manifest_sha256"],
        "point_count": point_count,
        "receipt_count": point_count + 1,
        "source_sha256": first["source_sha256"],
        "source_unchanged": first["source_unchanged"],
    }


def human_report(report: Mapping[str, object]) -> str:
    lines = [
        "Legacy Migration Oracle",
        f"Overall: {report['status']}",
        f"Report schema: {report['schema']}",
        f"Android decimal definitions: {report['android_evidence']['accepted_decimal_block_count']}",  # type: ignore[index]
        f"Android emitted decimal blocks: {report['android_evidence']['emitted_decimal_block_count']}",  # type: ignore[index]
        f"Android formatter fallback candidates: {report['android_evidence']['formatter_fallback_candidate_count']}",  # type: ignore[index]
        f"Defect candidates: {report['detectors']['candidate_count']}",  # type: ignore[index]
        f"Large-point gate: {report['large_gate']['point_count']}",  # type: ignore[index]
        "",
    ]
    for engine in report["engines"]:  # type: ignore[assignment]
        assert isinstance(engine, dict)
        lines.append(
            f"SQLite {engine['version']}: {engine['baseline_status']} "
            f"({engine['source_id']})"
        )
    lines.extend(
        [
            "",
            "Every flawed candidate was executed twice and reproduced its detector code.",
            "Every non-mutation candidate left the source database and sidecars unchanged.",
        ]
    )
    return "\n".join(lines) + "\n"


def run_oracle(
    *,
    cache_dir: Path,
    exact_report_path: Path,
    work_dir: Path,
    output_dir: Path,
    offline: bool,
    point_count: int,
) -> dict[str, object]:
    if point_count != 100_000:
        raise OracleError(
            "INVALID_POINT_COUNT",
            "acceptance runs require exactly 100,000 points",
        )
    catalog, _ = load_digit_catalog()
    scenarios = load_scenarios()
    exact_report = validate_exact_report(exact_report_path)
    exact_report_sha256 = sha256_file(exact_report_path)
    reset_directory(work_dir)
    reset_directory(output_dir)

    specs = sqlite_harness.load_manifest()
    sqlite_harness.prepare_archive_cache(cache_dir, specs)
    engine_results: list[dict[str, object]] = []
    baseline_hashes: list[dict[str, object]] = []
    detector_results: list[dict[str, object]] = []
    large_result: dict[str, object] | None = None
    passing = next(scenario for scenario in scenarios if scenario.expected_status == "PASS")
    flawed = tuple(scenario for scenario in scenarios if scenario.expected_status == "QUARANTINED")

    try:
        for engine_index, spec in enumerate(specs):
            archive = cache_dir / "archives" / spec.archive_name
            sqlite_harness.download_archive(spec, archive, offline)
            with sqlite_harness.fresh_build_tree(
                work_dir / "builds", spec
            ) as build_dir:
                engine, build_evidence = sqlite_harness.build_engine(
                    spec, archive, build_dir
                )
                observed_version, observed_source_id = sqlite_harness.assert_exact_identity(
                    spec, engine
                )
                engine_root = work_dir / "runs" / spec.release_code
                engine_root.mkdir(parents=True)
                baseline = execute_scenario(
                    engine,
                    passing,
                    engine_root / "baseline",
                    catalog,
                )
                baseline_output = (
                    engine_root / "baseline" / passing.scenario_id / "output"
                )
                validate_all_block_output(baseline_output, catalog)
                baseline_hashes.append(
                    {
                        "counts": baseline["counts"],
                    }
                )
                engine_results.append(
                    {
                        "archive_sha256": spec.archive_sha256,
                        "baseline_counts": baseline["counts"],
                        "baseline_manifest_sha256": baseline[
                            "output_manifest_sha256"
                        ],
                        "baseline_status": baseline["status"],
                        "baseline_source_unchanged": baseline[
                            "source_unchanged"
                        ],
                        "build_mode": build_evidence["mode"],
                        "cache_artifacts_used": build_evidence["cache_artifacts_used"],
                        "source_id": observed_source_id,
                        "version": observed_version,
                    }
                )

                if engine_index == 0:
                    for scenario in flawed:
                        first = execute_scenario(
                            engine,
                            scenario,
                            engine_root / "detectors-first",
                            catalog,
                        )
                        second = execute_scenario(
                            engine,
                            scenario,
                            engine_root / "detectors-second",
                            catalog,
                        )
                        compare_outcomes(first, second)
                        detector_results.append(first)
                    large_root = engine_root / "large"
                    large_root.mkdir()
                    large_result = run_large_gate(
                        engine, large_root, catalog, point_count
                    )
    finally:
        builds = work_dir / "builds"
        if builds.exists():
            shutil.rmtree(builds, ignore_errors=True)

    if not baseline_hashes or any(item != baseline_hashes[0] for item in baseline_hashes[1:]):
        raise OracleError(
            "NONDETERMINISTIC_RESULT",
            "baseline migration output differs across exact SQLite engines",
        )
    observed_codes = {
        code
        for result in detector_results
        for code in result["codes"]  # type: ignore[assignment]
    }
    if observed_codes != DETECTOR_CODES or len(detector_results) != len(DETECTOR_CODES):
        raise OracleError(
            "SCENARIO_EXPECTATION_FAILED",
            "flawed candidates did not independently cover every detector",
        )
    if large_result is None:
        raise OracleError("NONDETERMINISTIC_RESULT", "large migration gate did not run")

    exact_engines = exact_report["engines"]
    assert isinstance(exact_engines, list)
    api26_runtime = next(
        engine["runtime_capabilities"]  # type: ignore[index]
        for engine in exact_engines
        if isinstance(engine, dict) and engine.get("requested_version") == "3.18.2"
    )
    report: dict[str, object] = {
        "android_evidence": {
            "accepted_decimal_block_count": len(catalog.blocks),
            "emitted_decimal_block_count": sum(
                bool(block.emitted_api_levels) for block in catalog.blocks
            ),
            "evidence_sha256": dict(sorted(catalog.evidence_hashes.items())),
            "fallback_decimal_block_count": sum(
                bool(block.fallback_api_levels) for block in catalog.blocks
            ),
            "formatter_fallback_candidate_count": len(
                catalog.formatter_fallback_names
            ),
            "formatter_fallback_candidate_names": list(
                catalog.formatter_fallback_names
            ),
            "fullwidth_zero_code_point": "U+FF10",
            "supplementary_definition_zero_code_points": [
                f"U+{block.zero_code_point:04X}"
                for block in catalog.blocks
                if block.zero_code_point > 0xFFFF
            ],
            "supplementary_emitted_zero_code_points": [
                f"U+{block.zero_code_point:04X}"
                for block in catalog.blocks
                if block.zero_code_point > 0xFFFF
                and block.emitted_api_levels
            ],
            "supplementary_fallback_zero_code_points": [
                f"U+{block.zero_code_point:04X}"
                for block in catalog.blocks
                if block.zero_code_point > 0xFFFF
                and block.fallback_api_levels
            ],
        },
        "api26_sqlite_fallback": {
            "column_metadata": api26_runtime["column_metadata"],  # type: ignore[index]
            "schema_catalog": api26_runtime["schema_catalog"],  # type: ignore[index]
        },
        "detectors": {
            "candidate_count": len(detector_results),
            "results": detector_results,
        },
        "engines": engine_results,
        "exact_sqlite_report_sha256": exact_report_sha256,
        "implementation_sha256": sha256_file(TOOL_DIR / "oracle.py"),
        "large_gate": large_result,
        "legacy_schema_sha256": sha256_file(
            sqlite_harness.LEGACY_SCHEMA_PATH
        ),
        "scenario_manifest_sha256": sha256_file(SCENARIO_MANIFEST_PATH),
        "schema": ORACLE_REPORT_SCHEMA,
        "source_readonly_proven": all(
            result["source_unchanged"] is True
            for result in detector_results
            if result["codes"] != ["SOURCE_MUTATED"]
        ),
        "status": "PASS",
    }
    atomic_json(output_dir / "report.json", report)
    atomic_text(output_dir / "report.txt", human_report(report))
    shutil.rmtree(work_dir, ignore_errors=True)
    return report


def validate_inputs(exact_report_path: Path) -> dict[str, object]:
    catalog, _ = load_digit_catalog()
    scenarios = load_scenarios()
    validate_exact_report(exact_report_path)
    result = {
        "decimal_blocks": len(catalog.blocks),
        "detector_scenarios": sum(
            scenario.expected_status == "QUARANTINED" for scenario in scenarios
        ),
        "fallback_candidates": len(catalog.formatter_fallback_names),
    }
    print(
        "Validated inputs: "
        f"decimal-blocks={result['decimal_blocks']}, "
        f"fallback-candidates={result['fallback_candidates']}, "
        f"detector-scenarios={result['detector_scenarios']}"
    )
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser(
        "validate", help="validate Android evidence, scenario files, and exact report"
    )
    validate.add_argument("--exact-report", type=Path, default=DEFAULT_EXACT_REPORT)

    run = subparsers.add_parser(
        "run", help="execute real flawed candidates and deterministic migration gates"
    )
    run.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE_DIR)
    run.add_argument("--exact-report", type=Path, default=DEFAULT_EXACT_REPORT)
    run.add_argument("--work-dir", type=Path, default=DEFAULT_WORK_DIR)
    run.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    run.add_argument("--offline", action="store_true")
    run.add_argument("--large-points", type=int, default=100_000)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "validate":
            validate_inputs(arguments.exact_report.resolve())
            return 0
        report = run_oracle(
            cache_dir=arguments.cache_dir.resolve(),
            exact_report_path=arguments.exact_report.resolve(),
            work_dir=arguments.work_dir.resolve(),
            output_dir=arguments.output_dir.resolve(),
            offline=arguments.offline,
            point_count=arguments.large_points,
        )
        print(human_report(report), end="")
        return 0
    except (
        OracleError,
        formatter_evidence.EvidenceError,
        sqlite_harness.HarnessError,
        OSError,
        ValueError,
    ) as error:
        code = getattr(error, "code", error.__class__.__name__)
        message = getattr(error, "message", str(error))
        print(f"{code}: {message}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
