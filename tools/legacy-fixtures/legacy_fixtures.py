#!/usr/bin/env python3
"""Generate and verify synthetic legacy SportLogger SQLite fixtures."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import re
import shutil
import sqlite3
import struct
import sys
import unicodedata
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, Iterator, List, Mapping, Optional, Sequence, Tuple


TOOL_ROOT = Path(__file__).resolve().parent
ANDROID_FORMATTER_PROBE_SOURCE_PATH = (
    TOOL_ROOT / "AndroidLocaleTimestampProbeTest.java"
)
ANDROID_FORMATTER_PROBE_RUNNER_PATH = (
    TOOL_ROOT / "run_android_formatter_probe.py"
)
ANDROID_FORMATTER_EVIDENCE_PATHS = (
    TOOL_ROOT / "AndroidLocaleTimestampProbe.api26.tsv",
    TOOL_ROOT / "AndroidLocaleTimestampProbe.api36.tsv",
)
ANDROID_FORMATTER_PROBE_SOURCE_SHA256 = (
    "3ad88ddd9ec4f061bfdd041aba2f55973c00e78b973abd7a33105bd6831735e4"
)
ANDROID_FORMATTER_PROBE_RUNNER_SHA256 = (
    "738a95f767ed23d82f1bb1dfeea51ec3e41b9f836225f7348aa6e648e1b812d3"
)
ANDROID_FORMATTER_EVIDENCE_SHA256 = {
    26: "2e6d916117c7050c5702f269cc387af9f1db5286cbe65eddbfd3f2b02467e2e7",
    36: "c05aa33dd782cab7146b1c04847d2f2f358945fa482584ffeed3bbdbee51840a",
}
ANDROID_FORMATTER_EXPECTED_METADATA = {
    26: {
        "format_version": "2",
        "platform": "Android",
        "system_image_package": "system-images;android-26;google_apis;x86_64",
        "system_image_revision": "16.0.0",
        "emulator_package": "emulator",
        "emulator_package_revision": "36.6.11",
        "emulator_version": "36.6.11.0",
        "emulator_acceleration": "off",
        "compile_sdk_package": "platforms;android-36",
        "compile_sdk_revision": "2.0.0",
        "build_tools_package": "build-tools;36.0.0",
        "build_tools_revision": "36.0.0",
        "probe_runner": "run_android_formatter_probe.py",
        "probe_runner_version": "1",
        "probe_runner_command": (
            "python3 tools/legacy-fixtures/run_android_formatter_probe.py --compare"
        ),
        "api_level": "26",
        "release": "8.0.0",
        "build_fingerprint": (
            "Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/"
            "OSR1.180418.026/6741039:userdebug/dev-keys"
        ),
        "abi": "x86_64",
        "supported_abis": "x86_64,x86",
        "java_version": "0",
        "java_runtime_version": "0.9",
        "java_vm_name": "Dalvik",
        "java_vm_version": "2.1.0",
        "icu_version": "58.2.0.0",
        "unicode_version": "9.0.0.0",
        "cldr_version": "30.0.3.0",
        "pattern": "yyyy-MM-dd HH:mm:ss.SSS",
        "legacy_pattern": "yyyyMMddHHmmss",
        "timezone": "UTC",
        "available_locale_count": "710",
        "locale_rows_sha256": (
            "d11a12e4c3dd62ec7e2fca5ecefe61f368ecd1c13d185b0959e2fdc3e929d924"
        ),
        "numbering_candidate_source": (
            "android.icu.text.NumberingSystem.getAvailableNames"
        ),
        "numbering_candidate_version": "ICU-58.2.0.0",
        "numbering_candidate_count": "77",
        "numbering_candidate_names_sha256": (
            "0db8f4ff3f214e86b2e11647143aaed0c72c5cbd63d22750a8117c50e3243a8a"
        ),
        "numbering_candidate_instant_utc": "2024-07-08T09:10:11.123Z",
        "fixed_instants_utc": (
            "2000-02-29T12:34:56.789Z,"
            "2024-07-08T09:10:11.123Z,"
            "2032-02-29T23:59:59.999Z,"
            "2567-07-08T09:10:11.123Z"
        ),
    },
    36: {
        "format_version": "2",
        "platform": "Android",
        "system_image_package": "system-images;android-36;google_apis;x86_64",
        "system_image_revision": "7.0.0",
        "emulator_package": "emulator",
        "emulator_package_revision": "36.6.11",
        "emulator_version": "36.6.11.0",
        "emulator_acceleration": "off",
        "compile_sdk_package": "platforms;android-36",
        "compile_sdk_revision": "2.0.0",
        "build_tools_package": "build-tools;36.0.0",
        "build_tools_revision": "36.0.0",
        "probe_runner": "run_android_formatter_probe.py",
        "probe_runner_version": "1",
        "probe_runner_command": (
            "python3 tools/legacy-fixtures/run_android_formatter_probe.py --compare"
        ),
        "api_level": "36",
        "release": "16",
        "build_fingerprint": (
            "google/sdk_gphone64_x86_64/emu64xa:16/"
            "BE2A.250530.026.F3/13894323:userdebug/dev-keys"
        ),
        "abi": "x86_64",
        "supported_abis": "x86_64,arm64-v8a",
        "java_version": "0",
        "java_runtime_version": "0.9",
        "java_vm_name": "Dalvik",
        "java_vm_version": "2.1.0",
        "icu_version": "76.1.0.0",
        "unicode_version": "16.0.0.0",
        "cldr_version": "46.0.0.0",
        "pattern": "yyyy-MM-dd HH:mm:ss.SSS",
        "legacy_pattern": "yyyyMMddHHmmss",
        "timezone": "UTC",
        "available_locale_count": "881",
        "locale_rows_sha256": (
            "68c1f18354c6163af1b5cfd27613c7b373bb06e10d5c3b39984c226e621e222e"
        ),
        "numbering_candidate_source": (
            "android.icu.text.NumberingSystem.getAvailableNames"
        ),
        "numbering_candidate_version": "ICU-76.1.0.0",
        "numbering_candidate_count": "96",
        "numbering_candidate_names_sha256": (
            "3e5f3860817d257311d2ec33fe547abff81de3269cda749d334cdafc1c3ad202"
        ),
        "numbering_candidate_instant_utc": "2024-07-08T09:10:11.123Z",
        "fixed_instants_utc": (
            "2000-02-29T12:34:56.789Z,"
            "2024-07-08T09:10:11.123Z,"
            "2032-02-29T23:59:59.999Z,"
            "2567-07-08T09:10:11.123Z"
        ),
    },
}
ANDROID_FORMATTER_CALENDAR_CLASS = "java.util.GregorianCalendar"
ANDROID_FORMATTER_CALENDAR_TYPE = "gregory"
ANDROID_FORMATTER_SIGNATURE_INSTANT = "2024-07-08T09:10:11.123Z"
ANDROID_FORMATTER_THAI_CONTROL_TAGS = (
    "th-TH",
    "th-TH-u-nu-thai",
    "th-TH-u-nu-latn",
    "th-TH-u-ca-buddhist",
    "th-TH-u-ca-buddhist-nu-thai",
    "th-TH-u-ca-gregory-nu-thai",
)
ANDROID_FORMATTER_LEGACY_TEN_ZEROES = frozenset(
    (
        0x0030,
        0x0660,
        0x06F0,
        0x07C0,
        0x0966,
        0x09E6,
        0x0E50,
        0x0F20,
        0x1040,
        0x1C50,
    )
)
FORMAT_VERSION = 1
DATABASE_NAME = "GPSLOGGERDB_LONG2KNOW"
ANDROID_METADATA_LOCALE = "en_US"
AR_EG_ANDROID_METADATA_LOCALE = "ar_EG"
TH_TH_THAI_ANDROID_METADATA_LOCALE = "th_TH_#u-nu-thai"
FIXED_SQLITE_PAGE_SIZE = 4096
FIXTURE_NAMESPACE = uuid.uuid5(
    uuid.NAMESPACE_URL,
    "https://github.com/long2know/sport-logger/legacy-fixtures/v1",
)
ANDROID_LOCALE_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_#-]*$")
SQLITE_HEADER = b"SQLite format 3\x00"
SQLITE_PAGE_SIZE_ENCODINGS = frozenset(
    (1, 512, 1024, 2048, 4096, 8192, 16384, 32768)
)
SQLITE_HOST_HEADER_RANGES = (
    (18, 20),
    (24, 28),
    (92, 100),
)
SQLITE_LEGAL_FORMAT_VERSIONS = frozenset((1, 2))
SQLITE_ROLLBACK_FORMAT_VERSIONS = (1, 1)
SQLITE_WAL_FORMAT_VERSIONS = (2, 2)

CALENDAR_GREGORIAN = "gregory"
CALENDAR_EVIDENCE_SOURCE = "android_java_text_gregorian_invariant"
ANDROID_FORMATTER_EVIDENCE_SOURCE = "android_api26_api36_formatter_probe"

SCHEMA_PATH_AUTO = "auto"
SCHEMA_PATH_MODERN = "modern_table_xinfo_sqlite_master"
SCHEMA_PATH_SQLITE_SCHEMA_ALIAS = "modern_table_xinfo_sqlite_schema"
SCHEMA_PATH_ANDROID_API_26 = "android_api_26_table_info_sqlite_master"
SCHEMA_PATHS = (
    SCHEMA_PATH_MODERN,
    SCHEMA_PATH_SQLITE_SCHEMA_ALIAS,
    SCHEMA_PATH_ANDROID_API_26,
)
SQLITE_TABLE_XINFO_MIN_VERSION = (3, 26, 0)
SQLITE_GENERATED_COLUMNS_MIN_VERSION = (3, 31, 0)
SQLITE_SCHEMA_ALIAS_MIN_VERSION = (3, 33, 0)
THEORETICAL_SQLITE_PROFILE_VERSIONS = (
    ("android_api_26_sqlite_3_18", (3, 18, 2)),
    ("sqlite_3_26", (3, 26, 0)),
    ("sqlite_3_32", (3, 32, 0)),
    ("sqlite_3_33_plus", (3, 33, 0)),
)

GENERATED_COLUMN_DETECTOR = "generated_column_hidden_from_table_info"
FTS4_VIRTUAL_TABLE_DETECTOR = "virtual_table_shadow_substitution"
FTS5_VIRTUAL_TABLE_DETECTOR = "fts5_virtual_table_shadow_substitution"

STORAGE_STANDARD = "standard"
STORAGE_ACTIVE_WAL = "active_wal"
STORAGE_MALFORMED_SCHEMA = "malformed_schema"
STORAGE_TRUNCATED = "truncated"
STORAGE_CORRUPT = "corrupt"

COMPARISON_LOGICAL_DATABASE = "logical_database"
COMPARISON_ACTIVE_WAL = "active_wal_semantics"
COMPARISON_MALFORMED_SCHEMA = "malformed_schema_semantics"
COMPARISON_TRUNCATED = "truncated_canonical_bytes"
COMPARISON_CORRUPT = "corrupt_canonical_bytes"

WAL_SALT_1 = 0x13579BDF
WAL_SALT_2 = 0x2468ACE0

ACTIVITY_COLUMNS = (
    "ID",
    "GMTSTART",
    "GMTEND",
    "NAME",
    "DESCRIPTION",
    "DISTANCE",
    "TIME",
    "PACE",
)
GPS_POINT_COLUMNS = (
    "ID",
    "ACTIVITYID",
    "GMTTIMESTAMP",
    "LATITUDE",
    "LONGITUDE",
    "ALTITUDE",
    "ACCURACY",
    "SPEED",
    "BEARING",
    "HEARTRATE",
)
TABLE_COLUMNS = {
    "ACTIVITY": ACTIVITY_COLUMNS,
    "GPS_POINTS": GPS_POINT_COLUMNS,
}
BUSINESS_TABLES = ("ACTIVITY", "GPS_POINTS")
PLATFORM_TABLES = ("android_metadata",)

CREATE_ANDROID_METADATA_SQL = (
    "CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT);"
)
CREATE_ACTIVITY_SQL = (
    "CREATE TABLE IF NOT EXISTS ACTIVITY "
    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, GMTSTART VARCHAR, "
    "GMTEND VARCHAR, NAME VARCHAR, DESCRIPTION VARCHAR,"
    "DISTANCE REAL, TIME REAL, PACE REAL);"
)
CREATE_GPS_POINTS_SQL = (
    "CREATE TABLE IF NOT EXISTS GPS_POINTS "
    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ACTIVITYID INTEGER, "
    "GMTTIMESTAMP VARCHAR, LATITUDE REAL, LONGITUDE REAL,"
    "ALTITUDE REAL, ACCURACY REAL, SPEED REAL, BEARING REAL, HEARTRATE REAL);"
)

EXPECTED_TABLE_XINFO = {
    "android_metadata": (
        (0, "locale", "TEXT", 0, None, 0, 0),
    ),
    "ACTIVITY": (
        (0, "ID", "INTEGER", 1, None, 1, 0),
        (1, "GMTSTART", "VARCHAR", 0, None, 0, 0),
        (2, "GMTEND", "VARCHAR", 0, None, 0, 0),
        (3, "NAME", "VARCHAR", 0, None, 0, 0),
        (4, "DESCRIPTION", "VARCHAR", 0, None, 0, 0),
        (5, "DISTANCE", "REAL", 0, None, 0, 0),
        (6, "TIME", "REAL", 0, None, 0, 0),
        (7, "PACE", "REAL", 0, None, 0, 0),
    ),
    "GPS_POINTS": (
        (0, "ID", "INTEGER", 1, None, 1, 0),
        (1, "ACTIVITYID", "INTEGER", 0, None, 0, 0),
        (2, "GMTTIMESTAMP", "VARCHAR", 0, None, 0, 0),
        (3, "LATITUDE", "REAL", 0, None, 0, 0),
        (4, "LONGITUDE", "REAL", 0, None, 0, 0),
        (5, "ALTITUDE", "REAL", 0, None, 0, 0),
        (6, "ACCURACY", "REAL", 0, None, 0, 0),
        (7, "SPEED", "REAL", 0, None, 0, 0),
        (8, "BEARING", "REAL", 0, None, 0, 0),
        (9, "HEARTRATE", "REAL", 0, None, 0, 0),
    ),
}
EXPECTED_TABLE_INFO = {
    table: tuple(row[:6] for row in rows)
    for table, rows in EXPECTED_TABLE_XINFO.items()
}

EXPECTED_SCHEMA_SQL = {
    "android_metadata": "CREATE TABLE android_metadata (locale TEXT)",
    "ACTIVITY": (
        "CREATE TABLE ACTIVITY "
        "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, GMTSTART VARCHAR, "
        "GMTEND VARCHAR, NAME VARCHAR, DESCRIPTION VARCHAR, DISTANCE REAL, "
        "TIME REAL, PACE REAL)"
    ),
    "GPS_POINTS": (
        "CREATE TABLE GPS_POINTS "
        "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ACTIVITYID INTEGER, "
        "GMTTIMESTAMP VARCHAR, LATITUDE REAL, LONGITUDE REAL, ALTITUDE REAL, "
        "ACCURACY REAL, SPEED REAL, BEARING REAL, HEARTRATE REAL)"
    ),
}

EXPECTED_SQLITE_INTERNAL_OBJECTS = {
    ("table", "sqlite_sequence", "sqlite_sequence")
}


class FixtureValidationError(RuntimeError):
    """Raised when a fixture or expected output violates the contract."""


@dataclass(frozen=True)
class SchemaCapabilities:
    table_xinfo: bool
    sqlite_schema_alias: bool


@dataclass(frozen=True)
class SQLiteVersionProfile:
    name: str
    sqlite_version: Tuple[int, int, int]
    schema_capabilities: SchemaCapabilities
    generated_columns: bool


@dataclass(frozen=True)
class ExecutableSchemaProfile:
    name: str
    capabilities: SchemaCapabilities
    forbidden_tokens: Tuple[str, ...]


@dataclass(frozen=True)
class SQLiteDetectorCapabilities:
    sqlite_version: Tuple[int, int, int]
    generated_columns: bool
    fts4: bool
    fts5: bool


@dataclass(frozen=True)
class DetectorReport:
    passed: Tuple[str, ...]
    not_applicable: Tuple[Tuple[str, str], ...] = ()


_SCHEMA_CAPABILITY_CACHE: Dict[
    int,
    Tuple[Any, SchemaCapabilities],
] = {}


class SchemaSqlGuard:
    def __init__(
        self,
        connection: sqlite3.Connection,
        forbidden_tokens: Sequence[str] = (),
    ) -> None:
        self.connection = connection
        self.forbidden_tokens = tuple(
            token.casefold() for token in forbidden_tokens
        )
        self.statements: List[str] = []
        self.rejected_statements: List[str] = []

    def execute(
        self,
        sql: str,
        parameters: Sequence[Any] = (),
    ) -> sqlite3.Cursor:
        normalized = sql.casefold()
        if any(token in normalized for token in self.forbidden_tokens):
            self.rejected_statements.append(sql)
            raise sqlite3.OperationalError(
                "Schema capability proxy rejected unsupported SQL {!r}".format(
                    sql
                )
            )
        self.statements.append(sql)
        return self.connection.execute(sql, parameters)


@dataclass(frozen=True)
class CalendarEvidence:
    calendar: str
    locale_tag: str
    source: str = CALENDAR_EVIDENCE_SOURCE


DEFAULT_GREGORIAN_CALENDAR_EVIDENCE = CalendarEvidence(
    calendar=CALENDAR_GREGORIAN,
    locale_tag="en-US",
)


@dataclass(frozen=True)
class FixtureCase:
    key: str
    description: str
    activities: Tuple[Tuple[Any, ...], ...]
    track_points: Tuple[Tuple[Any, ...], ...]
    business_tables: Tuple[str, ...] = BUSINESS_TABLES
    representative_values: Tuple[Mapping[str, Any], ...] = ()
    exercise_idempotency: bool = False
    storage: str = STORAGE_STANDARD
    blocked_reason: Optional[str] = None
    android_locale: str = ANDROID_METADATA_LOCALE
    default_calendar_evidence: Optional[
        CalendarEvidence
    ] = DEFAULT_GREGORIAN_CALENDAR_EVIDENCE
    activity_calendar_evidence: Tuple[
        Tuple[int, CalendarEvidence], ...
    ] = ()

    @property
    def database_identity(self) -> str:
        return "synthetic:sport-logger:legacy:{}:v1".format(self.key)


def activity(
    row_id: int,
    start: Any,
    end: Any,
    name: Any,
    description: Any,
    distance: Any,
    elapsed_time: Any,
    pace: Any,
) -> Tuple[Any, ...]:
    return (row_id, start, end, name, description, distance, elapsed_time, pace)


def point(
    row_id: int,
    activity_id: Any,
    timestamp: Any,
    latitude: Any,
    longitude: Any,
    altitude: Any,
    accuracy: Any,
    speed: Any,
    bearing: Any,
    heart_rate: Any,
) -> Tuple[Any, ...]:
    return (
        row_id,
        activity_id,
        timestamp,
        latitude,
        longitude,
        altitude,
        accuracy,
        speed,
        bearing,
        heart_rate,
    )


def parse_code_point(value: str) -> int:
    if not re.fullmatch(r"U\+[0-9A-F]{4,6}", value):
        raise FixtureValidationError(
            "Invalid formatter-matrix code point {!r}".format(value)
        )
    return int(value[2:], 16)


def parse_control_layout(value: str) -> Tuple[Tuple[int, int], ...]:
    if not value:
        return ()
    result: List[Tuple[int, int]] = []
    for item in value.split(","):
        match = re.fullmatch(r"(0|[1-9][0-9]*):(U\+[0-9A-F]{4,6})", item)
        if match is None:
            raise FixtureValidationError(
                "Invalid formatter control layout {!r}".format(value)
            )
        position = int(match.group(1))
        if position > 14:
            raise FixtureValidationError(
                "Formatter control position exceeds 14 digits"
            )
        result.append((position, parse_code_point(match.group(2))))
    if [position for position, _ in result] != sorted(
        position for position, _ in result
    ):
        raise FixtureValidationError("Formatter control layout is not ordered")
    return tuple(result)


def normalize_matrix_digits(
    value: str,
    zero_code_point: int,
    control_layout: str = "",
) -> str:
    normalized: List[str] = []
    controls: List[Tuple[int, int]] = []
    for character in value:
        code_point = ord(character)
        decimal = code_point - zero_code_point
        if 0 <= decimal <= 9:
            normalized.append(str(decimal))
        else:
            controls.append((len(normalized), code_point))
    if tuple(controls) != parse_control_layout(control_layout):
        raise FixtureValidationError(
            "Formatter evidence contains an unproven control layout"
        )
    return "".join(normalized)


def matrix_timestamp_source_year(
    value: str,
    zero_code_point: int,
    control_layout: str = "",
) -> int:
    normalized = normalize_matrix_digits(
        value,
        zero_code_point,
        control_layout,
    )
    if len(normalized) != 14:
        raise FixtureValidationError(
            "Formatter evidence candidate timestamp must contain 14 digits"
        )
    return int(normalized[:4])


def decimal_definition_zero(value: str) -> Optional[int]:
    if len(value) != 10:
        return None
    zero_code_point = ord(value[0])
    if tuple(ord(character) for character in value) != tuple(
        zero_code_point + offset for offset in range(10)
    ):
        return None
    return zero_code_point


def formatter_instant_fields(instant_utc: str) -> Tuple[str, ...]:
    match = re.fullmatch(
        r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})\.(\d{3})Z",
        instant_utc,
    )
    if match is None:
        raise FixtureValidationError(
            "Invalid formatter evidence instant {!r}".format(instant_utc)
        )
    return match.groups()


def legacy_digits_for_instant(instant_utc: str) -> str:
    return "".join(formatter_instant_fields(instant_utc)[:6])


def localized_formatter_digits(value: str, zero_code_point: int) -> str:
    return "".join(
        chr(zero_code_point + int(character))
        if "0" <= character <= "9"
        else character
        for character in value
    )


def formatted_timestamp_for_instant(
    instant_utc: str,
    zero_code_point: int,
) -> str:
    year, month, day, hour, minute, second, millis = formatter_instant_fields(
        instant_utc
    )
    return localized_formatter_digits(
        "{}-{}-{} {}:{}:{}.{}".format(
            year,
            month,
            day,
            hour,
            minute,
            second,
            millis,
        ),
        zero_code_point,
    )


def epoch_millis_for_instant(instant_utc: str) -> int:
    year, month, day, hour, minute, second, millis = formatter_instant_fields(
        instant_utc
    )
    instant = datetime(
        int(year),
        int(month),
        int(day),
        int(hour),
        int(minute),
        int(second),
        int(millis) * 1000,
    )
    delta = instant - datetime(1970, 1, 1)
    return (
        delta.days * 86_400_000
        + delta.seconds * 1000
        + delta.microseconds // 1000
    )


def parse_android_formatter_evidence(path: Path) -> Mapping[str, Any]:
    if not path.is_file():
        raise FixtureValidationError(
            "Missing Android formatter evidence {}".format(path)
        )
    data = path.read_bytes()
    if not data.endswith(b"\n") or b"\r" in data:
        raise FixtureValidationError(
            "{} must be UTF-8 text with LF line endings".format(path.name)
        )
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise FixtureValidationError(
            "{} is not valid UTF-8".format(path.name)
        ) from error

    metadata: Dict[str, str] = {}
    signatures: List[Mapping[str, Any]] = []
    definitions: List[Mapping[str, Any]] = []
    candidates: List[Mapping[str, Any]] = []
    candidate_failures: List[Mapping[str, str]] = []
    candidate_outcomes: List[str] = []
    controls: List[Mapping[str, Any]] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        fields = line.split("\t")
        kind = fields[0]
        if kind == "meta" and len(fields) == 3:
            if fields[1] in metadata:
                raise FixtureValidationError(
                    "{} line {} duplicates metadata key {!r}".format(
                        path.name,
                        line_number,
                        fields[1],
                    )
                )
            metadata[fields[1]] = fields[2]
            continue
        if kind == "signature" and len(fields) == 10:
            signatures.append(
                {
                    "zero_code_point": parse_code_point(fields[1]),
                    "control_layout": fields[2],
                    "calendar_class": fields[3],
                    "calendar": fields[4],
                    "formatted": fields[5],
                    "legacy_formatted": fields[6],
                    "number_digits": fields[7],
                    "locale_count": int(fields[8]),
                    "first_locale": fields[9],
                }
            )
            continue
        if kind == "candidate_definition" and len(fields) in (3, 7):
            definition_fields = (
                fields[3:] if len(fields) == 7 else ("", "", "", "")
            )
            radix = int(definition_fields[1]) if definition_fields[1] else None
            algorithmic = (
                definition_fields[2] == "true"
                if definition_fields[2]
                else None
            )
            description_zero = (
                decimal_definition_zero(definition_fields[3])
                if radix == 10 and algorithmic is False
                else None
            )
            definitions.append(
                {
                    "name": fields[1],
                    "definition_status": fields[2],
                    "definition_name": definition_fields[0],
                    "radix": radix,
                    "algorithmic": algorithmic,
                    "description": definition_fields[3],
                    "definition_zero_code_point": description_zero,
                }
            )
            continue
        if kind == "candidate_failure" and len(fields) == 3:
            candidate_failures.append(
                {
                    "name": fields[1],
                    "status": fields[2],
                }
            )
            candidate_outcomes.append(fields[1])
            continue
        if kind in ("numbering_candidate", "thai_control") and len(fields) == 25:
            zero_code_point = parse_code_point(fields[8])
            control_layout = fields[9]
            legacy_formatted = fields[18]
            source_year = matrix_timestamp_source_year(
                legacy_formatted,
                zero_code_point,
                control_layout,
            )
            detailed = {
                "kind": kind,
                "candidate_name": fields[1],
                "definition_name": fields[2],
                "definition_status": fields[3],
                "observation_status": fields[4],
                "radix": int(fields[5]) if fields[5] else None,
                "algorithmic": fields[6] == "true" if fields[6] else None,
                "description": fields[7],
                "zero_code_point": zero_code_point,
                "control_layout": control_layout,
                "locale_tag": fields[10],
                "locale_string": fields[11],
                "numbering_system": fields[12],
                "calendar_keyword": fields[13],
                "calendar_class": fields[14],
                "calendar": fields[15],
                "instant_utc": fields[16],
                "formatted": fields[17],
                "legacy_formatted": legacy_formatted,
                "number_digits": fields[19],
                "strict_round_trip": fields[20],
                "parsed_epoch_millis": fields[21],
                "default_constructor_matches": fields[22],
                "default_calendar_class": fields[23],
                "default_calendar": fields[24],
                "source_year": source_year,
            }
            if kind == "numbering_candidate":
                candidates.append(detailed)
                candidate_outcomes.append(fields[1])
            else:
                controls.append(detailed)
            continue
        raise FixtureValidationError(
            "{} has an invalid record at line {}".format(path.name, line_number)
        )

    try:
        api_level = int(metadata["api_level"])
    except (KeyError, ValueError) as error:
        raise FixtureValidationError(
            "{} lacks a valid API level".format(path.name)
        ) from error
    expected_metadata = ANDROID_FORMATTER_EXPECTED_METADATA.get(api_level)
    if expected_metadata is None or metadata != expected_metadata:
        raise FixtureValidationError(
            "{} Android formatter metadata changed".format(path.name)
        )
    expected_sha256 = ANDROID_FORMATTER_EVIDENCE_SHA256[api_level]
    if hashlib.sha256(data).hexdigest() != expected_sha256:
        raise FixtureValidationError(
            "{} Android formatter evidence bytes changed".format(path.name)
        )

    fixed_instants = tuple(metadata["fixed_instants_utc"].split(","))
    if len(fixed_instants) != len(set(fixed_instants)):
        raise FixtureValidationError(
            "{} formatter fixed instants are not unique".format(path.name)
        )
    for instant_utc in fixed_instants:
        formatter_instant_fields(instant_utc)
        epoch_millis_for_instant(instant_utc)
    signature_keys = [
        (row["zero_code_point"], row["control_layout"])
        for row in signatures
    ]
    if (
        signature_keys != sorted(signature_keys)
        or len(signature_keys) != len(set(signature_keys))
        or sum(row["locale_count"] for row in signatures)
        != int(metadata["available_locale_count"])
    ):
        raise FixtureValidationError(
            "{} formatter signatures do not partition available locales".format(
                path.name
            )
        )
    if any(
        row["calendar_class"] != ANDROID_FORMATTER_CALENDAR_CLASS
        or row["calendar"] != ANDROID_FORMATTER_CALENDAR_TYPE
        or row["locale_count"] <= 0
        or not row["first_locale"]
        or row["formatted"]
        != formatted_timestamp_for_instant(
            ANDROID_FORMATTER_SIGNATURE_INSTANT,
            row["zero_code_point"],
        )
        or row["legacy_formatted"]
        != localized_formatter_digits(
            legacy_digits_for_instant(ANDROID_FORMATTER_SIGNATURE_INSTANT),
            row["zero_code_point"],
        )
        or matrix_timestamp_source_year(
            row["legacy_formatted"],
            row["zero_code_point"],
            row["control_layout"],
        )
        != 2024
        or normalize_matrix_digits(
            row["number_digits"],
            row["zero_code_point"],
        )
        != "1234567890"
        for row in signatures
    ):
        raise FixtureValidationError(
            "{} contains a non-Gregorian or malformed signature".format(path.name)
        )

    candidate_names = [row["name"] for row in definitions]
    if (
        candidate_names != sorted(candidate_names)
        or len(candidate_names) != len(set(candidate_names))
        or len(candidate_names) != int(metadata["numbering_candidate_count"])
    ):
        raise FixtureValidationError(
            "{} numbering candidate definitions changed".format(path.name)
        )
    candidate_digest = hashlib.sha256()
    for name in candidate_names:
        candidate_digest.update((name + "\n").encode("utf-8"))
    if (
        candidate_digest.hexdigest()
        != metadata["numbering_candidate_names_sha256"]
    ):
        raise FixtureValidationError(
            "{} numbering candidate list hash changed".format(path.name)
        )
    for definition in definitions:
        if definition["definition_status"] == "ok":
            if (
                definition["definition_name"] != definition["name"]
                or definition["radix"] is None
                or definition["algorithmic"] is None
                or not definition["description"]
            ):
                raise FixtureValidationError(
                    "{} has malformed numbering definition {!r}".format(
                        path.name,
                        definition["name"],
                    )
                )
        elif any(
            definition[key] not in (None, "")
            for key in (
                "definition_name",
                "radix",
                "algorithmic",
                "description",
                "definition_zero_code_point",
            )
        ):
            raise FixtureValidationError(
                "{} failed numbering definition {!r} contains data".format(
                    path.name,
                    definition["name"],
                )
            )
    if candidate_outcomes != candidate_names:
        raise FixtureValidationError(
            "{} did not probe every numbering candidate exactly once".format(
                path.name
            )
        )
    if len(candidate_outcomes) != len(set(candidate_outcomes)):
        raise FixtureValidationError(
            "{} duplicates a numbering candidate outcome".format(path.name)
        )
    definitions_by_name = {row["name"]: row for row in definitions}
    for failure in candidate_failures:
        if not failure["status"] or failure["name"] not in definitions_by_name:
            raise FixtureValidationError(
                "{} contains a malformed candidate failure".format(path.name)
            )

    controls_by_tag: Dict[str, List[Mapping[str, Any]]] = {}
    for row in controls:
        controls_by_tag.setdefault(row["locale_tag"], []).append(row)
    if tuple(controls_by_tag) != ANDROID_FORMATTER_THAI_CONTROL_TAGS:
        raise FixtureValidationError(
            "{} Thai control locale coverage changed".format(path.name)
        )
    if any(
        [row["instant_utc"] for row in rows] != list(fixed_instants)
        for rows in controls_by_tag.values()
    ):
        raise FixtureValidationError(
            "{} Thai control instant coverage changed".format(path.name)
        )

    for row in candidates + controls:
        try:
            parsed_epoch_millis = int(row["parsed_epoch_millis"])
        except (TypeError, ValueError) as error:
            raise FixtureValidationError(
                "{} contains an invalid parsed epoch".format(path.name)
            ) from error
        normalized_legacy = normalize_matrix_digits(
            row["legacy_formatted"],
            row["zero_code_point"],
            row["control_layout"],
        )
        if row["kind"] == "numbering_candidate":
            definition = definitions_by_name[row["candidate_name"]]
            expected_locale_tag = "en-US-u-nu-{}".format(
                row["candidate_name"]
            )
            definition_matches = (
                row["definition_name"] == definition["definition_name"]
                and row["definition_status"] == definition["definition_status"]
                and row["radix"] == definition["radix"]
                and row["algorithmic"] == definition["algorithmic"]
                and row["description"] == definition["description"]
                and row["numbering_system"] == row["candidate_name"]
                and row["locale_tag"] == expected_locale_tag
            )
        else:
            definition_matches = (
                not row["candidate_name"]
                and not row["definition_name"]
                and row["definition_status"] == "not_applicable"
                and row["radix"] is None
                and row["algorithmic"] is None
                and not row["description"]
            )
        if (
            not definition_matches
            or row["observation_status"] != "ok"
            or row["calendar_class"] != ANDROID_FORMATTER_CALENDAR_CLASS
            or row["calendar"] != ANDROID_FORMATTER_CALENDAR_TYPE
            or row["default_calendar_class"] != ANDROID_FORMATTER_CALENDAR_CLASS
            or row["default_calendar"] != ANDROID_FORMATTER_CALENDAR_TYPE
            or row["strict_round_trip"] != "true"
            or parsed_epoch_millis
            != epoch_millis_for_instant(row["instant_utc"])
            or row["default_constructor_matches"] != "true"
            or row["formatted"]
            != formatted_timestamp_for_instant(
                row["instant_utc"],
                row["zero_code_point"],
            )
            or row["legacy_formatted"]
            != localized_formatter_digits(
                legacy_digits_for_instant(row["instant_utc"]),
                row["zero_code_point"],
            )
            or normalized_legacy != legacy_digits_for_instant(row["instant_utc"])
            or row["source_year"]
            != int(formatter_instant_fields(row["instant_utc"])[0])
            or normalize_matrix_digits(
                row["number_digits"],
                row["zero_code_point"],
            )
            != "1234567890"
        ):
            raise FixtureValidationError(
                "{} contains non-Gregorian or non-round-tripping formatter evidence".format(
                    path.name
                )
            )

    successful_zeroes = {row["zero_code_point"] for row in candidates}
    if not {
        row["zero_code_point"] for row in signatures
    }.issubset(successful_zeroes):
        raise FixtureValidationError(
            "{} explicit numbering candidates do not cover locale outputs".format(
                path.name
            )
        )

    return {
        "path": path,
        "sha256": expected_sha256,
        "api_level": api_level,
        "metadata": metadata,
        "signatures": tuple(signatures),
        "candidate_definitions": tuple(definitions),
        "candidates": tuple(candidates),
        "candidate_failures": tuple(candidate_failures),
        "thai_controls": tuple(controls),
    }


@lru_cache(maxsize=1)
def formatter_probe_matrix() -> Mapping[str, Any]:
    for artifact in (
        ANDROID_FORMATTER_PROBE_SOURCE_PATH,
        ANDROID_FORMATTER_PROBE_RUNNER_PATH,
    ):
        if not artifact.is_file():
            raise FixtureValidationError(
                "Missing Android formatter probe artifact {}".format(artifact)
            )
    source_sha256 = hashlib.sha256(
        ANDROID_FORMATTER_PROBE_SOURCE_PATH.read_bytes()
    ).hexdigest()
    if source_sha256 != ANDROID_FORMATTER_PROBE_SOURCE_SHA256:
        raise FixtureValidationError(
            "Android instrumentation probe source bytes changed"
        )
    runner_sha256 = hashlib.sha256(
        ANDROID_FORMATTER_PROBE_RUNNER_PATH.read_bytes()
    ).hexdigest()
    if not (ANDROID_FORMATTER_PROBE_RUNNER_PATH.stat().st_mode & 0o111):
        raise FixtureValidationError(
            "Android formatter probe runner is not executable"
        )
    if runner_sha256 != ANDROID_FORMATTER_PROBE_RUNNER_SHA256:
        raise FixtureValidationError(
            "Android formatter probe runner bytes changed"
        )
    platforms = tuple(
        parse_android_formatter_evidence(path)
        for path in ANDROID_FORMATTER_EVIDENCE_PATHS
    )
    if tuple(platform["api_level"] for platform in platforms) != (26, 36):
        raise FixtureValidationError(
            "Android formatter evidence must be ordered API26 then API36"
        )

    representative_by_zero: Dict[int, Mapping[str, Any]] = {}
    api_levels_by_zero: Dict[int, set[int]] = {}
    control_layouts_by_zero: Dict[int, set[str]] = {}
    definition_api_levels_by_zero: Dict[int, set[int]] = {}
    for platform in platforms:
        definitions = {
            row["name"]: row for row in platform["candidate_definitions"]
        }
        for definition in definitions.values():
            definition_zero = definition["definition_zero_code_point"]
            if definition_zero is not None:
                definition_api_levels_by_zero.setdefault(
                    definition_zero,
                    set(),
                ).add(platform["api_level"])
        for row in platform["candidates"]:
            if row["instant_utc"] != ANDROID_FORMATTER_SIGNATURE_INSTANT:
                continue
            zero_code_point = row["zero_code_point"]
            api_levels_by_zero.setdefault(zero_code_point, set()).add(
                platform["api_level"]
            )
            control_layouts_by_zero.setdefault(
                zero_code_point,
                set(),
            ).add(row["control_layout"])
            definition = definitions[row["candidate_name"]]
            if (
                definition["definition_zero_code_point"] == zero_code_point
                and definition["algorithmic"] is False
            ):
                representative_by_zero.setdefault(zero_code_point, row)
        for row in (
            *platform["signatures"],
            *platform["thai_controls"],
        ):
            zero_code_point = row["zero_code_point"]
            control_layouts_by_zero.setdefault(
                zero_code_point,
                set(),
            ).add(row["control_layout"])

    if set(representative_by_zero) != set(api_levels_by_zero):
        raise FixtureValidationError(
            "Every emitted Android digit block needs an exact decimal candidate"
        )

    candidate_digit_blocks = tuple(
        {
            "zero_code_point": zero_code_point,
            "numbering_system": representative_by_zero[zero_code_point][
                "candidate_name"
            ],
            "locale_tag": representative_by_zero[zero_code_point]["locale_tag"],
            "calendar": representative_by_zero[zero_code_point]["calendar"],
            "instant_utc": representative_by_zero[zero_code_point]["instant_utc"],
            "formatted": representative_by_zero[zero_code_point][
                "legacy_formatted"
            ],
            "source_year": representative_by_zero[zero_code_point]["source_year"],
            "api_levels": tuple(sorted(api_levels_by_zero[zero_code_point])),
            "control_layouts": tuple(
                sorted(control_layouts_by_zero[zero_code_point])
            ),
        }
        for zero_code_point in sorted(representative_by_zero)
    )
    if any(
        row["calendar"] != CALENDAR_GREGORIAN
        for row in candidate_digit_blocks
    ):
        raise FixtureValidationError(
            "Android formatter candidate uses a non-Gregorian calendar"
        )
    return {
        "probe_source": ANDROID_FORMATTER_PROBE_SOURCE_PATH,
        "probe_source_sha256": source_sha256,
        "probe_runner": ANDROID_FORMATTER_PROBE_RUNNER_PATH,
        "probe_runner_sha256": runner_sha256,
        "platforms": platforms,
        "candidate_digit_blocks": candidate_digit_blocks,
        "candidate_definition_digit_blocks": tuple(
            {
                "zero_code_point": zero_code_point,
                "api_levels": tuple(
                    sorted(definition_api_levels_by_zero[zero_code_point])
                ),
            }
            for zero_code_point in sorted(definition_api_levels_by_zero)
        ),
    }


def formatter_candidate_digit_blocks() -> Tuple[Mapping[str, Any], ...]:
    return tuple(formatter_probe_matrix()["candidate_digit_blocks"])


def formatter_source_emittable_zeroes() -> frozenset[int]:
    return frozenset(
        row["zero_code_point"] for row in formatter_candidate_digit_blocks()
    )


def formatter_source_control_layouts() -> Mapping[int, frozenset[str]]:
    return {
        row["zero_code_point"]: frozenset(row["control_layouts"])
        for row in formatter_candidate_digit_blocks()
    }


def formatter_candidate_definition_zeroes() -> frozenset[int]:
    return frozenset(
        row["zero_code_point"]
        for row in formatter_probe_matrix()["candidate_definition_digit_blocks"]
    )


def formatter_oracle_manifest() -> Mapping[str, Any]:
    matrix = formatter_probe_matrix()
    return {
        "platform": "Android",
        "writer": "java.text.SimpleDateFormat",
        "legacy_pattern": "yyyyMMddHHmmss",
        "probe_source": ANDROID_FORMATTER_PROBE_SOURCE_PATH.name,
        "probe_source_sha256": matrix["probe_source_sha256"],
        "probe_runner": ANDROID_FORMATTER_PROBE_RUNNER_PATH.name,
        "probe_runner_sha256": matrix["probe_runner_sha256"],
        "probe_runner_command": (
            "python3 tools/legacy-fixtures/"
            "run_android_formatter_probe.py --compare"
        ),
        "probe_runner_update_command": (
            "python3 tools/legacy-fixtures/"
            "run_android_formatter_probe.py --update"
        ),
        "probe_runner_required_environment": [
            "ANDROID_SDK_ROOT",
            "ANDROID_AVD_HOME",
            "JAVA_HOME",
        ],
        "probe_runner_exact_command_sequence_source": (
            "the byte-pinned run_android_formatter_probe.py source"
        ),
        "evidence": [
            {
                "file": platform["path"].name,
                "sha256": platform["sha256"],
                "api_level": platform["api_level"],
                "release": platform["metadata"]["release"],
                "system_image_package": platform["metadata"][
                    "system_image_package"
                ],
                "system_image_revision": platform["metadata"][
                    "system_image_revision"
                ],
                "build_fingerprint": platform["metadata"][
                    "build_fingerprint"
                ],
                "abi": platform["metadata"]["abi"],
                "supported_abis": platform["metadata"]["supported_abis"],
                "emulator_package": platform["metadata"]["emulator_package"],
                "emulator_package_revision": platform["metadata"][
                    "emulator_package_revision"
                ],
                "emulator_version": platform["metadata"]["emulator_version"],
                "emulator_acceleration": platform["metadata"][
                    "emulator_acceleration"
                ],
                "compile_sdk_package": platform["metadata"][
                    "compile_sdk_package"
                ],
                "compile_sdk_revision": platform["metadata"][
                    "compile_sdk_revision"
                ],
                "build_tools_package": platform["metadata"][
                    "build_tools_package"
                ],
                "build_tools_revision": platform["metadata"][
                    "build_tools_revision"
                ],
                "java_version": platform["metadata"]["java_version"],
                "java_runtime_version": platform["metadata"][
                    "java_runtime_version"
                ],
                "java_vm_name": platform["metadata"]["java_vm_name"],
                "java_vm_version": platform["metadata"]["java_vm_version"],
                "icu_version": platform["metadata"]["icu_version"],
                "unicode_version": platform["metadata"]["unicode_version"],
                "cldr_version": platform["metadata"]["cldr_version"],
                "formatter_pattern": platform["metadata"]["pattern"],
                "legacy_pattern": platform["metadata"]["legacy_pattern"],
                "timezone": platform["metadata"]["timezone"],
                "fixed_instants_utc": platform["metadata"][
                    "fixed_instants_utc"
                ].split(","),
                "numbering_candidate_instant_utc": platform["metadata"][
                    "numbering_candidate_instant_utc"
                ],
                "available_locale_count": int(
                    platform["metadata"]["available_locale_count"]
                ),
                "locale_rows_sha256": platform["metadata"][
                    "locale_rows_sha256"
                ],
                "numbering_candidate_source": platform["metadata"][
                    "numbering_candidate_source"
                ],
                "numbering_candidate_version": platform["metadata"][
                    "numbering_candidate_version"
                ],
                "numbering_candidate_count": len(
                    platform["candidate_definitions"]
                ),
                "numbering_candidate_names_sha256": platform["metadata"][
                    "numbering_candidate_names_sha256"
                ],
                "numbering_candidate_success_count": len(
                    platform["candidates"]
                ),
                "numbering_candidate_failure_count": len(
                    platform["candidate_failures"]
                ),
                "numbering_candidate_native_crash_count": sum(
                    row["status"] == "native_process_crash"
                    for row in platform["candidate_failures"]
                ),
                "emitted_digit_block_count": len(
                    {
                        row["zero_code_point"]
                        for row in platform["candidates"]
                    }
                ),
            }
            for platform in matrix["platforms"]
        ],
        "calendar_class": ANDROID_FORMATTER_CALENDAR_CLASS,
        "calendar_type": ANDROID_FORMATTER_CALENDAR_TYPE,
        "platform_union_policy": (
            "union_of_decimal_blocks_actually_emitted_by_every_"
            "android_icu_numbering_candidate_on_api26_and_api36"
        ),
        "source_emittable_digit_zero_code_points": [
            "U+{:04X}".format(row["zero_code_point"])
            for row in matrix["candidate_digit_blocks"]
        ],
        "source_emittable_supplementary_zero_code_points": [
            "U+{:04X}".format(row["zero_code_point"])
            for row in matrix["candidate_digit_blocks"]
            if row["zero_code_point"] > 0xFFFF
        ],
        "candidate_definition_supplementary_zero_code_points": [
            "U+{:04X}".format(row["zero_code_point"])
            for row in matrix["candidate_definition_digit_blocks"]
            if row["zero_code_point"] > 0xFFFF
        ],
        "source_proven_format_control_layouts": {
            "U+{:04X}".format(row["zero_code_point"]): list(
                row["control_layouts"]
            )
            for row in matrix["candidate_digit_blocks"]
            if any(row["control_layouts"])
        },
        "timestamp_parser": (
            "code_point_aware_single_evidenced_contiguous_decimal_block_"
            "with_only_source_proven_control_layouts"
        ),
        "candidate_fixture": "formatter_digit_blocks",
    }


def verify_android_formatter_evidence(
    root: Path = TOOL_ROOT,
) -> Mapping[str, Any]:
    root = root.resolve()
    matrix = formatter_probe_matrix()
    for source in (
        ANDROID_FORMATTER_PROBE_SOURCE_PATH,
        ANDROID_FORMATTER_PROBE_RUNNER_PATH,
        *ANDROID_FORMATTER_EVIDENCE_PATHS,
    ):
        candidate = root / source.name
        if not candidate.is_file():
            raise FixtureValidationError(
                "Missing Android formatter artifact {}".format(candidate)
            )
        compare_exact_artifact_bytes(
            source.name,
            source,
            candidate,
        )
    return {
        "platform_count": len(matrix["platforms"]),
        "api_levels": [
            platform["api_level"] for platform in matrix["platforms"]
        ],
        "digit_block_count": len(matrix["candidate_digit_blocks"]),
        "numbering_candidate_count": sum(
            len(platform["candidate_definitions"])
            for platform in matrix["platforms"]
        ),
        "numbering_candidate_failure_count": sum(
            len(platform["candidate_failures"])
            for platform in matrix["platforms"]
        ),
        "numbering_candidate_native_crash_count": sum(
            row["status"] == "native_process_crash"
            for platform in matrix["platforms"]
            for row in platform["candidate_failures"]
        ),
        "supplementary_emitted_digit_block_count": sum(
            row["zero_code_point"] > 0xFFFF
            for row in matrix["candidate_digit_blocks"]
        ),
        "detailed_row_count": sum(
            len(platform["candidates"]) + len(platform["thai_controls"])
            for platform in matrix["platforms"]
        ),
    }


def fixture_cases() -> Tuple[FixtureCase, ...]:
    exact = "exact"
    epsilon = "epsilon"
    formatter_blocks = formatter_candidate_digit_blocks()
    return (
        FixtureCase(
            key="empty",
            description=(
                "Complete Android/application schema with no activities or track "
                "points; this is valid empty data."
            ),
            activities=(),
            track_points=(),
        ),
        FixtureCase(
            key="startup_no_business_tables",
            description=(
                "Startup snapshot after Android created platform metadata but before "
                "either application table was created."
            ),
            activities=(),
            track_points=(),
            business_tables=(),
        ),
        FixtureCase(
            key="startup_activity_only",
            description=(
                "Startup snapshot interrupted between the independent ACTIVITY and "
                "GPS_POINTS CREATE TABLE statements."
            ),
            activities=(),
            track_points=(),
            business_tables=("ACTIVITY",),
        ),
        FixtureCase(
            key="startup_activity_id_zero",
            description=(
                "Complete schema with a scheduled startup point written before the "
                "new activity ID replaced the shared default value 0."
            ),
            activities=(),
            track_points=(
                point(
                    1,
                    0,
                    "20240101000000",
                    0.0,
                    0.0,
                    None,
                    None,
                    None,
                    None,
                    0.0,
                ),
            ),
            representative_values=(
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 1,
                    "column": "ACTIVITYID",
                    "expected": 0,
                    "comparison": exact,
                },
            ),
        ),
        FixtureCase(
            key="start_only_zero_points",
            description=(
                "Complete schema with one source-reachable activity containing only "
                "GMTSTART and no GPS points; point-driven or inner-join extraction "
                "must not drop it."
            ),
            activities=(
                activity(
                    1,
                    "20240101120000",
                    None,
                    None,
                    None,
                    None,
                    None,
                    None,
                ),
            ),
            track_points=(),
            representative_values=(
                {
                    "table": "ACTIVITY",
                    "legacy_id": 1,
                    "column": "GMTSTART",
                    "expected": "20240101120000",
                    "comparison": exact,
                },
            ),
        ),
        FixtureCase(
            key="active_wal_snapshot",
            description=(
                "Android-compatible WAL snapshot whose committed activity and point "
                "rows remain outside the main database file in the active -wal/-shm "
                "snapshot."
            ),
            activities=(
                activity(
                    1,
                    "20240101130000",
                    None,
                    None,
                    None,
                    None,
                    None,
                    None,
                ),
            ),
            track_points=(
                point(
                    1,
                    1,
                    "20240101130000",
                    47.0,
                    -122.0,
                    10.0,
                    3.0,
                    1.5,
                    90.0,
                    100.0,
                ),
                point(
                    2,
                    1,
                    "20240101130001",
                    47.0001,
                    -122.0001,
                    10.25,
                    3.25,
                    1.625,
                    91.0,
                    101.0,
                ),
            ),
            representative_values=(
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 2,
                    "column": "GMTTIMESTAMP",
                    "expected": "20240101130001",
                    "comparison": exact,
                },
            ),
            storage=STORAGE_ACTIVE_WAL,
        ),
        FixtureCase(
            key="representative",
            description=(
                "Three synthetic activities with multiple points, nullable optional "
                "measurements, and a source-reachable unfinished activity."
            ),
            activities=(
                activity(
                    1,
                    "20240102030405",
                    "20240102033405",
                    "Synthetic Morning Run",
                    "Generated fixture activity A; no real user data.",
                    5123.75,
                    1800.25,
                    351.384765625,
                ),
                activity(
                    2,
                    "20240103120000",
                    "20240103124530",
                    "Synthetic Trail Walk",
                    "Generated fixture activity B; no real user data.",
                    2345.5,
                    2730.0,
                    1163.896184,
                ),
                activity(3, "20240104101010", None, None, None, None, None, None),
            ),
            track_points=(
                point(
                    1,
                    1,
                    "20240102030405",
                    47.6205,
                    -122.3493,
                    32.125,
                    3.5,
                    2.75,
                    91.25,
                    121.5,
                ),
                point(
                    2,
                    1,
                    "20240102030406",
                    47.6205101,
                    -122.3492899,
                    None,
                    4.25,
                    None,
                    None,
                    123.0,
                ),
                point(
                    3,
                    1,
                    "20240102030407",
                    47.6205202,
                    -122.3492798,
                    32.625,
                    4.0,
                    2.875,
                    92.5,
                    124.25,
                ),
                point(
                    4,
                    2,
                    "20240103120000",
                    40.015,
                    -105.2705,
                    1640.75,
                    5.5,
                    1.25,
                    180.0,
                    98.0,
                ),
                point(
                    5,
                    2,
                    "20240103120001",
                    40.0150105,
                    -105.2704895,
                    1641.125,
                    5.25,
                    1.375,
                    181.25,
                    99.5,
                ),
                point(
                    6,
                    3,
                    "20240104101010",
                    0.0,
                    0.0,
                    None,
                    None,
                    None,
                    None,
                    0.0,
                ),
            ),
            representative_values=(
                {
                    "table": "ACTIVITY",
                    "legacy_id": 1,
                    "column": "GMTSTART",
                    "expected": "20240102030405",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 1,
                    "column": "DISTANCE",
                    "expected": 5123.75,
                    "comparison": epsilon,
                    "epsilon": 1e-12,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 2,
                    "column": "ALTITUDE",
                    "expected": None,
                    "comparison": exact,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 6,
                    "column": "LONGITUDE",
                    "expected": 0.0,
                    "comparison": epsilon,
                    "epsilon": 0.0,
                },
            ),
        ),
        FixtureCase(
            key="timestamp_ordering",
            description=(
                "Duplicate and non-monotonic point timestamps stored in required "
                "ascending signed 64-bit ID order."
            ),
            activities=(
                activity(
                    9007199254740993,
                    "20240801000000",
                    "20240801000003",
                    "Synthetic Timestamp Ordering",
                    "ID order is authoritative even when timestamps duplicate or regress.",
                    3.0,
                    3.0,
                    1.0,
                ),
            ),
            track_points=(
                point(
                    2147483648,
                    9007199254740993,
                    "20240801000002",
                    35.0,
                    -120.0,
                    1.0,
                    2.0,
                    3.0,
                    4.0,
                    100.0,
                ),
                point(
                    9007199254740992,
                    9007199254740993,
                    "20240801000001",
                    35.0001,
                    -120.0001,
                    1.1,
                    2.1,
                    3.1,
                    4.1,
                    101.0,
                ),
                point(
                    9007199254740993,
                    9007199254740993,
                    "20240801000002",
                    35.0002,
                    -120.0002,
                    1.2,
                    2.2,
                    3.2,
                    4.2,
                    102.0,
                ),
            ),
            representative_values=(
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 9007199254740992,
                    "column": "GMTTIMESTAMP",
                    "expected": "20240801000001",
                    "comparison": exact,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 9007199254740993,
                    "column": "ID",
                    "expected": 9007199254740993,
                    "comparison": exact,
                },
            ),
        ),
        FixtureCase(
            key="precision",
            description=(
                "Precision-sensitive REAL values, leap-day timestamps, fractional "
                "heart rate, and an exact 64-bit ID above the JSON safe-integer range."
            ),
            activities=(
                activity(
                    1,
                    "20240229010203",
                    "20240229012345",
                    "Synthetic Precision Session",
                    "Values are chosen to expose integer and float narrowing.",
                    12345.678901234567,
                    987.654321098765,
                    4.3210987654321,
                ),
                activity(
                    9007199254740993,
                    "20240301000000",
                    "20240301000001",
                    "Synthetic 64-bit ID Session",
                    "Exercises SQLite INTEGER values above Java int and JSON safe ranges.",
                    0.000000123456789,
                    1.000000000000001,
                    0.0000001234567889,
                ),
            ),
            track_points=(
                point(
                    1,
                    1,
                    "20240229010203",
                    89.99999912345678,
                    -179.9999998765432,
                    -12.3456789012345,
                    0.1234567890123,
                    5.6789012345678,
                    359.9876543210987,
                    147.625,
                ),
                point(
                    9007199254740993,
                    9007199254740993,
                    "20240301000000",
                    -45.1234567890123,
                    170.9876543210987,
                    8848.860123456,
                    1.00000011920929,
                    0.0009765625,
                    0.0001220703125,
                    63.875,
                ),
            ),
            representative_values=(
                {
                    "table": "ACTIVITY",
                    "legacy_id": 1,
                    "column": "DISTANCE",
                    "expected": 12345.678901234567,
                    "comparison": epsilon,
                    "epsilon": 1e-12,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 9007199254740993,
                    "column": "ID",
                    "expected": 9007199254740993,
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 9007199254740993,
                    "column": "TIME",
                    "expected": 1.000000000000001,
                    "comparison": "ieee754",
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 1,
                    "column": "LATITUDE",
                    "expected": 89.99999912345678,
                    "comparison": epsilon,
                    "epsilon": 1e-12,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 1,
                    "column": "LONGITUDE",
                    "expected": -179.9999998765432,
                    "comparison": epsilon,
                    "epsilon": 1e-12,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 1,
                    "column": "ALTITUDE",
                    "expected": -12.3456789012345,
                    "comparison": epsilon,
                    "epsilon": 1e-12,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 1,
                    "column": "SPEED",
                    "expected": 5.6789012345678,
                    "comparison": epsilon,
                    "epsilon": 1e-12,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 1,
                    "column": "BEARING",
                    "expected": 359.9876543210987,
                    "comparison": epsilon,
                    "epsilon": 1e-12,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 1,
                    "column": "HEARTRATE",
                    "expected": 147.625,
                    "comparison": epsilon,
                    "epsilon": 1e-12,
                },
            ),
        ),
        FixtureCase(
            key="localized_timestamps",
            description=(
                "Current Arabic-Egypt metadata with source-realistic Arabic-Indic, "
                "historical Bengali, ASCII, and fullwidth rows, plus strict "
                "mixed-block, unknown supplementary, and Unicode-format controls."
            ),
            activities=(
                activity(
                    1,
                    "٢٠٢٤٠٧٠٨٠٩١٠١١",
                    "٢٠٢٤٠٧٠٨٠٩١٠١٣",
                    "Synthetic Arabic-Indic Session",
                    "Valid localized legacy timestamps retain their exact source text.",
                    120.0,
                    300.0,
                    2.5,
                ),
                activity(
                    2,
                    "২০২৪০৭০৮০৯১১১১",
                    "২০২৪০৭০৮০৯১১১৩",
                    "Synthetic Historical Bengali Session",
                    "Bengali digits predate the database's current ar_EG metadata.",
                    121.0,
                    301.0,
                    2.6,
                ),
                activity(
                    3,
                    "20240708091211",
                    "20240708091213",
                    "Synthetic Historical ASCII Session",
                    "ASCII digits predate the database's current ar_EG metadata.",
                    122.0,
                    302.0,
                    2.7,
                ),
                activity(
                    4,
                    "٢٠٢٤٠٢٣٠٠١٠١٠١",
                    None,
                    "Synthetic Invalid Localized Date",
                    None,
                    None,
                    None,
                    None,
                ),
                activity(
                    5,
                    "٢٠٢٤٠٧٠٨09١٠١١",
                    None,
                    "Synthetic Mixed ASCII Arabic Digits",
                    None,
                    None,
                    None,
                    None,
                ),
                activity(
                    6,
                    "٢٠٢٤٠٧٠٨০৯١٠١١",
                    None,
                    "Synthetic Mixed Non-ASCII Digit Blocks",
                    None,
                    None,
                    None,
                    None,
                ),
                activity(
                    7,
                    "٢٠٢٤٠٧٠٨\u200f٠٩١٠١١",
                    None,
                    "Synthetic Embedded Right-To-Left Mark",
                    None,
                    None,
                    None,
                    None,
                ),
                activity(
                    8,
                    "٢٠٢٤٠٧٠٨٠٩١٠١¹",
                    None,
                    "Synthetic Non-Decimal Lookalike",
                    None,
                    None,
                    None,
                    None,
                ),
                activity(
                    9,
                    "٢٠٢٤٠٧٠٨/٩١٠١١",
                    None,
                    "Synthetic Localized Separator",
                    None,
                    None,
                    None,
                    None,
                ),
                activity(
                    10,
                    "٢٠٢٤٠٧٠٨٢٤٠٠٠٠",
                    None,
                    "Synthetic Invalid Localized Time",
                    None,
                    None,
                    None,
                    None,
                ),
                activity(
                    11,
                    "２０２４０７０８０９１３１１",
                    None,
                    "Synthetic Fullwidth Digit Session",
                    (
                        "Fullwidth digits are emitted by explicit Android "
                        "u-nu-fullwide formatter candidates."
                    ),
                    None,
                    None,
                    None,
                ),
                activity(
                    12,
                    "𐒢𐒠𐒢𐒤𐒠𐒧𐒠𐒨𐒠𐒩𐒡𐒤𐒡𐒡",
                    None,
                    "Synthetic Unsupported Supplementary Digits",
                    (
                        "Osmanya is an ICU candidate definition, but the pinned "
                        "java.text formatter falls back to ASCII rather than "
                        "emitting this supplementary-plane block."
                    ),
                    None,
                    None,
                    None,
                ),
            ),
            track_points=(
                point(
                    1,
                    1,
                    "٢٠٢٤٠٧٠٨٠٩١٠١١",
                    30.0444,
                    31.2357,
                    23.5,
                    3.0,
                    2.0,
                    90.0,
                    120.0,
                ),
                point(
                    2,
                    1,
                    "٢٠٢٤٠٧٠٨٠٩١٠١٢",
                    30.0445,
                    31.2358,
                    23.625,
                    3.125,
                    2.125,
                    91.0,
                    121.0,
                ),
                point(
                    3,
                    2,
                    "২০২৪০৭০৮০৯১১১১",
                    23.8103,
                    90.4125,
                    7.5,
                    3.25,
                    2.25,
                    92.0,
                    122.0,
                ),
                point(
                    4,
                    2,
                    "২০২৪০৭০৮০৯১১১২",
                    23.8104,
                    90.4126,
                    7.625,
                    3.375,
                    2.375,
                    93.0,
                    123.0,
                ),
                point(
                    5,
                    3,
                    "20240708091211",
                    47.6062,
                    -122.3321,
                    12.5,
                    3.5,
                    2.5,
                    94.0,
                    124.0,
                ),
                point(
                    6,
                    3,
                    "20240708091212",
                    47.6063,
                    -122.3320,
                    12.625,
                    3.625,
                    2.625,
                    95.0,
                    125.0,
                ),
                point(
                    7,
                    1,
                    "٢٠٢٤١٣٠٨٠٩١٠١٤",
                    30.0446,
                    31.2359,
                    None,
                    None,
                    None,
                    None,
                    122.0,
                ),
                point(
                    8,
                    1,
                    "٢٠٢٤٠٧٠٨09١٠١٥",
                    30.0447,
                    31.2360,
                    None,
                    None,
                    None,
                    None,
                    123.0,
                ),
                point(
                    9,
                    1,
                    "٢٠٢٤٠٧٠٨০৯١٠١٦",
                    30.0448,
                    31.2361,
                    None,
                    None,
                    None,
                    None,
                    124.0,
                ),
                point(
                    10,
                    1,
                    "٢٠٢٤٠٧٠٨\u2066٠٩١٠١٧",
                    30.0449,
                    31.2362,
                    None,
                    None,
                    None,
                    None,
                    125.0,
                ),
                point(
                    11,
                    1,
                    "٢٠٢٤٠٧٠٨٠٩١٠١⁶",
                    30.0450,
                    31.2363,
                    None,
                    None,
                    None,
                    None,
                    126.0,
                ),
                point(
                    12,
                    1,
                    "٢٠٢٤٠٧٠٨/٩١٠١٧",
                    30.0451,
                    31.2364,
                    None,
                    None,
                    None,
                    None,
                    127.0,
                ),
                point(
                    13,
                    11,
                    "２０２４０７０８０９１３１２",
                    30.0452,
                    31.2365,
                    None,
                    None,
                    None,
                    None,
                    128.0,
                ),
                point(
                    14,
                    12,
                    "𐒢𐒠𐒢𐒤𐒠𐒧𐒠𐒨𐒠𐒩𐒡𐒤𐒡𐒢",
                    30.0453,
                    31.2366,
                    None,
                    None,
                    None,
                    None,
                    129.0,
                ),
            ),
            representative_values=(
                {
                    "table": "ACTIVITY",
                    "legacy_id": 1,
                    "column": "GMTSTART",
                    "expected": "٢٠٢٤٠٧٠٨٠٩١٠١١",
                    "comparison": exact,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 4,
                    "column": "GMTTIMESTAMP",
                    "expected": "২০২৪০৭০৮০৯১১১২",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 3,
                    "column": "GMTSTART",
                    "expected": "20240708091211",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 6,
                    "column": "GMTSTART",
                    "expected": "٢٠٢٤٠٧٠٨০৯١٠١١",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 7,
                    "column": "GMTSTART",
                    "expected": "٢٠٢٤٠٧٠٨\u200f٠٩١٠١١",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 11,
                    "column": "GMTSTART",
                    "expected": "２０２４０７０８０９１３１１",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 12,
                    "column": "GMTSTART",
                    "expected": "𐒢𐒠𐒢𐒤𐒠𐒧𐒠𐒨𐒠𐒩𐒡𐒤𐒡𐒡",
                    "comparison": exact,
                },
            ),
            android_locale=AR_EG_ANDROID_METADATA_LOCALE,
            default_calendar_evidence=CalendarEvidence(
                calendar=CALENDAR_GREGORIAN,
                locale_tag="ar-EG",
            ),
            activity_calendar_evidence=(
                (
                    2,
                    CalendarEvidence(
                        calendar=CALENDAR_GREGORIAN,
                        locale_tag="bn-BD",
                    ),
                ),
                (
                    3,
                    CalendarEvidence(
                        calendar=CALENDAR_GREGORIAN,
                        locale_tag="en-US",
                    ),
                ),
                (
                    11,
                    CalendarEvidence(
                        calendar=CALENDAR_GREGORIAN,
                        locale_tag="en-US-u-nu-fullwide",
                    ),
                ),
            ),
        ),
        FixtureCase(
            key="formatter_digit_blocks",
            description=(
                "One activity and point for every Unicode Nd digit block emitted "
                "by Android java.text.SimpleDateFormat after probing every ICU "
                "numbering-system candidate on API26 and API36."
            ),
            activities=tuple(
                activity(
                    index,
                    block["formatted"],
                    None,
                    "Synthetic Formatter Block U+{:04X}".format(
                        block["zero_code_point"]
                    ),
                    (
                        "Source-backed by Android API {} formatter evidence for "
                        "numbering system {} ({})."
                    ).format(
                        "/".join(str(value) for value in block["api_levels"]),
                        block["numbering_system"],
                        block["locale_tag"],
                    ),
                    None,
                    None,
                    None,
                )
                for index, block in enumerate(formatter_blocks, start=1)
            ),
            track_points=tuple(
                point(
                    index,
                    index,
                    block["formatted"],
                    10.0 + index,
                    20.0 + index,
                    None,
                    None,
                    None,
                    None,
                    100.0 + index,
                )
                for index, block in enumerate(formatter_blocks, start=1)
            ),
            representative_values=tuple(
                {
                    "table": "ACTIVITY",
                    "legacy_id": index,
                    "column": "GMTSTART",
                    "expected": block["formatted"],
                    "comparison": exact,
                }
                for index, block in enumerate(formatter_blocks, start=1)
            ),
            activity_calendar_evidence=tuple(
                (
                    index,
                    CalendarEvidence(
                        calendar=block["calendar"],
                        locale_tag=block["locale_tag"],
                        source=ANDROID_FORMATTER_EVIDENCE_SOURCE,
                    ),
                )
                for index, block in enumerate(formatter_blocks, start=1)
            ),
        ),
        FixtureCase(
            key="android_thai_gregorian",
            description=(
                "Android API26/API36 Thai controls across years 2000, 2024, 2032, "
                "and 2567 prove ordinary th-TH and explicit Thai digits retain "
                "Gregorian calendar semantics without a 543-year conversion."
            ),
            activities=(
                activity(
                    1,
                    "20000229123456",
                    "20000229123458",
                    "Synthetic Ordinary Thai Year 2000",
                    "Android th-TH emits and parses Gregorian leap day 2000.",
                    120.0,
                    2.0,
                    16.666666666666668,
                ),
                activity(
                    2,
                    "20240708091011",
                    "20240708091013",
                    "Synthetic Ordinary Thai Year 2024",
                    "Android th-TH emits Gregorian year 2024 with ASCII digits.",
                    121.0,
                    2.0,
                    16.52892561983471,
                ),
                activity(
                    3,
                    "20320229235957",
                    "20320229235959",
                    "Synthetic Ordinary Thai Year 2032",
                    "Android th-TH emits and parses Gregorian leap day 2032.",
                    122.0,
                    2.0,
                    16.39344262295082,
                ),
                activity(
                    4,
                    "25670708091011",
                    "25670708091013",
                    "Synthetic Ordinary Thai Year 2567",
                    (
                        "Android th-TH preserves valid Gregorian year 2567 rather "
                        "than subtracting 543."
                    ),
                    123.0,
                    2.0,
                    16.260162601626018,
                ),
                activity(
                    5,
                    "๒๐๒๔๐๗๐๘๐๙๑๐๑๑",
                    "๒๐๒๔๐๗๐๘๐๙๑๐๑๓",
                    "Synthetic Thai Digits Year 2024",
                    (
                        "Android th-TH-u-nu-thai changes digits but retains "
                        "Gregorian year 2024."
                    ),
                    124.0,
                    2.0,
                    16.129032258064516,
                ),
                activity(
                    6,
                    "๒๕๖๗๐๗๐๘๐๙๑๐๑๑",
                    "๒๕๖๗๐๗๐๘๐๙๑๐๑๓",
                    "Synthetic Thai Digits Year 2567",
                    (
                        "Android th-TH-u-nu-thai preserves Gregorian year 2567 "
                        "without a Buddhist-calendar conversion."
                    ),
                    125.0,
                    2.0,
                    16.0,
                ),
            ),
            track_points=(
                point(
                    1,
                    1,
                    "20000229123456",
                    13.7563,
                    100.5018,
                    5.0,
                    3.0,
                    2.0,
                    90.0,
                    120.0,
                ),
                point(
                    2,
                    2,
                    "20240708091011",
                    13.7564,
                    100.5019,
                    5.125,
                    3.125,
                    2.125,
                    91.0,
                    121.0,
                ),
                point(
                    3,
                    3,
                    "20320229235957",
                    13.7565,
                    100.5020,
                    5.25,
                    3.25,
                    2.25,
                    92.0,
                    122.0,
                ),
                point(
                    4,
                    4,
                    "25670708091011",
                    13.7566,
                    100.5021,
                    5.375,
                    3.375,
                    2.375,
                    93.0,
                    123.0,
                ),
                point(
                    5,
                    5,
                    "๒๐๒๔๐๗๐๘๐๙๑๐๑๑",
                    13.7567,
                    100.5022,
                    5.5,
                    3.5,
                    2.5,
                    94.0,
                    124.0,
                ),
                point(
                    6,
                    6,
                    "๒๕๖๗๐๗๐๘๐๙๑๐๑๑",
                    13.7568,
                    100.5023,
                    5.625,
                    3.625,
                    2.625,
                    95.0,
                    125.0,
                ),
            ),
            representative_values=(
                {
                    "table": "ACTIVITY",
                    "legacy_id": 1,
                    "column": "GMTSTART",
                    "expected": "20000229123456",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 2,
                    "column": "GMTSTART",
                    "expected": "20240708091011",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 3,
                    "column": "GMTSTART",
                    "expected": "20320229235957",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 4,
                    "column": "GMTSTART",
                    "expected": "25670708091011",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 5,
                    "column": "GMTSTART",
                    "expected": "๒๐๒๔๐๗๐๘๐๙๑๐๑๑",
                    "comparison": exact,
                },
                {
                    "table": "ACTIVITY",
                    "legacy_id": 6,
                    "column": "GMTSTART",
                    "expected": "๒๕๖๗๐๗๐๘๐๙๑๐๑๑",
                    "comparison": exact,
                },
            ),
            android_locale=TH_TH_THAI_ANDROID_METADATA_LOCALE,
            default_calendar_evidence=CalendarEvidence(
                calendar=CALENDAR_GREGORIAN,
                locale_tag="th-TH",
                source=ANDROID_FORMATTER_EVIDENCE_SOURCE,
            ),
            activity_calendar_evidence=(
                (
                    5,
                    CalendarEvidence(
                        calendar=CALENDAR_GREGORIAN,
                        locale_tag="th-TH-u-nu-thai",
                        source=ANDROID_FORMATTER_EVIDENCE_SOURCE,
                    ),
                ),
                (
                    6,
                    CalendarEvidence(
                        calendar=CALENDAR_GREGORIAN,
                        locale_tag="th-TH-u-nu-thai",
                        source=ANDROID_FORMATTER_EVIDENCE_SOURCE,
                    ),
                ),
            ),
        ),
        FixtureCase(
            key="orphan",
            description=(
                "A valid activity plus one valid point and one point referencing a "
                "missing activity, which the legacy schema permits."
            ),
            activities=(
                activity(
                    1,
                    "20240401090000",
                    "20240401090500",
                    "Synthetic Orphan Control",
                    "Parent row for the non-orphan control point.",
                    750.0,
                    300.0,
                    400.0,
                ),
            ),
            track_points=(
                point(
                    1,
                    1,
                    "20240401090000",
                    51.5007,
                    -0.1246,
                    15.25,
                    3.0,
                    2.5,
                    45.0,
                    110.0,
                ),
                point(
                    2,
                    999,
                    "20240401090001",
                    51.5008,
                    -0.1245,
                    15.5,
                    3.25,
                    2.625,
                    46.0,
                    111.0,
                ),
            ),
            representative_values=(
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 2,
                    "column": "ACTIVITYID",
                    "expected": 999,
                    "comparison": exact,
                },
            ),
        ),
        FixtureCase(
            key="malformed_null_partial",
            description=(
                "A source-reachable partial activity plus constraint-permitted invalid "
                "timestamps, dynamic SQLite types, null ownership, and invalid ranges."
            ),
            activities=(
                activity(1, "20240506070809", None, None, None, None, None, None),
                activity(
                    2,
                    "20240230010101",
                    "not-a-timestamp",
                    "Synthetic Malformed Session",
                    None,
                    "not-a-number",
                    None,
                    -3.5,
                ),
            ),
            track_points=(
                point(
                    1,
                    1,
                    "20240506070809",
                    0.0,
                    0.0,
                    None,
                    None,
                    None,
                    None,
                    0.0,
                ),
                point(
                    2,
                    1,
                    "2024050607080",
                    None,
                    "west",
                    "high",
                    -5.0,
                    -1.0,
                    721.25,
                    "fast",
                ),
                point(
                    3,
                    None,
                    "20240506070810",
                    34.0001,
                    -118.0001,
                    100.0,
                    4.0,
                    1.0,
                    90.0,
                    80.0,
                ),
            ),
            representative_values=(
                {
                    "table": "ACTIVITY",
                    "legacy_id": 2,
                    "column": "DISTANCE",
                    "expected": "not-a-number",
                    "comparison": exact,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 2,
                    "column": "LONGITUDE",
                    "expected": "west",
                    "comparison": exact,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 3,
                    "column": "ACTIVITYID",
                    "expected": None,
                    "comparison": exact,
                },
            ),
        ),
        FixtureCase(
            key="malformed_schema",
            description=(
                "Structurally intact SQLite database with both business table names "
                "but an incompatible ACTIVITY column declaration."
            ),
            activities=(
                activity(
                    1,
                    "20240901000000",
                    None,
                    None,
                    None,
                    None,
                    None,
                    None,
                ),
            ),
            track_points=(
                point(
                    1,
                    1,
                    "20240901000000",
                    1.0,
                    2.0,
                    None,
                    None,
                    None,
                    None,
                    0.0,
                ),
            ),
            storage=STORAGE_MALFORMED_SCHEMA,
            blocked_reason="malformed_schema",
        ),
        FixtureCase(
            key="truncated",
            description=(
                "SQLite database shortened by one declared page; migration must stop "
                "at file-structure preflight."
            ),
            activities=(
                activity(
                    1,
                    "20240902000000",
                    None,
                    None,
                    None,
                    None,
                    None,
                    None,
                ),
            ),
            track_points=(
                point(
                    1,
                    1,
                    "20240902000000",
                    1.0,
                    2.0,
                    None,
                    None,
                    None,
                    None,
                    0.0,
                ),
            ),
            storage=STORAGE_TRUNCATED,
            blocked_reason="truncated_sqlite",
        ),
        FixtureCase(
            key="corrupt",
            description=(
                "Page-aligned SQLite database with a damaged GPS_POINTS b-tree page; "
                "integrity preflight must block migration."
            ),
            activities=(
                activity(
                    1,
                    "20240903000000",
                    None,
                    None,
                    None,
                    None,
                    None,
                    None,
                ),
            ),
            track_points=(
                point(
                    1,
                    1,
                    "20240903000000",
                    1.0,
                    2.0,
                    None,
                    None,
                    None,
                    None,
                    0.0,
                ),
            ),
            storage=STORAGE_CORRUPT,
            blocked_reason="corrupt_sqlite",
        ),
        FixtureCase(
            key="interrupted_idempotency",
            description=(
                "Three activity groups used by the reference simulator to commit a "
                "partial first pass and verify a duplicate-free deterministic rerun."
            ),
            activities=(
                activity(
                    1,
                    "20240601080000",
                    "20240601080030",
                    "Synthetic Interrupted A",
                    "First committed group before interruption.",
                    100.125,
                    30.0,
                    299.625468,
                ),
                activity(
                    2,
                    "20240602080000",
                    "20240602080030",
                    "Synthetic Interrupted B",
                    "First group processed after restart.",
                    101.25,
                    30.0,
                    296.296296,
                ),
                activity(
                    3,
                    "20240603080000",
                    "20240603080030",
                    "Synthetic Interrupted C",
                    "Final group processed after restart.",
                    102.5,
                    30.0,
                    292.682927,
                ),
            ),
            track_points=(
                point(1, 1, "20240601080000", 10.0001, 20.0001, 5.1, 2.1, 3.1, 4.1, 101.1),
                point(2, 1, "20240601080001", 10.0002, 20.0002, 5.2, 2.2, 3.2, 4.2, 101.2),
                point(3, 2, "20240602080000", 11.0001, 21.0001, 6.1, 2.1, 3.1, 5.1, 102.1),
                point(4, 2, "20240602080001", 11.0002, 21.0002, 6.2, 2.2, 3.2, 5.2, 102.2),
                point(5, 3, "20240603080000", 12.0001, 22.0001, 7.1, 2.1, 3.1, 6.1, 103.1),
                point(6, 3, "20240603080001", 12.0002, 22.0002, 7.2, 2.2, 3.2, 6.2, 103.2),
            ),
            representative_values=(
                {
                    "table": "ACTIVITY",
                    "legacy_id": 1,
                    "column": "GMTSTART",
                    "expected": "20240601080000",
                    "comparison": exact,
                },
                {
                    "table": "GPS_POINTS",
                    "legacy_id": 6,
                    "column": "GMTTIMESTAMP",
                    "expected": "20240603080001",
                    "comparison": exact,
                },
            ),
            exercise_idempotency=True,
        ),
    )


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        allow_nan=False,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def pretty_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n"
    ).encode("utf-8")


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(pretty_json_bytes(value))


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def typed_value(value: Any) -> Mapping[str, str]:
    if value is None:
        return {"type": "null", "value": ""}
    if isinstance(value, bool):
        return {"type": "integer", "value": "1" if value else "0"}
    if isinstance(value, int):
        return {"type": "integer", "value": str(value)}
    if isinstance(value, float):
        if not math.isfinite(value):
            raise FixtureValidationError("Non-finite REAL value is not canonicalizable")
        return {"type": "real", "value": value.hex()}
    if isinstance(value, str):
        return {"type": "text", "value": value}
    if isinstance(value, bytes):
        return {"type": "blob", "value": value.hex()}
    raise FixtureValidationError("Unsupported SQLite value type: {!r}".format(type(value)))


def hash_value(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


@contextmanager
def open_readonly(database: Path) -> Iterator[sqlite3.Connection]:
    connection = sqlite3.connect(
        "file:{}?mode=ro".format(database.resolve().as_posix()),
        uri=True,
    )
    try:
        yield connection
    finally:
        connection.close()


def remove_database_artifacts(database: Path) -> None:
    for path in (
        database,
        Path(str(database) + "-journal"),
        Path(str(database) + "-wal"),
        Path(str(database) + "-shm"),
    ):
        if path.exists():
            path.unlink()


def create_schema(
    connection: sqlite3.Connection,
    business_tables: Sequence[str] = BUSINESS_TABLES,
    android_locale: str = ANDROID_METADATA_LOCALE,
) -> None:
    requested_tables = set(business_tables)
    unknown_tables = requested_tables - set(BUSINESS_TABLES)
    if unknown_tables:
        raise FixtureValidationError(
            "Unknown business tables requested: {}".format(sorted(unknown_tables))
        )
    connection.execute(CREATE_ANDROID_METADATA_SQL)
    connection.execute("DELETE FROM android_metadata")
    connection.execute(
        "INSERT INTO android_metadata (locale) VALUES (?)",
        (android_locale,),
    )
    if "ACTIVITY" in requested_tables:
        connection.execute(CREATE_ACTIVITY_SQL)
    if "GPS_POINTS" in requested_tables:
        connection.execute(CREATE_GPS_POINTS_SQL)
    connection.execute("PRAGMA user_version = 0")


def create_database(
    database: Path,
    activities: Sequence[Sequence[Any]],
    track_points: Sequence[Sequence[Any]],
    business_tables: Sequence[str] = BUSINESS_TABLES,
    android_locale: str = ANDROID_METADATA_LOCALE,
) -> None:
    requested_tables = set(business_tables)
    if activities and "ACTIVITY" not in requested_tables:
        raise FixtureValidationError(
            "Cannot insert ACTIVITY rows when the ACTIVITY table is absent"
        )
    if track_points and "GPS_POINTS" not in requested_tables:
        raise FixtureValidationError(
            "Cannot insert GPS_POINTS rows when the GPS_POINTS table is absent"
        )
    database.parent.mkdir(parents=True, exist_ok=True)
    remove_database_artifacts(database)
    connection = sqlite3.connect(str(database))
    try:
        connection.execute("PRAGMA page_size = {}".format(FIXED_SQLITE_PAGE_SIZE))
        connection.execute("PRAGMA journal_mode = DELETE")
        connection.execute("PRAGMA synchronous = FULL")
        connection.execute("PRAGMA foreign_keys = OFF")
        with connection:
            create_schema(connection, business_tables, android_locale)
            if "ACTIVITY" in requested_tables:
                connection.executemany(
                    "INSERT INTO ACTIVITY ({}) VALUES ({})".format(
                        ", ".join(ACTIVITY_COLUMNS),
                        ", ".join("?" for _ in ACTIVITY_COLUMNS),
                    ),
                    activities,
                )
            if "GPS_POINTS" in requested_tables:
                connection.executemany(
                    "INSERT INTO GPS_POINTS ({}) VALUES ({})".format(
                        ", ".join(GPS_POINT_COLUMNS),
                        ", ".join("?" for _ in GPS_POINT_COLUMNS),
                    ),
                    track_points,
                )
        connection.execute("VACUUM")
    finally:
        connection.close()


def insert_fixture_rows(
    connection: sqlite3.Connection,
    activities: Sequence[Sequence[Any]],
    track_points: Sequence[Sequence[Any]],
) -> None:
    connection.executemany(
        "INSERT INTO ACTIVITY ({}) VALUES ({})".format(
            ", ".join(ACTIVITY_COLUMNS),
            ", ".join("?" for _ in ACTIVITY_COLUMNS),
        ),
        activities,
    )
    connection.executemany(
        "INSERT INTO GPS_POINTS ({}) VALUES ({})".format(
            ", ".join(GPS_POINT_COLUMNS),
            ", ".join("?" for _ in GPS_POINT_COLUMNS),
        ),
        track_points,
    )


def wal_checksum(
    data: bytes,
    byte_order: str,
    seed: Tuple[int, int] = (0, 0),
) -> Tuple[int, int]:
    if len(data) % 8 != 0:
        raise FixtureValidationError("WAL checksum input must use 8-byte groups")
    words = struct.unpack(
        "{}{}I".format(byte_order, len(data) // 4),
        data,
    )
    first, second = seed
    for index in range(0, len(words), 2):
        first = (first + words[index] + second) & 0xFFFFFFFF
        second = (second + words[index + 1] + first) & 0xFFFFFFFF
    return first, second


def canonicalize_wal(wal_path: Path) -> int:
    wal = bytearray(wal_path.read_bytes())
    if len(wal) < 32:
        raise FixtureValidationError("Active WAL is missing its 32-byte header")
    magic = struct.unpack(">I", wal[0:4])[0]
    if magic == 0x377F0682:
        checksum_byte_order = "<"
    elif magic == 0x377F0683:
        checksum_byte_order = ">"
    else:
        raise FixtureValidationError(
            "Unsupported WAL magic 0x{:08x}".format(magic)
        )
    page_size = struct.unpack(">I", wal[8:12])[0]
    if page_size == 0:
        page_size = 65536
    frame_size = 24 + page_size
    if (len(wal) - 32) % frame_size != 0:
        raise FixtureValidationError("Active WAL has an incomplete frame")

    struct.pack_into(">II", wal, 16, WAL_SALT_1, WAL_SALT_2)
    checksum = wal_checksum(bytes(wal[:24]), checksum_byte_order)
    struct.pack_into(">II", wal, 24, *checksum)

    frame_count = 0
    offset = 32
    while offset < len(wal):
        struct.pack_into(">II", wal, offset + 8, WAL_SALT_1, WAL_SALT_2)
        checksum = wal_checksum(
            bytes(wal[offset : offset + 8])
            + bytes(wal[offset + 24 : offset + frame_size]),
            checksum_byte_order,
            checksum,
        )
        struct.pack_into(">II", wal, offset + 16, *checksum)
        frame_count += 1
        offset += frame_size
    if frame_count == 0:
        raise FixtureValidationError("Active WAL must contain committed frames")
    wal_path.write_bytes(wal)
    return frame_count


def create_active_wal_snapshot(
    root: Path,
    database: Path,
    activities: Sequence[Sequence[Any]],
    track_points: Sequence[Sequence[Any]],
    android_locale: str = ANDROID_METADATA_LOCALE,
) -> None:
    remove_database_artifacts(database)
    work_dir = root / "generated" / ".active-wal-work"
    if work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True)
    source = work_dir / "source.db"
    canonical = work_dir / "canonical.db"

    writer: Optional[sqlite3.Connection] = None
    snapshot: Optional[sqlite3.Connection] = None
    try:
        create_database(source, (), (), android_locale=android_locale)
        writer = sqlite3.connect(str(source))
        journal_mode = writer.execute("PRAGMA journal_mode = WAL").fetchone()[0]
        if str(journal_mode).lower() != "wal":
            raise FixtureValidationError("Could not enable WAL mode for snapshot")
        writer.execute("PRAGMA wal_autocheckpoint = 0")
        writer.execute("PRAGMA synchronous = FULL")
        with writer:
            insert_fixture_rows(writer, activities, track_points)

        source_wal = Path(str(source) + "-wal")
        source_shm = Path(str(source) + "-shm")
        if not source_wal.is_file() or not source_shm.is_file():
            raise FixtureValidationError("Active source did not retain WAL sidecars")
        shutil.copyfile(source, canonical)
        canonical_wal = Path(str(canonical) + "-wal")
        shutil.copyfile(source_wal, canonical_wal)
        canonicalize_wal(canonical_wal)

        snapshot = sqlite3.connect(str(canonical))
        snapshot.execute("PRAGMA query_only = ON")
        snapshot.execute("SELECT COUNT(*) FROM ACTIVITY").fetchone()
        canonical_shm = Path(str(canonical) + "-shm")
        if not canonical_shm.is_file():
            raise FixtureValidationError("Canonical WAL did not rebuild -shm")

        database.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(canonical, database)
        shutil.copyfile(canonical_wal, Path(str(database) + "-wal"))
        shutil.copyfile(canonical_shm, Path(str(database) + "-shm"))
    finally:
        if snapshot is not None:
            snapshot.close()
        if writer is not None:
            writer.close()
        if work_dir.exists():
            shutil.rmtree(work_dir)


def create_malformed_schema_database(
    database: Path,
    activities: Sequence[Sequence[Any]],
    track_points: Sequence[Sequence[Any]],
    android_locale: str = ANDROID_METADATA_LOCALE,
) -> None:
    database.parent.mkdir(parents=True, exist_ok=True)
    remove_database_artifacts(database)
    connection = sqlite3.connect(str(database))
    try:
        connection.execute("PRAGMA page_size = {}".format(FIXED_SQLITE_PAGE_SIZE))
        connection.execute("PRAGMA journal_mode = DELETE")
        connection.execute("PRAGMA synchronous = FULL")
        connection.execute("PRAGMA foreign_keys = OFF")
        with connection:
            connection.execute(CREATE_ANDROID_METADATA_SQL)
            connection.execute(
                "INSERT INTO android_metadata (locale) VALUES (?)",
                (android_locale,),
            )
            connection.execute(
                "CREATE TABLE ACTIVITY "
                "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, GMTSTART VARCHAR, "
                "GMTEND VARCHAR, NAME VARCHAR, DESCRIPTION VARCHAR, "
                "DISTANCE REAL, TIME INTEGER, PACE REAL);"
            )
            connection.execute(CREATE_GPS_POINTS_SQL)
            connection.execute("PRAGMA user_version = 0")
            insert_fixture_rows(connection, activities, track_points)
        connection.execute("VACUUM")
    finally:
        connection.close()


def sqlite_page_size(database_bytes: bytes) -> int:
    if len(database_bytes) < 100 or database_bytes[:16] != SQLITE_HEADER:
        raise FixtureValidationError("File lacks a complete SQLite header")
    encoded_page_size = int.from_bytes(database_bytes[16:18], "big")
    if encoded_page_size not in SQLITE_PAGE_SIZE_ENCODINGS:
        raise FixtureValidationError(
            "Invalid SQLite page-size encoding {}".format(encoded_page_size)
        )
    return 65536 if encoded_page_size == 1 else encoded_page_size


def sqlite_header_diagnostics(database_bytes: bytes) -> Mapping[str, Any]:
    if len(database_bytes) < 100:
        return {
            "status": "truncated",
            "write_version": None,
            "read_version": None,
            "journal_mode": None,
            "change_counter": None,
            "version_valid_for": None,
            "change_counter_matches_version_valid_for": None,
        }
    if database_bytes[:16] != SQLITE_HEADER:
        return {
            "status": "invalid_header",
            "write_version": None,
            "read_version": None,
            "journal_mode": None,
            "change_counter": None,
            "version_valid_for": None,
            "change_counter_matches_version_valid_for": None,
        }

    write_version = database_bytes[18]
    read_version = database_bytes[19]
    versions = (write_version, read_version)
    if versions == SQLITE_ROLLBACK_FORMAT_VERSIONS:
        journal_mode = "rollback"
    elif versions == SQLITE_WAL_FORMAT_VERSIONS:
        journal_mode = "wal"
    else:
        journal_mode = None

    change_counter = int.from_bytes(database_bytes[24:28], "big")
    version_valid_for = int.from_bytes(database_bytes[92:96], "big")
    counters_match = change_counter == version_valid_for
    if (
        write_version not in SQLITE_LEGAL_FORMAT_VERSIONS
        or read_version not in SQLITE_LEGAL_FORMAT_VERSIONS
        or journal_mode is None
    ):
        status = "invalid_format_version"
    elif not counters_match:
        status = "inconsistent_change_counter"
    else:
        status = "valid"
    return {
        "status": status,
        "write_version": write_version,
        "read_version": read_version,
        "journal_mode": journal_mode,
        "change_counter": change_counter,
        "version_valid_for": version_valid_for,
        "change_counter_matches_version_valid_for": counters_match,
    }


def create_truncated_database(
    database: Path,
    activities: Sequence[Sequence[Any]],
    track_points: Sequence[Sequence[Any]],
    android_locale: str = ANDROID_METADATA_LOCALE,
) -> None:
    create_database(
        database,
        activities,
        track_points,
        android_locale=android_locale,
    )
    database_bytes = database.read_bytes()
    page_size = sqlite_page_size(database_bytes)
    if len(database_bytes) <= page_size:
        raise FixtureValidationError("Truncation seed must contain multiple pages")
    database.write_bytes(database_bytes[:-page_size])


def create_corrupt_database(
    database: Path,
    activities: Sequence[Sequence[Any]],
    track_points: Sequence[Sequence[Any]],
    android_locale: str = ANDROID_METADATA_LOCALE,
) -> None:
    create_database(
        database,
        activities,
        track_points,
        android_locale=android_locale,
    )
    with open_readonly(database) as connection:
        root_page = connection.execute(
            "SELECT rootpage FROM sqlite_master "
            "WHERE type = 'table' AND name = 'GPS_POINTS'"
        ).fetchone()[0]
    database_bytes = bytearray(database.read_bytes())
    page_size = sqlite_page_size(database_bytes)
    page_offset = (int(root_page) - 1) * page_size
    if page_offset >= len(database_bytes):
        raise FixtureValidationError("GPS_POINTS root page is outside the database")
    database_bytes[page_offset] = 0
    database.write_bytes(database_bytes)


def generate_case_database(root: Path, case: FixtureCase, database: Path) -> None:
    if case.storage == STORAGE_STANDARD:
        create_database(
            database,
            case.activities,
            case.track_points,
            case.business_tables,
            case.android_locale,
        )
        return
    if case.storage == STORAGE_ACTIVE_WAL:
        create_active_wal_snapshot(
            root,
            database,
            case.activities,
            case.track_points,
            case.android_locale,
        )
        return
    if case.storage == STORAGE_MALFORMED_SCHEMA:
        create_malformed_schema_database(
            database,
            case.activities,
            case.track_points,
            case.android_locale,
        )
        return
    if case.storage == STORAGE_TRUNCATED:
        create_truncated_database(
            database,
            case.activities,
            case.track_points,
            case.android_locale,
        )
        return
    if case.storage == STORAGE_CORRUPT:
        create_corrupt_database(
            database,
            case.activities,
            case.track_points,
            case.android_locale,
        )
        return
    raise FixtureValidationError(
        "{} has unknown storage mode {!r}".format(case.key, case.storage)
    )


def table_info(
    connection: sqlite3.Connection,
    table: str,
) -> Tuple[Tuple[Any, ...], ...]:
    rows = []
    for row in connection.execute('PRAGMA table_info("{}")'.format(table)):
        materialized = list(row)
        if isinstance(materialized[2], str):
            materialized[2] = materialized[2].upper()
        rows.append(tuple(materialized))
    return tuple(rows)


def table_xinfo(
    connection: sqlite3.Connection,
    table: str,
) -> Tuple[Tuple[Any, ...], ...]:
    rows = []
    for row in connection.execute('PRAGMA table_xinfo("{}")'.format(table)):
        materialized = list(row)
        if isinstance(materialized[2], str):
            materialized[2] = materialized[2].upper()
        rows.append(tuple(materialized))
    return tuple(rows)


def normalized_sqlite_version(
    version: Sequence[int],
) -> Tuple[int, int, int]:
    if len(version) < 2 or len(version) > 3:
        raise FixtureValidationError(
            "SQLite version must contain two or three numeric components"
        )
    normalized = tuple(int(component) for component in version)
    if any(component < 0 for component in normalized):
        raise FixtureValidationError(
            "SQLite version components must be non-negative"
        )
    return (
        normalized[0],
        normalized[1],
        normalized[2] if len(normalized) == 3 else 0,
    )


def schema_capabilities_for_version(
    version: Sequence[int],
) -> SchemaCapabilities:
    normalized = normalized_sqlite_version(version)
    return SchemaCapabilities(
        table_xinfo=normalized >= SQLITE_TABLE_XINFO_MIN_VERSION,
        sqlite_schema_alias=normalized >= SQLITE_SCHEMA_ALIAS_MIN_VERSION,
    )


def generated_columns_supported_by_version(
    version: Sequence[int],
) -> bool:
    return (
        normalized_sqlite_version(version)
        >= SQLITE_GENERATED_COLUMNS_MIN_VERSION
    )


def theoretical_sqlite_profiles() -> Tuple[SQLiteVersionProfile, ...]:
    return tuple(
        SQLiteVersionProfile(
            name=name,
            sqlite_version=version,
            schema_capabilities=schema_capabilities_for_version(version),
            generated_columns=generated_columns_supported_by_version(version),
        )
        for name, version in THEORETICAL_SQLITE_PROFILE_VERSIONS
    )


def schema_paths_for_capabilities(
    capabilities: SchemaCapabilities,
) -> Tuple[str, ...]:
    paths: List[str] = []
    if capabilities.table_xinfo:
        paths.append(SCHEMA_PATH_MODERN)
        if capabilities.sqlite_schema_alias:
            paths.append(SCHEMA_PATH_SQLITE_SCHEMA_ALIAS)
    paths.append(SCHEMA_PATH_ANDROID_API_26)
    return tuple(paths)


def preferred_schema_path_for_capabilities(
    capabilities: SchemaCapabilities,
) -> str:
    return schema_paths_for_capabilities(capabilities)[0]


def schema_capabilities_are_subset(
    candidate: SchemaCapabilities,
    host: SchemaCapabilities,
) -> bool:
    return (
        (not candidate.table_xinfo or host.table_xinfo)
        and (not candidate.sqlite_schema_alias or host.sqlite_schema_alias)
    )


def executable_schema_profiles(
    host_capabilities: SchemaCapabilities,
) -> Tuple[ExecutableSchemaProfile, ...]:
    targets = (
        (
            "android_api_26_sqlite_3_18",
            SchemaCapabilities(False, False),
        ),
        (
            "sqlite_3_26_to_3_32",
            SchemaCapabilities(True, False),
        ),
        (
            "sqlite_3_33_plus",
            SchemaCapabilities(True, True),
        ),
    )
    profiles: List[ExecutableSchemaProfile] = []
    for name, capabilities in targets:
        if not schema_capabilities_are_subset(capabilities, host_capabilities):
            continue
        forbidden_tokens: List[str] = []
        if host_capabilities.table_xinfo and not capabilities.table_xinfo:
            forbidden_tokens.append("table_xinfo")
        if (
            host_capabilities.sqlite_schema_alias
            and not capabilities.sqlite_schema_alias
        ):
            forbidden_tokens.append("sqlite_schema")
        profiles.append(
            ExecutableSchemaProfile(
                name=name,
                capabilities=capabilities,
                forbidden_tokens=tuple(forbidden_tokens),
            )
        )
    return tuple(profiles)


def sqlite_runtime_version(
    connection: sqlite3.Connection,
) -> Tuple[int, int, int]:
    row = connection.execute("SELECT sqlite_version()").fetchone()
    value = row[0] if row else None
    match = (
        re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", value)
        if isinstance(value, str)
        else None
    )
    if match is None:
        raise FixtureValidationError(
            "SQLite connection returned an invalid version {!r}".format(value)
        )
    return tuple(int(component) for component in match.groups())


def sqlite_compile_options(
    connection: sqlite3.Connection,
) -> Optional[frozenset[str]]:
    try:
        rows = connection.execute("PRAGMA compile_options")
    except Exception:
        return None
    options = frozenset(
        str(row[0]).upper()
        for row in rows
        if row and isinstance(row[0], str)
    )
    return options or None


_COMPILE_OPTIONS_UNSET = object()


def probe_generated_columns_support(
    connection: sqlite3.Connection,
    sqlite_version: Optional[Sequence[int]] = None,
) -> bool:
    version = (
        normalized_sqlite_version(sqlite_version)
        if sqlite_version is not None
        else sqlite_runtime_version(connection)
    )
    if not generated_columns_supported_by_version(version):
        return False
    try:
        tuple(
            connection.execute(
                "EXPLAIN CREATE TABLE __sport_logger_generated_column_probe "
                "(source TEXT, derived TEXT GENERATED ALWAYS AS (source) VIRTUAL)"
            )
        )
    except Exception:
        return False
    return True


def probe_virtual_table_module_support(
    connection: sqlite3.Connection,
    module: str,
    compile_options: Any = _COMPILE_OPTIONS_UNSET,
) -> bool:
    if module not in ("fts4", "fts5"):
        raise FixtureValidationError(
            "Unsupported virtual-table capability probe {!r}".format(module)
        )
    if compile_options is _COMPILE_OPTIONS_UNSET:
        options = sqlite_compile_options(connection)
    elif compile_options is None:
        options = None
    else:
        options = frozenset(str(option).upper() for option in compile_options)
    diagnostics_known = (
        bool(options)
        and "OMIT_COMPILEOPTION_DIAGS" not in options
    )
    if (
        module == "fts4"
        and diagnostics_known
        and not {"ENABLE_FTS3", "ENABLE_FTS4"} & options
    ):
        return False
    if (
        module == "fts5"
        and diagnostics_known
        and "ENABLE_FTS5" not in options
    ):
        return False
    probe_table = "__sport_logger_{}_capability_probe_{:x}".format(
        module,
        id(connection),
    )
    supported = False
    try:
        connection.execute(
            "CREATE VIRTUAL TABLE temp.{} USING {}(value)".format(
                probe_table,
                module,
            )
        )
        supported = True
    except Exception:
        supported = False
    finally:
        try:
            connection.execute(
                "DROP TABLE IF EXISTS temp.{}".format(probe_table)
            )
        except Exception:
            supported = False
        try:
            prefix = probe_table + "_"
            residual = tuple(
                connection.execute(
                    "SELECT name FROM sqlite_temp_master "
                    "WHERE name = ? OR substr(name, 1, ?) = ?",
                    (probe_table, len(prefix), prefix),
                )
            )
        except Exception:
            supported = False
        else:
            if residual:
                supported = False
    return supported


def sqlite_detector_capabilities(
    connection: sqlite3.Connection,
) -> SQLiteDetectorCapabilities:
    version = sqlite_runtime_version(connection)
    compile_options = sqlite_compile_options(connection)
    return SQLiteDetectorCapabilities(
        sqlite_version=version,
        generated_columns=probe_generated_columns_support(connection, version),
        fts4=probe_virtual_table_module_support(
            connection,
            "fts4",
            compile_options,
        ),
        fts5=probe_virtual_table_module_support(
            connection,
            "fts5",
            compile_options,
        ),
    )


def optional_detector_not_applicable(
    capabilities: SQLiteDetectorCapabilities,
) -> Tuple[Tuple[str, str], ...]:
    version_text = ".".join(
        str(component) for component in capabilities.sqlite_version
    )
    not_applicable: List[Tuple[str, str]] = []
    if not capabilities.generated_columns:
        not_applicable.append(
            (
                GENERATED_COLUMN_DETECTOR,
                "SQLite {} does not support generated columns".format(
                    version_text
                ),
            )
        )
    if not capabilities.fts4:
        not_applicable.append(
            (
                FTS4_VIRTUAL_TABLE_DETECTOR,
                "SQLite {} does not expose the API26-compatible FTS4 module".format(
                    version_text
                ),
            )
        )
    if not capabilities.fts5:
        not_applicable.append(
            (
                FTS5_VIRTUAL_TABLE_DETECTOR,
                "SQLite {} does not expose the optional FTS5 module".format(
                    version_text
                ),
            )
        )
    return tuple(not_applicable)


def merge_detector_reports(*reports: DetectorReport) -> DetectorReport:
    passed: List[str] = []
    not_applicable: List[Tuple[str, str]] = []
    seen: set[str] = set()
    for report in reports:
        for name in report.passed:
            if name in seen:
                raise FixtureValidationError(
                    "Duplicate defect-detector result {!r}".format(name)
                )
            seen.add(name)
            passed.append(name)
        for name, reason in report.not_applicable:
            if name in seen:
                raise FixtureValidationError(
                    "Duplicate defect-detector result {!r}".format(name)
                )
            seen.add(name)
            not_applicable.append((name, reason))
    return DetectorReport(tuple(passed), tuple(not_applicable))


def probe_table_xinfo_support(connection: sqlite3.Connection) -> bool:
    try:
        rows = tuple(
            tuple(row)
            for row in connection.execute('PRAGMA table_xinfo("sqlite_master")')
        )
    except Exception:
        return False
    return bool(rows) and all(len(row) == 7 for row in rows)


def probe_sqlite_schema_alias_support(connection: sqlite3.Connection) -> bool:
    try:
        tuple(
            connection.execute(
                "SELECT type, name, tbl_name, rootpage, sql "
                "FROM sqlite_schema WHERE 0"
            )
        )
    except Exception:
        return False
    return True


def schema_capabilities(connection: sqlite3.Connection) -> SchemaCapabilities:
    cache_key = id(connection)
    cached = _SCHEMA_CAPABILITY_CACHE.get(cache_key)
    if cached is not None and cached[0] is connection:
        return cached[1]
    capabilities = SchemaCapabilities(
        table_xinfo=probe_table_xinfo_support(connection),
        sqlite_schema_alias=probe_sqlite_schema_alias_support(connection),
    )
    _SCHEMA_CAPABILITY_CACHE[cache_key] = (connection, capabilities)
    return capabilities


def table_xinfo_supported(connection: sqlite3.Connection) -> bool:
    return schema_capabilities(connection).table_xinfo


def sqlite_schema_alias_supported(connection: sqlite3.Connection) -> bool:
    return schema_capabilities(connection).sqlite_schema_alias


def supported_schema_paths(
    connection: sqlite3.Connection,
) -> Tuple[str, ...]:
    return schema_paths_for_capabilities(schema_capabilities(connection))


def resolve_schema_path(
    connection: sqlite3.Connection,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> str:
    if schema_path == SCHEMA_PATH_AUTO:
        return preferred_schema_path_for_capabilities(
            schema_capabilities(connection)
        )
    if schema_path not in SCHEMA_PATHS:
        raise FixtureValidationError(
            "Unknown schema validation path {!r}".format(schema_path)
        )
    if schema_path == SCHEMA_PATH_ANDROID_API_26:
        return schema_path
    if schema_path not in supported_schema_paths(connection):
        raise FixtureValidationError(
            "Schema validation path {!r} is unsupported by this SQLite "
            "connection".format(schema_path)
        )
    return schema_path


def schema_path_uses_table_xinfo(schema_path: str) -> bool:
    if schema_path in (SCHEMA_PATH_MODERN, SCHEMA_PATH_SQLITE_SCHEMA_ALIAS):
        return True
    if schema_path == SCHEMA_PATH_ANDROID_API_26:
        return False
    raise FixtureValidationError(
        "Column metadata requested for unresolved path {!r}".format(schema_path)
    )


def schema_catalog(schema_path: str) -> str:
    if schema_path in (SCHEMA_PATH_MODERN, SCHEMA_PATH_ANDROID_API_26):
        return "sqlite_master"
    if schema_path == SCHEMA_PATH_SQLITE_SCHEMA_ALIAS:
        return "sqlite_schema"
    raise FixtureValidationError(
        "Schema catalog requested for unresolved path {!r}".format(schema_path)
    )


def canonical_schema_sql_tokens(sql: Any) -> Tuple[str, ...]:
    if not isinstance(sql, str):
        return ()
    tokens: List[str] = []
    index = 0
    while index < len(sql):
        character = sql[index]
        if character.isspace():
            index += 1
            continue
        if sql.startswith("--", index):
            newline = sql.find("\n", index + 2)
            index = len(sql) if newline < 0 else newline + 1
            continue
        if sql.startswith("/*", index):
            end = sql.find("*/", index + 2)
            if end < 0:
                raise FixtureValidationError("Unterminated SQLite schema comment")
            index = end + 2
            continue
        if character in ('"', "`", "["):
            closing = "]" if character == "[" else character
            index += 1
            value: List[str] = []
            while index < len(sql):
                current = sql[index]
                if current == closing:
                    if (
                        closing != "]"
                        and index + 1 < len(sql)
                        and sql[index + 1] == closing
                    ):
                        value.append(closing)
                        index += 2
                        continue
                    index += 1
                    break
                value.append(current)
                index += 1
            else:
                raise FixtureValidationError(
                    "Unterminated quoted SQLite schema identifier"
                )
            tokens.append("".join(value).upper())
            continue
        if character == "'":
            index += 1
            value = []
            while index < len(sql):
                current = sql[index]
                if current == "'":
                    if index + 1 < len(sql) and sql[index + 1] == "'":
                        value.append("'")
                        index += 2
                        continue
                    index += 1
                    break
                value.append(current)
                index += 1
            else:
                raise FixtureValidationError(
                    "Unterminated SQLite schema string literal"
                )
            tokens.append("STRING:{}".format("".join(value)))
            continue
        if character == ";":
            index += 1
            continue
        if character.isalnum() or character in ("_", "$"):
            end = index + 1
            while end < len(sql) and (
                sql[end].isalnum() or sql[end] in ("_", "$")
            ):
                end += 1
            tokens.append(sql[index:end].upper())
            index = end
            continue
        two_character = sql[index : index + 2]
        if two_character in ("<=", ">=", "<>", "!=", "==", "||", "<<", ">>"):
            tokens.append(two_character)
            index += 2
            continue
        tokens.append(character)
        index += 1
    return tuple(tokens)


def sqlite_schema_record(
    connection: sqlite3.Connection,
    name: str,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> Optional[Mapping[str, Any]]:
    resolved_path = resolve_schema_path(connection, schema_path)
    catalog = schema_catalog(resolved_path)
    rows = tuple(
        connection.execute(
            "SELECT type, name, tbl_name, rootpage, sql "
            "FROM {} WHERE name = ?".format(catalog),
            (name,),
        )
    )
    if len(rows) != 1:
        return None
    row = rows[0]
    return {
        "type": row[0],
        "name": row[1],
        "table_name": row[2],
        "root_page": row[3],
        "sql": row[4],
    }


def sqlite_schema_record_errors(
    table: str,
    record: Optional[Mapping[str, Any]],
    catalog: str,
) -> List[str]:
    if catalog not in ("sqlite_master", "sqlite_schema"):
        raise FixtureValidationError(
            "Unknown SQLite schema catalog {!r}".format(catalog)
        )
    if record is None:
        return ["{}:schema_record".format(table)]
    errors: List[str] = []
    if (
        record["type"] != "table"
        or record["name"] != table
        or record["table_name"] != table
        or not isinstance(record["root_page"], int)
        or record["root_page"] <= 0
    ):
        errors.append("{}:sqlite_schema_table_kind".format(table))
    if canonical_schema_sql_tokens(
        record["sql"]
    ) != canonical_schema_sql_tokens(EXPECTED_SCHEMA_SQL[table]):
        errors.append("{}:{}_sql".format(table, catalog))
    return errors


def table_schema_errors(
    connection: sqlite3.Connection,
    table: str,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> List[str]:
    resolved_path = resolve_schema_path(connection, schema_path)
    errors: List[str] = []
    if schema_path_uses_table_xinfo(resolved_path):
        if table_xinfo(connection, table) != EXPECTED_TABLE_XINFO[table]:
            errors.append("{}:table_xinfo".format(table))
    elif table_info(connection, table) != EXPECTED_TABLE_INFO[table]:
        errors.append("{}:table_info".format(table))
    record = sqlite_schema_record(connection, table, resolved_path)
    errors.extend(
        sqlite_schema_record_errors(
            table,
            record,
            schema_catalog(resolved_path),
        )
    )
    if tuple(
        connection.execute('PRAGMA foreign_key_list("{}")'.format(table))
    ):
        errors.append("{}:foreign_keys".format(table))
    if tuple(connection.execute('PRAGMA index_list("{}")'.format(table))):
        errors.append("{}:indexes".format(table))
    return errors


def schema_object_rows(
    connection: sqlite3.Connection,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> Tuple[Tuple[str, str, str], ...]:
    resolved_path = resolve_schema_path(connection, schema_path)
    return tuple(
        tuple(row)
        for row in connection.execute(
            "SELECT type, name, tbl_name FROM {} ORDER BY type, name".format(
                schema_catalog(resolved_path)
            )
        )
    )


def sqlite_internal_object_name(name: Any) -> bool:
    return isinstance(name, str) and name.casefold().startswith("sqlite_")


def user_table_names(
    connection: sqlite3.Connection,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> Tuple[str, ...]:
    return tuple(
        sorted(
            name
            for object_type, name, _ in schema_object_rows(
                connection,
                schema_path,
            )
            if object_type == "table" and not sqlite_internal_object_name(name)
        )
    )


def user_schema_objects(
    connection: sqlite3.Connection,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> Tuple[Tuple[str, str, str], ...]:
    return tuple(
        row
        for row in schema_object_rows(connection, schema_path)
        if not sqlite_internal_object_name(row[1])
    )


def sqlite_internal_schema_objects(
    connection: sqlite3.Connection,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> Tuple[Tuple[str, str, str], ...]:
    return tuple(
        row
        for row in schema_object_rows(connection, schema_path)
        if sqlite_internal_object_name(row[1])
    )


def expected_sqlite_internal_objects(
    present_tables: Iterable[str],
) -> set[Tuple[str, str, str]]:
    return (
        set(EXPECTED_SQLITE_INTERNAL_OBJECTS)
        if set(present_tables) & set(BUSINESS_TABLES)
        else set()
    )


def sqlite_internal_schema_errors(
    connection: sqlite3.Connection,
    present_tables: Iterable[str],
    schema_path: str = SCHEMA_PATH_AUTO,
) -> List[str]:
    resolved_path = resolve_schema_path(connection, schema_path)
    actual = set(sqlite_internal_schema_objects(connection, resolved_path))
    expected = expected_sqlite_internal_objects(present_tables)
    errors: List[str] = []
    if actual != expected:
        errors.append("database:sqlite_internal_schema_objects")
    if ("table", "sqlite_sequence", "sqlite_sequence") in actual:
        record = sqlite_schema_record(
            connection,
            "sqlite_sequence",
            resolved_path,
        )
        if (
            record is None
            or record["type"] != "table"
            or record["name"] != "sqlite_sequence"
            or record["table_name"] != "sqlite_sequence"
            or not isinstance(record["root_page"], int)
            or record["root_page"] <= 0
            or canonical_schema_sql_tokens(record["sql"])
            != canonical_schema_sql_tokens(
                "CREATE TABLE sqlite_sequence(name,seq)"
            )
        ):
            errors.append("database:sqlite_sequence_schema")
    return errors


def schema_payload(connection: sqlite3.Connection) -> Mapping[str, Any]:
    portable_path = SCHEMA_PATH_ANDROID_API_26
    present_tables = set(user_table_names(connection, portable_path))
    table_payload: Dict[str, Any] = {}
    for table in PLATFORM_TABLES + BUSINESS_TABLES:
        if table not in present_tables:
            continue
        record = sqlite_schema_record(connection, table, portable_path)
        table_payload[table] = {
            "table_info": [list(row) for row in table_info(connection, table)],
            "schema_record": (
                None
                if record is None
                else {
                    "type": record["type"],
                    "name": record["name"],
                    "table_name": record["table_name"],
                    "root_page_kind": (
                        "table_btree"
                        if isinstance(record["root_page"], int)
                        and record["root_page"] > 0
                        else "non_table_btree"
                    ),
                    "sql_tokens": list(
                        canonical_schema_sql_tokens(record["sql"])
                    ),
                }
            ),
        }
    return {
        "tables": table_payload,
        "foreign_keys": {
            table: [
                list(row)
                for row in connection.execute(
                    'PRAGMA foreign_key_list("{}")'.format(table)
                )
            ]
            for table in BUSINESS_TABLES
            if table in present_tables
        },
        "indexes": {
            table: [
                list(row)
                for row in connection.execute(
                    'PRAGMA index_list("{}")'.format(table)
                )
            ]
            for table in PLATFORM_TABLES + BUSINESS_TABLES
            if table in present_tables
        },
        "user_schema_objects": [
            list(row)
            for row in user_schema_objects(connection, portable_path)
        ],
        "sqlite_internal_schema_objects": [
            list(row)
            for row in sqlite_internal_schema_objects(
                connection,
                portable_path,
            )
        ],
        "user_version": connection.execute("PRAGMA user_version").fetchone()[0],
    }


def platform_metadata_payload(connection: sqlite3.Connection) -> Mapping[str, Any]:
    rows = [
        {"locale": row[0], "storage_type": row[1]}
        for row in connection.execute(
            "SELECT locale, typeof(locale) FROM android_metadata ORDER BY rowid"
        )
    ]
    return {
        "android_metadata": {
            "business_data": False,
            "rows": rows,
        }
    }


def valid_android_locale(value: Any) -> bool:
    return (
        isinstance(value, str)
        and bool(value)
        and value == value.strip()
        and "\x00" not in value
        and ANDROID_LOCALE_PATTERN.fullmatch(value) is not None
    )


def android_metadata_diagnostics(
    connection: sqlite3.Connection,
    user_tables: Optional[Iterable[str]] = None,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> Mapping[str, Any]:
    resolved_path = resolve_schema_path(connection, schema_path)
    tables = (
        set(user_tables)
        if user_tables is not None
        else set(user_table_names(connection, resolved_path))
    )
    if "android_metadata" not in tables:
        return {
            "state": "missing_table",
            "table_present": False,
            "row_count": 0,
            "valid_locale_rows": 0,
            "storage_types": [],
            "schema_errors": ["android_metadata:missing_table"],
        }
    schema_errors = table_schema_errors(
        connection,
        "android_metadata",
        resolved_path,
    )
    if schema_errors:
        return {
            "state": "invalid_schema",
            "table_present": True,
            "row_count": None,
            "valid_locale_rows": 0,
            "storage_types": [],
            "schema_errors": schema_errors,
        }

    rows = tuple(
        connection.execute(
            "SELECT locale, typeof(locale) FROM android_metadata ORDER BY rowid"
        )
    )
    storage_types = [str(row[1]) for row in rows]
    valid_locale_rows = sum(
        1
        for locale, storage_type in rows
        if storage_type == "text" and valid_android_locale(locale)
    )
    errors: List[str] = []
    if len(rows) != 1:
        errors.append("android_metadata:row_count")
        state = "empty" if not rows else "multiple_rows"
    elif rows[0][1] != "text":
        errors.append("android_metadata:locale_storage_type")
        state = "invalid_locale_type"
    elif not valid_android_locale(rows[0][0]):
        errors.append("android_metadata:locale_value")
        state = "invalid_locale_value"
    else:
        state = "valid"
    return {
        "state": state,
        "table_present": True,
        "row_count": len(rows),
        "valid_locale_rows": valid_locale_rows,
        "storage_types": storage_types,
        "schema_errors": errors,
    }


def schema_diagnostics(
    connection: sqlite3.Connection,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> Mapping[str, Any]:
    resolved_path = resolve_schema_path(connection, schema_path)
    user_tables = set(user_table_names(connection, resolved_path))
    business_tables_present = [
        table for table in BUSINESS_TABLES if table in user_tables
    ]
    missing_business_tables = [
        table for table in BUSINESS_TABLES if table not in user_tables
    ]
    metadata_diagnostics = android_metadata_diagnostics(
        connection,
        user_tables,
        resolved_path,
    )
    schema_errors: List[str] = list(metadata_diagnostics["schema_errors"])
    for table in BUSINESS_TABLES:
        if table in user_tables:
            schema_errors.extend(
                table_schema_errors(connection, table, resolved_path)
            )
    schema_errors.extend(
        sqlite_internal_schema_errors(
            connection,
            user_tables,
            resolved_path,
        )
    )
    if connection.execute("PRAGMA user_version").fetchone()[0] != 0:
        schema_errors.append("database:user_version")
    unexpected_tables = sorted(
        user_tables - set(BUSINESS_TABLES) - set(PLATFORM_TABLES)
    )
    if unexpected_tables:
        schema_errors.append("database:unexpected_tables")
    expected_object_names = set(BUSINESS_TABLES) | set(PLATFORM_TABLES)
    unexpected_schema_objects = sorted(
        "{}:{}".format(object_type, name)
        for object_type, name, _ in user_schema_objects(
            connection,
            resolved_path,
        )
        if name not in expected_object_names
        or object_type != "table"
    )
    if unexpected_schema_objects:
        schema_errors.append("database:unexpected_schema_objects")
    internal_schema_objects = [
        "{}:{}".format(object_type, name)
        for object_type, name, _ in sqlite_internal_schema_objects(
            connection,
            resolved_path,
        )
    ]
    expected_internal_schema_objects = sorted(
        "{}:{}".format(object_type, name)
        for object_type, name, _ in expected_sqlite_internal_objects(
            user_tables
        )
    )
    unexpected_internal_schema_objects = sorted(
        set(internal_schema_objects) - set(expected_internal_schema_objects)
    )

    if schema_errors:
        state = "malformed_schema"
    elif not missing_business_tables:
        state = "complete"
    elif not business_tables_present:
        state = "no_business_tables"
    else:
        state = "partial_business_schema"
    return {
        "validation_path": resolved_path,
        "state": state,
        "migration_readiness": "ready" if state == "complete" else "blocked",
        "business_tables_present": business_tables_present,
        "missing_business_tables": missing_business_tables,
        "platform_tables_present": [
            table for table in PLATFORM_TABLES if table in user_tables
        ],
        "android_metadata": metadata_diagnostics,
        "unexpected_tables": unexpected_tables,
        "unexpected_schema_objects": unexpected_schema_objects,
        "sqlite_internal_schema_objects": sorted(internal_schema_objects),
        "unexpected_sqlite_internal_schema_objects": (
            unexpected_internal_schema_objects
        ),
        "schema_errors": sorted(set(schema_errors)),
    }


def schema_decision_payload(
    diagnostics: Mapping[str, Any],
) -> Mapping[str, Any]:
    return {
        "state": diagnostics["state"],
        "migration_readiness": diagnostics["migration_readiness"],
        "business_tables_present": diagnostics["business_tables_present"],
        "missing_business_tables": diagnostics["missing_business_tables"],
        "platform_tables_present": diagnostics["platform_tables_present"],
        "unexpected_tables": diagnostics["unexpected_tables"],
        "unexpected_schema_objects": diagnostics["unexpected_schema_objects"],
        "unexpected_sqlite_internal_schema_objects": diagnostics[
            "unexpected_sqlite_internal_schema_objects"
        ],
        "android_metadata_state": diagnostics["android_metadata"]["state"],
        "accepted": diagnostics["migration_readiness"] == "ready",
    }


def portable_schema_errors(errors: Iterable[str]) -> List[str]:
    normalized = set()
    for error in errors:
        normalized_error = re.sub(
            r":sqlite_(?:master|schema)_sql$",
            ":schema_sql",
            error,
        )
        normalized_error = re.sub(
            r":table_(?:xinfo|info)$",
            ":column_metadata",
            normalized_error,
        )
        normalized.add(normalized_error)
    schema_sql_tables = {
        error.rsplit(":", 1)[0]
        for error in normalized
        if error.endswith(":schema_sql")
    }
    normalized = {
        error
        for error in normalized
        if not (
            error.endswith(":column_metadata")
            and error.rsplit(":", 1)[0] in schema_sql_tables
        )
    }
    return sorted(normalized)


def corpus_schema_diagnostics(
    diagnostics: Mapping[str, Any],
) -> Mapping[str, Any]:
    portable = copy.deepcopy(dict(diagnostics))
    portable.pop("validation_path", None)
    portable["schema_errors"] = portable_schema_errors(
        portable.get("schema_errors", ())
    )
    metadata = portable.get("android_metadata")
    if isinstance(metadata, dict):
        metadata["schema_errors"] = portable_schema_errors(
            metadata.get("schema_errors", ())
        )
    return portable


def validate_schema(
    connection: sqlite3.Connection,
    label: str,
    expected_business_tables: Sequence[str] = BUSINESS_TABLES,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> str:
    resolved_path = resolve_schema_path(connection, schema_path)
    expected_business_table_set = set(expected_business_tables)
    unknown_expected_tables = expected_business_table_set - set(BUSINESS_TABLES)
    if unknown_expected_tables:
        raise FixtureValidationError(
            "{} has unknown expected business tables {}".format(
                label,
                sorted(unknown_expected_tables),
            )
        )
    expected_tables = expected_business_table_set | set(PLATFORM_TABLES)
    user_tables = set(user_table_names(connection, resolved_path))
    if user_tables != expected_tables:
        raise FixtureValidationError(
            "{} tables mismatch: expected {}, found {}".format(
                label,
                sorted(expected_tables),
                sorted(user_tables),
            )
        )
    expected_objects = {
        ("table", table, table) for table in expected_tables
    }
    actual_objects = set(user_schema_objects(connection, resolved_path))
    if actual_objects != expected_objects:
        raise FixtureValidationError(
            "{} user schema objects mismatch: expected {}, found {}".format(
                label,
                sorted(expected_objects),
                sorted(actual_objects),
            )
        )
    actual_internal_objects = set(
        sqlite_internal_schema_objects(connection, resolved_path)
    )
    expected_internal_objects = expected_sqlite_internal_objects(user_tables)
    if actual_internal_objects != expected_internal_objects:
        raise FixtureValidationError(
            "{} SQLite internal schema objects mismatch: expected {}, found {}".format(
                label,
                sorted(expected_internal_objects),
                sorted(actual_internal_objects),
            )
        )
    internal_errors = sqlite_internal_schema_errors(
        connection,
        user_tables,
        resolved_path,
    )
    if internal_errors:
        raise FixtureValidationError(
            "{} SQLite internal schema semantics mismatch: {}".format(
                label,
                internal_errors,
            )
        )
    metadata_diagnostics = android_metadata_diagnostics(
        connection,
        user_tables,
        resolved_path,
    )
    if metadata_diagnostics["state"] != "valid":
        raise FixtureValidationError(
            "{} android_metadata is invalid: state {}, errors {}".format(
                label,
                metadata_diagnostics["state"],
                metadata_diagnostics["schema_errors"],
            )
        )
    for table in sorted(expected_tables):
        errors = table_schema_errors(connection, table, resolved_path)
        if errors:
            raise FixtureValidationError(
                "{} {} schema semantics mismatch: {}".format(
                    label,
                    table,
                    errors,
                )
            )
    user_version = connection.execute("PRAGMA user_version").fetchone()[0]
    if user_version != 0:
        raise FixtureValidationError(
            "{} user_version must be 0, found {}".format(label, user_version)
        )
    return hash_value(
        {
            "tables": {
                table: {
                    "table_xinfo": [
                        list(row) for row in EXPECTED_TABLE_XINFO[table]
                    ],
                    "sql_tokens": list(
                        canonical_schema_sql_tokens(
                            EXPECTED_SCHEMA_SQL[table]
                        )
                    ),
                }
                for table in sorted(expected_tables)
            },
            "sqlite_internal_schema_objects": [
                list(row) for row in sorted(expected_internal_objects)
            ],
            "user_version": user_version,
        }
    )


def validate_schema_all_paths(
    connection: sqlite3.Connection,
    label: str,
    expected_business_tables: Sequence[str] = BUSINESS_TABLES,
) -> str:
    checksums: Dict[str, str] = {}
    decisions: Dict[str, Mapping[str, Any]] = {}
    paths = supported_schema_paths(connection)
    for schema_path in paths:
        checksums[schema_path] = validate_schema(
            connection,
            "{} [{}]".format(label, schema_path),
            expected_business_tables,
            schema_path,
        )
        decisions[schema_path] = schema_decision_payload(
            schema_diagnostics(connection, schema_path)
        )
    if len(set(checksums.values())) != 1:
        raise FixtureValidationError(
            "{} schema checksums differ between validation paths".format(label)
        )
    first_decision = decisions[paths[0]]
    if any(decision != first_decision for decision in decisions.values()):
        raise FixtureValidationError(
            "{} schema decisions differ between supported paths".format(label)
        )
    return checksums[resolve_schema_path(connection)]


def schema_path_outcome(
    connection: sqlite3.Connection,
    label: str,
    expected_business_tables: Sequence[str] = BUSINESS_TABLES,
    schema_path: str = SCHEMA_PATH_AUTO,
) -> Mapping[str, Any]:
    diagnostics = schema_diagnostics(connection, schema_path)
    try:
        validate_schema(
            connection,
            label,
            expected_business_tables,
            schema_path,
        )
    except FixtureValidationError:
        exact_schema_valid = False
    else:
        exact_schema_valid = True
    return {
        "decision": schema_decision_payload(diagnostics),
        "exact_schema_valid": exact_schema_valid,
    }


def require_equivalent_schema_path_outcomes(
    connection: sqlite3.Connection,
    label: str,
    expected_business_tables: Sequence[str] = BUSINESS_TABLES,
) -> Mapping[str, Any]:
    paths = supported_schema_paths(connection)
    outcomes = {
        schema_path: schema_path_outcome(
            connection,
            "{} [{}]".format(label, schema_path),
            expected_business_tables,
            schema_path,
        )
        for schema_path in paths
    }
    first_outcome = outcomes[paths[0]]
    if any(outcome != first_outcome for outcome in outcomes.values()):
        raise FixtureValidationError(
            "{} has different supported schema outcomes".format(label)
        )
    return outcomes[resolve_schema_path(connection)]


def sqlite_file_structure_diagnostics(database: Path) -> Mapping[str, Any]:
    size = database.stat().st_size
    with database.open("rb") as handle:
        header = handle.read(100)
    header_diagnostics = sqlite_header_diagnostics(header)
    if len(header) < 100:
        return {
            "status": "truncated",
            "actual_bytes": size,
            "encoded_page_size": None,
            "page_size": None,
            "declared_pages": None,
            "declared_bytes": None,
            "header": header_diagnostics,
        }
    if header[:16] != SQLITE_HEADER:
        return {
            "status": "invalid_header",
            "actual_bytes": size,
            "encoded_page_size": None,
            "page_size": None,
            "declared_pages": None,
            "declared_bytes": None,
            "header": header_diagnostics,
        }
    encoded_page_size = int.from_bytes(header[16:18], "big")
    try:
        page_size = sqlite_page_size(header)
    except FixtureValidationError:
        return {
            "status": "invalid_page_size",
            "actual_bytes": size,
            "encoded_page_size": encoded_page_size,
            "page_size": None,
            "declared_pages": None,
            "declared_bytes": None,
            "header": header_diagnostics,
        }
    if header_diagnostics["status"] != "valid":
        return {
            "status": header_diagnostics["status"],
            "actual_bytes": size,
            "encoded_page_size": encoded_page_size,
            "page_size": page_size,
            "declared_pages": None,
            "declared_bytes": None,
            "header": header_diagnostics,
        }
    declared_pages = int.from_bytes(header[28:32], "big")
    declared_bytes = declared_pages * page_size
    if size < declared_bytes or size % page_size != 0:
        status = "truncated"
    elif size > declared_bytes:
        status = "trailing_bytes"
    else:
        status = "complete"
    return {
        "status": status,
        "actual_bytes": size,
        "encoded_page_size": encoded_page_size,
        "page_size": page_size,
        "declared_pages": declared_pages,
        "declared_bytes": declared_bytes,
        "header": header_diagnostics,
    }


def sqlite_integrity_diagnostics(database: Path) -> Mapping[str, Any]:
    try:
        with open_readonly(database) as connection:
            results = tuple(
                str(row[0]) for row in connection.execute("PRAGMA integrity_check")
            )
    except sqlite3.DatabaseError:
        return {
            "status": "failed",
            "result_count": 0,
        }
    if results == ("ok",):
        return {
            "status": "passed",
            "result_count": 1,
        }
    return {
        "status": "failed",
        "result_count": len(results),
    }


def require_sqlite_header_mode(
    structure: Mapping[str, Any],
    expected_versions: Tuple[int, int],
    label: str,
) -> None:
    header = structure.get("header")
    if (
        structure.get("status") not in ("complete", "truncated")
        or not isinstance(header, Mapping)
        or header.get("status") != "valid"
        or (
            header.get("write_version"),
            header.get("read_version"),
        )
        != expected_versions
    ):
        raise FixtureValidationError(
            "{} has the wrong SQLite journal-format header".format(label)
        )


def blocked_migration_expectations(reason: str) -> Mapping[str, Any]:
    return {
        "status": "blocked",
        "reason": reason,
        "source_rows_read": 0,
        "target_write_attempted": False,
        "target_rows_written": 0,
        "receipt_write_attempted": False,
        "receipt_written": False,
    }


def build_blocked_preflight_output(
    case: FixtureCase,
    database: Path,
) -> Mapping[str, Any]:
    structure = sqlite_file_structure_diagnostics(database)
    integrity: Mapping[str, Any]
    schema_check: Mapping[str, Any]
    detected_reason: Optional[str] = None

    if structure["status"] == "truncated":
        detected_reason = "truncated_sqlite"
        integrity = {"status": "not_run", "result_count": 0}
        schema_check = {
            "status": "not_run",
            "state": None,
            "schema_errors": [],
            "android_metadata": None,
        }
    elif structure["status"] != "complete":
        detected_reason = "corrupt_sqlite"
        integrity = {"status": "not_run", "result_count": 0}
        schema_check = {
            "status": "not_run",
            "state": None,
            "schema_errors": [],
            "android_metadata": None,
        }
    else:
        integrity = sqlite_integrity_diagnostics(database)
        if integrity["status"] != "passed":
            detected_reason = "corrupt_sqlite"
            schema_check = {
                "status": "not_run",
                "state": None,
                "schema_errors": [],
                "android_metadata": None,
            }
        else:
            with open_readonly(database) as connection:
                diagnostics = schema_diagnostics(connection)
            portable_diagnostics = corpus_schema_diagnostics(diagnostics)
            schema_check = {
                "status": (
                    "passed"
                    if diagnostics["migration_readiness"] == "ready"
                    else "failed"
                ),
                "state": diagnostics["state"],
                "schema_errors": portable_diagnostics["schema_errors"],
                "android_metadata": portable_diagnostics["android_metadata"],
            }
            if schema_check["status"] == "failed":
                detected_reason = diagnostics["state"]

    if detected_reason != case.blocked_reason:
        raise FixtureValidationError(
            "{} expected preflight block {!r}, found {!r}".format(
                case.key,
                case.blocked_reason,
                detected_reason,
            )
        )
    return {
        "format_version": FORMAT_VERSION,
        "output_kind": "blocked_preflight",
        "fixture": case.key,
        "database_identity": case.database_identity,
        "diagnostics": {
            "data_state": "not_examined_due_to_blocked_preflight",
            "preflight": {
                "file_structure": structure,
                "integrity_check": integrity,
                "schema_check": schema_check,
            },
        },
        "migration_expectations": blocked_migration_expectations(
            detected_reason
        ),
    }


def read_table_rows(
    connection: sqlite3.Connection, table: str
) -> Tuple[Tuple[Any, ...], ...]:
    columns = TABLE_COLUMNS[table]
    query = 'SELECT {} FROM "{}" ORDER BY "ID"'.format(
        ", ".join('"{}"'.format(column) for column in columns),
        table,
    )
    return tuple(tuple(row) for row in connection.execute(query))


def read_all_rows(
    connection: sqlite3.Connection,
) -> Mapping[str, Tuple[Tuple[Any, ...], ...]]:
    present_tables = set(user_table_names(connection))
    return {
        table: (
            read_table_rows(connection, table)
            if table in present_tables
            else ()
        )
        for table in BUSINESS_TABLES
    }


def table_logical_checksum(
    connection: sqlite3.Connection, table: str
) -> str:
    columns = TABLE_COLUMNS[table]
    hasher = hashlib.sha256()
    hasher.update(
        canonical_json_bytes({"columns": columns, "table": table}) + b"\n"
    )
    query = 'SELECT {} FROM "{}" ORDER BY "ID"'.format(
        ", ".join('"{}"'.format(column) for column in columns),
        table,
    )
    cursor = connection.execute(query)
    while True:
        batch = cursor.fetchmany(1000)
        if not batch:
            break
        for row in batch:
            hasher.update(
                canonical_json_bytes([typed_value(value) for value in row]) + b"\n"
            )
    return hasher.hexdigest()


def logical_checksums(
    connection: sqlite3.Connection,
) -> Mapping[str, Optional[str]]:
    present_tables = set(user_table_names(connection))
    table_checksums = {
        table: (
            table_logical_checksum(connection, table)
            if table in present_tables
            else None
        )
        for table in BUSINESS_TABLES
    }
    database_hasher = hashlib.sha256()
    for table in BUSINESS_TABLES:
        database_hasher.update(
            "{}:{}\n".format(
                table,
                table_checksums[table]
                if table_checksums[table] is not None
                else "MISSING",
            ).encode("ascii")
        )
    return {
        "activity": table_checksums["ACTIVITY"],
        "gps_points": table_checksums["GPS_POINTS"],
        "database": database_hasher.hexdigest(),
    }


def active_wal_snapshot_diagnostics(
    database: Path,
    case: FixtureCase,
) -> Mapping[str, Any]:
    wal_path = Path(str(database) + "-wal")
    shm_path = Path(str(database) + "-shm")
    missing = [
        path.name
        for path in (database, wal_path, shm_path)
        if not path.is_file()
    ]
    if missing:
        raise FixtureValidationError(
            "{} active snapshot is missing {}".format(case.key, missing)
        )

    wal = wal_path.read_bytes()
    if len(wal) < 32:
        raise FixtureValidationError("{} WAL header is truncated".format(case.key))
    magic, _, encoded_page_size = struct.unpack(">III", wal[:12])
    if magic not in (0x377F0682, 0x377F0683):
        raise FixtureValidationError("{} WAL magic is invalid".format(case.key))
    page_size = 65536 if encoded_page_size == 0 else encoded_page_size
    frame_size = 24 + page_size
    if (len(wal) - 32) % frame_size != 0:
        raise FixtureValidationError("{} WAL frames are truncated".format(case.key))
    frame_count = (len(wal) - 32) // frame_size
    if frame_count == 0:
        raise FixtureValidationError("{} WAL has no frames".format(case.key))
    salt_1, salt_2 = struct.unpack(">II", wal[16:24])
    if (salt_1, salt_2) != (WAL_SALT_1, WAL_SALT_2):
        raise FixtureValidationError("{} WAL salts are not canonical".format(case.key))
    final_commit_pages = struct.unpack(
        ">I",
        wal[32 + (frame_count - 1) * frame_size + 4 : 40 + (frame_count - 1) * frame_size],
    )[0]
    if final_commit_pages == 0:
        raise FixtureValidationError(
            "{} WAL does not end with a committed transaction".format(case.key)
        )

    immutable_uri = "file:{}?mode=ro&immutable=1".format(
        database.resolve().as_posix()
    )
    main_only = sqlite3.connect(immutable_uri, uri=True)
    try:
        main_rows = read_all_rows(main_only)
    finally:
        main_only.close()
    main_only_counts = {
        "activity_rows": len(main_rows["ACTIVITY"]),
        "track_point_rows": len(main_rows["GPS_POINTS"]),
    }
    if main_only_counts != {"activity_rows": 0, "track_point_rows": 0}:
        raise FixtureValidationError(
            "{} committed rows leaked into the main file".format(case.key)
        )

    with open_readonly(database) as source:
        source.execute("BEGIN")
        full_rows = read_all_rows(source)
        backup = sqlite3.connect(":memory:")
        try:
            source.backup(backup)
            backup_rows = read_all_rows(backup)
            backup_integrity = tuple(
                row[0] for row in backup.execute("PRAGMA integrity_check")
            )
        finally:
            backup.close()
            source.rollback()
    compare_rows_to_case(case, full_rows)
    compare_rows_to_case(case, backup_rows)
    if backup_integrity != ("ok",):
        raise FixtureValidationError(
            "{} consistent backup failed integrity check".format(case.key)
        )

    return {
        "mode": "active_wal",
        "required_artifacts": [
            database.name,
            wal_path.name,
            shm_path.name,
        ],
        "page_size": page_size,
        "wal_frames": frame_count,
        "shm_bytes": shm_path.stat().st_size,
        "rows_resident_in_wal": True,
        "main_only_counts": main_only_counts,
        "consistent_read_transaction": {
            "activity_rows": len(backup_rows["ACTIVITY"]),
            "track_point_rows": len(backup_rows["GPS_POINTS"]),
            "integrity_check": "ok",
        },
    }


TimestampParser = Callable[[Any, CalendarEvidence], Optional[datetime]]


def calendar_evidence_payload(evidence: CalendarEvidence) -> Mapping[str, str]:
    if evidence.calendar != CALENDAR_GREGORIAN:
        raise FixtureValidationError(
            "Android java.text source calendar must be Gregorian, found {!r}".format(
                evidence.calendar
            )
        )
    if not evidence.locale_tag or not evidence.source:
        raise FixtureValidationError("Calendar evidence must name a locale and source")
    return {
        "calendar": evidence.calendar,
        "locale_tag": evidence.locale_tag,
        "source": evidence.source,
    }


def activity_calendar_evidence(
    case: FixtureCase,
    activity_id: Any,
) -> CalendarEvidence:
    overrides = dict(case.activity_calendar_evidence)
    if len(overrides) != len(case.activity_calendar_evidence):
        raise FixtureValidationError(
            "{} has duplicate per-activity calendar evidence".format(case.key)
        )
    evidence = overrides.get(activity_id, case.default_calendar_evidence)
    if evidence is None:
        raise FixtureValidationError(
            "{} lacks Android formatter evidence for activity {}".format(
                case.key,
                activity_id,
            )
        )
    calendar_evidence_payload(evidence)
    return evidence


def point_calendar_evidence(
    case: FixtureCase,
    row: Mapping[str, Any],
) -> CalendarEvidence:
    return activity_calendar_evidence(case, row.get("ACTIVITYID"))


def normalized_legacy_timestamp_digits(
    value: Any,
    require_single_numbering_system: bool = True,
) -> Optional[str]:
    """Normalize one evidenced decimal block without changing the source string."""
    del require_single_numbering_system
    components = legacy_timestamp_digit_components(value)
    return components[0] if components is not None else None


def legacy_timestamp_digit_components(
    value: Any,
    allowed_zero_code_points: Optional[Iterable[int]] = None,
    allowed_control_layouts: Optional[Mapping[int, Iterable[str]]] = None,
) -> Optional[Tuple[str, int, str]]:
    if not isinstance(value, str):
        return None
    zeroes = tuple(
        sorted(
            formatter_source_emittable_zeroes()
            if allowed_zero_code_points is None
            else set(allowed_zero_code_points)
        )
    )
    layouts = (
        formatter_source_control_layouts()
        if allowed_control_layouts is None
        else {
            zero: frozenset(values)
            for zero, values in allowed_control_layouts.items()
        }
    )
    matches: List[Tuple[str, int, str]] = []
    for zero_code_point in zeroes:
        normalized: List[str] = []
        controls: List[Tuple[int, int]] = []
        for character in value:
            code_point = ord(character)
            decimal = code_point - zero_code_point
            if 0 <= decimal <= 9:
                normalized.append(str(decimal))
            else:
                controls.append((len(normalized), code_point))
        if len(normalized) != 14:
            continue
        control_layout = ",".join(
            "{}:U+{:04X}".format(position, code_point)
            for position, code_point in controls
        )
        if control_layout not in frozenset(layouts.get(zero_code_point, ())):
            continue
        matches.append(
            ("".join(normalized), zero_code_point, control_layout)
        )
    return matches[0] if len(matches) == 1 else None


def timestamp_digit_zero_code_point(value: Any) -> Optional[int]:
    components = legacy_timestamp_digit_components(value)
    return components[1] if components is not None else None


def parse_evidenced_legacy_timestamp(
    value: Any,
    evidence: CalendarEvidence,
) -> Optional[datetime]:
    calendar_evidence_payload(evidence)
    components = legacy_timestamp_digit_components(value)
    if components is None:
        return None
    normalized, _, _ = components
    source_year = int(normalized[0:4])
    try:
        parsed = datetime(
            source_year,
            int(normalized[4:6]),
            int(normalized[6:8]),
            int(normalized[8:10]),
            int(normalized[10:12]),
            int(normalized[12:14]),
        )
    except ValueError:
        return None
    round_trip = "{:04d}{:02d}{:02d}{:02d}{:02d}{:02d}".format(
        parsed.year,
        parsed.month,
        parsed.day,
        parsed.hour,
        parsed.minute,
        parsed.second,
    )
    return parsed if round_trip == normalized else None


def parse_strict_gregorian_legacy_timestamp(value: Any) -> Optional[datetime]:
    return parse_evidenced_legacy_timestamp(
        value,
        DEFAULT_GREGORIAN_CALENDAR_EVIDENCE,
    )


def strict_legacy_timestamp(value: Any, evidence: CalendarEvidence) -> bool:
    return parse_evidenced_legacy_timestamp(value, evidence) is not None


def canonical_utc_timestamp(value: datetime) -> str:
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")


def finite_number(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
    )


def ieee754_double_hex(value: Any) -> str:
    if not finite_number(value):
        raise FixtureValidationError("IEEE-754 comparison requires a finite number")
    return float(value).hex()


def row_mapping(columns: Sequence[str], row: Sequence[Any]) -> Dict[str, Any]:
    return dict(zip(columns, row))


def database_uuid(database_identity: str) -> uuid.UUID:
    return uuid.uuid5(FIXTURE_NAMESPACE, database_identity)


def deterministic_id(database_identity: str, table: str, legacy_id: int) -> str:
    return str(
        uuid.uuid5(
            database_uuid(database_identity),
            "{}:{}".format(table, legacy_id),
        )
    )


def activity_rejection_reasons(
    row: Mapping[str, Any],
    evidence: CalendarEvidence,
    timestamp_parser: TimestampParser = parse_evidenced_legacy_timestamp,
) -> List[str]:
    reasons: List[str] = []
    if not isinstance(row["ID"], int):
        reasons.append("invalid_id")
    if timestamp_parser(row["GMTSTART"], evidence) is None:
        reasons.append("invalid_gmtstart")
    if (
        row["GMTEND"] is not None
        and timestamp_parser(row["GMTEND"], evidence) is None
    ):
        reasons.append("invalid_gmtend")
    for column in ("NAME", "DESCRIPTION"):
        if row[column] is not None and not isinstance(row[column], str):
            reasons.append("non_text_{}".format(column.lower()))
    for column in ("DISTANCE", "TIME", "PACE"):
        value = row[column]
        if value is None:
            continue
        if not finite_number(value):
            reasons.append("non_numeric_{}".format(column.lower()))
        elif float(value) < 0:
            reasons.append("negative_{}".format(column.lower()))
    return sorted(reasons)


def point_rejection_reasons(
    row: Mapping[str, Any],
    evidence: CalendarEvidence,
    timestamp_parser: TimestampParser = parse_evidenced_legacy_timestamp,
) -> List[str]:
    reasons: List[str] = []
    if not isinstance(row["ID"], int):
        reasons.append("invalid_id")
    if not isinstance(row["ACTIVITYID"], int):
        reasons.append("missing_or_invalid_activity_id")
    if timestamp_parser(row["GMTTIMESTAMP"], evidence) is None:
        reasons.append("invalid_gmttimestamp")

    latitude = row["LATITUDE"]
    if not finite_number(latitude):
        reasons.append("missing_or_non_numeric_latitude")
    elif not -90.0 <= float(latitude) <= 90.0:
        reasons.append("latitude_out_of_range")

    longitude = row["LONGITUDE"]
    if not finite_number(longitude):
        reasons.append("missing_or_non_numeric_longitude")
    elif not -180.0 <= float(longitude) <= 180.0:
        reasons.append("longitude_out_of_range")

    altitude = row["ALTITUDE"]
    if altitude is not None and not finite_number(altitude):
        reasons.append("non_numeric_altitude")

    accuracy = row["ACCURACY"]
    if accuracy is not None:
        if not finite_number(accuracy):
            reasons.append("non_numeric_accuracy")
        elif float(accuracy) < 0:
            reasons.append("negative_accuracy")

    speed = row["SPEED"]
    if speed is not None:
        if not finite_number(speed):
            reasons.append("non_numeric_speed")
        elif float(speed) < 0:
            reasons.append("negative_speed")

    bearing = row["BEARING"]
    if bearing is not None:
        if not finite_number(bearing):
            reasons.append("non_numeric_bearing")
        elif not 0.0 <= float(bearing) < 360.0:
            reasons.append("bearing_out_of_range")

    heart_rate = row["HEARTRATE"]
    if heart_rate is not None:
        if not finite_number(heart_rate):
            reasons.append("non_numeric_heartrate")
        elif float(heart_rate) < 0:
            reasons.append("negative_heartrate")

    return sorted(reasons)


def source_key(database_identity: str, table: str, legacy_id: Any) -> str:
    return "{}:{}:{}".format(database_identity, table, legacy_id)


def normalized_session(
    database_identity: str,
    row: Mapping[str, Any],
    evidence: CalendarEvidence,
    timestamp_parser: TimestampParser,
) -> Mapping[str, Any]:
    optional_columns = (
        "GMTEND",
        "NAME",
        "DESCRIPTION",
        "DISTANCE",
        "TIME",
        "PACE",
    )
    legacy_id = row["ID"]
    parsed_start = timestamp_parser(row["GMTSTART"], evidence)
    parsed_end = (
        timestamp_parser(row["GMTEND"], evidence)
        if row["GMTEND"] is not None
        else None
    )
    if parsed_start is None or (row["GMTEND"] is not None and parsed_end is None):
        raise FixtureValidationError(
            "Accepted activity {} could not be interpreted with Android "
            "formatter semantics".format(legacy_id)
        )
    return {
        "source_key": source_key(database_identity, "ACTIVITY", legacy_id),
        "legacy_id": legacy_id,
        "deterministic_id": deterministic_id(
            database_identity, "ACTIVITY", legacy_id
        ),
        "timestamp_calendar": evidence.calendar,
        "timestamp_locale": evidence.locale_tag,
        "calendar_evidence_source": evidence.source,
        "gmt_start": row["GMTSTART"],
        "gmt_start_utc": canonical_utc_timestamp(parsed_start),
        "gmt_end": row["GMTEND"],
        "gmt_end_utc": (
            canonical_utc_timestamp(parsed_end) if parsed_end is not None else None
        ),
        "name": row["NAME"],
        "description": row["DESCRIPTION"],
        "distance": row["DISTANCE"],
        "time": row["TIME"],
        "pace": row["PACE"],
        "partial": any(row[column] is None for column in optional_columns),
    }


def normalized_point(
    database_identity: str,
    row: Mapping[str, Any],
    session_id: Optional[str],
    evidence: CalendarEvidence,
    timestamp_parser: TimestampParser,
) -> Mapping[str, Any]:
    legacy_id = row["ID"]
    parsed_timestamp = timestamp_parser(row["GMTTIMESTAMP"], evidence)
    if parsed_timestamp is None:
        raise FixtureValidationError(
            "Accepted point {} could not be interpreted with Android "
            "formatter semantics".format(legacy_id)
        )
    return {
        "source_key": source_key(database_identity, "GPS_POINTS", legacy_id),
        "legacy_id": legacy_id,
        "deterministic_id": deterministic_id(
            database_identity, "GPS_POINTS", legacy_id
        ),
        "activity_legacy_id": row["ACTIVITYID"],
        "session_id": session_id,
        "timestamp_calendar": evidence.calendar,
        "timestamp_locale": evidence.locale_tag,
        "calendar_evidence_source": evidence.source,
        "gmt_timestamp": row["GMTTIMESTAMP"],
        "gmt_timestamp_utc": canonical_utc_timestamp(parsed_timestamp),
        "latitude": row["LATITUDE"],
        "longitude": row["LONGITUDE"],
        "altitude": row["ALTITUDE"],
        "accuracy": row["ACCURACY"],
        "speed": row["SPEED"],
        "bearing": row["BEARING"],
        "heart_rate": row["HEARTRATE"],
    }


def rejected_row(
    database_identity: str,
    table: str,
    row: Mapping[str, Any],
    reasons: Sequence[str],
) -> Mapping[str, Any]:
    return {
        "source_key": source_key(database_identity, table, row.get("ID")),
        "table": table,
        "legacy_id": row.get("ID"),
        "reasons": sorted(reasons),
        "raw": dict(row),
    }


def calendar_diagnostics(
    case: FixtureCase,
    rows_by_table: Mapping[str, Sequence[Sequence[Any]]],
) -> Mapping[str, Any]:
    activity_rows = [
        row_mapping(ACTIVITY_COLUMNS, row) for row in rows_by_table["ACTIVITY"]
    ]
    evidence_rows = []
    for row in activity_rows:
        evidence = activity_calendar_evidence(case, row["ID"])
        evidence_rows.append(
            {
                "legacy_activity_id": row["ID"],
                **calendar_evidence_payload(evidence),
            }
        )
    locale_tags = {row["locale_tag"] for row in evidence_rows}
    return {
        "state": "android_gregorian",
        "migration_readiness": "ready",
        "source_platform": "Android java.text.SimpleDateFormat",
        "calendar": CALENDAR_GREGORIAN,
        "calendar_class": ANDROID_FORMATTER_CALENDAR_CLASS,
        "platform_invariant": True,
        "current_android_locale": case.android_locale,
        "current_locale_used_as_row_evidence": False,
        "historical_locale_change": len(locale_tags) > 1,
        "per_activity": evidence_rows,
    }


def ordering_diagnostics(
    case: FixtureCase,
    rows_by_table: Mapping[str, Sequence[Sequence[Any]]],
    timestamp_parser: TimestampParser = parse_evidenced_legacy_timestamp,
) -> Mapping[str, Any]:
    activity_ids = [
        row_mapping(ACTIVITY_COLUMNS, row)["ID"]
        for row in rows_by_table["ACTIVITY"]
    ]
    point_rows = [
        row_mapping(GPS_POINT_COLUMNS, row)
        for row in rows_by_table["GPS_POINTS"]
    ]
    point_ids = [row["ID"] for row in point_rows]
    valid_timestamps = [
        parsed
        for row in point_rows
        if (
            parsed := timestamp_parser(
                row["GMTTIMESTAMP"],
                point_calendar_evidence(case, row),
            )
        )
        is not None
    ]
    return {
        "activity_rows_ordered_by_64_bit_id": activity_ids == sorted(activity_ids),
        "track_point_rows_ordered_by_64_bit_id": point_ids == sorted(point_ids),
        "duplicate_track_point_timestamp_rows": (
            len(valid_timestamps) - len(set(valid_timestamps))
        ),
        "non_monotonic_track_point_timestamp_transitions": sum(
            1
            for previous, current in zip(
                valid_timestamps,
                valid_timestamps[1:],
            )
            if current < previous
        ),
    }


def build_canonical_output(
    case: FixtureCase,
    rows_by_table: Mapping[str, Sequence[Sequence[Any]]],
    source_schema_diagnostics: Mapping[str, Any],
    timestamp_parser: TimestampParser = parse_evidenced_legacy_timestamp,
) -> Mapping[str, Any]:
    portable_schema = corpus_schema_diagnostics(source_schema_diagnostics)
    activity_rows = [
        row_mapping(ACTIVITY_COLUMNS, row) for row in rows_by_table["ACTIVITY"]
    ]
    point_rows = [
        row_mapping(GPS_POINT_COLUMNS, row)
        for row in rows_by_table["GPS_POINTS"]
    ]

    sessions: List[Mapping[str, Any]] = []
    rejected: List[Mapping[str, Any]] = []
    session_by_legacy_id: Dict[int, Mapping[str, Any]] = {}
    invalid_activity_ids = set()

    for row in activity_rows:
        evidence = activity_calendar_evidence(case, row["ID"])
        reasons = activity_rejection_reasons(row, evidence, timestamp_parser)
        if reasons:
            invalid_activity_ids.add(row["ID"])
            rejected.append(
                rejected_row(case.database_identity, "ACTIVITY", row, reasons)
            )
            continue
        session = normalized_session(
            case.database_identity,
            row,
            evidence,
            timestamp_parser,
        )
        sessions.append(session)
        session_by_legacy_id[row["ID"]] = session

    track_points: List[Mapping[str, Any]] = []
    orphan_track_points: List[Mapping[str, Any]] = []
    for row in point_rows:
        evidence = point_calendar_evidence(case, row)
        reasons = point_rejection_reasons(row, evidence, timestamp_parser)
        if isinstance(row["ACTIVITYID"], int) and row["ACTIVITYID"] in invalid_activity_ids:
            reasons.append("invalid_parent_activity")
        if reasons:
            rejected.append(
                rejected_row(
                    case.database_identity,
                    "GPS_POINTS",
                    row,
                    sorted(set(reasons)),
                )
            )
            continue
        parent = session_by_legacy_id.get(row["ACTIVITYID"])
        if parent is None:
            orphan = dict(
                normalized_point(
                    case.database_identity,
                    row,
                    None,
                    evidence,
                    timestamp_parser,
                )
            )
            orphan["reason"] = "missing_activity"
            orphan_track_points.append(orphan)
        else:
            track_points.append(
                normalized_point(
                    case.database_identity,
                    row,
                    parent["deterministic_id"],
                    evidence,
                    timestamp_parser,
                )
            )

    rejected_activity_count = sum(
        1 for row in rejected if row["table"] == "ACTIVITY"
    )
    rejected_point_count = sum(
        1 for row in rejected if row["table"] == "GPS_POINTS"
    )
    output: Dict[str, Any] = {
        "format_version": FORMAT_VERSION,
        "output_kind": "canonical",
        "fixture": case.key,
        "database_identity": case.database_identity,
        "diagnostics": {
            "schema": portable_schema,
            "calendar": calendar_diagnostics(case, rows_by_table),
            "data_state": (
                "empty"
                if not activity_rows and not point_rows
                else "contains_business_rows"
            ),
            "ordering": ordering_diagnostics(
                case,
                rows_by_table,
                timestamp_parser,
            ),
        },
        "sessions": sessions,
        "track_points": track_points,
        "orphan_track_points": orphan_track_points,
        "rejected_rows": rejected,
        "summary": {
            "source_activity_rows": len(activity_rows),
            "source_track_point_rows": len(point_rows),
            "sessions": len(sessions),
            "partial_sessions": sum(
                1 for session in sessions if session["partial"]
            ),
            "track_points": len(track_points),
            "orphan_track_points": len(orphan_track_points),
            "rejected_activity_rows": rejected_activity_count,
            "rejected_track_point_rows": rejected_point_count,
        },
        "idempotency": None,
        "migration_expectations": (
            None
            if source_schema_diagnostics["migration_readiness"] == "ready"
            else blocked_migration_expectations(
                source_schema_diagnostics["state"]
            )
        ),
    }
    if case.exercise_idempotency:
        output["idempotency"] = simulate_idempotent_migration(output)
    return output


def ordered_migration_records(
    output: Mapping[str, Any],
) -> List[Tuple[str, Mapping[str, Any]]]:
    points_by_session: Dict[str, List[Mapping[str, Any]]] = {}
    for track_point in output["track_points"]:
        points_by_session.setdefault(track_point["session_id"], []).append(track_point)

    records: List[Tuple[str, Mapping[str, Any]]] = []
    for session in output["sessions"]:
        records.append(("session", session))
        records.extend(
            ("track_point", track_point)
            for track_point in points_by_session.get(
                session["deterministic_id"],
                (),
            )
        )
    return records


def duplicate_row_count(
    by_source: Mapping[str, Mapping[str, Any]],
) -> int:
    rows = list(by_source.values())
    duplicate_source_rows = len(rows) - len(
        {row["source_key"] for row in rows}
    )
    duplicate_id_rows = len(rows) - len(
        {row["deterministic_id"] for row in rows}
    )
    return max(duplicate_source_rows, duplicate_id_rows)


def apply_insert_attempts(
    by_source: Dict[str, Mapping[str, Any]],
    by_id: Dict[str, str],
    records: Iterable[Tuple[str, Mapping[str, Any]]],
) -> Mapping[str, Any]:
    attempted_by_kind = {"session": 0, "track_point": 0}
    inserted_by_kind = {"session": 0, "track_point": 0}

    for kind, record in records:
        if kind not in attempted_by_kind:
            raise FixtureValidationError(
                "Unknown migration record kind {!r}".format(kind)
            )
        attempted_by_kind[kind] += 1
        key = record["source_key"]
        record_id = record["deterministic_id"]
        payload_sha256 = hashlib.sha256(canonical_json_bytes(record)).hexdigest()
        existing = by_source.get(key)
        if existing is not None:
            if (
                existing["kind"] != kind
                or existing["deterministic_id"] != record_id
                or existing["payload_sha256"] != payload_sha256
            ):
                raise FixtureValidationError(
                    "Duplicate insert attempt changed deterministic identity or "
                    "payload for {}".format(key)
                )
            continue

        other_source = by_id.get(record_id)
        if other_source is not None and other_source != key:
            raise FixtureValidationError(
                "Duplicate deterministic ID {} for {} and {}".format(
                    record_id, other_source, key
                )
            )
        by_source[key] = {
            "source_key": key,
            "kind": kind,
            "deterministic_id": record_id,
            "payload_sha256": payload_sha256,
            "record": copy.deepcopy(record),
        }
        by_id[record_id] = key
        inserted_by_kind[kind] += 1

    attempted_rows = sum(attempted_by_kind.values())
    inserted_rows = sum(inserted_by_kind.values())
    duplicate_attempts = attempted_rows - inserted_rows
    return {
        "attempted_rows": attempted_rows,
        "inserted_rows": inserted_rows,
        "duplicate_attempts": duplicate_attempts,
        "duplicate_rows": duplicate_row_count(by_source),
        "attempted_sessions": attempted_by_kind["session"],
        "inserted_sessions": inserted_by_kind["session"],
        "duplicate_session_attempts": (
            attempted_by_kind["session"] - inserted_by_kind["session"]
        ),
        "attempted_track_points": attempted_by_kind["track_point"],
        "inserted_track_points": inserted_by_kind["track_point"],
        "duplicate_track_point_attempts": (
            attempted_by_kind["track_point"]
            - inserted_by_kind["track_point"]
        ),
        "attempts_fully_accounted": (
            attempted_rows == inserted_rows + duplicate_attempts
        ),
    }


def migration_state_payload(
    by_source: Mapping[str, Mapping[str, Any]],
) -> List[Mapping[str, Any]]:
    return [by_source[key] for key in sorted(by_source)]


def migration_state_counts(
    by_source: Mapping[str, Mapping[str, Any]],
) -> Mapping[str, int]:
    return {
        "sessions": sum(
            1 for value in by_source.values() if value["kind"] == "session"
        ),
        "track_points": sum(
            1 for value in by_source.values() if value["kind"] == "track_point"
        ),
    }


def expected_migration_state(
    records: Sequence[Tuple[str, Mapping[str, Any]]],
) -> List[Mapping[str, Any]]:
    by_source: Dict[str, Mapping[str, Any]] = {}
    by_id: Dict[str, str] = {}
    stats = apply_insert_attempts(by_source, by_id, records)
    if (
        stats["inserted_rows"] != len(records)
        or stats["duplicate_attempts"] != 0
        or stats["duplicate_rows"] != 0
    ):
        raise FixtureValidationError(
            "Canonical migration records are not unique insert attempts"
        )
    return migration_state_payload(by_source)


def canonical_migration_records(
    records: Sequence[Tuple[str, Mapping[str, Any]]],
) -> List[Mapping[str, Any]]:
    return [
        {
            "kind": kind,
            "record": record,
        }
        for kind, record in records
    ]


def assert_exact_migration_records(
    label: str,
    actual_records: Sequence[Tuple[str, Mapping[str, Any]]],
    expected_records: Sequence[Tuple[str, Mapping[str, Any]]],
) -> None:
    if canonical_json_bytes(
        canonical_migration_records(actual_records)
    ) != canonical_json_bytes(canonical_migration_records(expected_records)):
        raise FixtureValidationError(
            "{} rerun records do not exactly match the canonical migration "
            "records".format(label)
        )


def assert_exact_final_state(
    label: str,
    actual_state: Sequence[Mapping[str, Any]],
    expected_state: Sequence[Mapping[str, Any]],
) -> None:
    if canonical_json_bytes(actual_state) != canonical_json_bytes(expected_state):
        raise FixtureValidationError(
            "{} did not produce exact canonical final state".format(label)
        )


def simulate_interrupted_insert_attempts(
    first_output: Mapping[str, Any],
    interruption_after_attempts: int,
    interruption_point: str,
    rerun_output: Optional[Mapping[str, Any]] = None,
) -> Mapping[str, Any]:
    first_records = ordered_migration_records(first_output)
    if (
        interruption_after_attempts <= 0
        or interruption_after_attempts >= len(first_records)
    ):
        raise FixtureValidationError(
            "Interruption must occur after a non-empty strict prefix of attempts"
        )
    rerun = rerun_output if rerun_output is not None else first_output
    rerun_records = ordered_migration_records(rerun)
    assert_exact_migration_records(
        interruption_point,
        rerun_records,
        first_records,
    )
    expected_state = expected_migration_state(first_records)
    by_source: Dict[str, Mapping[str, Any]] = {}
    by_id: Dict[str, str] = {}

    first_attempt = apply_insert_attempts(
        by_source,
        by_id,
        first_records[:interruption_after_attempts],
    )
    first_attempt = dict(first_attempt)
    first_attempt.update(migration_state_counts(by_source))
    first_attempt["migration_complete"] = False
    first_attempt["receipt_present"] = False
    if (
        first_attempt["attempted_rows"] != interruption_after_attempts
        or first_attempt["inserted_rows"] != interruption_after_attempts
        or first_attempt["duplicate_attempts"] != 0
    ):
        raise FixtureValidationError(
            "{} first attempt is not the canonical committed prefix".format(
                interruption_point
            )
        )

    replay = apply_insert_attempts(by_source, by_id, rerun_records)
    replay = dict(replay)
    replay.update(migration_state_counts(by_source))
    replay["migration_complete"] = True
    replay["receipt_present"] = True
    replay["receipt_completed"] = True
    canonical_sessions = sum(1 for kind, _ in first_records if kind == "session")
    canonical_track_points = sum(
        1 for kind, _ in first_records if kind == "track_point"
    )
    if (
        replay["attempted_rows"] != len(first_records)
        or replay["attempted_sessions"] != canonical_sessions
        or replay["attempted_track_points"] != canonical_track_points
        or replay["duplicate_attempts"] != interruption_after_attempts
        or replay["duplicate_session_attempts"]
        != first_attempt["inserted_sessions"]
        or replay["duplicate_track_point_attempts"]
        != first_attempt["inserted_track_points"]
        or replay["inserted_rows"]
        != len(first_records) - interruption_after_attempts
        or replay["inserted_sessions"]
        != canonical_sessions - first_attempt["inserted_sessions"]
        or replay["inserted_track_points"]
        != canonical_track_points - first_attempt["inserted_track_points"]
    ):
        raise FixtureValidationError(
            "{} replay attempts do not account for the full canonical record "
            "set".format(interruption_point)
        )

    final_state = migration_state_payload(by_source)
    assert_exact_final_state(interruption_point, final_state, expected_state)
    duplicate_attempts_prevented = (
        first_attempt["duplicate_attempts"] + replay["duplicate_attempts"]
    )
    return {
        "interruption_point": interruption_point,
        "interruption_after_attempts": interruption_after_attempts,
        "canonical_record_count": len(first_records),
        "rerun_record_count": len(rerun_records),
        "rerun_records_exactly_canonical": True,
        "replay_attempts_cover_canonical_set": True,
        "first_attempt": first_attempt,
        "replay": replay,
        "duplicate_attempts_prevented": duplicate_attempts_prevented,
        "duplicate_rows": duplicate_row_count(by_source),
        "exact_final_equality": True,
        "state_logical_checksum": hash_value(final_state),
    }


def simulate_same_run_duplicates(
    output: Mapping[str, Any],
) -> Mapping[str, Any]:
    records = ordered_migration_records(output)
    expected_state = expected_migration_state(records)
    duplicate_attempts = [
        record
        for record in records
        for _ in range(2)
    ]
    by_source: Dict[str, Mapping[str, Any]] = {}
    by_id: Dict[str, str] = {}
    attempt = apply_insert_attempts(by_source, by_id, duplicate_attempts)
    final_state = migration_state_payload(by_source)
    assert_exact_final_state("same_run_duplicates", final_state, expected_state)
    result = dict(attempt)
    result.update(migration_state_counts(by_source))
    result.update(
        {
            "migration_complete": True,
            "receipt_present": True,
            "receipt_completed": True,
            "exact_final_equality": True,
            "state_logical_checksum": hash_value(final_state),
        }
    )
    return result


def simulate_receipt_gap_replay(
    output: Mapping[str, Any],
) -> Mapping[str, Any]:
    records = ordered_migration_records(output)
    expected_state = expected_migration_state(records)
    by_source: Dict[str, Mapping[str, Any]] = {}
    by_id: Dict[str, str] = {}

    committed_rows = dict(apply_insert_attempts(by_source, by_id, records))
    committed_rows.update(migration_state_counts(by_source))
    committed_rows.update(
        {
            "migration_complete": False,
            "receipt_present": False,
            "receipt_completed": False,
        }
    )
    committed_state = migration_state_payload(by_source)
    assert_exact_final_state(
        "receipt_gap_target_commit",
        committed_state,
        expected_state,
    )

    replay = dict(apply_insert_attempts(by_source, by_id, records))
    replay.update(migration_state_counts(by_source))
    replay.update(
        {
            "migration_complete": True,
            "receipt_present": True,
            "receipt_completed": True,
        }
    )
    final_state = migration_state_payload(by_source)
    assert_exact_final_state("receipt_gap_replay", final_state, expected_state)
    if replay["inserted_rows"] != 0 or replay["duplicate_attempts"] != len(records):
        raise FixtureValidationError(
            "Receipt-gap replay must insert zero rows and count every duplicate"
        )
    if canonical_json_bytes(committed_state) != canonical_json_bytes(final_state):
        raise FixtureValidationError("Receipt-gap replay changed committed target state")

    return {
        "failure_point": "after_target_commit_before_receipt",
        "committed_rows": committed_rows,
        "replay": replay,
        "duplicate_attempts_prevented": replay["duplicate_attempts"],
        "exact_state_preserved": True,
        "state_logical_checksum": hash_value(final_state),
    }


def simulate_idempotent_migration(
    output: Mapping[str, Any],
) -> Mapping[str, Any]:
    records = ordered_migration_records(output)
    if (
        len(records) < 3
        or records[0][0] != "session"
        or records[1][0] != "track_point"
        or records[2][0] != "track_point"
    ):
        raise FixtureValidationError(
            "Idempotency fixture must start with a session and at least two points"
        )
    expected_state = expected_migration_state(records)
    return {
        "canonical_insert_rows": len(records),
        "expected_sessions": sum(1 for kind, _ in records if kind == "session"),
        "expected_track_points": sum(
            1 for kind, _ in records if kind == "track_point"
        ),
        "expected_state_logical_checksum": hash_value(expected_state),
        "scenarios": {
            "interrupt_after_session_row": simulate_interrupted_insert_attempts(
                output,
                interruption_after_attempts=1,
                interruption_point="after_session_row",
            ),
            "interrupt_after_partial_point_prefix": (
                simulate_interrupted_insert_attempts(
                    output,
                    interruption_after_attempts=2,
                    interruption_point="after_partial_point_prefix",
                )
            ),
            "same_run_duplicates": simulate_same_run_duplicates(output),
            "receipt_gap_replay": simulate_receipt_gap_replay(output),
        },
    }


def timestamp_summary(
    case: FixtureCase,
    rows_by_table: Mapping[str, Sequence[Sequence[Any]]],
    timestamp_parser: TimestampParser = parse_evidenced_legacy_timestamp,
) -> Mapping[str, Mapping[str, Any]]:
    activity_values: List[Tuple[Any, CalendarEvidence]] = []
    for row in rows_by_table["ACTIVITY"]:
        mapped = row_mapping(ACTIVITY_COLUMNS, row)
        evidence = activity_calendar_evidence(case, mapped["ID"])
        activity_values.extend(
            (
                (mapped["GMTSTART"], evidence),
                (mapped["GMTEND"], evidence),
            )
        )
    point_values: List[Tuple[Any, CalendarEvidence]] = []
    for row in rows_by_table["GPS_POINTS"]:
        mapped = row_mapping(GPS_POINT_COLUMNS, row)
        evidence = point_calendar_evidence(case, mapped)
        point_values.append((mapped["GMTTIMESTAMP"], evidence))

    def summarize(
        values: Sequence[Tuple[Any, CalendarEvidence]],
    ) -> Mapping[str, Any]:
        valid = [
            (parsed, value)
            for value, evidence in values
            if (parsed := timestamp_parser(value, evidence)) is not None
        ]
        invalid = [
            value
            for value, evidence in values
            if value is not None
            and timestamp_parser(value, evidence) is None
        ]
        return {
            "min": min(valid, key=lambda item: item[0])[1] if valid else None,
            "max": max(valid, key=lambda item: item[0])[1] if valid else None,
            "valid_count": len(valid),
            "invalid_count": len(invalid),
            "null_count": sum(1 for value, _ in values if value is None),
        }

    return {
        "activity": summarize(activity_values),
        "track_point": summarize(point_values),
        "all": summarize(activity_values + point_values),
    }


def points_per_activity(
    rows: Sequence[Sequence[Any]],
) -> Mapping[str, int]:
    counts: Dict[str, int] = {}
    for row in rows:
        activity_id = row_mapping(GPS_POINT_COLUMNS, row)["ACTIVITYID"]
        key = "null" if activity_id is None else str(activity_id)
        counts[key] = counts.get(key, 0) + 1
    return dict(sorted(counts.items()))


def physical_orphan_count(
    rows_by_table: Mapping[str, Sequence[Sequence[Any]]]
) -> int:
    activity_ids = {
        row_mapping(ACTIVITY_COLUMNS, row)["ID"]
        for row in rows_by_table["ACTIVITY"]
    }
    return sum(
        1
        for raw in rows_by_table["GPS_POINTS"]
        if (
            (row := row_mapping(GPS_POINT_COLUMNS, raw))["ACTIVITYID"] is not None
            and row["ACTIVITYID"] not in activity_ids
        )
    )


def representative_values(
    connection: sqlite3.Connection,
    case: FixtureCase,
) -> List[Mapping[str, Any]]:
    result: List[Mapping[str, Any]] = []
    for representative in case.representative_values:
        table = representative["table"]
        column = representative["column"]
        legacy_id = representative["legacy_id"]
        if table not in TABLE_COLUMNS or column not in TABLE_COLUMNS[table]:
            raise FixtureValidationError(
                "Invalid representative selector {}.{}".format(table, column)
            )
        row = connection.execute(
            'SELECT "{}", typeof("{}") FROM "{}" WHERE ID = ?'.format(
                column, column, table
            ),
            (legacy_id,),
        ).fetchone()
        if row is None:
            raise FixtureValidationError(
                "Representative row {}:{} is missing".format(table, legacy_id)
            )
        actual, storage_type = row
        compare_representative(representative, actual)
        materialized = dict(representative)
        materialized["storage_type"] = storage_type
        result.append(materialized)
    return result


def compare_representative(representative: Mapping[str, Any], actual: Any) -> None:
    expected = representative["expected"]
    comparison = representative["comparison"]
    if comparison == "exact":
        if type(actual) is not type(expected) or actual != expected:
            raise FixtureValidationError(
                "Representative {}:{} {} expected {!r}, found {!r}".format(
                    representative["table"],
                    representative["legacy_id"],
                    representative["column"],
                    expected,
                    actual,
                )
            )
        return
    if comparison == "ieee754":
        if ieee754_double_hex(actual) != ieee754_double_hex(expected):
            raise FixtureValidationError(
                "Representative {}:{} {} expected IEEE-754 {}, found {}".format(
                    representative["table"],
                    representative["legacy_id"],
                    representative["column"],
                    ieee754_double_hex(expected),
                    ieee754_double_hex(actual),
                )
            )
        return
    if comparison == "epsilon":
        epsilon = float(representative["epsilon"])
        if not finite_number(actual) or not finite_number(expected):
            raise FixtureValidationError("Epsilon comparison requires finite numbers")
        if abs(float(actual) - float(expected)) > epsilon:
            raise FixtureValidationError(
                "Representative {}:{} {} differs by more than {}".format(
                    representative["table"],
                    representative["legacy_id"],
                    representative["column"],
                    epsilon,
                )
            )
        return
    raise FixtureValidationError(
        "Unknown representative comparison {!r}".format(comparison)
    )


def fixture_artifact_paths(database: Path, case: FixtureCase) -> Tuple[Path, ...]:
    if case.storage == STORAGE_ACTIVE_WAL:
        return (
            database,
            Path(str(database) + "-wal"),
            Path(str(database) + "-shm"),
        )
    return (database,)


def fixture_artifact_comparison(case: FixtureCase) -> str:
    comparisons = {
        STORAGE_STANDARD: COMPARISON_LOGICAL_DATABASE,
        STORAGE_ACTIVE_WAL: COMPARISON_ACTIVE_WAL,
        STORAGE_MALFORMED_SCHEMA: COMPARISON_MALFORMED_SCHEMA,
        STORAGE_TRUNCATED: COMPARISON_TRUNCATED,
        STORAGE_CORRUPT: COMPARISON_CORRUPT,
    }
    try:
        return comparisons[case.storage]
    except KeyError as error:
        raise FixtureValidationError(
            "{} has unknown storage mode {!r}".format(case.key, case.storage)
        ) from error


def artifact_manifest(
    root: Path,
    database: Path,
    case: FixtureCase,
) -> List[Mapping[str, Any]]:
    comparison = fixture_artifact_comparison(case)
    artifacts: List[Mapping[str, Any]] = []
    for path in fixture_artifact_paths(database, case):
        if not path.is_file():
            raise FixtureValidationError(
                "{} is missing fixture artifact {}".format(case.key, path)
            )
        artifact: Dict[str, Any] = {
            "path": path.relative_to(root).as_posix(),
            "comparison": comparison,
            "exact_bytes_required": False,
        }
        artifacts.append(artifact)
    return artifacts


def manifest_entry(
    root: Path,
    case: FixtureCase,
    database: Path,
    expected_output_path: Path,
    connection: sqlite3.Connection,
    rows_by_table: Mapping[str, Sequence[Sequence[Any]]],
    output: Mapping[str, Any],
) -> Mapping[str, Any]:
    return {
        "name": case.key,
        "description": case.description,
        "storage": case.storage,
        "android_locale": case.android_locale,
        "database": database.relative_to(root).as_posix(),
        "expected_output": expected_output_path.relative_to(root).as_posix(),
        "database_identity": case.database_identity,
        "artifacts": artifact_manifest(root, database, case),
        "storage_canonical_checksum": hash_value(
            fixture_storage_snapshot(database, case)
        ),
        "expected": {
            "activity_rows": len(rows_by_table["ACTIVITY"]),
            "track_point_rows": len(rows_by_table["GPS_POINTS"]),
            "physical_orphan_track_points": physical_orphan_count(rows_by_table),
            "points_per_activity": points_per_activity(
                rows_by_table["GPS_POINTS"]
            ),
            "timestamps": timestamp_summary(case, rows_by_table),
            "canonical": output["summary"],
            "diagnostics": output["diagnostics"],
            "calendar_evidence": output["diagnostics"]["calendar"],
            "platform_metadata": platform_metadata_payload(connection),
            "schema_logical_checksum": validate_schema_all_paths(
                connection,
                "{} manifest".format(case.key),
                case.business_tables,
            ),
            "logical_checksums": logical_checksums(connection),
            "canonical_output_logical_checksum": hash_value(output),
            "representative_values": representative_values(connection, case),
            "idempotency": output["idempotency"],
        },
    }


def blocked_manifest_entry(
    root: Path,
    case: FixtureCase,
    database: Path,
    expected_output_path: Path,
    output: Mapping[str, Any],
) -> Mapping[str, Any]:
    return {
        "name": case.key,
        "description": case.description,
        "storage": case.storage,
        "android_locale": case.android_locale,
        "database": database.relative_to(root).as_posix(),
        "expected_output": expected_output_path.relative_to(root).as_posix(),
        "database_identity": case.database_identity,
        "artifacts": artifact_manifest(root, database, case),
        "storage_canonical_checksum": hash_value(
            fixture_storage_snapshot(database, case)
        ),
        "expected": {
            "generation_seed_counts": {
                "activity_rows": len(case.activities),
                "track_point_rows": len(case.track_points),
            },
            "diagnostics": output["diagnostics"],
            "migration_expectations": output["migration_expectations"],
            "canonical_output_logical_checksum": hash_value(output),
        },
    }


def generate_corpus(root: Path = TOOL_ROOT) -> Mapping[str, Any]:
    root = root.resolve()
    formatter_probe_matrix()
    exact_formatter_artifacts = (
        ANDROID_FORMATTER_PROBE_SOURCE_PATH,
        ANDROID_FORMATTER_PROBE_RUNNER_PATH,
        *ANDROID_FORMATTER_EVIDENCE_PATHS,
    )
    for source in exact_formatter_artifacts:
        destination = root / source.name
        if destination.resolve() != source.resolve():
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
    fixtures_dir = root / "fixtures"
    expected_dir = root / "expected"
    fixtures_dir.mkdir(parents=True, exist_ok=True)
    expected_dir.mkdir(parents=True, exist_ok=True)
    for pattern in ("*.db", "*.db-wal", "*.db-shm"):
        for path in fixtures_dir.glob(pattern):
            path.unlink()
    for path in expected_dir.glob("*.json"):
        path.unlink()

    entries: List[Mapping[str, Any]] = []
    for case in fixture_cases():
        database = fixtures_dir / "{}.db".format(case.key)
        expected_output_path = expected_dir / "{}.json".format(case.key)
        generate_case_database(root, case, database)
        if case.blocked_reason is not None:
            output = build_blocked_preflight_output(case, database)
            write_json(expected_output_path, output)
            entries.append(
                blocked_manifest_entry(
                    root,
                    case,
                    database,
                    expected_output_path,
                    output,
                )
            )
            continue
        with open_readonly(database) as connection:
            validate_schema_all_paths(
                connection,
                case.key,
                case.business_tables,
            )
            rows_by_table = read_all_rows(connection)
            source_schema_diagnostics = schema_diagnostics(connection)
            output = build_canonical_output(
                case,
                rows_by_table,
                source_schema_diagnostics,
            )
            if case.storage == STORAGE_ACTIVE_WAL:
                output["diagnostics"]["snapshot"] = (
                    active_wal_snapshot_diagnostics(database, case)
                )
            write_json(expected_output_path, output)
            entry = manifest_entry(
                root,
                case,
                database,
                expected_output_path,
                connection,
                rows_by_table,
                output,
            )
            entries.append(entry)

    manifest = {
        "format_version": FORMAT_VERSION,
        "synthetic_data_only": True,
        "database_name": DATABASE_NAME,
        "fixture_namespace_uuid": str(FIXTURE_NAMESPACE),
        "determinism": {
            "json_encoding": "UTF-8",
            "json_newline": "LF",
            "sqlite_host_header_ignored_byte_ranges": [
                list(byte_range) for byte_range in SQLITE_HOST_HEADER_RANGES
            ],
        },
        "logical_checksum_algorithm": (
            "SHA-256 over ordered table rows; each SQLite value is type-tagged, "
            "REAL values use exact IEEE-754 float.hex(), and table hashes are "
            "combined in ACTIVITY/GPS_POINTS order"
        ),
        "calendar_oracle": {
            "source_platform": "Android java.text.SimpleDateFormat",
            "source_calendar": CALENDAR_GREGORIAN,
            "calendar_class": ANDROID_FORMATTER_CALENDAR_CLASS,
            "current_android_metadata_is_row_evidence": False,
            "durable_per_activity_evidence_required": False,
            "locale_calendar_extension_changes_calendar": False,
            "year_interpretation": "strict_proleptic_gregorian_without_offset",
        },
        "formatter_oracle": formatter_oracle_manifest(),
        "source_schema": {
            "android_metadata_ddl": CREATE_ANDROID_METADATA_SQL,
            "android_metadata_business_data": False,
            "fixture_android_locales": sorted(
                {case.android_locale for case in fixture_cases()}
            ),
            "activity_ddl": CREATE_ACTIVITY_SQL,
            "gps_points_ddl": CREATE_GPS_POINTS_SQL,
            "user_version": 0,
            "foreign_keys_declared": False,
            "schema_validation_paths": list(SCHEMA_PATHS),
            "android_api_26_sqlite_version": "3.18.2",
            "table_xinfo_supported_from": "3.26.0",
            "sqlite_schema_alias_supported_from": "3.33.0",
            "schema_capabilities_probed_independently": True,
            "sqlite_catalog_preferred": "sqlite_master",
        },
        "fixtures": entries,
    }
    write_json(root / "manifest.json", manifest)
    return manifest


def compare_rows_to_case(
    case: FixtureCase,
    rows_by_table: Mapping[str, Sequence[Sequence[Any]]],
) -> None:
    expected = {
        "ACTIVITY": case.activities,
        "GPS_POINTS": case.track_points,
    }
    for table in ("ACTIVITY", "GPS_POINTS"):
        actual_rows = rows_by_table[table]
        expected_rows = expected[table]
        if len(actual_rows) != len(expected_rows):
            raise FixtureValidationError(
                "{} {} count expected {}, found {}".format(
                    case.key, table, len(expected_rows), len(actual_rows)
                )
            )
        for index, (actual, wanted) in enumerate(zip(actual_rows, expected_rows)):
            actual_typed = [typed_value(value) for value in actual]
            wanted_typed = [typed_value(value) for value in wanted]
            if actual_typed != wanted_typed:
                raise FixtureValidationError(
                    "{} {} row {} differs from generator definition".format(
                        case.key, table, index
                    )
                )


def compare_json(
    expected: Any,
    actual: Any,
    path: str = "$",
) -> None:
    if isinstance(expected, Mapping):
        if not isinstance(actual, Mapping):
            raise FixtureValidationError(
                "{} expected object, found {}".format(path, type(actual).__name__)
            )
        expected_keys = set(expected)
        actual_keys = set(actual)
        if expected_keys != actual_keys:
            raise FixtureValidationError(
                "{} keys mismatch; missing {}, extra {}".format(
                    path,
                    sorted(expected_keys - actual_keys),
                    sorted(actual_keys - expected_keys),
                )
            )
        for key in sorted(expected_keys):
            compare_json(
                expected[key],
                actual[key],
                "{}.{}".format(path, key),
            )
        return
    if isinstance(expected, list):
        if not isinstance(actual, list):
            raise FixtureValidationError(
                "{} expected array, found {}".format(path, type(actual).__name__)
            )
        if len(expected) != len(actual):
            raise FixtureValidationError(
                "{} length expected {}, found {}".format(
                    path, len(expected), len(actual)
                )
            )
        for index, (wanted, found) in enumerate(zip(expected, actual)):
            compare_json(
                wanted,
                found,
                "{}[{}]".format(path, index),
            )
        return
    if isinstance(expected, int) and not isinstance(expected, bool):
        if (
            not isinstance(actual, int)
            or isinstance(actual, bool)
            or actual != expected
        ):
            raise FixtureValidationError(
                "{} integer value expected {!r}, found {!r}".format(
                    path, expected, actual
                )
            )
        return
    if isinstance(expected, float):
        if (
            not isinstance(actual, (int, float))
            or isinstance(actual, bool)
            or not math.isfinite(float(actual))
        ):
            raise FixtureValidationError(
                "{} finite numeric value expected {!r}, found {!r}".format(
                    path, expected, actual
                )
            )
        if ieee754_double_hex(expected) != ieee754_double_hex(actual):
            raise FixtureValidationError(
                "{} IEEE-754 value expected {!r} ({}), found {!r} ({})".format(
                    path,
                    expected,
                    ieee754_double_hex(expected),
                    actual,
                    ieee754_double_hex(actual),
                )
            )
        return
    if type(expected) is not type(actual) or expected != actual:
        raise FixtureValidationError(
            "{} expected {!r}, found {!r}".format(path, expected, actual)
        )


def validate_output_invariants(
    outputs: Mapping[str, Mapping[str, Any]]
) -> None:
    deterministic_ids: Dict[str, str] = {}
    source_keys: Dict[str, str] = {}
    cases = {case.key: case for case in fixture_cases()}
    for fixture_name, output in outputs.items():
        if output.get("fixture") != fixture_name:
            raise FixtureValidationError(
                "{} output fixture field is {!r}".format(
                    fixture_name, output.get("fixture")
                )
            )
        if output.get("output_kind") == "blocked_preflight":
            expectations = output.get("migration_expectations")
            if (
                not isinstance(expectations, Mapping)
                or expectations.get("status") != "blocked"
                or expectations.get("source_rows_read") != 0
                or expectations.get("target_write_attempted") is not False
                or expectations.get("target_rows_written") != 0
                or expectations.get("receipt_write_attempted") is not False
                or expectations.get("receipt_written") is not False
            ):
                raise FixtureValidationError(
                    "{} blocked migration expectations are unsafe".format(
                        fixture_name
                    )
                )
            preflight = output["diagnostics"]["preflight"]
            structure_status = preflight["file_structure"]["status"]
            if structure_status == "truncated":
                detected_reason = "truncated_sqlite"
            elif structure_status != "complete":
                detected_reason = "corrupt_sqlite"
            elif preflight["integrity_check"]["status"] == "failed":
                detected_reason = "corrupt_sqlite"
            else:
                detected_reason = preflight["schema_check"]["state"]
            if expectations["reason"] != detected_reason:
                raise FixtureValidationError(
                    "{} blocked reason does not match preflight".format(
                        fixture_name
                    )
                )
            continue
        if output.get("output_kind") != "canonical":
            raise FixtureValidationError(
                "{} has unknown output kind {!r}".format(
                    fixture_name,
                    output.get("output_kind"),
                )
            )
        sessions = output["sessions"]
        track_points = output["track_points"]
        orphans = output["orphan_track_points"]
        rejected = output["rejected_rows"]
        summary = output["summary"]
        diagnostics = output["diagnostics"]
        schema_diagnostic = diagnostics["schema"]
        calendar_diagnostic = diagnostics["calendar"]

        present_business_tables = schema_diagnostic["business_tables_present"]
        missing_business_tables = schema_diagnostic["missing_business_tables"]
        if sorted(present_business_tables + missing_business_tables) != sorted(
            BUSINESS_TABLES
        ):
            raise FixtureValidationError(
                "{} schema diagnostic does not partition business tables".format(
                    fixture_name
                )
            )
        expected_schema_state = (
            "malformed_schema"
            if schema_diagnostic["schema_errors"]
            else (
                "complete"
                if not missing_business_tables
                else (
                    "no_business_tables"
                    if not present_business_tables
                    else "partial_business_schema"
                )
            )
        )
        if schema_diagnostic["state"] != expected_schema_state:
            raise FixtureValidationError(
                "{} schema-state diagnostic mismatch".format(fixture_name)
            )
        expected_readiness = (
            "ready" if expected_schema_state == "complete" else "blocked"
        )
        if schema_diagnostic["migration_readiness"] != expected_readiness:
            raise FixtureValidationError(
                "{} migration-readiness diagnostic mismatch".format(fixture_name)
            )
        if schema_diagnostic["android_metadata"] != {
            "state": "valid",
            "table_present": True,
            "row_count": 1,
            "valid_locale_rows": 1,
            "storage_types": ["text"],
            "schema_errors": [],
        }:
            raise FixtureValidationError(
                "{} must report exactly one valid TEXT android_metadata locale "
                "row".format(fixture_name)
            )
        if schema_diagnostic["platform_tables_present"] != ["android_metadata"]:
            raise FixtureValidationError(
                "{} must report verified android_metadata".format(fixture_name)
            )
        if "validation_path" in schema_diagnostic:
            raise FixtureValidationError(
                "{} committed oracle must not encode a runtime-specific "
                "schema path".format(fixture_name)
            )
        if schema_diagnostic["unexpected_tables"]:
            raise FixtureValidationError(
                "{} reports unexpected source tables".format(fixture_name)
            )
        if schema_diagnostic["unexpected_schema_objects"]:
            raise FixtureValidationError(
                "{} reports unexpected schema objects".format(fixture_name)
            )
        if (
            calendar_diagnostic["state"] != "android_gregorian"
            or calendar_diagnostic["migration_readiness"] != "ready"
            or calendar_diagnostic["source_platform"]
            != "Android java.text.SimpleDateFormat"
            or calendar_diagnostic["calendar"] != CALENDAR_GREGORIAN
            or calendar_diagnostic["calendar_class"]
            != ANDROID_FORMATTER_CALENDAR_CLASS
            or calendar_diagnostic["platform_invariant"] is not True
            or calendar_diagnostic["current_android_locale"]
            != cases[fixture_name].android_locale
            or calendar_diagnostic["current_locale_used_as_row_evidence"] is not False
        ):
            raise FixtureValidationError(
                "{} calendar evidence diagnostic is unsafe".format(fixture_name)
            )
        expected_data_state = (
            "empty"
            if (
                summary["source_activity_rows"] == 0
                and summary["source_track_point_rows"] == 0
            )
            else "contains_business_rows"
        )
        if diagnostics["data_state"] != expected_data_state:
            raise FixtureValidationError(
                "{} data-state diagnostic mismatch".format(fixture_name)
            )

        if summary["sessions"] != len(sessions):
            raise FixtureValidationError(
                "{} session summary mismatch".format(fixture_name)
            )
        if summary["track_points"] != len(track_points):
            raise FixtureValidationError(
                "{} point summary mismatch".format(fixture_name)
            )
        if summary["orphan_track_points"] != len(orphans):
            raise FixtureValidationError(
                "{} orphan summary mismatch".format(fixture_name)
            )
        if summary["partial_sessions"] != sum(
            1 for session in sessions if session["partial"]
        ):
            raise FixtureValidationError(
                "{} partial-session summary mismatch".format(fixture_name)
            )
        rejected_activities = sum(
            1 for row in rejected if row["table"] == "ACTIVITY"
        )
        rejected_points = sum(
            1 for row in rejected if row["table"] == "GPS_POINTS"
        )
        if summary["rejected_activity_rows"] != rejected_activities:
            raise FixtureValidationError(
                "{} rejected activity summary mismatch".format(fixture_name)
            )
        if summary["rejected_track_point_rows"] != rejected_points:
            raise FixtureValidationError(
                "{} rejected point summary mismatch".format(fixture_name)
            )
        if (
            len(sessions) + rejected_activities
            != summary["source_activity_rows"]
        ):
            raise FixtureValidationError(
                "{} activity accounting mismatch".format(fixture_name)
            )
        if (
            len(track_points) + len(orphans) + rejected_points
            != summary["source_track_point_rows"]
        ):
            raise FixtureValidationError(
                "{} point accounting mismatch".format(fixture_name)
            )

        session_ids = {session["deterministic_id"] for session in sessions}
        for kind, records in (
            ("session", sessions),
            ("track_point", track_points),
            ("orphan_track_point", orphans),
        ):
            for record in records:
                record_id = record["deterministic_id"]
                try:
                    parsed = uuid.UUID(record_id)
                except (ValueError, AttributeError, TypeError) as error:
                    raise FixtureValidationError(
                        "{} {} has invalid UUID {!r}".format(
                            fixture_name, kind, record_id
                        )
                    ) from error
                if str(parsed) != record_id:
                    raise FixtureValidationError(
                        "{} {} UUID is not canonical: {}".format(
                            fixture_name, kind, record_id
                        )
                    )
                previous = deterministic_ids.get(record_id)
                label = "{}:{}".format(fixture_name, record["source_key"])
                if previous is not None:
                    raise FixtureValidationError(
                        "Duplicate deterministic ID {} for {} and {}".format(
                            record_id, previous, label
                        )
                    )
                deterministic_ids[record_id] = label

                source = record["source_key"]
                previous_id = source_keys.get(source)
                if previous_id is not None and previous_id != record_id:
                    raise FixtureValidationError(
                        "Source key {} maps to multiple deterministic IDs".format(
                            source
                        )
                    )
                source_keys[source] = record_id

                evidence = CalendarEvidence(
                    calendar=record["timestamp_calendar"],
                    locale_tag=record["timestamp_locale"],
                    source=record["calendar_evidence_source"],
                )
                if kind == "session":
                    parsed_start = parse_evidenced_legacy_timestamp(
                        record["gmt_start"],
                        evidence,
                    )
                    parsed_end = (
                        parse_evidenced_legacy_timestamp(
                            record["gmt_end"],
                            evidence,
                        )
                        if record["gmt_end"] is not None
                        else None
                    )
                    if (
                        parsed_start is None
                        or record["gmt_start_utc"]
                        != canonical_utc_timestamp(parsed_start)
                        or record["gmt_end_utc"]
                        != (
                            canonical_utc_timestamp(parsed_end)
                            if parsed_end is not None
                            else None
                        )
                    ):
                        raise FixtureValidationError(
                            "{} session {} calendar interpretation drifted".format(
                                fixture_name,
                                record["source_key"],
                            )
                        )
                else:
                    parsed_timestamp = parse_evidenced_legacy_timestamp(
                        record["gmt_timestamp"],
                        evidence,
                    )
                    if (
                        parsed_timestamp is None
                        or record["gmt_timestamp_utc"]
                        != canonical_utc_timestamp(parsed_timestamp)
                    ):
                        raise FixtureValidationError(
                            "{} {} {} calendar interpretation drifted".format(
                                fixture_name,
                                kind,
                                record["source_key"],
                            )
                        )

        ordering = diagnostics["ordering"]
        if (
            ordering["activity_rows_ordered_by_64_bit_id"] is not True
            or ordering["track_point_rows_ordered_by_64_bit_id"] is not True
        ):
            raise FixtureValidationError(
                "{} source rows are not in required 64-bit ID order".format(
                    fixture_name
                )
            )
        expected_internal_objects = sorted(
            "{}:{}".format(object_type, name)
            for object_type, name, _ in expected_sqlite_internal_objects(
                present_business_tables
            )
        )
        if (
            schema_diagnostic["sqlite_internal_schema_objects"]
            != expected_internal_objects
            or schema_diagnostic[
                "unexpected_sqlite_internal_schema_objects"
            ]
        ):
            raise FixtureValidationError(
                "{} reports unexpected SQLite internal objects".format(
                    fixture_name
                )
            )

        for label, records in (
            ("sessions", sessions),
            ("track_points", track_points),
            ("orphan_track_points", orphans),
        ):
            legacy_ids = [record["legacy_id"] for record in records]
            if legacy_ids != sorted(legacy_ids) or len(legacy_ids) != len(
                set(legacy_ids)
            ):
                raise FixtureValidationError(
                    "{} {} are not strictly ordered by legacy ID".format(
                        fixture_name,
                        label,
                    )
                )

        for point_row in track_points:
            if point_row["session_id"] not in session_ids:
                raise FixtureValidationError(
                    "{} point {} references missing session {}".format(
                        fixture_name,
                        point_row["source_key"],
                        point_row["session_id"],
                    )
                )
        for orphan in orphans:
            if orphan["session_id"] is not None:
                raise FixtureValidationError(
                    "{} orphan {} was attached to a session".format(
                        fixture_name, orphan["source_key"]
                    )
                )
            if orphan.get("reason") != "missing_activity":
                raise FixtureValidationError(
                    "{} orphan {} lacks missing_activity reason".format(
                        fixture_name, orphan["source_key"]
                    )
                )

        migration_expectations = output["migration_expectations"]
        if schema_diagnostic["migration_readiness"] == "ready":
            if migration_expectations is not None:
                raise FixtureValidationError(
                    "{} ready schema unexpectedly blocks migration".format(
                        fixture_name
                    )
                )
        else:
            if (
                migration_expectations
                != blocked_migration_expectations(schema_diagnostic["state"])
            ):
                raise FixtureValidationError(
                    "{} blocked schema lacks no-write/no-receipt expectations".format(
                        fixture_name
                    )
                )

        idempotency = output["idempotency"]
        if idempotency is not None:
            expected_checksum = idempotency["expected_state_logical_checksum"]
            scenarios = idempotency["scenarios"]
            for scenario_name in (
                "interrupt_after_session_row",
                "interrupt_after_partial_point_prefix",
            ):
                scenario = scenarios[scenario_name]
                first_attempt = scenario["first_attempt"]
                replay = scenario["replay"]
                if (
                    scenario["canonical_record_count"]
                    != idempotency["canonical_insert_rows"]
                    or scenario["rerun_record_count"]
                    != idempotency["canonical_insert_rows"]
                    or scenario["rerun_records_exactly_canonical"] is not True
                    or scenario["replay_attempts_cover_canonical_set"] is not True
                    or first_attempt["attempted_rows"]
                    != scenario["interruption_after_attempts"]
                    or first_attempt["inserted_rows"]
                    != scenario["interruption_after_attempts"]
                    or first_attempt["duplicate_attempts"] != 0
                    or replay["attempted_rows"]
                    != idempotency["canonical_insert_rows"]
                    or replay["attempted_sessions"]
                    != idempotency["expected_sessions"]
                    or replay["attempted_track_points"]
                    != idempotency["expected_track_points"]
                    or replay["duplicate_attempts"]
                    != scenario["interruption_after_attempts"]
                    or replay["duplicate_session_attempts"]
                    != first_attempt["inserted_sessions"]
                    or replay["duplicate_track_point_attempts"]
                    != first_attempt["inserted_track_points"]
                    or replay["inserted_rows"]
                    != idempotency["canonical_insert_rows"]
                    - scenario["interruption_after_attempts"]
                    or replay["inserted_sessions"]
                    != idempotency["expected_sessions"]
                    - first_attempt["inserted_sessions"]
                    or replay["inserted_track_points"]
                    != idempotency["expected_track_points"]
                    - first_attempt["inserted_track_points"]
                ):
                    raise FixtureValidationError(
                        "{} {} replay does not cover the full canonical record "
                        "set".format(fixture_name, scenario_name)
                    )
                for phase_name in ("first_attempt", "replay"):
                    phase = scenario[phase_name]
                    if (
                        phase["attempted_rows"]
                        != phase["inserted_rows"] + phase["duplicate_attempts"]
                        or not phase["attempts_fully_accounted"]
                        or phase["duplicate_rows"] != 0
                    ):
                        raise FixtureValidationError(
                            "{} {} {} insert attempts are not accounted for".format(
                                fixture_name, scenario_name, phase_name
                            )
                        )
                computed_duplicate_attempts = (
                    scenario["first_attempt"]["duplicate_attempts"]
                    + scenario["replay"]["duplicate_attempts"]
                )
                if (
                    scenario["duplicate_attempts_prevented"]
                    != computed_duplicate_attempts
                    or scenario["duplicate_rows"] != 0
                ):
                    raise FixtureValidationError(
                        "{} {} duplicate accounting mismatch".format(
                            fixture_name, scenario_name
                        )
                    )
                if (
                    not scenario["exact_final_equality"]
                    or scenario["state_logical_checksum"] != expected_checksum
                    or scenario["first_attempt"]["receipt_present"] is not False
                    or scenario["replay"]["receipt_present"] is not True
                    or scenario["replay"]["receipt_completed"] is not True
                ):
                    raise FixtureValidationError(
                        "{} {} final state mismatch".format(
                            fixture_name, scenario_name
                        )
                    )
            same_run = scenarios["same_run_duplicates"]
            if (
                same_run["attempted_rows"]
                != same_run["inserted_rows"] + same_run["duplicate_attempts"]
                or not same_run["attempts_fully_accounted"]
                or same_run["duplicate_rows"] != 0
                or not same_run["exact_final_equality"]
                or same_run["state_logical_checksum"] != expected_checksum
                or same_run["receipt_present"] is not True
                or same_run["receipt_completed"] is not True
            ):
                raise FixtureValidationError(
                    "{} same-run duplicate accounting mismatch".format(fixture_name)
                )
            receipt_gap = scenarios["receipt_gap_replay"]
            committed_rows = receipt_gap["committed_rows"]
            replay = receipt_gap["replay"]
            if (
                committed_rows["inserted_rows"] != idempotency["canonical_insert_rows"]
                or committed_rows["receipt_present"] is not False
                or committed_rows["migration_complete"] is not False
                or replay["inserted_rows"] != 0
                or replay["duplicate_attempts"]
                != idempotency["canonical_insert_rows"]
                or replay["attempted_rows"]
                != replay["duplicate_attempts"]
                or replay["receipt_present"] is not True
                or replay["receipt_completed"] is not True
                or replay["migration_complete"] is not True
                or receipt_gap["duplicate_attempts_prevented"]
                != idempotency["canonical_insert_rows"]
                or receipt_gap["exact_state_preserved"] is not True
                or receipt_gap["state_logical_checksum"] != expected_checksum
            ):
                raise FixtureValidationError(
                    "{} receipt-gap replay is incomplete or lossy".format(
                        fixture_name
                    )
                )


def validate_candidate_outputs(
    expected_outputs: Mapping[str, Mapping[str, Any]],
    candidate_outputs: Mapping[str, Mapping[str, Any]],
) -> None:
    if set(expected_outputs) != set(candidate_outputs):
        raise FixtureValidationError(
            "Candidate output cases mismatch; expected {}, found {}".format(
                sorted(expected_outputs), sorted(candidate_outputs)
            )
        )
    validate_output_invariants(candidate_outputs)
    for name in sorted(expected_outputs):
        compare_json(
            expected_outputs[name],
            candidate_outputs[name],
            path="$.{}".format(name),
        )


def load_outputs(directory: Path) -> Mapping[str, Mapping[str, Any]]:
    outputs: Dict[str, Mapping[str, Any]] = {}
    for case in fixture_cases():
        path = directory / "{}.json".format(case.key)
        if not path.is_file():
            raise FixtureValidationError("Missing output file {}".format(path))
        outputs[case.key] = load_json(path)
    return outputs


def run_mutation_detection_tests(
    expected_outputs: Mapping[str, Mapping[str, Any]]
) -> DetectorReport:
    passed: List[str] = []

    def rejected(name: str, candidate: Mapping[str, Mapping[str, Any]]) -> None:
        try:
            validate_candidate_outputs(expected_outputs, candidate)
        except FixtureValidationError:
            passed.append(name)
            return
        raise FixtureValidationError(
            "Validator self-test did not detect {}".format(name)
        )

    candidate = copy.deepcopy(expected_outputs)
    precision_point = candidate["precision"]["track_points"][0]
    precision_point["longitude"] = int(precision_point["longitude"])
    rejected("integer_truncation", candidate)

    candidate = copy.deepcopy(expected_outputs)
    precision_session = next(
        session
        for session in candidate["precision"]["sessions"]
        if session["legacy_id"] == 9007199254740993
    )
    precision_session["time"] = 1.0
    rejected("float_precision_round_trip", candidate)

    candidate = copy.deepcopy(expected_outputs)
    precision_session = next(
        session
        for session in candidate["precision"]["sessions"]
        if session["legacy_id"] == 9007199254740993
    )
    precision_session["legacy_id"] = 9007199254740992
    rejected("integer_precision_above_2_53", candidate)

    candidate = copy.deepcopy(expected_outputs)
    precision_point = candidate["precision"]["track_points"][0]
    precision_point["latitude"], precision_point["longitude"] = (
        precision_point["longitude"],
        precision_point["latitude"],
    )
    rejected("swapped_columns", candidate)

    candidate = copy.deepcopy(expected_outputs)
    del candidate["precision"]["track_points"][0]["speed"]
    rejected("missing_columns", candidate)

    candidate = copy.deepcopy(expected_outputs)
    candidate["representative"]["track_points"][0][
        "gmt_timestamp"
    ] = "20240102030406"
    rejected("timestamp_drift", candidate)

    candidate = copy.deepcopy(expected_outputs)
    localized = candidate["localized_timestamps"]
    localized["sessions"][0]["gmt_start"] = "20240708091011"
    localized["sessions"][0]["gmt_end"] = "20240708091013"
    localized["track_points"][0]["gmt_timestamp"] = "20240708091011"
    localized["track_points"][1]["gmt_timestamp"] = "20240708091012"
    rejected("localized_timestamp_text_normalized", candidate)

    candidate = copy.deepcopy(expected_outputs)
    candidate["timestamp_ordering"]["track_points"].sort(
        key=lambda row: (row["gmt_timestamp"], row["legacy_id"])
    )
    rejected("timestamp_ordering", candidate)

    candidate = copy.deepcopy(expected_outputs)
    timestamp_output = candidate["timestamp_ordering"]
    duplicate_timestamp = timestamp_output["track_points"].pop()
    if duplicate_timestamp["gmt_timestamp"] != "20240801000002":
        raise FixtureValidationError("Timestamp fixture lost its duplicate control")
    timestamp_output["summary"]["source_track_point_rows"] -= 1
    timestamp_output["summary"]["track_points"] -= 1
    timestamp_output["diagnostics"]["ordering"][
        "duplicate_track_point_timestamp_rows"
    ] = 0
    rejected("duplicate_timestamp_collapsed", candidate)

    candidate = copy.deepcopy(expected_outputs)
    points = candidate["representative"]["track_points"]
    points[1]["deterministic_id"] = points[0]["deterministic_id"]
    rejected("duplicate_deterministic_ids", candidate)

    candidate = copy.deepcopy(expected_outputs)
    orphan = candidate["orphan"]["orphan_track_points"].pop()
    orphan["session_id"] = candidate["orphan"]["sessions"][0]["deterministic_id"]
    orphan.pop("reason")
    candidate["orphan"]["track_points"].append(orphan)
    rejected("orphan_mishandling", candidate)

    for fixture_name, detector_name in (
        ("start_only_zero_points", "start_only_activity_dropped"),
        ("active_wal_snapshot", "wal_sidecars_ignored"),
    ):
        candidate = copy.deepcopy(expected_outputs)
        output = candidate[fixture_name]
        output["sessions"] = []
        output["track_points"] = []
        output["orphan_track_points"] = []
        output["rejected_rows"] = []
        output["summary"].update(
            {
                "source_activity_rows": 0,
                "source_track_point_rows": 0,
                "sessions": 0,
                "partial_sessions": 0,
                "track_points": 0,
                "orphan_track_points": 0,
                "rejected_activity_rows": 0,
                "rejected_track_point_rows": 0,
            }
        )
        output["diagnostics"]["data_state"] = "empty"
        output["diagnostics"]["ordering"].update(
            {
                "duplicate_track_point_timestamp_rows": 0,
                "non_monotonic_track_point_timestamp_transitions": 0,
            }
        )
        rejected(detector_name, candidate)

    for fixture_name, detector_name in (
        ("malformed_schema", "malformed_schema_target_write"),
        ("truncated", "truncated_sqlite_target_write"),
        ("corrupt", "corrupt_sqlite_receipt_write"),
    ):
        candidate = copy.deepcopy(expected_outputs)
        expectations = candidate[fixture_name]["migration_expectations"]
        if fixture_name == "corrupt":
            expectations["receipt_write_attempted"] = True
            expectations["receipt_written"] = True
        else:
            expectations["target_write_attempted"] = True
            expectations["target_rows_written"] = 1
        rejected(detector_name, candidate)

    interrupted = expected_outputs["interrupted_idempotency"]
    bad_rerun = copy.deepcopy(interrupted)
    replacement_id = "00000000-0000-5000-8000-000000000001"
    old_id = bad_rerun["sessions"][0]["deterministic_id"]
    bad_rerun["sessions"][0]["deterministic_id"] = replacement_id
    for track_point in bad_rerun["track_points"]:
        if track_point["session_id"] == old_id:
            track_point["session_id"] = replacement_id
    try:
        simulate_interrupted_insert_attempts(
            interrupted,
            interruption_after_attempts=1,
            interruption_point="after_session_row",
            rerun_output=bad_rerun,
        )
    except FixtureValidationError:
        passed.append("partial_rerun_identity_drift")
    else:
        raise FixtureValidationError(
            "Validator self-test did not detect partial_rerun_identity_drift"
        )

    bad_rerun = copy.deepcopy(interrupted)
    bad_rerun["track_points"].pop()
    try:
        simulate_interrupted_insert_attempts(
            interrupted,
            interruption_after_attempts=1,
            interruption_point="after_session_row",
            rerun_output=bad_rerun,
        )
    except FixtureValidationError:
        passed.append("partial_rerun_missing_row")
    else:
        raise FixtureValidationError(
            "Validator self-test did not detect partial_rerun_missing_row"
        )

    bad_rerun = copy.deepcopy(interrupted)
    committed_prefix_row = bad_rerun["track_points"].pop(0)
    if committed_prefix_row["legacy_id"] != 1:
        raise FixtureValidationError(
            "Idempotency fixture lost its committed-prefix point control"
        )
    try:
        simulate_interrupted_insert_attempts(
            interrupted,
            interruption_after_attempts=2,
            interruption_point="after_partial_point_prefix",
            rerun_output=bad_rerun,
        )
    except FixtureValidationError:
        passed.append("partial_rerun_committed_prefix_row_missing")
    else:
        raise FixtureValidationError(
            "Validator self-test did not detect "
            "partial_rerun_committed_prefix_row_missing"
        )

    candidate = copy.deepcopy(expected_outputs)
    receipt_gap_replay = candidate["interrupted_idempotency"]["idempotency"][
        "scenarios"
    ]["receipt_gap_replay"]["replay"]
    receipt_gap_replay["migration_complete"] = False
    receipt_gap_replay["receipt_present"] = False
    receipt_gap_replay["receipt_completed"] = False
    rejected("receipt_gap_not_completed", candidate)

    return DetectorReport(tuple(passed))


def run_storage_detection_tests(
    root: Path,
    cases: Mapping[str, FixtureCase],
    expected_outputs: Mapping[str, Mapping[str, Any]],
) -> DetectorReport:
    passed: List[str] = []
    capability_connection = sqlite3.connect(":memory:")
    try:
        detector_capabilities = sqlite_detector_capabilities(
            capability_connection
        )
    finally:
        capability_connection.close()
    not_applicable = optional_detector_not_applicable(detector_capabilities)
    active_case = cases["active_wal_snapshot"]
    active_database = root / "fixtures" / "active_wal_snapshot.db"
    work_dir = root / "generated" / ".storage-detectors"
    if work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True)
    try:
        localized_case = cases["localized_timestamps"]
        localized_database = root / "fixtures" / "localized_timestamps.db"
        with open_readonly(localized_database) as connection:
            localized_rows = read_all_rows(connection)
            localized_schema = schema_diagnostics(connection)

        def parse_ascii_only(
            value: Any,
            evidence: CalendarEvidence,
        ) -> Optional[datetime]:
            del evidence
            if (
                not isinstance(value, str)
                or len(value) != 14
                or any(character < "0" or character > "9" for character in value)
            ):
                return None
            return parse_strict_gregorian_legacy_timestamp(value)

        def parse_mixed_decimal_digits(
            value: Any,
            evidence: CalendarEvidence,
        ) -> Optional[datetime]:
            del evidence
            if not isinstance(value, str) or len(value) != 14:
                return None
            normalized: List[str] = []
            zero_code_points = set()
            for character in value:
                if unicodedata.category(character) != "Nd":
                    return None
                try:
                    decimal = unicodedata.decimal(character)
                except ValueError:
                    return None
                zero_code_points.add(ord(character) - decimal)
                normalized.append(chr(ord("0") + decimal))
            if not zero_code_points <= formatter_source_emittable_zeroes():
                return None
            return parse_strict_gregorian_legacy_timestamp("".join(normalized))

        def parse_any_single_nd_block(
            value: Any,
            evidence: CalendarEvidence,
        ) -> Optional[datetime]:
            del evidence
            if not isinstance(value, str) or len(value) != 14:
                return None
            normalized: List[str] = []
            zero_code_point: Optional[int] = None
            for character in value:
                if unicodedata.category(character) != "Nd":
                    return None
                try:
                    decimal = unicodedata.decimal(character)
                except ValueError:
                    return None
                character_zero = ord(character) - decimal
                if zero_code_point is None:
                    zero_code_point = character_zero
                elif character_zero != zero_code_point:
                    return None
                normalized.append(str(decimal))
            return parse_strict_gregorian_legacy_timestamp(
                "".join(normalized)
            )

        def parse_limited_digit_blocks(
            value: Any,
            evidence: CalendarEvidence,
            allowed_zero_code_points: Sequence[int],
        ) -> Optional[datetime]:
            del evidence
            components = legacy_timestamp_digit_components(
                value,
                allowed_zero_code_points,
                formatter_source_control_layouts(),
            )
            if components is None:
                return None
            normalized, _, _ = components
            return parse_strict_gregorian_legacy_timestamp(normalized)

        def parse_ascii_arabic_only(
            value: Any,
            evidence: CalendarEvidence,
        ) -> Optional[datetime]:
            return parse_limited_digit_blocks(
                value,
                evidence,
                (ord("0"), ord("٠")),
            )

        def parse_current_metadata_block(
            value: Any,
            evidence: CalendarEvidence,
        ) -> Optional[datetime]:
            if localized_case.android_locale != AR_EG_ANDROID_METADATA_LOCALE:
                raise FixtureValidationError(
                    "Metadata-coupled detector requires the ar_EG control"
                )
            return parse_limited_digit_blocks(
                value,
                evidence,
                (ord("٠"),),
            )

        def parse_after_stripping_format_controls(
            value: Any,
            evidence: CalendarEvidence,
        ) -> Optional[datetime]:
            if not isinstance(value, str):
                return None
            stripped = "".join(
                character
                for character in value
                if unicodedata.category(character) != "Cf"
            )
            return parse_evidenced_legacy_timestamp(stripped, evidence)

        for detector_name, timestamp_parser in (
            ("localized_timestamp_ascii_only_parser", parse_ascii_only),
            (
                "localized_timestamp_ascii_arabic_only_parser",
                parse_ascii_arabic_only,
            ),
            (
                "localized_timestamp_metadata_coupled_parser",
                parse_current_metadata_block,
            ),
            ("mixed_numbering_system_accepted", parse_mixed_decimal_digits),
            (
                "unsupported_android_digit_block_accepted",
                parse_any_single_nd_block,
            ),
            (
                "unicode_format_controls_stripped",
                parse_after_stripping_format_controls,
            ),
        ):
            candidate = copy.deepcopy(expected_outputs)
            candidate["localized_timestamps"] = build_canonical_output(
                localized_case,
                localized_rows,
                localized_schema,
                timestamp_parser,
            )
            try:
                validate_candidate_outputs(expected_outputs, candidate)
            except FixtureValidationError:
                passed.append(detector_name)
            else:
                raise FixtureValidationError(
                    "Validator self-test did not detect {}".format(detector_name)
                )

        formatter_case = cases["formatter_digit_blocks"]
        formatter_database = root / "fixtures" / "formatter_digit_blocks.db"
        with open_readonly(formatter_database) as connection:
            formatter_rows = read_all_rows(connection)
            formatter_schema = schema_diagnostics(connection)
        formatter_zeroes = {
            row["zero_code_point"] for row in formatter_candidate_digit_blocks()
        }

        def require_formatter_candidate_rejection(
            detector_name: str,
            timestamp_parser: TimestampParser,
        ) -> None:
            candidate = copy.deepcopy(expected_outputs)
            candidate["formatter_digit_blocks"] = build_canonical_output(
                formatter_case,
                formatter_rows,
                formatter_schema,
                timestamp_parser,
            )
            try:
                validate_candidate_outputs(expected_outputs, candidate)
            except FixtureValidationError:
                passed.append(detector_name)
            else:
                raise FixtureValidationError(
                    "Validator self-test did not detect {}".format(
                        detector_name
                    )
                )

        require_formatter_candidate_rejection(
            "hardcoded_ten_digit_block_oracle",
            lambda value, evidence: parse_limited_digit_blocks(
                value,
                evidence,
                tuple(ANDROID_FORMATTER_LEGACY_TEN_ZEROES),
            ),
        )
        require_formatter_candidate_rejection(
            "fullwidth_digit_block_omitted",
            lambda value, evidence: parse_limited_digit_blocks(
                value,
                evidence,
                tuple(formatter_zeroes - {0xFF10}),
            ),
        )

        supplementary_definitions = {
            row["definition_zero_code_point"]
            for platform in formatter_probe_matrix()["platforms"]
            for row in platform["candidate_definitions"]
            if row["definition_zero_code_point"] is not None
            and row["definition_zero_code_point"] > 0xFFFF
        }
        if not supplementary_definitions:
            raise FixtureValidationError(
                "Formatter candidate evidence lacks supplementary digit definitions"
            )
        supplementary_zero = 0x1E5F1
        if supplementary_zero not in supplementary_definitions:
            raise FixtureValidationError(
                "Formatter evidence lacks the pinned Unicode 16 digit control"
            )
        supplementary_timestamp = localized_formatter_digits(
            "20240708091011",
            supplementary_zero,
        )
        supplementary_components = legacy_timestamp_digit_components(
            supplementary_timestamp,
            (supplementary_zero,),
            {supplementary_zero: frozenset({""})},
        )
        if (
            supplementary_components is None
            or supplementary_components[0] != "20240708091011"
            or len(supplementary_timestamp) != 14
            or len(supplementary_timestamp.encode("utf-16-le")) // 2 != 28
        ):
            raise FixtureValidationError(
                "Code-point timestamp parser cannot retain supplementary digits"
            )
        utf16_character_zeroes = {
            ord(row["description"][0])
            for platform in formatter_probe_matrix()["platforms"]
            for row in platform["candidate_definitions"]
            if row["definition_zero_code_point"] is not None
            and len(row["description"].encode("utf-16-le")) // 2 == 10
        }
        if supplementary_definitions & utf16_character_zeroes:
            raise FixtureValidationError(
                "UTF-16 character detector unexpectedly retained supplementary blocks"
            )
        passed.append("utf16_character_only_candidate_parser")

        bmp_only_definition_zeroes = {
            row["definition_zero_code_point"]
            for platform in formatter_probe_matrix()["platforms"]
            for row in platform["candidate_definitions"]
            if row["definition_zero_code_point"] is not None
            and row["definition_zero_code_point"] <= 0xFFFF
        }
        if (
            formatter_candidate_definition_zeroes()
            - bmp_only_definition_zeroes
            != supplementary_definitions
        ):
            raise FixtureValidationError(
                "BMP-only candidate omission control did not isolate supplementary blocks"
            )
        passed.append("supplementary_numbering_candidate_omitted")

        for omitted_zero in sorted(formatter_zeroes):
            allowed_zeroes = formatter_zeroes - {omitted_zero}

            def parse_all_matrix_blocks_except_one(
                value: Any,
                evidence: CalendarEvidence,
                allowed: set[int] = allowed_zeroes,
            ) -> Optional[datetime]:
                zero_code_point = timestamp_digit_zero_code_point(value)
                if zero_code_point not in allowed:
                    return None
                return parse_evidenced_legacy_timestamp(value, evidence)

            candidate = copy.deepcopy(expected_outputs)
            candidate["formatter_digit_blocks"] = build_canonical_output(
                formatter_case,
                formatter_rows,
                formatter_schema,
                parse_all_matrix_blocks_except_one,
            )
            try:
                validate_candidate_outputs(expected_outputs, candidate)
            except FixtureValidationError:
                continue
            raise FixtureValidationError(
                "Validator self-test accepted a parser omitting U+{:04X}".format(
                    omitted_zero
                )
            )
        passed.append("source_emittable_digit_block_omitted")

        calendar_case = cases["android_thai_gregorian"]
        calendar_database = root / "fixtures" / "android_thai_gregorian.db"
        with open_readonly(calendar_database) as connection:
            calendar_rows = read_all_rows(connection)
            calendar_schema = schema_diagnostics(connection)

        def parse_with_year_transform(
            value: Any,
            evidence: CalendarEvidence,
            transform: Callable[[int, CalendarEvidence], int],
        ) -> Optional[datetime]:
            normalized = normalized_legacy_timestamp_digits(
                value,
                require_single_numbering_system=True,
            )
            if normalized is None:
                return None
            zero_code_point = timestamp_digit_zero_code_point(value)
            if zero_code_point not in formatter_source_emittable_zeroes():
                return None
            source_year = int(normalized[0:4])
            guessed_year = transform(source_year, evidence)
            try:
                return datetime(
                    guessed_year,
                    int(normalized[4:6]),
                    int(normalized[6:8]),
                    int(normalized[8:10]),
                    int(normalized[10:12]),
                    int(normalized[12:14]),
                )
            except ValueError:
                return None

        def parse_with_desktop_buddhist_assumption(
            value: Any,
            evidence: CalendarEvidence,
        ) -> Optional[datetime]:
            return parse_with_year_transform(
                value,
                evidence,
                lambda year, context: (
                    year - 543
                    if context.locale_tag.startswith("th-TH")
                    else year
                ),
            )

        def parse_year_magnitude_with_fixed_offset(
            value: Any,
            evidence: CalendarEvidence,
        ) -> Optional[datetime]:
            return parse_with_year_transform(
                value,
                evidence,
                lambda year, context: year - 543 if year >= 2400 else year,
            )

        def parse_with_single_year_lookup(
            value: Any,
            evidence: CalendarEvidence,
        ) -> Optional[datetime]:
            return parse_with_year_transform(
                value,
                evidence,
                lambda year, context: 2024 if year == 2567 else year,
            )

        for detector_name, timestamp_parser in (
            (
                "android_timestamp_desktop_buddhist_calendar_assumption",
                parse_with_desktop_buddhist_assumption,
            ),
            (
                "android_timestamp_magic_543_year_offset",
                parse_year_magnitude_with_fixed_offset,
            ),
            (
                "android_timestamp_single_year_lookup",
                parse_with_single_year_lookup,
            ),
        ):
            candidate = copy.deepcopy(expected_outputs)
            candidate["android_thai_gregorian"] = build_canonical_output(
                calendar_case,
                calendar_rows,
                calendar_schema,
                timestamp_parser,
            )
            try:
                validate_candidate_outputs(expected_outputs, candidate)
            except FixtureValidationError:
                passed.append(detector_name)
            else:
                raise FixtureValidationError(
                    "Validator self-test did not detect {}".format(detector_name)
                )

        crlf_json = work_dir / "localized-timestamps-crlf.json"
        committed_json = root / "expected" / "localized_timestamps.json"
        crlf_json.write_bytes(
            committed_json.read_bytes().replace(b"\n", b"\r\n")
        )
        try:
            compare_exact_artifact_bytes(
                "json_crlf_drift",
                committed_json,
                crlf_json,
            )
        except FixtureValidationError:
            passed.append("json_crlf_drift")
        else:
            raise FixtureValidationError(
                "Validator self-test did not detect json_crlf_drift"
            )

        missing_shm = work_dir / "missing-shm.db"
        shutil.copyfile(active_database, missing_shm)
        shutil.copyfile(
            Path(str(active_database) + "-wal"),
            Path(str(missing_shm) + "-wal"),
        )
        try:
            active_wal_snapshot_diagnostics(missing_shm, active_case)
        except (FixtureValidationError, sqlite3.DatabaseError):
            passed.append("wal_shm_omitted")
        else:
            raise FixtureValidationError(
                "Validator self-test did not detect wal_shm_omitted"
            )

        torn = work_dir / "torn.db"
        for source, destination in zip(
            fixture_artifact_paths(active_database, active_case),
            (
                torn,
                Path(str(torn) + "-wal"),
                Path(str(torn) + "-shm"),
            ),
        ):
            shutil.copyfile(source, destination)
        torn_wal = Path(str(torn) + "-wal")
        wal = torn_wal.read_bytes()
        encoded_page_size = struct.unpack(">I", wal[8:12])[0]
        page_size = 65536 if encoded_page_size == 0 else encoded_page_size
        torn_wal.write_bytes(wal[: -(24 + page_size)])
        try:
            active_wal_snapshot_diagnostics(torn, active_case)
        except (FixtureValidationError, sqlite3.DatabaseError):
            passed.append("wal_inconsistent_snapshot")
        else:
            raise FixtureValidationError(
                "Validator self-test did not detect wal_inconsistent_snapshot"
            )

        for fixture_name, detector_name in (
            ("malformed_schema", "malformed_schema_preflight"),
            ("truncated", "truncated_sqlite_preflight"),
            ("corrupt", "corrupt_sqlite_preflight"),
        ):
            case = cases[fixture_name]
            actual = build_blocked_preflight_output(
                case,
                root / "fixtures" / "{}.db".format(fixture_name),
            )
            compare_json(
                expected_outputs[fixture_name],
                actual,
                "$.storage.{}".format(fixture_name),
            )
            passed.append(detector_name)

        metadata_source = root / "fixtures" / "empty.db"
        metadata_mutations = (
            (
                "android_metadata_missing",
                ("DROP TABLE android_metadata",),
                "missing_table",
                "android_metadata:missing_table",
            ),
            (
                "android_metadata_empty",
                ("DELETE FROM android_metadata",),
                "empty",
                "android_metadata:row_count",
            ),
            (
                "android_metadata_wrong_type",
                (
                    "DELETE FROM android_metadata",
                    "INSERT INTO android_metadata (locale) "
                    "VALUES (X'656e5f5553')",
                ),
                "invalid_locale_type",
                "android_metadata:locale_storage_type",
            ),
            (
                "android_metadata_invalid_value",
                (
                    "DELETE FROM android_metadata",
                    "INSERT INTO android_metadata (locale) "
                    "VALUES ('not a locale')",
                ),
                "invalid_locale_value",
                "android_metadata:locale_value",
            ),
            (
                "android_metadata_multiple_rows",
                (
                    "INSERT INTO android_metadata (locale) VALUES ('fr_CA')",
                ),
                "multiple_rows",
                "android_metadata:row_count",
            ),
        )
        for detector_name, statements, expected_state, expected_error in (
            metadata_mutations
        ):
            mutated_database = work_dir / "{}.db".format(detector_name)
            shutil.copyfile(metadata_source, mutated_database)
            connection = sqlite3.connect(str(mutated_database))
            try:
                with connection:
                    for statement in statements:
                        connection.execute(statement)
            finally:
                connection.close()
            with open_readonly(mutated_database) as connection:
                diagnostics = schema_diagnostics(connection)
                if (
                    diagnostics["state"] != "malformed_schema"
                    or diagnostics["migration_readiness"] != "blocked"
                    or diagnostics["android_metadata"]["state"]
                    != expected_state
                    or expected_error not in diagnostics["schema_errors"]
                ):
                    raise FixtureValidationError(
                        "Validator self-test did not diagnose {}".format(
                            detector_name
                        )
                    )
                try:
                    validate_schema(
                        connection,
                        detector_name,
                    )
                except FixtureValidationError:
                    passed.append(detector_name)
                else:
                    raise FixtureValidationError(
                        "Validator self-test did not reject {}".format(
                            detector_name
                        )
                    )
            blocked = build_blocked_preflight_output(
                cases["malformed_schema"],
                mutated_database,
            )
            if (
                blocked["diagnostics"]["preflight"]["schema_check"][
                    "android_metadata"
                ]["state"]
                != expected_state
                or blocked["migration_expectations"]
                != blocked_migration_expectations("malformed_schema")
            ):
                raise FixtureValidationError(
                    "{} did not block before source or target side effects".format(
                        detector_name
                    )
                )

        corrupt_case = cases["corrupt"]
        for encoded_page_size in (0, 2, 511, 513, 32767, 32769, 65535):
            invalid_page_size = work_dir / "invalid-page-size-{}.db".format(
                encoded_page_size
            )
            shutil.copyfile(metadata_source, invalid_page_size)
            database_bytes = bytearray(invalid_page_size.read_bytes())
            database_bytes[16:18] = encoded_page_size.to_bytes(2, "big")
            invalid_page_size.write_bytes(database_bytes)
            output = build_blocked_preflight_output(
                corrupt_case,
                invalid_page_size,
            )
            expectations = output["migration_expectations"]
            if (
                output["diagnostics"]["preflight"]["file_structure"]["status"]
                != "invalid_page_size"
                or expectations != blocked_migration_expectations(
                    "corrupt_sqlite"
                )
            ):
                raise FixtureValidationError(
                    "Validator self-test did not safely block page-size "
                    "encoding {}".format(encoded_page_size)
                )
        passed.append("invalid_sqlite_page_size_preflight")

        representative_case = cases["representative"]
        representative_database = root / "fixtures" / "representative.db"
        for field_name, offset in (
            ("write", 18),
            ("read", 19),
        ):
            for invalid_version in (0, 255):
                detector_name = "sqlite_{}_version_{}".format(
                    field_name,
                    invalid_version,
                )
                invalid_format = work_dir / "{}.db".format(detector_name)
                shutil.copyfile(representative_database, invalid_format)
                database_bytes = bytearray(invalid_format.read_bytes())
                database_bytes[offset] = invalid_version
                invalid_format.write_bytes(database_bytes)
                output = build_blocked_preflight_output(
                    corrupt_case,
                    invalid_format,
                )
                structure = output["diagnostics"]["preflight"]["file_structure"]
                if (
                    structure["status"] != "invalid_format_version"
                    or structure["header"]["status"] != "invalid_format_version"
                    or output["migration_expectations"]
                    != blocked_migration_expectations("corrupt_sqlite")
                ):
                    raise FixtureValidationError(
                        "Validator self-test did not reject {}".format(
                            detector_name
                        )
                    )
                try:
                    canonical_sqlite_artifact_bytes(invalid_format)
                except FixtureValidationError:
                    passed.append(detector_name)
                else:
                    raise FixtureValidationError(
                        "Canonicalization accepted {}".format(detector_name)
                    )

        mismatched_counter = work_dir / "sqlite-change-counter-mismatch.db"
        shutil.copyfile(representative_database, mismatched_counter)
        database_bytes = bytearray(mismatched_counter.read_bytes())
        change_counter = int.from_bytes(database_bytes[24:28], "big")
        database_bytes[92:96] = (
            (change_counter + 1) & 0xFFFFFFFF
        ).to_bytes(4, "big")
        mismatched_counter.write_bytes(database_bytes)
        output = build_blocked_preflight_output(
            corrupt_case,
            mismatched_counter,
        )
        structure = output["diagnostics"]["preflight"]["file_structure"]
        if (
            structure["status"] != "inconsistent_change_counter"
            or structure["header"]["change_counter_matches_version_valid_for"]
            is not False
            or output["migration_expectations"]
            != blocked_migration_expectations("corrupt_sqlite")
        ):
            raise FixtureValidationError(
                "Validator self-test did not reject sqlite_change_counter_mismatch"
            )
        try:
            canonical_sqlite_artifact_bytes(mismatched_counter)
        except FixtureValidationError:
            passed.append("sqlite_change_counter_mismatch")
        else:
            raise FixtureValidationError(
                "Canonicalization accepted sqlite_change_counter_mismatch"
            )

        active_structure = sqlite_file_structure_diagnostics(active_database)
        active_header = active_structure["header"]
        if (
            active_structure["status"] != "complete"
            or (
                active_header["write_version"],
                active_header["read_version"],
            )
            != SQLITE_WAL_FORMAT_VERSIONS
            or active_header["change_counter_matches_version_valid_for"] is not True
        ):
            raise FixtureValidationError(
                "Active WAL main header does not model documented counter semantics"
            )

        writer_header_variant = work_dir / "writer-header-variant.db"
        shutil.copyfile(representative_database, writer_header_variant)
        database_bytes = bytearray(writer_header_variant.read_bytes())
        database_bytes[96:100] = (3049000).to_bytes(4, "big")
        writer_header_variant.write_bytes(database_bytes)
        compare_standard_database_artifacts(
            "writer header variation",
            representative_database,
            writer_header_variant,
            representative_case,
        )

        def create_schema_detector_database(
            database: Path,
            activity_sql: str,
            metadata_sql: str = "CREATE TABLE android_metadata (locale TEXT)",
            gps_sql: str = CREATE_GPS_POINTS_SQL,
        ) -> None:
            remove_database_artifacts(database)
            connection = sqlite3.connect(str(database))
            try:
                connection.execute(
                    "PRAGMA page_size = {}".format(FIXED_SQLITE_PAGE_SIZE)
                )
                with connection:
                    connection.execute(metadata_sql)
                    connection.execute(
                        "INSERT INTO android_metadata (locale) VALUES ('en_US')"
                    )
                    connection.execute(activity_sql)
                    connection.execute(gps_sql)
                    connection.execute("PRAGMA user_version = 0")
                connection.execute("VACUUM")
            finally:
                connection.close()

        formatting_only = work_dir / "schema-formatting-only.db"
        create_schema_detector_database(
            formatting_only,
            (
                'create table "ACTIVITY" (\n'
                '  "ID" integer primary key autoincrement not null,\n'
                '  "GMTSTART" varchar, "GMTEND" varchar,\n'
                '  "NAME" varchar, "DESCRIPTION" varchar,\n'
                '  "DISTANCE" real, "TIME" real, "PACE" real\n'
                ")"
            ),
            metadata_sql='create table "android_metadata" ("locale" text)',
            gps_sql=(
                'create table "GPS_POINTS" ('
                '"ID" integer primary key autoincrement not null, '
                '"ACTIVITYID" integer, "GMTTIMESTAMP" varchar, '
                '"LATITUDE" real, "LONGITUDE" real, "ALTITUDE" real, '
                '"ACCURACY" real, "SPEED" real, "BEARING" real, '
                '"HEARTRATE" real)'
            ),
        )
        with open_readonly(formatting_only) as connection:
            validate_schema_all_paths(
                connection,
                "schema formatting-only equivalence",
            )

        def require_schema_detector(
            detector_name: str,
            database: Path,
            required_errors: Mapping[str, str],
        ) -> None:
            with open_readonly(database) as connection:
                decisions = []
                for schema_path in supported_schema_paths(connection):
                    diagnostics = schema_diagnostics(
                        connection,
                        schema_path,
                    )
                    required_error = required_errors[schema_path]
                    if (
                        diagnostics["state"] != "malformed_schema"
                        or diagnostics["migration_readiness"] != "blocked"
                        or required_error not in diagnostics["schema_errors"]
                    ):
                        raise FixtureValidationError(
                            "{} [{}] did not report {}".format(
                                detector_name,
                                schema_path,
                                required_error,
                            )
                        )
                    decisions.append(schema_decision_payload(diagnostics))
                    try:
                        validate_schema(
                            connection,
                            detector_name,
                            schema_path=schema_path,
                        )
                    except FixtureValidationError:
                        pass
                    else:
                        raise FixtureValidationError(
                            "{} [{}] escaped exact schema validation".format(
                                detector_name,
                                schema_path,
                            )
                        )
                if any(decision != decisions[0] for decision in decisions[1:]):
                    raise FixtureValidationError(
                        "{} schema paths made different decisions".format(
                            detector_name
                        )
                    )
            blocked = build_blocked_preflight_output(
                cases["malformed_schema"],
                database,
            )
            if (
                blocked["migration_expectations"]
                != blocked_migration_expectations("malformed_schema")
            ):
                raise FixtureValidationError(
                    "{} did not fail closed".format(detector_name)
                )
            passed.append(detector_name)

        sqlite_x_mutations = (
            (
                "sqliteX_user_table_not_hidden",
                "CREATE TABLE sqliteX_user_table (value TEXT)",
                "table:sqliteX_user_table",
            ),
            (
                "sqliteX_user_index_not_hidden",
                "CREATE INDEX sqliteX_user_index ON ACTIVITY(NAME)",
                "index:sqliteX_user_index",
            ),
            (
                "sqliteX_user_trigger_not_hidden",
                (
                    "CREATE TRIGGER sqliteX_user_trigger AFTER INSERT ON ACTIVITY "
                    "BEGIN SELECT 1; END"
                ),
                "trigger:sqliteX_user_trigger",
            ),
            (
                "sqliteX_user_view_not_hidden",
                (
                    "CREATE VIEW sqliteX_user_view AS "
                    "SELECT ID, GMTSTART FROM ACTIVITY"
                ),
                "view:sqliteX_user_view",
            ),
        )
        for detector_name, statement, expected_object in sqlite_x_mutations:
            database = work_dir / "{}.db".format(detector_name)
            shutil.copyfile(metadata_source, database)
            connection = sqlite3.connect(str(database))
            try:
                with connection:
                    connection.execute(statement)
            finally:
                connection.close()
            with open_readonly(database) as connection:
                decisions = []
                for schema_path in supported_schema_paths(connection):
                    diagnostics = schema_diagnostics(connection, schema_path)
                    if (
                        diagnostics["state"] != "malformed_schema"
                        or expected_object
                        not in diagnostics["unexpected_schema_objects"]
                    ):
                        raise FixtureValidationError(
                            "{} [{}] hid {}".format(
                                detector_name,
                                schema_path,
                                expected_object,
                            )
                        )
                    decisions.append(schema_decision_payload(diagnostics))
                    try:
                        validate_schema(
                            connection,
                            detector_name,
                            schema_path=schema_path,
                        )
                    except FixtureValidationError:
                        pass
                    else:
                        raise FixtureValidationError(
                            "{} [{}] escaped exact schema validation".format(
                                detector_name,
                                schema_path,
                            )
                        )
                if any(decision != decisions[0] for decision in decisions[1:]):
                    raise FixtureValidationError(
                        "{} schema paths made different decisions".format(
                            detector_name
                        )
                    )
            blocked = build_blocked_preflight_output(
                cases["malformed_schema"],
                database,
            )
            if blocked["migration_expectations"] != blocked_migration_expectations(
                "malformed_schema"
            ):
                raise FixtureValidationError(
                    "{} did not fail closed".format(detector_name)
                )
            passed.append(detector_name)

        if detector_capabilities.generated_columns:
            generated_column = work_dir / "generated-column.db"
            create_schema_detector_database(
                generated_column,
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR, "
                    "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL, "
                    "HIDDEN_COPY TEXT GENERATED ALWAYS AS (GMTSTART) VIRTUAL)"
                ),
            )
            with open_readonly(generated_column) as connection:
                if table_info(connection, "ACTIVITY") != tuple(
                    row[:6] for row in EXPECTED_TABLE_XINFO["ACTIVITY"]
                ):
                    raise FixtureValidationError(
                        "Generated-column detector no longer demonstrates "
                        "table_info loss"
                    )
            require_schema_detector(
                GENERATED_COLUMN_DETECTOR,
                generated_column,
                {
                    SCHEMA_PATH_MODERN: "ACTIVITY:table_xinfo",
                    SCHEMA_PATH_SQLITE_SCHEMA_ALIAS: "ACTIVITY:table_xinfo",
                    SCHEMA_PATH_ANDROID_API_26: "ACTIVITY:sqlite_master_sql",
                },
            )

        constraint_substitution = work_dir / "constraint-substitution.db"
        create_schema_detector_database(
            constraint_substitution,
            (
                "CREATE TABLE ACTIVITY "
                "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                "GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR, "
                "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL, "
                "CHECK (DISTANCE IS NULL OR DISTANCE >= 0))"
            ),
        )
        with open_readonly(constraint_substitution) as connection:
            if table_info(
                connection,
                "ACTIVITY",
            ) != EXPECTED_TABLE_INFO["ACTIVITY"]:
                raise FixtureValidationError(
                    "Constraint detector must differ only in schema SQL"
                )
            if (
                table_xinfo_supported(connection)
                and table_xinfo(connection, "ACTIVITY")
                != EXPECTED_TABLE_XINFO["ACTIVITY"]
            ):
                raise FixtureValidationError(
                    "Constraint detector changed table_xinfo metadata"
                )
        require_schema_detector(
            "sqlite_schema_constraint_substitution",
            constraint_substitution,
            {
                SCHEMA_PATH_MODERN: "ACTIVITY:sqlite_master_sql",
                SCHEMA_PATH_SQLITE_SCHEMA_ALIAS: "ACTIVITY:sqlite_schema_sql",
                SCHEMA_PATH_ANDROID_API_26: "ACTIVITY:sqlite_master_sql",
            },
        )

        for module, detector_name, supported in (
            (
                "fts4",
                FTS4_VIRTUAL_TABLE_DETECTOR,
                detector_capabilities.fts4,
            ),
            (
                "fts5",
                FTS5_VIRTUAL_TABLE_DETECTOR,
                detector_capabilities.fts5,
            ),
        ):
            if not supported:
                continue
            virtual_substitution = work_dir / "{}-virtual-substitution.db".format(
                module
            )
            create_schema_detector_database(
                virtual_substitution,
                (
                    "CREATE VIRTUAL TABLE ACTIVITY USING {}("
                    "ID, GMTSTART, GMTEND, NAME, DESCRIPTION, DISTANCE, TIME, "
                    "PACE)"
                ).format(module),
            )
            require_schema_detector(
                detector_name,
                virtual_substitution,
                {
                    SCHEMA_PATH_MODERN: "ACTIVITY:sqlite_schema_table_kind",
                    SCHEMA_PATH_SQLITE_SCHEMA_ALIAS: (
                        "ACTIVITY:sqlite_schema_table_kind"
                    ),
                    SCHEMA_PATH_ANDROID_API_26: (
                        "ACTIVITY:sqlite_schema_table_kind"
                    ),
                },
            )

        logical_drift = work_dir / "standard-logical-drift.db"
        shutil.copyfile(representative_database, logical_drift)
        connection = sqlite3.connect(str(logical_drift))
        try:
            with connection:
                connection.execute(
                    "UPDATE ACTIVITY SET NAME = ? WHERE ID = 1",
                    ("Changed logical value",),
                )
        finally:
            connection.close()
        try:
            compare_standard_database_artifacts(
                "logical drift",
                representative_database,
                logical_drift,
                representative_case,
            )
        except FixtureValidationError:
            passed.append("standard_database_logical_drift")
        else:
            raise FixtureValidationError(
                "Validator self-test did not detect standard_database_logical_drift"
            )
    finally:
        if work_dir.exists():
            shutil.rmtree(work_dir)
    return DetectorReport(tuple(passed), not_applicable)


def verify_legacy_schema_path(
    root: Path = TOOL_ROOT,
) -> Mapping[str, Any]:
    root = root.resolve()
    canonical_database = root / "fixtures" / "empty.db"
    malformed_database = root / "fixtures" / "malformed_schema.db"
    checked = 0
    capability_profiles_checked = 0
    executable_profile_names: Optional[Tuple[str, ...]] = None
    capability_connection = sqlite3.connect(":memory:")
    try:
        detector_capabilities = sqlite_detector_capabilities(
            capability_connection
        )
    finally:
        capability_connection.close()
    not_applicable_detectors = optional_detector_not_applicable(
        detector_capabilities
    )

    def verify_capability_profiles(
        database: Path,
        label: str,
        accepted: bool,
    ) -> None:
        nonlocal capability_profiles_checked, executable_profile_names
        with open_readonly(database) as connection:
            host_capabilities = schema_capabilities(connection)
            profiles = executable_schema_profiles(host_capabilities)
            profile_names = tuple(profile.name for profile in profiles)
            if executable_profile_names is None:
                executable_profile_names = profile_names
            elif executable_profile_names != profile_names:
                raise FixtureValidationError(
                    "Fixture connections exposed different schema capabilities"
                )

            for profile in profiles:
                expected_capabilities = profile.capabilities
                expected_path = preferred_schema_path_for_capabilities(
                    expected_capabilities
                )
                expected_paths = schema_paths_for_capabilities(
                    expected_capabilities
                )
                guard = SchemaSqlGuard(
                    connection,
                    forbidden_tokens=profile.forbidden_tokens,
                )
                actual_capabilities = schema_capabilities(guard)
                if actual_capabilities != expected_capabilities:
                    raise FixtureValidationError(
                        "{} {} capability probe mismatch: expected {}, "
                        "found {}".format(
                            label,
                            profile.name,
                            expected_capabilities,
                            actual_capabilities,
                        )
                    )
                actual_paths = supported_schema_paths(guard)
                if actual_paths != expected_paths:
                    raise FixtureValidationError(
                        "{} {} supported paths mismatch: expected {}, "
                        "found {}".format(
                            label,
                            profile.name,
                            expected_paths,
                            actual_paths,
                        )
                    )
                if resolve_schema_path(guard) != expected_path:
                    raise FixtureValidationError(
                        "{} {} selected the wrong schema path".format(
                            label,
                            profile.name,
                        )
                    )

                guard.statements.clear()
                outcome = schema_path_outcome(
                    guard,
                    "{} {}".format(label, profile.name),
                )
                if (
                    outcome["exact_schema_valid"] is not accepted
                    or outcome["decision"]["accepted"] is not accepted
                ):
                    raise FixtureValidationError(
                        "{} {} made the wrong schema decision".format(
                            label,
                            profile.name,
                        )
                    )
                selected_sql = "\n".join(guard.statements).casefold()
                if "sqlite_master" not in selected_sql:
                    raise FixtureValidationError(
                        "{} {} did not use the preferred sqlite_master "
                        "catalog".format(label, profile.name)
                    )
                if "sqlite_schema" in selected_sql:
                    raise FixtureValidationError(
                        "{} {} used sqlite_schema for automatic "
                        "validation".format(label, profile.name)
                    )
                expected_pragma = (
                    "table_xinfo"
                    if expected_path == SCHEMA_PATH_MODERN
                    else "table_info"
                )
                if expected_pragma not in selected_sql:
                    raise FixtureValidationError(
                        "{} {} did not use {}".format(
                            label,
                            profile.name,
                            expected_pragma,
                        )
                    )

                outcomes = [
                    schema_path_outcome(
                        guard,
                        "{} {} [{}]".format(
                            label,
                            profile.name,
                            schema_path,
                        ),
                        schema_path=schema_path,
                    )
                    for schema_path in actual_paths
                ]
                if any(candidate != outcomes[0] for candidate in outcomes[1:]):
                    raise FixtureValidationError(
                        "{} {} supported paths made different decisions".format(
                            label,
                            profile.name,
                        )
                    )
                capability_profiles_checked += 1

    with open_readonly(canonical_database) as connection:
        outcome = require_equivalent_schema_path_outcomes(
            connection,
            "canonical committed schema",
        )
        if not outcome["exact_schema_valid"] or not outcome["decision"]["accepted"]:
            raise FixtureValidationError(
                "Canonical schema was not accepted by all supported paths"
            )
        legacy_guard = SchemaSqlGuard(
            connection,
            forbidden_tokens=("table_xinfo", "sqlite_schema"),
        )
        legacy_outcome = schema_path_outcome(
            legacy_guard,
            "canonical API-26 guard",
            schema_path=SCHEMA_PATH_ANDROID_API_26,
        )
        if legacy_outcome != outcome:
            raise FixtureValidationError(
                "Guarded API-26 path changed the canonical schema decision"
            )
        legacy_sql = "\n".join(legacy_guard.statements).casefold()
        if "table_info" not in legacy_sql or "sqlite_master" not in legacy_sql:
            raise FixtureValidationError(
                "API-26 schema path did not use table_info plus sqlite_master"
            )

        if table_xinfo_supported(connection):
            modern_guard = SchemaSqlGuard(
                connection,
                forbidden_tokens=("sqlite_schema",),
            )
            modern_outcome = schema_path_outcome(
                modern_guard,
                "canonical modern guard",
                schema_path=SCHEMA_PATH_MODERN,
            )
            modern_sql = "\n".join(modern_guard.statements).casefold()
            if (
                modern_outcome != outcome
                or "table_xinfo" not in modern_sql
                or "sqlite_master" not in modern_sql
            ):
                raise FixtureValidationError(
                    "Modern schema path did not use table_xinfo plus "
                    "sqlite_master"
                )
        if sqlite_schema_alias_supported(connection):
            alias_guard = SchemaSqlGuard(connection)
            alias_outcome = schema_path_outcome(
                alias_guard,
                "canonical sqlite_schema alias guard",
                schema_path=SCHEMA_PATH_SQLITE_SCHEMA_ALIAS,
            )
            alias_sql = "\n".join(alias_guard.statements).casefold()
            if (
                alias_outcome != outcome
                or "table_xinfo" not in alias_sql
                or "sqlite_schema" not in alias_sql
            ):
                raise FixtureValidationError(
                    "Explicit sqlite_schema alias path did not use "
                    "table_xinfo plus sqlite_schema"
                )
        checked += 1

    with open_readonly(malformed_database) as connection:
        outcome = require_equivalent_schema_path_outcomes(
            connection,
            "committed malformed schema",
        )
        if outcome["exact_schema_valid"] or outcome["decision"]["accepted"]:
            raise FixtureValidationError(
                "Committed malformed schema was accepted by a validation path"
            )
        checked += 1

    verify_capability_profiles(
        canonical_database,
        "canonical committed schema",
        True,
    )
    verify_capability_profiles(
        malformed_database,
        "committed malformed schema",
        False,
    )

    variants: List[Tuple[str, str, Tuple[str, ...]]] = []
    if detector_capabilities.generated_columns:
        variants.append(
            (
                "generated column",
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR, "
                    "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL, "
                    "HIDDEN_COPY TEXT GENERATED ALWAYS AS (GMTSTART) VIRTUAL)"
                ),
                (),
            )
        )
    variants.extend(
        (
            (
                "extra check constraint",
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR, "
                    "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL, "
                    "CHECK (DISTANCE IS NULL OR DISTANCE >= 0))"
                ),
                (),
            ),
            (
                "extra default",
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR DEFAULT '', "
                    "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL)"
                ),
                (),
            ),
            (
                "reordered columns",
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, DESCRIPTION VARCHAR, "
                    "NAME VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL)"
                ),
                (),
            ),
            (
                "changed declared type",
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, NAME TEXT, "
                    "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL)"
                ),
                (),
            ),
            (
                "extra collation",
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR COLLATE NOCASE, "
                    "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL)"
                ),
                (),
            ),
        )
    )
    for module, supported in (
        ("fts4", detector_capabilities.fts4),
        ("fts5", detector_capabilities.fts5),
    ):
        if not supported:
            continue
        variants.append(
            (
                "{} virtual shadow substitution".format(module.upper()),
                (
                    "CREATE VIRTUAL TABLE ACTIVITY USING {}("
                    "ID, GMTSTART, GMTEND, NAME, DESCRIPTION, DISTANCE, TIME, "
                    "PACE)"
                ).format(module),
                (),
            )
        )
    variants.append(
        (
            "sqliteX user objects",
            CREATE_ACTIVITY_SQL,
            (
                "CREATE TABLE sqliteX_user_table (value TEXT)",
                "CREATE INDEX sqliteX_user_index ON ACTIVITY(NAME)",
                (
                    "CREATE TRIGGER sqliteX_user_trigger AFTER INSERT ON ACTIVITY "
                    "BEGIN SELECT 1; END"
                ),
                (
                    "CREATE VIEW sqliteX_user_view AS "
                    "SELECT ID, GMTSTART FROM ACTIVITY"
                ),
            ),
        )
    )
    for label, activity_sql, extra_sql in variants:
        connection = sqlite3.connect(":memory:")
        try:
            connection.execute("CREATE TABLE android_metadata (locale TEXT)")
            connection.execute(
                "INSERT INTO android_metadata (locale) VALUES ('en_US')"
            )
            connection.execute(activity_sql)
            connection.execute(CREATE_GPS_POINTS_SQL)
            for statement in extra_sql:
                connection.execute(statement)
            connection.execute("PRAGMA user_version = 0")
            outcome = require_equivalent_schema_path_outcomes(
                connection,
                label,
            )
            if outcome["exact_schema_valid"] or outcome["decision"]["accepted"]:
                raise FixtureValidationError(
                    "{} was accepted by a schema path".format(label)
                )
            legacy_guard = SchemaSqlGuard(
                connection,
                forbidden_tokens=("table_xinfo", "sqlite_schema"),
            )
            legacy_outcome = schema_path_outcome(
                legacy_guard,
                "{} API-26 guard".format(label),
                schema_path=SCHEMA_PATH_ANDROID_API_26,
            )
            if legacy_outcome != outcome:
                raise FixtureValidationError(
                    "{} changed under the guarded API-26 path".format(label)
                )
            checked += 1
        finally:
            connection.close()
    return {
        "schemas_checked": checked,
        "capability_profiles_checked": capability_profiles_checked,
        "theoretical_profiles_checked": len(theoretical_sqlite_profiles()),
        "executable_profiles": list(executable_profile_names or ()),
        "runtime_capabilities": {
            "sqlite_version": list(detector_capabilities.sqlite_version),
            "generated_columns": detector_capabilities.generated_columns,
            "fts4": detector_capabilities.fts4,
            "fts5": detector_capabilities.fts5,
        },
        "not_applicable_detectors": [
            {"name": name, "reason": reason}
            for name, reason in not_applicable_detectors
        ],
        "modern_path": SCHEMA_PATH_MODERN,
        "sqlite_schema_alias_path": SCHEMA_PATH_SQLITE_SCHEMA_ALIAS,
        "legacy_path": SCHEMA_PATH_ANDROID_API_26,
    }


def verify_manifest_header(manifest: Mapping[str, Any]) -> None:
    if manifest.get("format_version") != FORMAT_VERSION:
        raise FixtureValidationError("Unsupported manifest format version")
    if manifest.get("synthetic_data_only") is not True:
        raise FixtureValidationError("Manifest must identify the corpus as synthetic")
    if manifest.get("database_name") != DATABASE_NAME:
        raise FixtureValidationError("Manifest database name mismatch")
    if manifest.get("fixture_namespace_uuid") != str(FIXTURE_NAMESPACE):
        raise FixtureValidationError("Manifest fixture namespace mismatch")
    if manifest.get("determinism") != {
        "json_encoding": "UTF-8",
        "json_newline": "LF",
        "sqlite_host_header_ignored_byte_ranges": [
            list(byte_range) for byte_range in SQLITE_HOST_HEADER_RANGES
        ],
    }:
        raise FixtureValidationError("Manifest determinism contract mismatch")
    if manifest.get("calendar_oracle") != {
        "source_platform": "Android java.text.SimpleDateFormat",
        "source_calendar": CALENDAR_GREGORIAN,
        "calendar_class": ANDROID_FORMATTER_CALENDAR_CLASS,
        "current_android_metadata_is_row_evidence": False,
        "durable_per_activity_evidence_required": False,
        "locale_calendar_extension_changes_calendar": False,
        "year_interpretation": "strict_proleptic_gregorian_without_offset",
    }:
        raise FixtureValidationError("Manifest calendar oracle mismatch")
    if manifest.get("formatter_oracle") != formatter_oracle_manifest():
        raise FixtureValidationError("Manifest formatter oracle mismatch")
    source_schema = manifest.get("source_schema", {})
    expected_schema = {
        "android_metadata_ddl": CREATE_ANDROID_METADATA_SQL,
        "android_metadata_business_data": False,
        "fixture_android_locales": sorted(
            {case.android_locale for case in fixture_cases()}
        ),
        "activity_ddl": CREATE_ACTIVITY_SQL,
        "gps_points_ddl": CREATE_GPS_POINTS_SQL,
        "user_version": 0,
        "foreign_keys_declared": False,
        "schema_validation_paths": list(SCHEMA_PATHS),
        "android_api_26_sqlite_version": "3.18.2",
        "table_xinfo_supported_from": "3.26.0",
        "sqlite_schema_alias_supported_from": "3.33.0",
        "schema_capabilities_probed_independently": True,
        "sqlite_catalog_preferred": "sqlite_master",
    }
    if source_schema != expected_schema:
        raise FixtureValidationError("Manifest source schema mismatch")


def verify_corpus(
    root: Path = TOOL_ROOT,
    candidate_dir: Optional[Path] = None,
    run_mutations: bool = True,
) -> Mapping[str, Any]:
    root = root.resolve()
    verify_android_formatter_evidence(root)
    manifest_path = root / "manifest.json"
    if not manifest_path.is_file():
        raise FixtureValidationError("Missing manifest {}".format(manifest_path))
    manifest = load_json(manifest_path)
    verify_manifest_header(manifest)
    manifest_entries = {
        entry["name"]: entry for entry in manifest.get("fixtures", [])
    }
    cases = {case.key: case for case in fixture_cases()}
    if set(manifest_entries) != set(cases):
        raise FixtureValidationError(
            "Manifest cases mismatch; expected {}, found {}".format(
                sorted(cases), sorted(manifest_entries)
            )
        )
    expected_fixture_files = {
        artifact["path"]
        for entry in manifest_entries.values()
        for artifact in entry["artifacts"]
    }
    actual_fixture_files = {
        path.relative_to(root).as_posix()
        for path in (root / "fixtures").iterdir()
        if path.is_file()
    }
    if actual_fixture_files != expected_fixture_files:
        raise FixtureValidationError(
            "Fixture artifacts mismatch; expected {}, found {}".format(
                sorted(expected_fixture_files),
                sorted(actual_fixture_files),
            )
        )
    expected_output_files = {
        entry["expected_output"] for entry in manifest_entries.values()
    }
    actual_output_files = {
        path.relative_to(root).as_posix()
        for path in (root / "expected").iterdir()
        if path.is_file()
    }
    if actual_output_files != expected_output_files:
        raise FixtureValidationError(
            "Expected-output artifacts mismatch; expected {}, found {}".format(
                sorted(expected_output_files),
                sorted(actual_output_files),
            )
        )

    expected_outputs: Dict[str, Mapping[str, Any]] = {}
    for name in sorted(cases):
        case = cases[name]
        entry = manifest_entries[name]
        if entry["database_identity"] != case.database_identity:
            raise FixtureValidationError(
                "{} database identity mismatch".format(name)
            )
        if entry.get("storage") != case.storage:
            raise FixtureValidationError("{} storage mode mismatch".format(name))
        if entry.get("android_locale") != case.android_locale:
            raise FixtureValidationError("{} Android locale mismatch".format(name))
        database = root / entry["database"]
        expected_output_path = root / entry["expected_output"]
        if not database.is_file():
            raise FixtureValidationError("Missing fixture {}".format(database))
        if not expected_output_path.is_file():
            raise FixtureValidationError(
                "Missing expected output {}".format(expected_output_path)
            )
        if case.blocked_reason is not None:
            output = build_blocked_preflight_output(case, database)
            committed_output = load_json(expected_output_path)
            compare_json(output, committed_output, "$.expected.{}".format(name))
            expected_outputs[name] = committed_output
            recomputed_entry = blocked_manifest_entry(
                root,
                case,
                database,
                expected_output_path,
                output,
            )
            compare_json(
                recomputed_entry,
                entry,
                "$.manifest.{}".format(name),
            )
            continue
        with open_readonly(database) as connection:
            schema_checksum = validate_schema_all_paths(
                connection,
                name,
                case.business_tables,
            )
            rows_by_table = read_all_rows(connection)
            compare_rows_to_case(case, rows_by_table)
            source_schema_diagnostics = schema_diagnostics(connection)
            output = build_canonical_output(
                case,
                rows_by_table,
                source_schema_diagnostics,
            )
            if case.storage == STORAGE_ACTIVE_WAL:
                output["diagnostics"]["snapshot"] = (
                    active_wal_snapshot_diagnostics(database, case)
                )
            committed_output = load_json(expected_output_path)
            compare_json(output, committed_output, "$.expected.{}".format(name))
            expected_outputs[name] = committed_output

            recomputed_entry = manifest_entry(
                root,
                case,
                database,
                expected_output_path,
                connection,
                rows_by_table,
                output,
            )
            compare_json(
                recomputed_entry,
                entry,
                "$.manifest.{}".format(name),
            )
            if (
                entry["expected"]["schema_logical_checksum"]
                != schema_checksum
            ):
                raise FixtureValidationError(
                    "{} schema checksum mismatch".format(name)
                )

    validate_output_invariants(expected_outputs)

    if candidate_dir is not None:
        candidate_outputs = load_outputs(candidate_dir.resolve())
        validate_candidate_outputs(expected_outputs, candidate_outputs)

    detector_report = DetectorReport(())
    if run_mutations:
        detector_report = merge_detector_reports(
            run_mutation_detection_tests(expected_outputs),
            run_storage_detection_tests(root, cases, expected_outputs),
        )

    return {
        "fixture_count": len(cases),
        "mutation_detectors": list(detector_report.passed),
        "not_applicable_mutation_detectors": [
            {"name": name, "reason": reason}
            for name, reason in detector_report.not_applicable
        ],
        "mutation_detector_count": (
            len(detector_report.passed)
            + len(detector_report.not_applicable)
        ),
        "candidate_dir": str(candidate_dir.resolve()) if candidate_dir else None,
    }


def corpus_artifact_paths(root: Path) -> Mapping[str, Path]:
    paths = [
        root / "manifest.json",
        root / ANDROID_FORMATTER_PROBE_SOURCE_PATH.name,
        root / ANDROID_FORMATTER_PROBE_RUNNER_PATH.name,
        *(
            root / evidence_path.name
            for evidence_path in ANDROID_FORMATTER_EVIDENCE_PATHS
        ),
    ]
    paths.extend(sorted((root / "expected").glob("*.json")))
    paths.extend(sorted(path for path in (root / "fixtures").iterdir() if path.is_file()))
    return {
        path.relative_to(root).as_posix(): path
        for path in paths
    }


def database_content_snapshot(
    connection: sqlite3.Connection,
    rows_by_table: Optional[Mapping[str, Sequence[Sequence[Any]]]] = None,
) -> Mapping[str, Any]:
    rows = rows_by_table if rows_by_table is not None else read_all_rows(connection)
    return {
        "schema": schema_payload(connection),
        "schema_diagnostics": corpus_schema_diagnostics(
            schema_diagnostics(connection)
        ),
        "platform_metadata": platform_metadata_payload(connection),
        "business_rows": {
            table: [
                [typed_value(value) for value in row]
                for row in rows[table]
            ]
            for table in BUSINESS_TABLES
        },
    }


def canonical_sqlite_artifact_bytes(database: Path) -> bytes:
    database_bytes = bytearray(database.read_bytes())
    sqlite_page_size(database_bytes)
    header = sqlite_header_diagnostics(database_bytes)
    if header["status"] != "valid":
        raise FixtureValidationError(
            "{} has invalid SQLite header semantics: {}".format(
                database,
                header["status"],
            )
        )
    for start, end in SQLITE_HOST_HEADER_RANGES:
        database_bytes[start:end] = b"\x00" * (end - start)
    return bytes(database_bytes)


def canonical_sqlite_artifact_sha256(database: Path) -> str:
    return hashlib.sha256(canonical_sqlite_artifact_bytes(database)).hexdigest()


def standard_database_logical_snapshot(
    database: Path,
    case: FixtureCase,
) -> Mapping[str, Any]:
    if case.storage != STORAGE_STANDARD:
        raise FixtureValidationError(
            "{} is not a standard logical-database fixture".format(case.key)
        )
    structure = sqlite_file_structure_diagnostics(database)
    if structure["status"] != "complete":
        raise FixtureValidationError(
            "{} has invalid file structure {}".format(
                database,
                structure["status"],
            )
        )
    require_sqlite_header_mode(
        structure,
        SQLITE_ROLLBACK_FORMAT_VERSIONS,
        str(database),
    )
    integrity = sqlite_integrity_diagnostics(database)
    if integrity["status"] != "passed":
        raise FixtureValidationError(
            "{} failed SQLite integrity validation".format(database)
        )
    with open_readonly(database) as connection:
        validate_schema_all_paths(
            connection,
            str(database),
            case.business_tables,
        )
        rows_by_table = read_all_rows(connection)
        compare_rows_to_case(case, rows_by_table)
        return database_content_snapshot(connection, rows_by_table)


def active_wal_frame_shape(wal_path: Path) -> Mapping[str, Any]:
    wal = wal_path.read_bytes()
    if len(wal) < 32:
        raise FixtureValidationError("{} has a truncated WAL header".format(wal_path))
    magic, format_version, encoded_page_size = struct.unpack(">III", wal[:12])
    if magic not in (0x377F0682, 0x377F0683):
        raise FixtureValidationError("{} has an invalid WAL magic".format(wal_path))
    page_size = 65536 if encoded_page_size == 0 else encoded_page_size
    frame_size = 24 + page_size
    if (len(wal) - 32) % frame_size != 0:
        raise FixtureValidationError("{} has a truncated WAL frame".format(wal_path))
    salt_1, salt_2 = struct.unpack(">II", wal[16:24])
    if (salt_1, salt_2) != (WAL_SALT_1, WAL_SALT_2):
        raise FixtureValidationError("{} has non-canonical WAL salts".format(wal_path))
    page_numbers: List[int] = []
    commit_page_counts: List[int] = []
    for offset in range(32, len(wal), frame_size):
        page_number, commit_pages = struct.unpack(">II", wal[offset : offset + 8])
        page_numbers.append(page_number)
        commit_page_counts.append(commit_pages)
    if not page_numbers or commit_page_counts[-1] == 0:
        raise FixtureValidationError("{} lacks a committed WAL frame".format(wal_path))
    return {
        "format_version": format_version,
        "page_size": page_size,
        "frame_count": len(page_numbers),
        "page_numbers": page_numbers,
        "commit_page_counts": commit_page_counts,
        "canonical_salts": True,
    }


def active_wal_storage_snapshot(
    database: Path,
    case: FixtureCase,
) -> Mapping[str, Any]:
    if case.storage != STORAGE_ACTIVE_WAL:
        raise FixtureValidationError("{} is not an active-WAL fixture".format(case.key))
    diagnostics = dict(active_wal_snapshot_diagnostics(database, case))
    diagnostics.pop("shm_bytes", None)
    diagnostics["shm_sidecar_present"] = True

    structure = sqlite_file_structure_diagnostics(database)
    if structure["status"] != "complete":
        raise FixtureValidationError(
            "{} active-WAL main file is not structurally complete".format(case.key)
        )
    require_sqlite_header_mode(
        structure,
        SQLITE_WAL_FORMAT_VERSIONS,
        case.key,
    )

    immutable_uri = "file:{}?mode=ro&immutable=1".format(
        database.resolve().as_posix()
    )
    main_only = sqlite3.connect(immutable_uri, uri=True)
    try:
        validate_schema_all_paths(
            main_only,
            "{} main-only".format(case.key),
        )
        main_rows = read_all_rows(main_only)
        if any(main_rows[table] for table in BUSINESS_TABLES):
            raise FixtureValidationError(
                "{} active-WAL rows leaked into the main file".format(case.key)
            )
        main_snapshot = database_content_snapshot(main_only, main_rows)
    finally:
        main_only.close()

    with open_readonly(database) as connection:
        validate_schema_all_paths(
            connection,
            "{} consistent".format(case.key),
        )
        rows_by_table = read_all_rows(connection)
        compare_rows_to_case(case, rows_by_table)
        consistent_snapshot = database_content_snapshot(connection, rows_by_table)

    shm_path = Path(str(database) + "-shm")
    if not shm_path.is_file() or shm_path.stat().st_size < 32768:
        raise FixtureValidationError(
            "{} has an invalid active-WAL shared-memory sidecar".format(case.key)
        )
    return {
        "storage": STORAGE_ACTIVE_WAL,
        "main_page_size": structure["page_size"],
        "diagnostics": diagnostics,
        "wal_shape": active_wal_frame_shape(Path(str(database) + "-wal")),
        "main_only": main_snapshot,
        "consistent_snapshot": consistent_snapshot,
    }


def malformed_schema_storage_snapshot(
    database: Path,
    case: FixtureCase,
) -> Mapping[str, Any]:
    if case.storage != STORAGE_MALFORMED_SCHEMA:
        raise FixtureValidationError(
            "{} is not a malformed-schema fixture".format(case.key)
        )
    structure = sqlite_file_structure_diagnostics(database)
    integrity = sqlite_integrity_diagnostics(database)
    if structure["status"] != "complete" or integrity["status"] != "passed":
        raise FixtureValidationError(
            "{} no longer has an intact malformed-schema shape".format(case.key)
        )
    require_sqlite_header_mode(
        structure,
        SQLITE_ROLLBACK_FORMAT_VERSIONS,
        case.key,
    )
    with open_readonly(database) as connection:
        diagnostics = schema_diagnostics(connection)
        expected_catalog_error = "ACTIVITY:{}_sql".format(
            schema_catalog(resolve_schema_path(connection))
        )
        if diagnostics["state"] != "malformed_schema" or (
            expected_catalog_error not in diagnostics["schema_errors"]
        ):
            raise FixtureValidationError(
                "{} no longer has the expected schema defect".format(case.key)
            )
        if (
            table_xinfo_supported(connection)
            and "ACTIVITY:table_xinfo" not in diagnostics["schema_errors"]
        ):
            raise FixtureValidationError(
                "{} no longer exposes its hidden-column defect through "
                "table_xinfo".format(case.key)
            )
        rows_by_table = read_all_rows(connection)
        compare_rows_to_case(case, rows_by_table)
        return {
            "storage": STORAGE_MALFORMED_SCHEMA,
            "file_structure_status": structure["status"],
            "integrity_status": integrity["status"],
            "content": database_content_snapshot(connection, rows_by_table),
        }


def truncated_storage_snapshot(
    database: Path,
    case: FixtureCase,
) -> Mapping[str, Any]:
    if case.storage != STORAGE_TRUNCATED:
        raise FixtureValidationError("{} is not a truncated fixture".format(case.key))
    structure = sqlite_file_structure_diagnostics(database)
    if structure["status"] != "truncated" or structure["page_size"] is None:
        raise FixtureValidationError(
            "{} no longer has the expected truncated shape".format(case.key)
        )
    require_sqlite_header_mode(
        structure,
        SQLITE_ROLLBACK_FORMAT_VERSIONS,
        case.key,
    )
    actual_pages, partial_bytes = divmod(
        structure["actual_bytes"],
        structure["page_size"],
    )
    missing_pages = structure["declared_pages"] - actual_pages
    if missing_pages != 1 or partial_bytes != 0:
        raise FixtureValidationError(
            "{} must be exactly one complete page short".format(case.key)
        )
    return {
        "storage": STORAGE_TRUNCATED,
        "encoded_page_size": structure["encoded_page_size"],
        "page_size": structure["page_size"],
        "declared_pages": structure["declared_pages"],
        "actual_pages": actual_pages,
        "missing_pages": missing_pages,
        "canonical_bytes_sha256": canonical_sqlite_artifact_sha256(database),
    }


def corrupt_storage_snapshot(
    database: Path,
    case: FixtureCase,
) -> Mapping[str, Any]:
    if case.storage != STORAGE_CORRUPT:
        raise FixtureValidationError("{} is not a corrupt fixture".format(case.key))
    structure = sqlite_file_structure_diagnostics(database)
    integrity = sqlite_integrity_diagnostics(database)
    if structure["status"] != "complete" or integrity["status"] != "failed":
        raise FixtureValidationError(
            "{} no longer has the expected corruption shape".format(case.key)
        )
    require_sqlite_header_mode(
        structure,
        SQLITE_ROLLBACK_FORMAT_VERSIONS,
        case.key,
    )
    with open_readonly(database) as connection:
        root_row = connection.execute(
            "SELECT rootpage, sql FROM sqlite_master "
            "WHERE type = 'table' AND name = 'GPS_POINTS'"
        ).fetchone()
        if root_row is None:
            raise FixtureValidationError(
                "{} is missing the GPS_POINTS schema record".format(case.key)
            )
        root_page = int(root_row[0])
        activity_rows = read_table_rows(connection, "ACTIVITY")
        actual_activity_rows = [
            [typed_value(value) for value in row] for row in activity_rows
        ]
        expected_activity_rows = [
            [typed_value(value) for value in row] for row in case.activities
        ]
        if actual_activity_rows != expected_activity_rows:
            raise FixtureValidationError(
                "{} readable ACTIVITY payload changed".format(case.key)
            )
        metadata = platform_metadata_payload(connection)
        schema_records = [
            {
                "name": row[0],
                "root_page": row[1],
                "sql": row[2],
            }
            for row in connection.execute(
                "SELECT name, rootpage, sql FROM sqlite_master "
                "WHERE type = 'table' ORDER BY name"
            )
        ]
        try:
            read_table_rows(connection, "GPS_POINTS")
        except sqlite3.DatabaseError:
            pass
        else:
            raise FixtureValidationError(
                "{} GPS_POINTS corruption is no longer observable".format(case.key)
            )

    database_bytes = database.read_bytes()
    page_offset = (root_page - 1) * structure["page_size"]
    if page_offset >= len(database_bytes) or database_bytes[page_offset] != 0:
        raise FixtureValidationError(
            "{} GPS_POINTS root-page damage marker changed".format(case.key)
        )
    return {
        "storage": STORAGE_CORRUPT,
        "encoded_page_size": structure["encoded_page_size"],
        "page_size": structure["page_size"],
        "declared_pages": structure["declared_pages"],
        "gps_points_root_page": root_page,
        "gps_points_root_page_type": database_bytes[page_offset],
        "integrity_status": integrity["status"],
        "schema_records": schema_records,
        "platform_metadata": metadata,
        "activity_rows": actual_activity_rows,
        "canonical_bytes_sha256": canonical_sqlite_artifact_sha256(database),
    }


def fixture_storage_snapshot(
    database: Path,
    case: FixtureCase,
) -> Mapping[str, Any]:
    if case.storage == STORAGE_STANDARD:
        return standard_database_logical_snapshot(database, case)
    if case.storage == STORAGE_ACTIVE_WAL:
        return active_wal_storage_snapshot(database, case)
    if case.storage == STORAGE_MALFORMED_SCHEMA:
        return malformed_schema_storage_snapshot(database, case)
    if case.storage == STORAGE_TRUNCATED:
        return truncated_storage_snapshot(database, case)
    if case.storage == STORAGE_CORRUPT:
        return corrupt_storage_snapshot(database, case)
    raise FixtureValidationError(
        "{} has unknown storage mode {!r}".format(case.key, case.storage)
    )


def compare_fixture_storage_artifacts(
    label: str,
    expected_database: Path,
    actual_database: Path,
    case: FixtureCase,
) -> None:
    compare_json(
        fixture_storage_snapshot(expected_database, case),
        fixture_storage_snapshot(actual_database, case),
        "$.storage_artifacts.{}".format(label),
    )


def compare_standard_database_artifacts(
    label: str,
    expected_database: Path,
    actual_database: Path,
    case: FixtureCase,
) -> None:
    if case.storage != STORAGE_STANDARD:
        raise FixtureValidationError(
            "{} is not a standard logical-database fixture".format(case.key)
        )
    compare_fixture_storage_artifacts(
        label,
        expected_database,
        actual_database,
        case,
    )


def manifest_artifact_requirements(
    manifest: Mapping[str, Any],
) -> Tuple[Mapping[str, str], Mapping[str, str]]:
    comparisons: Dict[str, str] = {}
    fixture_by_path: Dict[str, str] = {}
    for entry in manifest.get("fixtures", []):
        fixture_name = entry["name"]
        for artifact in entry.get("artifacts", []):
            path = artifact["path"]
            exact = artifact.get("exact_bytes_required")
            comparison = artifact.get("comparison")
            if exact is not False or not isinstance(comparison, str):
                raise FixtureValidationError(
                    "{} SQLite artifact {} must use a non-byte comparison".format(
                        fixture_name,
                        path,
                    )
                )
            if "bytes" in artifact or "sha256" in artifact:
                raise FixtureValidationError(
                    "{} SQLite artifact {} contains host-dependent byte metadata".format(
                        fixture_name,
                        path,
                    )
                )
            if path in comparisons:
                raise FixtureValidationError(
                    "Duplicate manifest artifact path {}".format(path)
                )
            comparisons[path] = comparison
            fixture_by_path[path] = fixture_name
    return comparisons, fixture_by_path


def compare_exact_artifact_bytes(
    label: str,
    expected_path: Path,
    actual_path: Path,
) -> None:
    if expected_path.read_bytes() != actual_path.read_bytes():
        raise FixtureValidationError(
            "Exact-byte deterministic regeneration failed for {}".format(label)
        )


def verify_deterministic_regeneration(
    root: Path = TOOL_ROOT,
) -> Mapping[str, Any]:
    root = root.resolve()
    work_dir = root / "generated" / ".deterministic-regeneration"
    first = work_dir / "first"
    second = work_dir / "second"
    if work_dir.exists():
        shutil.rmtree(work_dir)
    try:
        generate_corpus(first)
        generate_corpus(second)
        first_artifacts = corpus_artifact_paths(first)
        second_artifacts = corpus_artifact_paths(second)
        committed_artifacts = corpus_artifact_paths(root)
        if set(first_artifacts) != set(second_artifacts):
            raise FixtureValidationError(
                "Deterministic generations produced different artifact sets"
            )
        if set(first_artifacts) != set(committed_artifacts):
            raise FixtureValidationError(
                "Committed corpus artifact set differs from regeneration"
            )

        committed_manifest = load_json(root / "manifest.json")
        first_manifest = load_json(first / "manifest.json")
        second_manifest = load_json(second / "manifest.json")
        committed_requirements, fixture_by_path = manifest_artifact_requirements(
            committed_manifest
        )
        first_requirements, _ = manifest_artifact_requirements(first_manifest)
        second_requirements, _ = manifest_artifact_requirements(second_manifest)
        compare_json(
            committed_requirements,
            first_requirements,
            "$.determinism.first_artifact_requirements",
        )
        compare_json(
            committed_requirements,
            second_requirements,
            "$.determinism.second_artifact_requirements",
        )

        cases = {case.key: case for case in fixture_cases()}
        fixture_artifact_paths_set = set(committed_requirements)
        exact_artifact_paths = set(first_artifacts) - fixture_artifact_paths_set
        expected_exact_artifact_paths = {"manifest.json"} | {
            "expected/{}.json".format(case.key) for case in fixture_cases()
        }
        expected_exact_artifact_paths.update(
            {
                ANDROID_FORMATTER_PROBE_SOURCE_PATH.name,
                ANDROID_FORMATTER_PROBE_RUNNER_PATH.name,
                *(
                    evidence_path.name
                    for evidence_path in ANDROID_FORMATTER_EVIDENCE_PATHS
                ),
            }
        )
        if exact_artifact_paths != expected_exact_artifact_paths:
            raise FixtureValidationError(
                "Exact-byte text artifact set mismatch; expected {}, found {}".format(
                    sorted(expected_exact_artifact_paths),
                    sorted(exact_artifact_paths),
                )
            )
        for path in sorted(exact_artifact_paths):
            compare_exact_artifact_bytes(
                "{} first/second".format(path),
                first_artifacts[path],
                second_artifacts[path],
            )
            compare_exact_artifact_bytes(
                "{} regenerated/committed".format(path),
                first_artifacts[path],
                committed_artifacts[path],
            )

        visited_fixture_paths = set()
        logical_database_artifact_count = 0
        canonical_storage_artifact_count = 0
        for fixture_name in sorted(cases):
            case = cases[fixture_name]
            first_database = first / "fixtures" / "{}.db".format(fixture_name)
            second_database = second / "fixtures" / "{}.db".format(fixture_name)
            committed_database = root / "fixtures" / "{}.db".format(fixture_name)
            comparison = fixture_artifact_comparison(case)
            paths = {
                path.relative_to(first).as_posix()
                for path in fixture_artifact_paths(first_database, case)
            }
            for path in paths:
                if (
                    fixture_by_path.get(path) != fixture_name
                    or committed_requirements.get(path) != comparison
                ):
                    raise FixtureValidationError(
                        "{} artifact comparison metadata is inconsistent".format(path)
                    )
            visited_fixture_paths.update(paths)
            compare_fixture_storage_artifacts(
                "{} first/second".format(fixture_name),
                first_database,
                second_database,
                case,
            )
            compare_fixture_storage_artifacts(
                "{} regenerated/committed".format(fixture_name),
                first_database,
                committed_database,
                case,
            )
            if comparison == COMPARISON_LOGICAL_DATABASE:
                logical_database_artifact_count += len(paths)
            else:
                canonical_storage_artifact_count += len(paths)
        if visited_fixture_paths != fixture_artifact_paths_set:
            raise FixtureValidationError(
                "Fixture artifact comparison coverage mismatch; missing {}, extra {}".format(
                    sorted(fixture_artifact_paths_set - visited_fixture_paths),
                    sorted(visited_fixture_paths - fixture_artifact_paths_set),
                )
            )
        return {
            "artifact_count": len(first_artifacts),
            "fixture_count": len(fixture_cases()),
            "exact_byte_artifact_count": len(exact_artifact_paths),
            "logical_database_artifact_count": (
                logical_database_artifact_count
            ),
            "canonical_storage_artifact_count": (
                canonical_storage_artifact_count
            ),
        }
    finally:
        if work_dir.exists():
            shutil.rmtree(work_dir)


def large_timestamp(base: datetime, offset_seconds: int) -> str:
    return (base + timedelta(seconds=offset_seconds)).strftime("%Y%m%d%H%M%S")


def generate_large_fixture(
    database: Path,
    activities: int,
    points_per_activity_count: int,
    manifest_path: Optional[Path] = None,
) -> Path:
    if activities <= 0 or points_per_activity_count <= 0:
        raise FixtureValidationError(
            "Large fixture activities and points-per-activity must be positive"
        )
    database = database.resolve()
    if manifest_path is None:
        manifest_path = Path(str(database) + ".manifest.json")
    else:
        manifest_path = manifest_path.resolve()
    database.parent.mkdir(parents=True, exist_ok=True)
    remove_database_artifacts(database)

    connection = sqlite3.connect(str(database))
    base = datetime(2030, 1, 1, 0, 0, 0)
    point_id = 1
    try:
        connection.execute("PRAGMA page_size = {}".format(FIXED_SQLITE_PAGE_SIZE))
        connection.execute("PRAGMA journal_mode = DELETE")
        connection.execute("PRAGMA synchronous = FULL")
        connection.execute("PRAGMA foreign_keys = OFF")
        create_schema(connection)
        for activity_index in range(1, activities + 1):
            start = base + timedelta(days=activity_index - 1)
            end = start + timedelta(seconds=points_per_activity_count - 1)
            distance = points_per_activity_count * 2.75 + activity_index / 1000.0
            elapsed = float(points_per_activity_count - 1)
            pace = elapsed / distance
            connection.execute(
                "INSERT INTO ACTIVITY ({}) VALUES ({})".format(
                    ", ".join(ACTIVITY_COLUMNS),
                    ", ".join("?" for _ in ACTIVITY_COLUMNS),
                ),
                activity(
                    activity_index,
                    start.strftime("%Y%m%d%H%M%S"),
                    end.strftime("%Y%m%d%H%M%S"),
                    "Synthetic Large Activity {:05d}".format(activity_index),
                    "Generated stress fixture; no real user data.",
                    distance,
                    elapsed,
                    pace,
                ),
            )
            batch = []
            for point_index in range(points_per_activity_count):
                timestamp = start + timedelta(seconds=point_index)
                latitude = 35.0 + activity_index * 0.001 + point_index * 0.000001
                longitude = -120.0 - activity_index * 0.001 - point_index * 0.000001
                altitude = 10.0 + (point_index % 100) * 0.125
                accuracy = 3.25 + (point_index % 7) * 0.125
                speed = 2.5 + (point_index % 11) * 0.0625
                bearing = (point_index * 7.25) % 360.0
                heart_rate = 100.5 + (point_index % 80)
                batch.append(
                    point(
                        point_id,
                        activity_index,
                        timestamp.strftime("%Y%m%d%H%M%S"),
                        latitude,
                        longitude,
                        altitude,
                        accuracy,
                        speed,
                        bearing,
                        heart_rate,
                    )
                )
                point_id += 1
            connection.executemany(
                "INSERT INTO GPS_POINTS ({}) VALUES ({})".format(
                    ", ".join(GPS_POINT_COLUMNS),
                    ", ".join("?" for _ in GPS_POINT_COLUMNS),
                ),
                batch,
            )
            if activity_index % 10 == 0:
                connection.commit()
        connection.commit()
        connection.execute("VACUUM")
    finally:
        connection.close()

    with open_readonly(database) as connection:
        schema_checksum = validate_schema_all_paths(connection, "large")
        total_points = activities * points_per_activity_count
        first_point = connection.execute(
            "SELECT LATITUDE, LONGITUDE, GMTTIMESTAMP FROM GPS_POINTS "
            "ORDER BY ID LIMIT 1"
        ).fetchone()
        last_point = connection.execute(
            "SELECT LATITUDE, LONGITUDE, GMTTIMESTAMP FROM GPS_POINTS "
            "ORDER BY ID DESC LIMIT 1"
        ).fetchone()
        large_manifest = {
            "format_version": FORMAT_VERSION,
            "synthetic_data_only": True,
            "database_name": DATABASE_NAME,
            "database": database.name,
            "parameters": {
                "activities": activities,
                "points_per_activity": points_per_activity_count,
            },
            "expected": {
                "activity_rows": activities,
                "track_point_rows": total_points,
                "physical_orphan_track_points": 0,
                "min_timestamp": base.strftime("%Y%m%d%H%M%S"),
                "max_timestamp": large_timestamp(
                    base,
                    (activities - 1) * 86400 + points_per_activity_count - 1,
                ),
                "schema_logical_checksum": schema_checksum,
                "logical_checksums": logical_checksums(connection),
                "representative_values": {
                    "first_point": list(first_point),
                    "last_point": list(last_point),
                },
            },
        }
    write_json(manifest_path, large_manifest)
    return manifest_path


def verify_large_fixture(database: Path, manifest_path: Path) -> Mapping[str, Any]:
    database = database.resolve()
    manifest_path = manifest_path.resolve()
    manifest = load_json(manifest_path)
    if manifest.get("format_version") != FORMAT_VERSION:
        raise FixtureValidationError("Unsupported large manifest format")
    if manifest.get("synthetic_data_only") is not True:
        raise FixtureValidationError("Large fixture must be synthetic")
    expected = manifest["expected"]
    with open_readonly(database) as connection:
        schema_checksum = validate_schema_all_paths(connection, "large")
        activity_count = connection.execute(
            "SELECT COUNT(*) FROM ACTIVITY"
        ).fetchone()[0]
        point_count = connection.execute(
            "SELECT COUNT(*) FROM GPS_POINTS"
        ).fetchone()[0]
        orphan_count = connection.execute(
            "SELECT COUNT(*) FROM GPS_POINTS AS point "
            "LEFT JOIN ACTIVITY AS activity ON activity.ID = point.ACTIVITYID "
            "WHERE point.ACTIVITYID IS NOT NULL AND activity.ID IS NULL"
        ).fetchone()[0]
        min_timestamp, max_timestamp = connection.execute(
            "SELECT MIN(GMTTIMESTAMP), MAX(GMTTIMESTAMP) FROM GPS_POINTS"
        ).fetchone()
        first_point = connection.execute(
            "SELECT LATITUDE, LONGITUDE, GMTTIMESTAMP FROM GPS_POINTS "
            "ORDER BY ID LIMIT 1"
        ).fetchone()
        last_point = connection.execute(
            "SELECT LATITUDE, LONGITUDE, GMTTIMESTAMP FROM GPS_POINTS "
            "ORDER BY ID DESC LIMIT 1"
        ).fetchone()
        actual = {
            "activity_rows": activity_count,
            "track_point_rows": point_count,
            "physical_orphan_track_points": orphan_count,
            "min_timestamp": min_timestamp,
            "max_timestamp": max_timestamp,
            "schema_logical_checksum": schema_checksum,
            "logical_checksums": logical_checksums(connection),
            "representative_values": {
                "first_point": list(first_point),
                "last_point": list(last_point),
            },
        }
    compare_json(expected, actual, "$.large.expected")
    return {
        "activity_rows": activity_count,
        "track_point_rows": point_count,
    }


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Generate and verify deterministic synthetic fixtures for the "
            "legacy SportLogger SQLite database."
        )
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    generate_parser = subparsers.add_parser(
        "generate", help="Regenerate the committed small fixture corpus."
    )
    generate_parser.add_argument(
        "--root",
        type=Path,
        default=TOOL_ROOT,
        help="Corpus root containing fixtures/, expected/, and manifest.json.",
    )

    verify_parser = subparsers.add_parser(
        "verify", help="Verify fixtures, manifest, outputs, and defect detectors."
    )
    verify_parser.add_argument("--root", type=Path, default=TOOL_ROOT)
    verify_parser.add_argument(
        "--candidate-dir",
        type=Path,
        help=(
            "Optional directory containing one canonical JSON output per fixture "
            "to compare with the committed oracle."
        ),
    )
    verify_parser.add_argument(
        "--skip-mutation-tests",
        action="store_true",
        help="Skip validator fault-injection self-tests.",
    )

    subparsers.add_parser(
        "verify-formatter-evidence",
        help=(
            "Verify the exact Android API26/API36 formatter probe source and "
            "sanitized evidence."
        ),
    ).add_argument("--root", type=Path, default=TOOL_ROOT)

    subparsers.add_parser(
        "verify-determinism",
        help=(
            "Regenerate the full corpus twice, compare SQLite artifacts through "
            "their declared canonical mode, and compare UTF-8/LF JSON byte-for-byte."
        ),
    ).add_argument("--root", type=Path, default=TOOL_ROOT)

    subparsers.add_parser(
        "verify-legacy-schema-path",
        help=(
            "Prove capability-selected table_xinfo/table_info and "
            "sqlite_master/sqlite_schema paths make equivalent schema decisions."
        ),
    ).add_argument("--root", type=Path, default=TOOL_ROOT)

    large_parser = subparsers.add_parser(
        "large", help="Generate an uncommitted deterministic stress fixture."
    )
    large_parser.add_argument(
        "--output",
        type=Path,
        default=TOOL_ROOT / "generated" / "large.db",
    )
    large_parser.add_argument("--activities", type=int, default=100)
    large_parser.add_argument("--points-per-activity", type=int, default=1000)
    large_parser.add_argument("--manifest", type=Path)

    verify_large_parser = subparsers.add_parser(
        "verify-large", help="Verify a generated stress fixture and its sidecar."
    )
    verify_large_parser.add_argument(
        "--database",
        type=Path,
        default=TOOL_ROOT / "generated" / "large.db",
    )
    verify_large_parser.add_argument("--manifest", type=Path)

    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    try:
        if args.command == "generate":
            manifest = generate_corpus(args.root)
            print(
                "Generated {} deterministic fixtures under {}".format(
                    len(manifest["fixtures"]), args.root.resolve()
                )
            )
            return 0
        if args.command == "verify":
            result = verify_corpus(
                args.root,
                candidate_dir=args.candidate_dir,
                run_mutations=not args.skip_mutation_tests,
            )
            suffix = ""
            if result["mutation_detector_count"]:
                suffix = " and {} applicable defect detectors".format(
                    len(result["mutation_detectors"])
                )
                not_applicable = result[
                    "not_applicable_mutation_detectors"
                ]
                if not_applicable:
                    suffix += " ({} not applicable: {})".format(
                        len(not_applicable),
                        ", ".join(
                            detector["name"] for detector in not_applicable
                        ),
                    )
            if result["candidate_dir"]:
                suffix += " plus candidate outputs"
            print(
                "Verified {} deterministic fixtures{}".format(
                    result["fixture_count"], suffix
                )
            )
            return 0
        if args.command == "verify-formatter-evidence":
            result = verify_android_formatter_evidence(args.root)
            print(
                "Verified Android formatter evidence for API {}: {} digit "
                "blocks and {} detailed rows".format(
                    "/".join(str(value) for value in result["api_levels"]),
                    result["digit_block_count"],
                    result["detailed_row_count"],
                )
            )
            return 0
        if args.command == "verify-determinism":
            result = verify_deterministic_regeneration(args.root)
            print(
                "Verified deterministic regeneration of {} artifacts across {} "
                "fixtures ({} exact-byte text artifacts, {} logical database "
                "artifacts, {} canonical storage artifacts)".format(
                    result["artifact_count"],
                    result["fixture_count"],
                    result["exact_byte_artifact_count"],
                    result["logical_database_artifact_count"],
                    result["canonical_storage_artifact_count"],
                )
            )
            return 0
        if args.command == "verify-legacy-schema-path":
            result = verify_legacy_schema_path(args.root)
            not_applicable = result["not_applicable_detectors"]
            suffix = ""
            if not_applicable:
                suffix = "; {} detector(s) not applicable: {}".format(
                    len(not_applicable),
                    ", ".join(
                        detector["name"] for detector in not_applicable
                    ),
                )
            print(
                "Verified {} schemas, {} host-supported capability-profile "
                "decisions, and {} pure version profiles{}".format(
                    result["schemas_checked"],
                    result["capability_profiles_checked"],
                    result["theoretical_profiles_checked"],
                    suffix,
                )
            )
            return 0
        if args.command == "large":
            manifest_path = generate_large_fixture(
                args.output,
                args.activities,
                args.points_per_activity,
                args.manifest,
            )
            print(
                "Generated large fixture {} with sidecar {}".format(
                    args.output.resolve(), manifest_path
                )
            )
            return 0
        if args.command == "verify-large":
            manifest_path = (
                args.manifest
                if args.manifest is not None
                else Path(str(args.database.resolve()) + ".manifest.json")
            )
            result = verify_large_fixture(args.database, manifest_path)
            print(
                "Verified large fixture: {} activities, {} track points".format(
                    result["activity_rows"], result["track_point_rows"]
                )
            )
            return 0
    except (FixtureValidationError, OSError, sqlite3.Error, ValueError) as error:
        print("ERROR: {}".format(error), file=sys.stderr)
        return 1
    raise AssertionError("Unhandled command")


if __name__ == "__main__":
    sys.exit(main())
