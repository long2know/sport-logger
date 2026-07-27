#!/usr/bin/env python3
"""Standard-library tests for the committed legacy fixture corpus."""

import copy
import json
import shutil
import sqlite3
import sys
import unicodedata
import unittest
from datetime import datetime
from pathlib import Path


TOOL_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_ROOT))

import legacy_fixtures  # noqa: E402


EXPECTED_FIXTURES = {
    "active_wal_snapshot",
    "calendar_ambiguous",
    "calendar_semantics",
    "corrupt",
    "empty",
    "interrupted_idempotency",
    "localized_timestamps",
    "malformed_schema",
    "malformed_null_partial",
    "orphan",
    "precision",
    "representative",
    "start_only_zero_points",
    "startup_activity_id_zero",
    "startup_activity_only",
    "startup_no_business_tables",
    "timestamp_ordering",
    "truncated",
}

EXPECTED_DEFECT_DETECTORS = {
    "android_metadata_empty",
    "android_metadata_invalid_value",
    "android_metadata_missing",
    "android_metadata_multiple_rows",
    "android_metadata_wrong_type",
    "corrupt_sqlite_preflight",
    "corrupt_sqlite_receipt_write",
    "calendar_ambiguous_rows_migrated",
    "calendar_gregorian_only_parser",
    "calendar_thai_digits_auto_buddhist_conversion",
    "duplicate_timestamp_collapsed",
    "float_precision_round_trip",
    "generated_column_hidden_from_table_info",
    "integer_truncation",
    "integer_precision_above_2_53",
    "json_crlf_drift",
    "localized_timestamp_ascii_arabic_only_parser",
    "localized_timestamp_ascii_only_parser",
    "localized_timestamp_metadata_coupled_parser",
    "localized_timestamp_text_normalized",
    "invalid_sqlite_page_size_preflight",
    "malformed_schema_preflight",
    "malformed_schema_target_write",
    "mixed_numbering_system_accepted",
    "missing_columns",
    "duplicate_deterministic_ids",
    "orphan_mishandling",
    "partial_rerun_committed_prefix_row_missing",
    "partial_rerun_identity_drift",
    "partial_rerun_missing_row",
    "receipt_gap_not_completed",
    "start_only_activity_dropped",
    "swapped_columns",
    "timestamp_drift",
    "timestamp_ordering",
    "truncated_sqlite_preflight",
    "truncated_sqlite_target_write",
    "unicode_format_controls_stripped",
    "virtual_table_shadow_substitution",
    "standard_database_logical_drift",
    "sqlite_change_counter_mismatch",
    "sqlite_read_version_0",
    "sqlite_read_version_255",
    "sqlite_schema_constraint_substitution",
    "sqlite_write_version_0",
    "sqlite_write_version_255",
    "wal_inconsistent_snapshot",
    "wal_shm_omitted",
    "wal_sidecars_ignored",
}


class LegacyFixtureTests(unittest.TestCase):
    def test_committed_corpus_verifies(self):
        result = legacy_fixtures.verify_corpus(
            TOOL_ROOT,
            run_mutations=False,
        )
        self.assertEqual(len(legacy_fixtures.fixture_cases()), result["fixture_count"])

    def test_counted_fixture_assertions_match_disk_reality(self):
        case_names = {case.key for case in legacy_fixtures.fixture_cases()}
        database_names = {
            path.stem for path in (TOOL_ROOT / "fixtures").glob("*.db")
        }
        output_names = {
            path.stem for path in (TOOL_ROOT / "expected").glob("*.json")
        }
        manifest = legacy_fixtures.load_json(TOOL_ROOT / "manifest.json")
        manifest_names = {entry["name"] for entry in manifest["fixtures"]}
        self.assertEqual(len(EXPECTED_FIXTURES), len(manifest["fixtures"]))
        manifest_artifacts = {
            artifact["path"]
            for entry in manifest["fixtures"]
            for artifact in entry["artifacts"]
        }
        disk_artifacts = {
            path.relative_to(TOOL_ROOT).as_posix()
            for path in (TOOL_ROOT / "fixtures").iterdir()
            if path.is_file()
        }
        self.assertEqual(EXPECTED_FIXTURES, case_names)
        self.assertEqual(EXPECTED_FIXTURES, database_names)
        self.assertEqual(EXPECTED_FIXTURES, output_names)
        self.assertEqual(EXPECTED_FIXTURES, manifest_names)
        self.assertEqual(manifest_artifacts, disk_artifacts)
        self.assertEqual(
            len(EXPECTED_FIXTURES) + 2,
            len(disk_artifacts),
        )
        self.assertEqual(
            {
                "fixtures/active_wal_snapshot.db",
                "fixtures/active_wal_snapshot.db-wal",
                "fixtures/active_wal_snapshot.db-shm",
            },
            {
                path
                for path in disk_artifacts
                if "active_wal_snapshot" in path
            },
        )
        for entry in manifest["fixtures"]:
            self.assertEqual(
                legacy_fixtures.fixture_artifact_comparison(
                    next(
                        case
                        for case in legacy_fixtures.fixture_cases()
                        if case.key == entry["name"]
                    )
                ),
                entry["artifacts"][0]["comparison"],
            )
            self.assertEqual(64, len(entry["storage_canonical_checksum"]))
            for artifact in entry["artifacts"]:
                path = TOOL_ROOT / artifact["path"]
                self.assertTrue(path.is_file())
                self.assertFalse(artifact["exact_bytes_required"])
                self.assertNotIn("bytes", artifact)
                self.assertNotIn("sha256", artifact)
        comparison_counts = {}
        for entry in manifest["fixtures"]:
            for artifact in entry["artifacts"]:
                comparison = artifact["comparison"]
                comparison_counts[comparison] = comparison_counts.get(comparison, 0) + 1
        self.assertEqual(
            {
                legacy_fixtures.COMPARISON_ACTIVE_WAL: 3,
                legacy_fixtures.COMPARISON_CORRUPT: 1,
                legacy_fixtures.COMPARISON_LOGICAL_DATABASE: 14,
                legacy_fixtures.COMPARISON_MALFORMED_SCHEMA: 1,
                legacy_fixtures.COMPARISON_TRUNCATED: 1,
            },
            comparison_counts,
        )
        self.assertEqual(
            ["ar_EG", "en_US", "th_TH_#u-nu-thai"],
            manifest["source_schema"]["fixture_android_locales"],
        )
        self.assertEqual(
            {
                "current_android_metadata_is_row_evidence": False,
                "durable_per_activity_evidence_required": True,
                "supported_calendars": ["buddhist", "gregory"],
                "buddhist_era_gregorian_year_delta": 543,
                "ambiguous_unit_policy": (
                    "quarantine_source_database_with_zero_target_writes_and_no_receipt"
                ),
            },
            manifest["calendar_oracle"],
        )
        self.assertEqual(
            {
                "json_encoding": "UTF-8",
                "json_newline": "LF",
                "sqlite_host_header_ignored_byte_ranges": [
                    [18, 20],
                    [24, 28],
                    [92, 100],
                ],
            },
            manifest["determinism"],
        )

    def test_android_metadata_is_verified_as_platform_metadata(self):
        empty_case = next(
            case for case in legacy_fixtures.fixture_cases() if case.key == "empty"
        )
        with legacy_fixtures.open_readonly(
            TOOL_ROOT / "fixtures" / "empty.db"
        ) as connection:
            legacy_fixtures.validate_schema(
                connection,
                "empty",
                empty_case.business_tables,
            )
            self.assertEqual(
                {
                    "android_metadata": {
                        "business_data": False,
                        "rows": [
                            {
                                "locale": legacy_fixtures.ANDROID_METADATA_LOCALE,
                                "storage_type": "text",
                            }
                        ],
                    }
                },
                legacy_fixtures.platform_metadata_payload(connection),
            )
            self.assertNotIn(
                "android_metadata",
                legacy_fixtures.read_all_rows(connection),
            )

        metadata_failures = (
            (
                "missing",
                ("DROP TABLE android_metadata",),
                "missing_table",
                "android_metadata:missing_table",
            ),
            (
                "empty",
                ("DELETE FROM android_metadata",),
                "empty",
                "android_metadata:row_count",
            ),
            (
                "wrong_type",
                (
                    "DELETE FROM android_metadata",
                    "INSERT INTO android_metadata (locale) VALUES (X'656e5f5553')",
                ),
                "invalid_locale_type",
                "android_metadata:locale_storage_type",
            ),
            (
                "invalid_value",
                (
                    "DELETE FROM android_metadata",
                    "INSERT INTO android_metadata (locale) "
                    "VALUES ('not a locale')",
                ),
                "invalid_locale_value",
                "android_metadata:locale_value",
            ),
            (
                "multiple_rows",
                (
                    "INSERT INTO android_metadata (locale) VALUES ('fr_CA')",
                ),
                "multiple_rows",
                "android_metadata:row_count",
            ),
        )
        for label, statements, state, error in metadata_failures:
            with self.subTest(label=label):
                connection = sqlite3.connect(":memory:")
                try:
                    legacy_fixtures.create_schema(connection)
                    for statement in statements:
                        connection.execute(statement)
                    diagnostics = legacy_fixtures.schema_diagnostics(connection)
                    self.assertEqual("malformed_schema", diagnostics["state"])
                    self.assertEqual("blocked", diagnostics["migration_readiness"])
                    self.assertEqual(state, diagnostics["android_metadata"]["state"])
                    self.assertIn(error, diagnostics["schema_errors"])
                    with self.assertRaises(legacy_fixtures.FixtureValidationError):
                        legacy_fixtures.validate_schema(
                            connection,
                            "invalid metadata {}".format(label),
                        )
                finally:
                    connection.close()

        connection = sqlite3.connect(":memory:")
        try:
            legacy_fixtures.create_schema(connection)
            connection.execute("DELETE FROM android_metadata")
            connection.execute(
                "INSERT INTO android_metadata (locale) VALUES (?)",
                ("fr_CA",),
            )
            diagnostics = legacy_fixtures.schema_diagnostics(connection)
            self.assertEqual("valid", diagnostics["android_metadata"]["state"])
            self.assertEqual("ready", diagnostics["migration_readiness"])
            legacy_fixtures.validate_schema(connection, "alternate valid locale")
        finally:
            connection.close()

    def test_table_xinfo_and_sql_semantics_reject_schema_substitutions(self):
        def connection_with_activity(activity_sql, metadata_sql=None, gps_sql=None):
            connection = sqlite3.connect(":memory:")
            connection.execute(
                metadata_sql
                or "CREATE TABLE android_metadata (locale TEXT)"
            )
            connection.execute(
                "INSERT INTO android_metadata (locale) VALUES ('en_US')"
            )
            connection.execute(activity_sql)
            connection.execute(gps_sql or legacy_fixtures.CREATE_GPS_POINTS_SQL)
            connection.execute("PRAGMA user_version = 0")
            return connection

        formatting = connection_with_activity(
            (
                'create /* formatting only */ table "ACTIVITY" ('
                '"ID" integer primary key autoincrement not null, '
                '"GMTSTART" varchar, "GMTEND" varchar, "NAME" varchar, '
                '"DESCRIPTION" varchar, "DISTANCE" real, "TIME" real, '
                '"PACE" real)'
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
        try:
            legacy_fixtures.validate_schema(
                formatting,
                "formatting-only schema",
            )
        finally:
            formatting.close()

        generated = connection_with_activity(
            (
                "CREATE TABLE ACTIVITY "
                "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                "GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR, "
                "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL, "
                "HIDDEN_COPY TEXT GENERATED ALWAYS AS (GMTSTART) VIRTUAL)"
            )
        )
        try:
            self.assertEqual(
                tuple(
                    row[:6]
                    for row in legacy_fixtures.EXPECTED_TABLE_XINFO["ACTIVITY"]
                ),
                legacy_fixtures.table_info(generated, "ACTIVITY"),
            )
            self.assertNotEqual(
                legacy_fixtures.EXPECTED_TABLE_XINFO["ACTIVITY"],
                legacy_fixtures.table_xinfo(generated, "ACTIVITY"),
            )
            diagnostics = legacy_fixtures.schema_diagnostics(generated)
            self.assertIn(
                "ACTIVITY:table_xinfo",
                diagnostics["schema_errors"],
            )
            with self.assertRaises(legacy_fixtures.FixtureValidationError):
                legacy_fixtures.validate_schema(
                    generated,
                    "generated column",
                )
        finally:
            generated.close()

        constraint = connection_with_activity(
            (
                "CREATE TABLE ACTIVITY "
                "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                "GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR, "
                "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL, "
                "CHECK (DISTANCE IS NULL OR DISTANCE >= 0))"
            )
        )
        try:
            self.assertEqual(
                legacy_fixtures.EXPECTED_TABLE_XINFO["ACTIVITY"],
                legacy_fixtures.table_xinfo(constraint, "ACTIVITY"),
            )
            diagnostics = legacy_fixtures.schema_diagnostics(constraint)
            self.assertIn(
                "ACTIVITY:sqlite_schema_sql",
                diagnostics["schema_errors"],
            )
        finally:
            constraint.close()

        schema_substitutions = (
            (
                "wrong default",
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR DEFAULT '', "
                    "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL)"
                ),
                "ACTIVITY:table_xinfo",
            ),
            (
                "wrong order",
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, DESCRIPTION VARCHAR, "
                    "NAME VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL)"
                ),
                "ACTIVITY:table_xinfo",
            ),
            (
                "wrong declared type",
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, NAME TEXT, "
                    "DESCRIPTION VARCHAR, DISTANCE REAL, TIME REAL, PACE REAL)"
                ),
                "ACTIVITY:table_xinfo",
            ),
            (
                "wrong collation",
                (
                    "CREATE TABLE ACTIVITY "
                    "(ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    "GMTSTART VARCHAR, GMTEND VARCHAR, "
                    "NAME VARCHAR COLLATE NOCASE, DESCRIPTION VARCHAR, "
                    "DISTANCE REAL, TIME REAL, PACE REAL)"
                ),
                "ACTIVITY:sqlite_schema_sql",
            ),
        )
        for label, activity_sql, expected_error in schema_substitutions:
            with self.subTest(schema=label):
                connection = connection_with_activity(activity_sql)
                try:
                    diagnostics = legacy_fixtures.schema_diagnostics(connection)
                    self.assertIn(expected_error, diagnostics["schema_errors"])
                    with self.assertRaises(
                        legacy_fixtures.FixtureValidationError
                    ):
                        legacy_fixtures.validate_schema(connection, label)
                finally:
                    connection.close()

        virtual = connection_with_activity(
            (
                "CREATE VIRTUAL TABLE ACTIVITY USING fts5("
                "ID, GMTSTART, GMTEND, NAME, DESCRIPTION, DISTANCE, TIME, PACE)"
            )
        )
        try:
            diagnostics = legacy_fixtures.schema_diagnostics(virtual)
            self.assertEqual("malformed_schema", diagnostics["state"])
            self.assertIn(
                "ACTIVITY:sqlite_schema_table_kind",
                diagnostics["schema_errors"],
            )
            self.assertTrue(diagnostics["unexpected_tables"])
            self.assertTrue(diagnostics["unexpected_schema_objects"])
        finally:
            virtual.close()

    def test_unicode_decimal_timestamp_normalization_is_strict(self):
        ascii_timestamp = "20240708091011"
        arabic_indic_timestamp = "٢٠٢٤٠٧٠٨٠٩١٠١١"
        bengali_timestamp = "২০২৪০৭০৮০৯১০১১"
        persian_timestamp = "۲۰۲۴۰۷۰۸۰۹۱۰۱۱"
        fullwidth_timestamp = "２０２４０７０８０９１０１１"
        expected = datetime(2024, 7, 8, 9, 10, 11)
        evidence = legacy_fixtures.DEFAULT_GREGORIAN_CALENDAR_EVIDENCE
        unicode_nd_digits = [
            character
            for code_point in range(0x110000)
            if unicodedata.category(character := chr(code_point)) == "Nd"
        ]

        self.assertEqual(
            ascii_timestamp,
            legacy_fixtures.normalized_legacy_timestamp_digits(
                arabic_indic_timestamp
            ),
        )
        self.assertEqual(
            expected,
            legacy_fixtures.parse_evidenced_legacy_timestamp(
                ascii_timestamp,
                evidence,
            ),
        )
        self.assertEqual(
            expected,
            legacy_fixtures.parse_evidenced_legacy_timestamp(
                arabic_indic_timestamp,
                evidence,
            ),
        )
        self.assertEqual(
            expected,
            legacy_fixtures.parse_evidenced_legacy_timestamp(
                bengali_timestamp,
                evidence,
            ),
        )
        self.assertEqual(
            expected,
            legacy_fixtures.parse_evidenced_legacy_timestamp(
                persian_timestamp,
                evidence,
            ),
        )
        self.assertEqual(
            expected,
            legacy_fixtures.parse_evidenced_legacy_timestamp(
                fullwidth_timestamp,
                evidence,
            ),
        )
        self.assertEqual(
            "20240708091011",
            legacy_fixtures.normalized_legacy_timestamp_digits(
                "٢٠٢٤٠٧٠٨09١٠١١"
            ),
        )
        self.assertIsNone(
            legacy_fixtures.normalized_legacy_timestamp_digits(
                "٢٠٢٤٠٧٠٨09١٠١١",
                require_single_numbering_system=True,
            )
        )
        self.assertFalse(
            all("0" <= character <= "9" for character in arabic_indic_timestamp)
        )
        self.assertTrue(
            unicode_nd_digits,
            "The runtime must expose Unicode decimal digits",
        )
        for character in unicode_nd_digits:
            with self.subTest(character=character):
                decimal = unicodedata.decimal(character)
                localized = character * 14
                self.assertEqual(
                    str(decimal) * 14,
                    legacy_fixtures.normalized_legacy_timestamp_digits(
                        localized
                    ),
                )

        invalid_values = {
            "impossible localized date": "٢٠٢٤٠٢٣٠٠١٠١٠١",
            "impossible localized time": "٢٠٢٤٠٧٠٨٢٤٠٠٠٠",
            "separator": "٢٠٢٤٠٧٠٨/٩١٠١١",
            "non-decimal lookalike": "٢٠٢٤٠٧٠٨٠٩١٠١¹",
            "mixed ASCII and Arabic-Indic": "٢٠٢٤٠٧٠٨09١٠١١",
            "mixed Arabic digit sets": "٢٠٢٤٠٧٠٨۰۹١٠١١",
            "mixed Arabic-Indic and Bengali": "٢٠٢٤٠٧٠٨০৯١٠١١",
            "embedded direction mark": "٢٠٢٤٠٧٠٨\u200f٠٩١٠١١",
            "embedded direction isolate": "٢٠٢٤٠٧٠٨\u2066٠٩١٠١١",
            "too short": "٢٠٢٤٠٧٠٨٠٩١٠١",
        }
        for label, value in invalid_values.items():
            with self.subTest(label=label):
                normalized = (
                    legacy_fixtures.normalized_legacy_timestamp_digits(
                        value,
                        require_single_numbering_system=True,
                    )
                )
                if label in {
                    "impossible localized date",
                    "impossible localized time",
                }:
                    self.assertIsNotNone(normalized)
                else:
                    self.assertIsNone(normalized)
                self.assertIsNone(
                    legacy_fixtures.parse_evidenced_legacy_timestamp(
                        value,
                        evidence,
                    )
                )
                self.assertFalse(
                    legacy_fixtures.strict_legacy_timestamp(value, evidence)
                )

    def test_localized_fixture_preserves_source_text_and_locale(self):
        case = next(
            case
            for case in legacy_fixtures.fixture_cases()
            if case.key == "localized_timestamps"
        )
        self.assertEqual("ar_EG", case.android_locale)
        self.assertEqual("٢٠٢٤٠٧٠٨٠٩١٠١١", case.activities[0][1])
        self.assertEqual("২০২৪০৭০৮০৯১১১১", case.activities[1][1])
        self.assertEqual("20240708091211", case.activities[2][1])

        output = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")[
            "localized_timestamps"
        ]
        self.assertEqual(3, output["summary"]["sessions"])
        self.assertEqual(6, output["summary"]["track_points"])
        self.assertEqual(7, output["summary"]["rejected_activity_rows"])
        self.assertEqual(6, output["summary"]["rejected_track_point_rows"])
        self.assertEqual(
            [
                "٢٠٢٤٠٧٠٨٠٩١٠١١",
                "২০২৪০৭০৮০৯১১১১",
                "20240708091211",
            ],
            [session["gmt_start"] for session in output["sessions"]],
        )
        self.assertEqual(
            [
                "٢٠٢٤٠٧٠٨٠٩١٠١٣",
                "২০২৪০৭০৮০৯১১১৩",
                "20240708091213",
            ],
            [session["gmt_end"] for session in output["sessions"]],
        )
        self.assertEqual(
            [
                "٢٠٢٤٠٧٠٨٠٩١٠١١",
                "٢٠٢٤٠٧٠٨٠٩١٠١٢",
                "২০২৪০৭০৮০৯১১১১",
                "২০২৪০৭০৮০৯১১১২",
                "20240708091211",
                "20240708091212",
            ],
            [point["gmt_timestamp"] for point in output["track_points"]],
        )

        rejected_activities = {
            row["legacy_id"]: row
            for row in output["rejected_rows"]
            if row["table"] == "ACTIVITY"
        }
        rejected_points = {
            row["legacy_id"]: row
            for row in output["rejected_rows"]
            if row["table"] == "GPS_POINTS"
        }
        self.assertEqual(
            "٢٠٢٤٠٧٠٨09١٠١١",
            rejected_activities[5]["raw"]["GMTSTART"],
        )
        self.assertEqual(
            "٢٠٢٤٠٧٠٨০৯١٠١١",
            rejected_activities[6]["raw"]["GMTSTART"],
        )
        self.assertEqual(
            "٢٠٢٤٠٧٠٨\u200f٠٩١٠١١",
            rejected_activities[7]["raw"]["GMTSTART"],
        )
        self.assertEqual(
            "٢٠٢٤٠٧٠٨\u2066٠٩١٠١٧",
            rejected_points[10]["raw"]["GMTTIMESTAMP"],
        )
        self.assertEqual(
            "٢٠٢٤٠٧٠٨/٩١٠١٧",
            rejected_points[12]["raw"]["GMTTIMESTAMP"],
        )

        manifest = legacy_fixtures.load_json(TOOL_ROOT / "manifest.json")
        entry = next(
            entry
            for entry in manifest["fixtures"]
            if entry["name"] == "localized_timestamps"
        )
        self.assertEqual("ar_EG", entry["android_locale"])
        self.assertEqual(
            [{"locale": "ar_EG", "storage_type": "text"}],
            entry["expected"]["platform_metadata"]["android_metadata"]["rows"],
        )
        timestamps = entry["expected"]["timestamps"]
        self.assertEqual("٢٠٢٤٠٧٠٨٠٩١٠١١", timestamps["all"]["min"])
        self.assertEqual("20240708091213", timestamps["all"]["max"])

    def test_calendar_evidence_converts_buddhist_and_quarantines_ambiguity(self):
        buddhist = legacy_fixtures.CalendarEvidence(
            calendar=legacy_fixtures.CALENDAR_BUDDHIST,
            locale_tag="th-TH-u-nu-thai",
        )
        gregorian_thai = legacy_fixtures.CalendarEvidence(
            calendar=legacy_fixtures.CALENDAR_GREGORIAN,
            locale_tag="th-TH-u-ca-gregory-nu-thai",
        )
        self.assertEqual(
            datetime(2024, 7, 8, 9, 10, 11),
            legacy_fixtures.parse_evidenced_legacy_timestamp(
                "๒๕๖๗๐๗๐๘๐๙๑๐๑๑",
                buddhist,
            ),
        )
        self.assertEqual(
            datetime(2024, 7, 8, 9, 10, 11),
            legacy_fixtures.parse_evidenced_legacy_timestamp(
                "๒๐๒๔๐๗๐๘๐๙๑๐๑๑",
                gregorian_thai,
            ),
        )
        self.assertEqual(
            datetime(2567, 7, 8, 9, 10, 11),
            legacy_fixtures.parse_strict_gregorian_legacy_timestamp(
                "๒๕๖๗๐๗๐๘๐๙๑๐๑๑"
            ),
        )

        outputs = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")
        calendar_output = outputs["calendar_semantics"]
        self.assertEqual(
            ["buddhist", "gregory"],
            [
                session["timestamp_calendar"]
                for session in calendar_output["sessions"]
            ],
        )
        self.assertEqual(
            [
                "2024-07-08T09:10:11Z",
                "2024-07-08T09:11:11Z",
            ],
            [session["gmt_start_utc"] for session in calendar_output["sessions"]],
        )
        self.assertTrue(
            calendar_output["diagnostics"]["calendar"][
                "historical_locale_change"
            ]
        )
        self.assertFalse(
            calendar_output["diagnostics"]["calendar"][
                "current_locale_used_as_row_evidence"
            ]
        )

        quarantine = outputs["calendar_ambiguous"]
        self.assertEqual("calendar_quarantine", quarantine["output_kind"])
        self.assertEqual(
            [1, 2],
            quarantine["diagnostics"]["calendar"]["ambiguous_activity_ids"],
        )
        self.assertEqual(
            "๒๕๖๗๐๗๐๘๐๙๑๐๑๑",
            quarantine["quarantined_activities"][0]["raw_activity"]["GMTSTART"],
        )
        self.assertEqual(
            "25670708091011",
            quarantine["quarantined_activities"][1]["raw_activity"]["GMTSTART"],
        )
        expectations = quarantine["migration_expectations"]
        self.assertEqual("calendar_ambiguous", expectations["reason"])
        self.assertEqual(4, expectations["source_rows_read"])
        self.assertFalse(expectations["target_write_attempted"])
        self.assertEqual(0, expectations["target_rows_written"])
        self.assertFalse(expectations["receipt_write_attempted"])
        self.assertFalse(expectations["receipt_written"])
        self.assertTrue(expectations["source_database_retained"])
        self.assertTrue(expectations["source_text_retained"])

    def test_sqlite_page_size_validation_blocks_all_illegal_encodings(self):
        legal_encodings = {
            1: 65536,
            512: 512,
            1024: 1024,
            2048: 2048,
            4096: 4096,
            8192: 8192,
            16384: 16384,
            32768: 32768,
        }
        header = bytearray(100)
        header[:16] = legacy_fixtures.SQLITE_HEADER
        incorrectly_accepted = []
        for encoded in range(65536):
            header[16:18] = encoded.to_bytes(2, "big")
            try:
                decoded = legacy_fixtures.sqlite_page_size(header)
            except legacy_fixtures.FixtureValidationError:
                if encoded in legal_encodings:
                    self.fail(
                        "Legal SQLite page-size encoding {} was rejected".format(
                            encoded
                        )
                    )
            else:
                if encoded not in legal_encodings:
                    incorrectly_accepted.append(encoded)
                else:
                    self.assertEqual(legal_encodings[encoded], decoded)
        self.assertEqual([], incorrectly_accepted)

        work_dir = TOOL_ROOT / "generated" / ".page-size-unit"
        if work_dir.exists():
            shutil.rmtree(work_dir)
        work_dir.mkdir(parents=True)
        corrupt_case = next(
            case
            for case in legacy_fixtures.fixture_cases()
            if case.key == "corrupt"
        )
        try:
            for encoded in (0, 2, 511, 513, 1023, 32767, 32769, 65535):
                database = work_dir / "invalid-{}.db".format(encoded)
                shutil.copyfile(TOOL_ROOT / "fixtures" / "empty.db", database)
                database_bytes = bytearray(database.read_bytes())
                database_bytes[16:18] = encoded.to_bytes(2, "big")
                database.write_bytes(database_bytes)

                structure = legacy_fixtures.sqlite_file_structure_diagnostics(
                    database
                )
                self.assertEqual("invalid_page_size", structure["status"])
                self.assertEqual(encoded, structure["encoded_page_size"])
                self.assertIsNone(structure["page_size"])

                output = legacy_fixtures.build_blocked_preflight_output(
                    corrupt_case,
                    database,
                )
                expectations = output["migration_expectations"]
                self.assertEqual("corrupt_sqlite", expectations["reason"])
                self.assertEqual(0, expectations["source_rows_read"])
                self.assertFalse(expectations["target_write_attempted"])
                self.assertEqual(0, expectations["target_rows_written"])
                self.assertFalse(expectations["receipt_write_attempted"])
                self.assertFalse(expectations["receipt_written"])
        finally:
            shutil.rmtree(work_dir)

    def test_sqlite_header_semantics_block_before_canonicalization(self):
        source = TOOL_ROOT / "fixtures" / "representative.db"
        corrupt_case = next(
            case
            for case in legacy_fixtures.fixture_cases()
            if case.key == "corrupt"
        )
        work_dir = TOOL_ROOT / "generated" / ".header-semantics-unit"
        if work_dir.exists():
            shutil.rmtree(work_dir)
        work_dir.mkdir(parents=True)
        try:
            for field_name, offset in (("write", 18), ("read", 19)):
                for invalid_version in (0, 255):
                    with self.subTest(
                        field=field_name,
                        version=invalid_version,
                    ):
                        database = work_dir / "{}-{}.db".format(
                            field_name,
                            invalid_version,
                        )
                        shutil.copyfile(source, database)
                        database_bytes = bytearray(database.read_bytes())
                        database_bytes[offset] = invalid_version
                        database.write_bytes(database_bytes)
                        structure = (
                            legacy_fixtures.sqlite_file_structure_diagnostics(
                                database
                            )
                        )
                        self.assertEqual(
                            "invalid_format_version",
                            structure["status"],
                        )
                        output = legacy_fixtures.build_blocked_preflight_output(
                            corrupt_case,
                            database,
                        )
                        self.assertEqual(
                            legacy_fixtures.blocked_migration_expectations(
                                "corrupt_sqlite"
                            ),
                            output["migration_expectations"],
                        )
                        with self.assertRaises(
                            legacy_fixtures.FixtureValidationError
                        ):
                            legacy_fixtures.canonical_sqlite_artifact_bytes(
                                database
                            )

            mismatch = work_dir / "counter-mismatch.db"
            shutil.copyfile(source, mismatch)
            database_bytes = bytearray(mismatch.read_bytes())
            counter = int.from_bytes(database_bytes[24:28], "big")
            database_bytes[92:96] = (counter + 1).to_bytes(4, "big")
            mismatch.write_bytes(database_bytes)
            structure = legacy_fixtures.sqlite_file_structure_diagnostics(
                mismatch
            )
            self.assertEqual("inconsistent_change_counter", structure["status"])
            self.assertFalse(
                structure["header"][
                    "change_counter_matches_version_valid_for"
                ]
            )
            with self.assertRaises(legacy_fixtures.FixtureValidationError):
                legacy_fixtures.canonical_sqlite_artifact_bytes(mismatch)

            active = legacy_fixtures.sqlite_file_structure_diagnostics(
                TOOL_ROOT / "fixtures" / "active_wal_snapshot.db"
            )
            self.assertEqual("complete", active["status"])
            self.assertEqual("wal", active["header"]["journal_mode"])
            self.assertEqual(
                (2, 2),
                (
                    active["header"]["write_version"],
                    active["header"]["read_version"],
                ),
            )
            self.assertTrue(
                active["header"][
                    "change_counter_matches_version_valid_for"
                ]
            )
        finally:
            shutil.rmtree(work_dir)

    def test_active_wal_snapshot_requires_sidecars_and_consistent_read(self):
        case = next(
            case
            for case in legacy_fixtures.fixture_cases()
            if case.key == "active_wal_snapshot"
        )
        database = TOOL_ROOT / "fixtures" / "active_wal_snapshot.db"
        diagnostics = legacy_fixtures.active_wal_snapshot_diagnostics(
            database,
            case,
        )
        self.assertTrue(diagnostics["rows_resident_in_wal"])
        self.assertEqual(
            {"activity_rows": 0, "track_point_rows": 0},
            diagnostics["main_only_counts"],
        )
        self.assertEqual(
            {
                "activity_rows": 1,
                "track_point_rows": 2,
                "integrity_check": "ok",
            },
            diagnostics["consistent_read_transaction"],
        )
        self.assertEqual(3, diagnostics["wal_frames"])

    def test_complete_schema_start_only_activity_survives_without_points(self):
        output = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")[
            "start_only_zero_points"
        ]
        self.assertEqual("ready", output["diagnostics"]["schema"]["migration_readiness"])
        self.assertEqual(1, output["summary"]["source_activity_rows"])
        self.assertEqual(1, output["summary"]["sessions"])
        self.assertEqual(0, output["summary"]["source_track_point_rows"])
        self.assertEqual(0, output["summary"]["track_points"])
        self.assertTrue(output["sessions"][0]["partial"])

    def test_timestamp_order_is_id_order_not_timestamp_order(self):
        output = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")[
            "timestamp_ordering"
        ]
        points = output["track_points"]
        self.assertEqual(
            [2147483648, 9007199254740992, 9007199254740993],
            [point["legacy_id"] for point in points],
        )
        self.assertEqual(
            [
                "20240801000002",
                "20240801000001",
                "20240801000002",
            ],
            [point["gmt_timestamp"] for point in points],
        )
        ordering = output["diagnostics"]["ordering"]
        self.assertEqual(1, ordering["duplicate_track_point_timestamp_rows"])
        self.assertEqual(
            1,
            ordering["non_monotonic_track_point_timestamp_transitions"],
        )

    def test_negative_sqlite_preflight_blocks_all_target_side_effects(self):
        outputs = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")
        expected = {
            "malformed_schema": (
                "malformed_schema",
                "passed",
                "failed",
            ),
            "truncated": (
                "truncated_sqlite",
                "not_run",
                "not_run",
            ),
            "corrupt": (
                "corrupt_sqlite",
                "failed",
                "not_run",
            ),
        }
        for fixture_name, (
            reason,
            integrity_status,
            schema_status,
        ) in expected.items():
            output = outputs[fixture_name]
            expectations = output["migration_expectations"]
            self.assertEqual("blocked", expectations["status"])
            self.assertEqual(reason, expectations["reason"])
            self.assertEqual(0, expectations["source_rows_read"])
            self.assertFalse(expectations["target_write_attempted"])
            self.assertEqual(0, expectations["target_rows_written"])
            self.assertFalse(expectations["receipt_write_attempted"])
            self.assertFalse(expectations["receipt_written"])
            preflight = output["diagnostics"]["preflight"]
            self.assertEqual(
                integrity_status,
                preflight["integrity_check"]["status"],
            )
            self.assertEqual(
                schema_status,
                preflight["schema_check"]["status"],
            )

    def test_startup_race_diagnostics_distinguish_schema_from_empty_data(self):
        outputs = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")

        valid_empty = outputs["empty"]["diagnostics"]
        self.assertEqual("complete", valid_empty["schema"]["state"])
        self.assertEqual("ready", valid_empty["schema"]["migration_readiness"])
        self.assertEqual("empty", valid_empty["data_state"])

        no_tables = outputs["startup_no_business_tables"]["diagnostics"]
        self.assertEqual("no_business_tables", no_tables["schema"]["state"])
        self.assertEqual("blocked", no_tables["schema"]["migration_readiness"])
        self.assertEqual(
            ["ACTIVITY", "GPS_POINTS"],
            no_tables["schema"]["missing_business_tables"],
        )
        self.assertEqual("empty", no_tables["data_state"])

        activity_only = outputs["startup_activity_only"]["diagnostics"]
        self.assertEqual(
            "partial_business_schema",
            activity_only["schema"]["state"],
        )
        self.assertEqual(
            ["ACTIVITY"],
            activity_only["schema"]["business_tables_present"],
        )
        self.assertEqual(
            ["GPS_POINTS"],
            activity_only["schema"]["missing_business_tables"],
        )
        self.assertEqual("blocked", activity_only["schema"]["migration_readiness"])
        self.assertEqual("empty", activity_only["data_state"])

        zero_owner = outputs["startup_activity_id_zero"]
        self.assertEqual("complete", zero_owner["diagnostics"]["schema"]["state"])
        self.assertEqual(1, zero_owner["summary"]["orphan_track_points"])
        self.assertEqual(0, zero_owner["orphan_track_points"][0]["activity_legacy_id"])
        self.assertEqual(
            "missing_activity",
            zero_owner["orphan_track_points"][0]["reason"],
        )

    def test_fault_injection_detectors_reject_known_etl_failures(self):
        outputs = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")
        cases = {case.key: case for case in legacy_fixtures.fixture_cases()}
        detected = set(
            legacy_fixtures.run_mutation_detection_tests(outputs)
            + legacy_fixtures.run_storage_detection_tests(
                TOOL_ROOT,
                cases,
                outputs,
            )
        )
        self.assertEqual(len(EXPECTED_DEFECT_DETECTORS), len(detected))
        self.assertEqual(EXPECTED_DEFECT_DETECTORS, detected)

    def test_json_integer_comparison_is_exact_above_2_53(self):
        exact_id = 9007199254740993
        legacy_fixtures.compare_json(
            {"legacy_id": exact_id},
            {"legacy_id": exact_id},
        )
        with self.assertRaises(legacy_fixtures.FixtureValidationError):
            legacy_fixtures.compare_json(
                {"legacy_id": exact_id},
                {"legacy_id": exact_id - 1},
            )
        with self.assertRaises(legacy_fixtures.FixtureValidationError):
            legacy_fixtures.compare_json(
                {"legacy_id": exact_id},
                {"legacy_id": float(exact_id)},
            )

        outputs = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")
        precision_ids = {
            session["legacy_id"] for session in outputs["precision"]["sessions"]
        }
        self.assertIn(exact_id, precision_ids)

    def test_json_float_comparison_uses_exact_double_round_trip(self):
        expected = 1.000000000000001
        legacy_fixtures.compare_json(
            {"time": expected},
            json.loads('{"time": 1.0000000000000010}'),
        )
        with self.assertRaises(legacy_fixtures.FixtureValidationError):
            legacy_fixtures.compare_json(
                {"time": expected},
                {"time": 1.0},
            )

    def test_insert_attempt_accounting_proves_idempotency(self):
        outputs = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")
        interrupted = outputs["interrupted_idempotency"]
        simulation = legacy_fixtures.simulate_idempotent_migration(interrupted)
        self.assertEqual(interrupted["idempotency"], simulation)

        scenarios = simulation["scenarios"]
        after_session = scenarios["interrupt_after_session_row"]
        self.assertEqual(1, after_session["first_attempt"]["sessions"])
        self.assertEqual(0, after_session["first_attempt"]["track_points"])
        self.assertEqual(1, after_session["replay"]["duplicate_attempts"])
        self.assertEqual(1, after_session["duplicate_attempts_prevented"])
        self.assertEqual(0, after_session["duplicate_rows"])
        self.assertTrue(after_session["exact_final_equality"])

        after_point_prefix = scenarios["interrupt_after_partial_point_prefix"]
        self.assertEqual(1, after_point_prefix["first_attempt"]["sessions"])
        self.assertEqual(1, after_point_prefix["first_attempt"]["track_points"])
        self.assertEqual(2, after_point_prefix["replay"]["duplicate_attempts"])
        self.assertEqual(2, after_point_prefix["duplicate_attempts_prevented"])
        self.assertEqual(0, after_point_prefix["duplicate_rows"])
        self.assertTrue(after_point_prefix["exact_final_equality"])

        same_run = scenarios["same_run_duplicates"]
        self.assertEqual(
            simulation["canonical_insert_rows"] * 2,
            same_run["attempted_rows"],
        )
        self.assertEqual(
            simulation["canonical_insert_rows"],
            same_run["inserted_rows"],
        )
        self.assertEqual(
            simulation["canonical_insert_rows"],
            same_run["duplicate_attempts"],
        )
        self.assertEqual(0, same_run["duplicate_rows"])
        self.assertTrue(same_run["attempts_fully_accounted"])
        self.assertTrue(same_run["exact_final_equality"])
        self.assertTrue(same_run["receipt_present"])
        self.assertTrue(same_run["receipt_completed"])

        receipt_gap = scenarios["receipt_gap_replay"]
        committed_rows = receipt_gap["committed_rows"]
        replay = receipt_gap["replay"]
        self.assertEqual(
            simulation["canonical_insert_rows"],
            committed_rows["inserted_rows"],
        )
        self.assertFalse(committed_rows["receipt_present"])
        self.assertFalse(committed_rows["migration_complete"])
        self.assertEqual(0, replay["inserted_rows"])
        self.assertEqual(
            simulation["canonical_insert_rows"],
            replay["duplicate_attempts"],
        )
        self.assertEqual(replay["attempted_rows"], replay["duplicate_attempts"])
        self.assertTrue(replay["receipt_present"])
        self.assertTrue(replay["receipt_completed"])
        self.assertTrue(replay["migration_complete"])
        self.assertTrue(receipt_gap["exact_state_preserved"])

        for scenario in (after_session, after_point_prefix):
            self.assertEqual(
                simulation["canonical_insert_rows"],
                scenario["canonical_record_count"],
            )
            self.assertEqual(
                simulation["canonical_insert_rows"],
                scenario["rerun_record_count"],
            )
            self.assertTrue(scenario["rerun_records_exactly_canonical"])
            self.assertTrue(scenario["replay_attempts_cover_canonical_set"])
            self.assertEqual(
                simulation["canonical_insert_rows"],
                scenario["replay"]["attempted_rows"],
            )
            for phase in (scenario["first_attempt"], scenario["replay"]):
                self.assertEqual(
                    phase["attempted_rows"],
                    phase["inserted_rows"] + phase["duplicate_attempts"],
                )
                self.assertTrue(phase["attempts_fully_accounted"])
                self.assertEqual(0, phase["duplicate_rows"])

        bad_rerun = copy.deepcopy(interrupted)
        committed_prefix_point = bad_rerun["track_points"].pop(0)
        self.assertEqual(1, committed_prefix_point["legacy_id"])
        with self.assertRaises(legacy_fixtures.FixtureValidationError):
            legacy_fixtures.simulate_interrupted_insert_attempts(
                interrupted,
                interruption_after_attempts=2,
                interruption_point="after_partial_point_prefix",
                rerun_output=bad_rerun,
            )

        self.assertEqual(
            1,
            legacy_fixtures.duplicate_row_count(
                {
                    "source-a": {
                        "source_key": "source-a",
                        "deterministic_id": "same-id",
                    },
                    "source-b": {
                        "source_key": "source-b",
                        "deterministic_id": "same-id",
                    },
                }
            ),
        )

    def test_standard_database_comparison_is_logical(self):
        case = next(
            case
            for case in legacy_fixtures.fixture_cases()
            if case.key == "representative"
        )
        source = TOOL_ROOT / "fixtures" / "representative.db"
        work_dir = TOOL_ROOT / "generated" / ".logical-comparison-unit"
        if work_dir.exists():
            shutil.rmtree(work_dir)
        work_dir.mkdir(parents=True)
        try:
            header_variant = work_dir / "writer-header-variant.db"
            shutil.copyfile(source, header_variant)
            database_bytes = bytearray(header_variant.read_bytes())
            database_bytes[96:100] = (3049000).to_bytes(4, "big")
            header_variant.write_bytes(database_bytes)
            self.assertNotEqual(source.read_bytes(), header_variant.read_bytes())
            legacy_fixtures.compare_standard_database_artifacts(
                "writer header variation",
                source,
                header_variant,
                case,
            )

            logical_drift = work_dir / "logical-drift.db"
            shutil.copyfile(source, logical_drift)
            connection = sqlite3.connect(str(logical_drift))
            try:
                with connection:
                    connection.execute(
                        "UPDATE ACTIVITY SET NAME = ? WHERE ID = 1",
                        ("Changed logical value",),
                    )
            finally:
                connection.close()
            with self.assertRaises(legacy_fixtures.FixtureValidationError):
                legacy_fixtures.compare_standard_database_artifacts(
                    "logical drift",
                    source,
                    logical_drift,
                    case,
                )
        finally:
            shutil.rmtree(work_dir)

    def test_json_artifacts_are_utf8_lf_and_crlf_drift_is_rejected(self):
        json_paths = [TOOL_ROOT / "manifest.json"]
        json_paths.extend(sorted((TOOL_ROOT / "expected").glob("*.json")))
        for path in json_paths:
            with self.subTest(path=path.name):
                data = path.read_bytes()
                self.assertEqual(
                    legacy_fixtures.pretty_json_bytes(
                        legacy_fixtures.load_json(path)
                    ),
                    data,
                )
                self.assertTrue(data.endswith(b"\n"))
                self.assertNotIn(b"\r", data)
                self.assertFalse(data.startswith(b"\xef\xbb\xbf"))
                data.decode("utf-8")

        attributes = {
            line.strip()
            for line in (TOOL_ROOT.parent.parent / ".gitattributes")
            .read_text(encoding="utf-8")
            .splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
        self.assertTrue(
            {
                "tools/legacy-fixtures/manifest.json text eol=lf",
                "tools/legacy-fixtures/expected/*.json text eol=lf",
                "tools/legacy-fixtures/*.py text eol=lf",
                "tools/legacy-fixtures/*.java text eol=lf",
                "tools/legacy-fixtures/README.md text eol=lf",
                "docs/legacy-database-contract.md text eol=lf",
            }.issubset(attributes)
        )

        work_dir = TOOL_ROOT / "generated" / ".json-newline-unit"
        if work_dir.exists():
            shutil.rmtree(work_dir)
        work_dir.mkdir(parents=True)
        try:
            generated = work_dir / "generated.json"
            legacy_fixtures.write_json(
                generated,
                {"timestamp": "২০২৪০৭০৮০৯১০১১"},
            )
            self.assertEqual(
                legacy_fixtures.pretty_json_bytes(
                    {"timestamp": "২০২৪০৭০৮০৯১০১১"}
                ),
                generated.read_bytes(),
            )
            crlf = work_dir / "crlf.json"
            crlf.write_bytes(generated.read_bytes().replace(b"\n", b"\r\n"))
            with self.assertRaises(legacy_fixtures.FixtureValidationError):
                legacy_fixtures.compare_exact_artifact_bytes(
                    "CRLF JSON",
                    generated,
                    crlf,
                )
        finally:
            shutil.rmtree(work_dir)

    def test_storage_modes_ignore_host_headers_but_detect_meaningful_drift(self):
        cases = {case.key: case for case in legacy_fixtures.fixture_cases()}
        work_dir = TOOL_ROOT / "generated" / ".storage-comparison-unit"
        if work_dir.exists():
            shutil.rmtree(work_dir)
        work_dir.mkdir(parents=True)

        def copy_case(case, label):
            destination_dir = work_dir / label
            destination_dir.mkdir(parents=True)
            source_database = TOOL_ROOT / "fixtures" / "{}.db".format(case.key)
            destination_database = destination_dir / "{}.db".format(case.key)
            for source, destination in zip(
                legacy_fixtures.fixture_artifact_paths(source_database, case),
                legacy_fixtures.fixture_artifact_paths(destination_database, case),
            ):
                shutil.copyfile(source, destination)
            return source_database, destination_database

        def vary_writer_version(database):
            database_bytes = bytearray(database.read_bytes())
            database_bytes[96:100] = (3049000).to_bytes(4, "big")
            database.write_bytes(database_bytes)

        try:
            for fixture_name in (
                "representative",
                "malformed_schema",
                "truncated",
                "corrupt",
            ):
                case = cases[fixture_name]
                source, variant = copy_case(case, "{}-header".format(fixture_name))
                vary_writer_version(variant)
                legacy_fixtures.compare_fixture_storage_artifacts(
                    "{} host header".format(fixture_name),
                    source,
                    variant,
                    case,
                )

            active_case = cases["active_wal_snapshot"]
            source, variant = copy_case(active_case, "active-header")
            vary_writer_version(variant)
            legacy_fixtures.compare_fixture_storage_artifacts(
                "active WAL host header",
                source,
                variant,
                active_case,
            )

            representative_case = cases["representative"]
            source, payload_drift = copy_case(
                representative_case,
                "payload-drift",
            )
            connection = sqlite3.connect(str(payload_drift))
            try:
                with connection:
                    connection.execute(
                        "UPDATE ACTIVITY SET NAME = ? WHERE ID = 1",
                        ("Changed logical value",),
                    )
            finally:
                connection.close()
            with self.assertRaises(legacy_fixtures.FixtureValidationError):
                legacy_fixtures.compare_fixture_storage_artifacts(
                    "payload drift",
                    source,
                    payload_drift,
                    representative_case,
                )

            malformed_case = cases["malformed_schema"]
            source, schema_drift = copy_case(malformed_case, "schema-drift")
            legacy_fixtures.create_database(
                schema_drift,
                malformed_case.activities,
                malformed_case.track_points,
                android_locale=malformed_case.android_locale,
            )
            with self.assertRaises(legacy_fixtures.FixtureValidationError):
                legacy_fixtures.compare_fixture_storage_artifacts(
                    "schema drift",
                    source,
                    schema_drift,
                    malformed_case,
                )

            truncated_case = cases["truncated"]
            source, page_drift = copy_case(truncated_case, "page-drift")
            database_bytes = bytearray(page_drift.read_bytes())
            database_bytes[-1] ^= 0x01
            page_drift.write_bytes(database_bytes)
            with self.assertRaises(legacy_fixtures.FixtureValidationError):
                legacy_fixtures.compare_fixture_storage_artifacts(
                    "retained page drift",
                    source,
                    page_drift,
                    truncated_case,
                )

            corrupt_case = cases["corrupt"]
            source, corruption_drift = copy_case(
                corrupt_case,
                "corruption-drift",
            )
            with legacy_fixtures.open_readonly(corruption_drift) as connection:
                root_page = connection.execute(
                    "SELECT rootpage FROM sqlite_master "
                    "WHERE type = 'table' AND name = 'GPS_POINTS'"
                ).fetchone()[0]
            database_bytes = bytearray(corruption_drift.read_bytes())
            page_size = legacy_fixtures.sqlite_page_size(database_bytes)
            database_bytes[(root_page - 1) * page_size] = 13
            corruption_drift.write_bytes(database_bytes)
            with self.assertRaises(legacy_fixtures.FixtureValidationError):
                legacy_fixtures.compare_fixture_storage_artifacts(
                    "corruption shape drift",
                    source,
                    corruption_drift,
                    corrupt_case,
                )

            source, wal_drift = copy_case(active_case, "wal-payload-drift")
            wal_path = Path(str(wal_drift) + "-wal")
            wal_bytes = bytearray(wal_path.read_bytes())
            wal_bytes[-1] ^= 0x01
            wal_path.write_bytes(wal_bytes)
            with self.assertRaises(
                (legacy_fixtures.FixtureValidationError, sqlite3.DatabaseError)
            ):
                legacy_fixtures.compare_fixture_storage_artifacts(
                    "active WAL payload drift",
                    source,
                    wal_drift,
                    active_case,
                )
        finally:
            shutil.rmtree(work_dir)

    def test_corpus_regenerates_deterministically(self):
        result = legacy_fixtures.verify_deterministic_regeneration(TOOL_ROOT)
        self.assertEqual(len(EXPECTED_FIXTURES), result["fixture_count"])
        self.assertEqual(39, result["artifact_count"])
        self.assertEqual(19, result["exact_byte_artifact_count"])
        self.assertEqual(14, result["logical_database_artifact_count"])
        self.assertEqual(6, result["canonical_storage_artifact_count"])


if __name__ == "__main__":
    unittest.main()
