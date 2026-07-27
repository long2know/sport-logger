#!/usr/bin/env python3
"""Standard-library tests for the committed legacy fixture corpus."""

import sqlite3
import sys
import unittest
from pathlib import Path


TOOL_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_ROOT))

import legacy_fixtures  # noqa: E402


EXPECTED_FIXTURES = {
    "empty",
    "startup_no_business_tables",
    "startup_activity_only",
    "startup_activity_id_zero",
    "representative",
    "precision",
    "orphan",
    "malformed_null_partial",
    "interrupted_idempotency",
}

EXPECTED_MUTATION_DETECTORS = {
    "integer_truncation",
    "integer_precision_above_2_53",
    "swapped_columns",
    "missing_columns",
    "timestamp_drift",
    "duplicate_deterministic_ids",
    "orphan_mishandling",
    "partial_rerun_identity_drift",
    "partial_rerun_missing_row",
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
        self.assertEqual(EXPECTED_FIXTURES, case_names)
        self.assertEqual(EXPECTED_FIXTURES, database_names)
        self.assertEqual(EXPECTED_FIXTURES, output_names)
        self.assertEqual(EXPECTED_FIXTURES, manifest_names)

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

        connection = sqlite3.connect(":memory:")
        try:
            legacy_fixtures.create_schema(connection)
            connection.execute("DELETE FROM android_metadata")
            with self.assertRaises(legacy_fixtures.FixtureValidationError):
                legacy_fixtures.validate_schema(connection, "missing locale row")
        finally:
            connection.close()

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
        detected = set(legacy_fixtures.run_mutation_detection_tests(outputs))
        self.assertEqual(EXPECTED_MUTATION_DETECTORS, detected)

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

        for scenario in (after_session, after_point_prefix):
            for phase in (scenario["first_attempt"], scenario["replay"]):
                self.assertEqual(
                    phase["attempted_rows"],
                    phase["inserted_rows"] + phase["duplicate_attempts"],
                )
                self.assertTrue(phase["attempts_fully_accounted"])
                self.assertEqual(0, phase["duplicate_rows"])

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


if __name__ == "__main__":
    unittest.main()
