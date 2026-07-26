#!/usr/bin/env python3
"""Standard-library tests for the committed legacy fixture corpus."""

import sys
import unittest
from pathlib import Path


TOOL_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_ROOT))

import legacy_fixtures  # noqa: E402


class LegacyFixtureTests(unittest.TestCase):
    def test_committed_corpus_verifies(self):
        result = legacy_fixtures.verify_corpus(
            TOOL_ROOT,
            run_mutations=False,
        )
        self.assertEqual(len(legacy_fixtures.fixture_cases()), result["fixture_count"])

    def test_fault_injection_detectors_reject_known_etl_failures(self):
        outputs = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")
        detected = set(legacy_fixtures.run_mutation_detection_tests(outputs))
        self.assertEqual(
            {
                "integer_truncation",
                "swapped_columns",
                "missing_columns",
                "timestamp_drift",
                "duplicate_deterministic_ids",
                "orphan_mishandling",
                "partial_rerun_identity_drift",
            },
            detected,
        )

    def test_interrupted_rerun_is_idempotent(self):
        outputs = legacy_fixtures.load_outputs(TOOL_ROOT / "expected")
        interrupted = outputs["interrupted_idempotency"]
        simulation = legacy_fixtures.simulate_interrupted_migration(
            interrupted,
            interrupt_after_sessions=1,
        )
        self.assertEqual(interrupted["idempotency"], simulation)
        self.assertFalse(simulation["first_attempt"]["migration_complete"])
        self.assertTrue(simulation["rerun"]["migration_complete"])
        self.assertEqual(0, simulation["rerun"]["duplicate_rows"])


if __name__ == "__main__":
    unittest.main()
