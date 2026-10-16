from __future__ import annotations

import json
import re
import shutil
import sys
import unittest
import uuid
from pathlib import Path


TOOL_DIR = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = TOOL_DIR.parents[1]
sys.path.insert(0, str(TOOL_DIR))

import oracle  # noqa: E402


class OracleTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog, cls.evidence = oracle.load_digit_catalog()

    def test_every_verified_android_decimal_definition_parses(self) -> None:
        expected = "2024-02-29T23:59:58.000Z"
        for block in self.catalog.blocks:
            original = oracle.encode_digits("20240229235958", block)
            parsed = oracle.parse_localized_timestamp(original, self.catalog)
            self.assertEqual(expected, parsed.canonical_utc)
            self.assertEqual(block.zero_code_point, parsed.zero_code_point)
        zeros = {block.zero_code_point for block in self.catalog.blocks}
        self.assertIn(0xFF10, zeros)
        self.assertTrue(any(zero > 0xFFFF for zero in zeros))
        self.assertFalse(
            any(
                block.zero_code_point > 0xFFFF
                and block.emitted_api_levels
                for block in self.catalog.blocks
            )
        )
        self.assertTrue(
            any(
                block.zero_code_point > 0xFFFF
                and block.fallback_api_levels
                for block in self.catalog.blocks
            )
        )

    def test_parser_rejects_unknown_mixed_controls_length_and_dates(self) -> None:
        ascii_block = next(
            block for block in self.catalog.blocks if block.zero_code_point == 0x0030
        )
        non_ascii = next(
            block for block in self.catalog.blocks if block.zero_code_point != 0x0030
        )
        unknown = oracle.find_unknown_decimal_block(self.catalog)
        cases = {
            "UNKNOWN_DIGIT_BLOCK": oracle.encode_digits("20240229235958", unknown),
            "MIXED_DIGIT_BLOCKS": (
                oracle.encode_digits("2024022", ascii_block)
                + oracle.encode_digits("9235958", non_ascii)
            ),
            "INVALID_CONTROL": "2024\n229235958",
            "INVALID_CHARACTER": "2024A229235958",
            "INVALID_LENGTH": "2024022923595",
            "INVALID_DATE": "20240230030405",
        }
        for expected_code, value in cases.items():
            with self.subTest(expected_code=expected_code):
                with self.assertRaises(oracle.OracleError) as context:
                    oracle.parse_localized_timestamp(value, self.catalog)
                self.assertEqual(expected_code, context.exception.code)
        with self.assertRaises(oracle.OracleError) as context:
            oracle.parse_localized_timestamp(None, self.catalog)
        self.assertEqual("MISSING_TIMESTAMP", context.exception.code)

    def test_formatter_fallback_and_exact_production_pattern_are_proven(self) -> None:
        self.assertGreater(len(self.catalog.formatter_fallback_names), 0)
        toolchain = self.evidence["toolchain"]
        self.assertEqual("yyyyMMddHHmmss", toolchain["formatter"]["pattern"])
        self.assertEqual(
            "java.text.SimpleDateFormat(String)",
            toolchain["formatter"]["constructor"],
        )
        self.assertEqual(
            "yyyyMMddHHmmss",
            toolchain["legacy_writer"]["pattern"],
        )

    def test_scenario_manifest_hashes_match_disk_and_cover_each_detector_once(self) -> None:
        scenarios = oracle.load_scenarios()
        flawed = [
            scenario for scenario in scenarios if scenario.expected_code is not None
        ]
        self.assertEqual(len(oracle.DETECTOR_CODES), len(flawed))
        self.assertEqual(
            oracle.DETECTOR_CODES,
            {scenario.expected_code for scenario in flawed},
        )
        self.assertEqual(
            1,
            sum(scenario.expected_status == "PASS" for scenario in scenarios),
        )

    def test_output_manifest_detects_real_count_and_hash_tampering(self) -> None:
        test_root = TOOL_DIR / ".test-work"
        test_root.mkdir(exist_ok=True)
        workspace = test_root / uuid.uuid4().hex
        workspace.mkdir()
        try:
            entries = []
            for name in oracle.OUTPUT_DATA_FILES:
                path = workspace / name
                writer = oracle.CanonicalNdjsonWriter(path)
                if name == "points.ndjson":
                    writer.write({"source_id": 1})
                entries.append(writer.close())
            manifest = {
                "files": entries,
                "schema": oracle.OUTPUT_MANIFEST_SCHEMA,
                "source_sha256": "0" * 64,
            }
            oracle.atomic_json(workspace / "manifest.json", manifest)
            oracle.validate_output_manifest(workspace)

            count_manifest = json.loads(
                (workspace / "manifest.json").read_text(encoding="utf-8")
            )
            count_manifest["files"][1]["records"] += 1
            oracle.atomic_json(workspace / "manifest.json", count_manifest)
            with self.assertRaises(oracle.OracleError) as context:
                oracle.validate_output_manifest(workspace)
            self.assertEqual("MANIFEST_COUNT_MISMATCH", context.exception.code)

            oracle.atomic_json(workspace / "manifest.json", manifest)
            points = workspace / "points.ndjson"
            payload = points.read_bytes()
            newline = payload.index(b"\n")
            points.write_bytes(payload[:newline] + b" " + payload[newline:])
            with self.assertRaises(oracle.OracleError) as context:
                oracle.validate_output_manifest(workspace)
            self.assertEqual("MANIFEST_HASH_MISMATCH", context.exception.code)
        finally:
            shutil.rmtree(workspace, ignore_errors=True)
            if test_root.exists() and not any(test_root.iterdir()):
                test_root.rmdir()

    def test_oracle_never_imports_host_sqlite(self) -> None:
        source = (TOOL_DIR / "oracle.py").read_text(encoding="utf-8")
        self.assertNotRegex(source, r"(?m)^\s*(?:from|import)\s+sqlite3\b")
        self.assertIn("sqlite_harness.build_engine", source)
        self.assertIn("engine.readonly_sql", source)

    def test_workflows_cover_dev_and_main_and_pin_actions(self) -> None:
        workflows = (
            REPOSITORY_ROOT / ".github/workflows/android-formatter-evidence.yml",
            REPOSITORY_ROOT / ".github/workflows/legacy-migration-oracle.yml",
            REPOSITORY_ROOT / ".github/workflows/sqlite-exact-compat.yml",
        )
        for workflow_path in workflows:
            workflow = workflow_path.read_text(encoding="utf-8")
            with self.subTest(workflow=workflow_path.name):
                self.assertRegex(
                    workflow,
                    r"pull_request:\s*\n\s+branches:\s*\[dev, main\]",
                )
                self.assertRegex(
                    workflow,
                    r"push:\s*\n\s+branches:\s*\[dev, main\]",
                )
                uses = re.findall(
                    r"^\s*-?\s*uses:\s*[^@\s]+@([^\s#]+)",
                    workflow,
                    re.MULTILINE,
                )
                self.assertGreater(len(uses), 0)
                self.assertTrue(
                    all(re.fullmatch(r"[0-9a-f]{40}", revision) for revision in uses)
                )


if __name__ == "__main__":
    unittest.main()
