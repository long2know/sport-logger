#!/usr/bin/env python3
"""Standard-library tests for the committed legacy fixture corpus."""

import copy
import json
import shutil
import sqlite3
import sys
import unittest
from pathlib import Path


TOOL_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_ROOT))

import legacy_fixtures  # noqa: E402


EXPECTED_FIXTURES = {
    "active_wal_snapshot",
    "corrupt",
    "empty",
    "interrupted_idempotency",
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
    "duplicate_timestamp_collapsed",
    "float_precision_round_trip",
    "integer_truncation",
    "integer_precision_above_2_53",
    "invalid_sqlite_page_size_preflight",
    "malformed_schema_preflight",
    "malformed_schema_target_write",
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
    "standard_database_logical_drift",
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
            for artifact in entry["artifacts"]:
                path = TOOL_ROOT / artifact["path"]
                self.assertEqual(path.stat().st_size, artifact["bytes"])
                if artifact["exact_bytes_required"]:
                    self.assertEqual(
                        legacy_fixtures.file_sha256(path),
                        artifact["sha256"],
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
            database_bytes[18] = 2 if database_bytes[18] == 1 else 1
            database_bytes[19] = 2 if database_bytes[19] == 1 else 1
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

    def test_corpus_regenerates_deterministically(self):
        result = legacy_fixtures.verify_deterministic_regeneration(TOOL_ROOT)
        self.assertEqual(len(EXPECTED_FIXTURES), result["fixture_count"])
        self.assertGreater(result["artifact_count"], len(EXPECTED_FIXTURES) * 2)
        self.assertGreater(result["exact_byte_artifact_count"], 0)
        self.assertGreater(result["logical_database_artifact_count"], 0)


if __name__ == "__main__":
    unittest.main()
