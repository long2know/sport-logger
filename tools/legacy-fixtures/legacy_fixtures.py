#!/usr/bin/env python3
"""Generate and verify synthetic legacy SportLogger SQLite fixtures."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import re
import sqlite3
import sys
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Mapping, Optional, Sequence, Tuple


TOOL_ROOT = Path(__file__).resolve().parent
FORMAT_VERSION = 1
DATABASE_NAME = "GPSLOGGERDB_LONG2KNOW"
ANDROID_METADATA_LOCALE = "en_US"
FIXTURE_NAMESPACE = uuid.uuid5(
    uuid.NAMESPACE_URL,
    "https://github.com/long2know/sport-logger/legacy-fixtures/v1",
)
TIMESTAMP_PATTERN = re.compile(r"^[0-9]{14}$")

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

EXPECTED_TABLE_INFO = {
    "android_metadata": (
        (0, "locale", "TEXT", 0, None, 0),
    ),
    "ACTIVITY": (
        (0, "ID", "INTEGER", 1, None, 1),
        (1, "GMTSTART", "VARCHAR", 0, None, 0),
        (2, "GMTEND", "VARCHAR", 0, None, 0),
        (3, "NAME", "VARCHAR", 0, None, 0),
        (4, "DESCRIPTION", "VARCHAR", 0, None, 0),
        (5, "DISTANCE", "REAL", 0, None, 0),
        (6, "TIME", "REAL", 0, None, 0),
        (7, "PACE", "REAL", 0, None, 0),
    ),
    "GPS_POINTS": (
        (0, "ID", "INTEGER", 1, None, 1),
        (1, "ACTIVITYID", "INTEGER", 0, None, 0),
        (2, "GMTTIMESTAMP", "VARCHAR", 0, None, 0),
        (3, "LATITUDE", "REAL", 0, None, 0),
        (4, "LONGITUDE", "REAL", 0, None, 0),
        (5, "ALTITUDE", "REAL", 0, None, 0),
        (6, "ACCURACY", "REAL", 0, None, 0),
        (7, "SPEED", "REAL", 0, None, 0),
        (8, "BEARING", "REAL", 0, None, 0),
        (9, "HEARTRATE", "REAL", 0, None, 0),
    ),
}


class FixtureValidationError(RuntimeError):
    """Raised when a fixture or expected output violates the contract."""


@dataclass(frozen=True)
class FixtureCase:
    key: str
    description: str
    activities: Tuple[Tuple[Any, ...], ...]
    track_points: Tuple[Tuple[Any, ...], ...]
    business_tables: Tuple[str, ...] = BUSINESS_TABLES
    representative_values: Tuple[Mapping[str, Any], ...] = ()
    exercise_idempotency: bool = False

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


def fixture_cases() -> Tuple[FixtureCase, ...]:
    exact = "exact"
    epsilon = "epsilon"
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


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, allow_nan=False, ensure_ascii=False, indent=2, sort_keys=True)
        + "\n",
        encoding="utf-8",
    )


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
        (ANDROID_METADATA_LOCALE,),
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
        connection.execute("PRAGMA journal_mode = DELETE")
        connection.execute("PRAGMA synchronous = FULL")
        connection.execute("PRAGMA foreign_keys = OFF")
        with connection:
            create_schema(connection, business_tables)
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


def table_info(connection: sqlite3.Connection, table: str) -> Tuple[Tuple[Any, ...], ...]:
    return tuple(tuple(row) for row in connection.execute('PRAGMA table_info("{}")'.format(table)))


def user_table_names(connection: sqlite3.Connection) -> Tuple[str, ...]:
    return tuple(
        sorted(
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master "
                "WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            )
        )
    )


def schema_payload(connection: sqlite3.Connection) -> Mapping[str, Any]:
    present_tables = set(user_table_names(connection))
    return {
        "tables": {
            table: [list(row) for row in table_info(connection, table)]
            for table in PLATFORM_TABLES + BUSINESS_TABLES
            if table in present_tables
        },
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


def schema_diagnostics(connection: sqlite3.Connection) -> Mapping[str, Any]:
    user_tables = set(user_table_names(connection))
    business_tables_present = [
        table for table in BUSINESS_TABLES if table in user_tables
    ]
    missing_business_tables = [
        table for table in BUSINESS_TABLES if table not in user_tables
    ]
    if not missing_business_tables:
        state = "complete"
    elif not business_tables_present:
        state = "no_business_tables"
    else:
        state = "partial_business_schema"
    return {
        "state": state,
        "migration_readiness": "ready" if state == "complete" else "blocked",
        "business_tables_present": business_tables_present,
        "missing_business_tables": missing_business_tables,
        "platform_tables_present": [
            table for table in PLATFORM_TABLES if table in user_tables
        ],
        "unexpected_tables": sorted(
            user_tables - set(BUSINESS_TABLES) - set(PLATFORM_TABLES)
        ),
    }


def validate_schema(
    connection: sqlite3.Connection,
    label: str,
    expected_business_tables: Sequence[str] = BUSINESS_TABLES,
) -> str:
    expected_business_table_set = set(expected_business_tables)
    unknown_expected_tables = expected_business_table_set - set(BUSINESS_TABLES)
    if unknown_expected_tables:
        raise FixtureValidationError(
            "{} has unknown expected business tables {}".format(
                label, sorted(unknown_expected_tables)
            )
        )
    user_tables = set(user_table_names(connection))
    expected_tables = expected_business_table_set | set(PLATFORM_TABLES)
    if user_tables != expected_tables:
        raise FixtureValidationError(
            "{} tables mismatch: expected {}, found {}".format(
                label, sorted(expected_tables), sorted(user_tables)
            )
        )
    metadata_rows = tuple(
        connection.execute(
            "SELECT locale, typeof(locale) FROM android_metadata ORDER BY rowid"
        )
    )
    if len(metadata_rows) != 1 or metadata_rows[0][1] != "text":
        raise FixtureValidationError(
            "{} android_metadata must contain exactly one TEXT locale row; "
            "found {!r}".format(label, metadata_rows)
        )
    for table in PLATFORM_TABLES + BUSINESS_TABLES:
        if table not in expected_tables:
            continue
        expected = EXPECTED_TABLE_INFO[table]
        actual = table_info(connection, table)
        if actual != expected:
            raise FixtureValidationError(
                "{} {} schema mismatch: expected {!r}, found {!r}".format(
                    label, table, expected, actual
                )
            )
        foreign_keys = tuple(connection.execute(
            'PRAGMA foreign_key_list("{}")'.format(table)
        ))
        if table in BUSINESS_TABLES and foreign_keys:
            raise FixtureValidationError(
                "{} unexpectedly declares foreign keys on {}".format(label, table)
            )
    user_version = connection.execute("PRAGMA user_version").fetchone()[0]
    if user_version != 0:
        raise FixtureValidationError(
            "{} user_version must be 0, found {}".format(label, user_version)
        )
    return hash_value(schema_payload(connection))


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


def strict_legacy_timestamp(value: Any) -> bool:
    if not isinstance(value, str) or not TIMESTAMP_PATTERN.fullmatch(value):
        return False
    try:
        datetime.strptime(value, "%Y%m%d%H%M%S")
    except ValueError:
        return False
    return True


def finite_number(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
    )


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


def activity_rejection_reasons(row: Mapping[str, Any]) -> List[str]:
    reasons: List[str] = []
    if not isinstance(row["ID"], int):
        reasons.append("invalid_id")
    if not strict_legacy_timestamp(row["GMTSTART"]):
        reasons.append("invalid_gmtstart")
    if row["GMTEND"] is not None and not strict_legacy_timestamp(row["GMTEND"]):
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


def point_rejection_reasons(row: Mapping[str, Any]) -> List[str]:
    reasons: List[str] = []
    if not isinstance(row["ID"], int):
        reasons.append("invalid_id")
    if not isinstance(row["ACTIVITYID"], int):
        reasons.append("missing_or_invalid_activity_id")
    if not strict_legacy_timestamp(row["GMTTIMESTAMP"]):
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
    database_identity: str, row: Mapping[str, Any]
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
    return {
        "source_key": source_key(database_identity, "ACTIVITY", legacy_id),
        "legacy_id": legacy_id,
        "deterministic_id": deterministic_id(
            database_identity, "ACTIVITY", legacy_id
        ),
        "gmt_start": row["GMTSTART"],
        "gmt_end": row["GMTEND"],
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
) -> Mapping[str, Any]:
    legacy_id = row["ID"]
    return {
        "source_key": source_key(database_identity, "GPS_POINTS", legacy_id),
        "legacy_id": legacy_id,
        "deterministic_id": deterministic_id(
            database_identity, "GPS_POINTS", legacy_id
        ),
        "activity_legacy_id": row["ACTIVITYID"],
        "session_id": session_id,
        "gmt_timestamp": row["GMTTIMESTAMP"],
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


def build_canonical_output(
    case: FixtureCase,
    rows_by_table: Mapping[str, Sequence[Sequence[Any]]],
    source_schema_diagnostics: Mapping[str, Any],
) -> Mapping[str, Any]:
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
        reasons = activity_rejection_reasons(row)
        if reasons:
            invalid_activity_ids.add(row["ID"])
            rejected.append(
                rejected_row(case.database_identity, "ACTIVITY", row, reasons)
            )
            continue
        session = normalized_session(case.database_identity, row)
        sessions.append(session)
        session_by_legacy_id[row["ID"]] = session

    track_points: List[Mapping[str, Any]] = []
    orphan_track_points: List[Mapping[str, Any]] = []
    for row in point_rows:
        reasons = point_rejection_reasons(row)
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
            orphan = dict(normalized_point(case.database_identity, row, None))
            orphan["reason"] = "missing_activity"
            orphan_track_points.append(orphan)
        else:
            track_points.append(
                normalized_point(
                    case.database_identity,
                    row,
                    parent["deterministic_id"],
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
        "fixture": case.key,
        "database_identity": case.database_identity,
        "diagnostics": {
            "schema": dict(source_schema_diagnostics),
            "data_state": (
                "empty"
                if not activity_rows and not point_rows
                else "contains_business_rows"
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

    replay = apply_insert_attempts(by_source, by_id, rerun_records)
    replay = dict(replay)
    replay.update(migration_state_counts(by_source))
    replay["migration_complete"] = True

    final_state = migration_state_payload(by_source)
    assert_exact_final_state(interruption_point, final_state, expected_state)
    duplicate_attempts_prevented = (
        first_attempt["duplicate_attempts"] + replay["duplicate_attempts"]
    )
    return {
        "interruption_point": interruption_point,
        "interruption_after_attempts": interruption_after_attempts,
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
            "exact_final_equality": True,
            "state_logical_checksum": hash_value(final_state),
        }
    )
    return result


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
        },
    }


def timestamp_summary(
    rows_by_table: Mapping[str, Sequence[Sequence[Any]]]
) -> Mapping[str, Mapping[str, Any]]:
    activity_values: List[Any] = []
    for row in rows_by_table["ACTIVITY"]:
        mapped = row_mapping(ACTIVITY_COLUMNS, row)
        activity_values.extend((mapped["GMTSTART"], mapped["GMTEND"]))
    point_values = [
        row_mapping(GPS_POINT_COLUMNS, row)["GMTTIMESTAMP"]
        for row in rows_by_table["GPS_POINTS"]
    ]

    def summarize(values: Sequence[Any]) -> Mapping[str, Any]:
        valid = sorted(value for value in values if strict_legacy_timestamp(value))
        invalid = [
            value
            for value in values
            if value is not None and not strict_legacy_timestamp(value)
        ]
        return {
            "min": valid[0] if valid else None,
            "max": valid[-1] if valid else None,
            "valid_count": len(valid),
            "invalid_count": len(invalid),
            "null_count": sum(1 for value in values if value is None),
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
        "database": database.relative_to(root).as_posix(),
        "expected_output": expected_output_path.relative_to(root).as_posix(),
        "database_identity": case.database_identity,
        "expected": {
            "activity_rows": len(rows_by_table["ACTIVITY"]),
            "track_point_rows": len(rows_by_table["GPS_POINTS"]),
            "physical_orphan_track_points": physical_orphan_count(rows_by_table),
            "points_per_activity": points_per_activity(
                rows_by_table["GPS_POINTS"]
            ),
            "timestamps": timestamp_summary(rows_by_table),
            "canonical": output["summary"],
            "diagnostics": output["diagnostics"],
            "platform_metadata": platform_metadata_payload(connection),
            "schema_logical_checksum": hash_value(schema_payload(connection)),
            "logical_checksums": logical_checksums(connection),
            "canonical_output_logical_checksum": hash_value(output),
            "representative_values": representative_values(connection, case),
            "idempotency": output["idempotency"],
        },
    }


def generate_corpus(root: Path = TOOL_ROOT) -> Mapping[str, Any]:
    root = root.resolve()
    fixtures_dir = root / "fixtures"
    expected_dir = root / "expected"
    fixtures_dir.mkdir(parents=True, exist_ok=True)
    expected_dir.mkdir(parents=True, exist_ok=True)

    entries: List[Mapping[str, Any]] = []
    for case in fixture_cases():
        database = fixtures_dir / "{}.db".format(case.key)
        expected_output_path = expected_dir / "{}.json".format(case.key)
        create_database(
            database,
            case.activities,
            case.track_points,
            case.business_tables,
        )
        with open_readonly(database) as connection:
            validate_schema(connection, case.key, case.business_tables)
            rows_by_table = read_all_rows(connection)
            output = build_canonical_output(
                case,
                rows_by_table,
                schema_diagnostics(connection),
            )
            write_json(expected_output_path, output)
            entries.append(
                manifest_entry(
                    root,
                    case,
                    database,
                    expected_output_path,
                    connection,
                    rows_by_table,
                    output,
                )
            )

    manifest = {
        "format_version": FORMAT_VERSION,
        "synthetic_data_only": True,
        "database_name": DATABASE_NAME,
        "fixture_namespace_uuid": str(FIXTURE_NAMESPACE),
        "logical_checksum_algorithm": (
            "SHA-256 over ordered table rows; each SQLite value is type-tagged, "
            "REAL values use exact IEEE-754 float.hex(), and table hashes are "
            "combined in ACTIVITY/GPS_POINTS order"
        ),
        "source_schema": {
            "android_metadata_ddl": CREATE_ANDROID_METADATA_SQL,
            "android_metadata_business_data": False,
            "fixture_android_locale": ANDROID_METADATA_LOCALE,
            "activity_ddl": CREATE_ACTIVITY_SQL,
            "gps_points_ddl": CREATE_GPS_POINTS_SQL,
            "user_version": 0,
            "foreign_keys_declared": False,
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
    float_epsilon: float = 1e-12,
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
                float_epsilon,
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
                float_epsilon,
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
        if not math.isclose(
            expected,
            float(actual),
            rel_tol=0.0,
            abs_tol=float_epsilon,
        ):
            raise FixtureValidationError(
                "{} numeric value expected {!r}, found {!r}".format(
                    path, expected, actual
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
    for fixture_name, output in outputs.items():
        if output.get("fixture") != fixture_name:
            raise FixtureValidationError(
                "{} output fixture field is {!r}".format(
                    fixture_name, output.get("fixture")
                )
            )
        sessions = output["sessions"]
        track_points = output["track_points"]
        orphans = output["orphan_track_points"]
        rejected = output["rejected_rows"]
        summary = output["summary"]
        diagnostics = output["diagnostics"]
        schema_diagnostic = diagnostics["schema"]

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
            "complete"
            if not missing_business_tables
            else (
                "no_business_tables"
                if not present_business_tables
                else "partial_business_schema"
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
        if schema_diagnostic["platform_tables_present"] != ["android_metadata"]:
            raise FixtureValidationError(
                "{} must report verified android_metadata".format(fixture_name)
            )
        if schema_diagnostic["unexpected_tables"]:
            raise FixtureValidationError(
                "{} reports unexpected source tables".format(fixture_name)
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

        idempotency = output["idempotency"]
        if idempotency is not None:
            expected_checksum = idempotency["expected_state_logical_checksum"]
            scenarios = idempotency["scenarios"]
            for scenario_name in (
                "interrupt_after_session_row",
                "interrupt_after_partial_point_prefix",
            ):
                scenario = scenarios[scenario_name]
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
            ):
                raise FixtureValidationError(
                    "{} same-run duplicate accounting mismatch".format(fixture_name)
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
) -> Tuple[str, ...]:
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
    points = candidate["representative"]["track_points"]
    points[1]["deterministic_id"] = points[0]["deterministic_id"]
    rejected("duplicate_deterministic_ids", candidate)

    candidate = copy.deepcopy(expected_outputs)
    orphan = candidate["orphan"]["orphan_track_points"].pop()
    orphan["session_id"] = candidate["orphan"]["sessions"][0]["deterministic_id"]
    orphan.pop("reason")
    candidate["orphan"]["track_points"].append(orphan)
    rejected("orphan_mishandling", candidate)

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

    return tuple(passed)


def verify_manifest_header(manifest: Mapping[str, Any]) -> None:
    if manifest.get("format_version") != FORMAT_VERSION:
        raise FixtureValidationError("Unsupported manifest format version")
    if manifest.get("synthetic_data_only") is not True:
        raise FixtureValidationError("Manifest must identify the corpus as synthetic")
    if manifest.get("database_name") != DATABASE_NAME:
        raise FixtureValidationError("Manifest database name mismatch")
    if manifest.get("fixture_namespace_uuid") != str(FIXTURE_NAMESPACE):
        raise FixtureValidationError("Manifest fixture namespace mismatch")
    source_schema = manifest.get("source_schema", {})
    expected_schema = {
        "android_metadata_ddl": CREATE_ANDROID_METADATA_SQL,
        "android_metadata_business_data": False,
        "fixture_android_locale": ANDROID_METADATA_LOCALE,
        "activity_ddl": CREATE_ACTIVITY_SQL,
        "gps_points_ddl": CREATE_GPS_POINTS_SQL,
        "user_version": 0,
        "foreign_keys_declared": False,
    }
    if source_schema != expected_schema:
        raise FixtureValidationError("Manifest source schema mismatch")


def verify_corpus(
    root: Path = TOOL_ROOT,
    candidate_dir: Optional[Path] = None,
    run_mutations: bool = True,
) -> Mapping[str, Any]:
    root = root.resolve()
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

    expected_outputs: Dict[str, Mapping[str, Any]] = {}
    for name in sorted(cases):
        case = cases[name]
        entry = manifest_entries[name]
        if entry["database_identity"] != case.database_identity:
            raise FixtureValidationError(
                "{} database identity mismatch".format(name)
            )
        database = root / entry["database"]
        expected_output_path = root / entry["expected_output"]
        if not database.is_file():
            raise FixtureValidationError("Missing fixture {}".format(database))
        if not expected_output_path.is_file():
            raise FixtureValidationError(
                "Missing expected output {}".format(expected_output_path)
            )
        with open_readonly(database) as connection:
            schema_checksum = validate_schema(
                connection,
                name,
                case.business_tables,
            )
            rows_by_table = read_all_rows(connection)
            compare_rows_to_case(case, rows_by_table)
            output = build_canonical_output(
                case,
                rows_by_table,
                schema_diagnostics(connection),
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

    mutation_names: Tuple[str, ...] = ()
    if run_mutations:
        mutation_names = run_mutation_detection_tests(expected_outputs)

    return {
        "fixture_count": len(cases),
        "mutation_detectors": list(mutation_names),
        "candidate_dir": str(candidate_dir.resolve()) if candidate_dir else None,
    }


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
        schema_checksum = validate_schema(connection, "large")
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
        schema_checksum = validate_schema(connection, "large")
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
            if result["mutation_detectors"]:
                suffix = " and {} defect detectors".format(
                    len(result["mutation_detectors"])
                )
            if result["candidate_dir"]:
                suffix += " plus candidate outputs"
            print(
                "Verified {} deterministic fixtures{}".format(
                    result["fixture_count"], suffix
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
