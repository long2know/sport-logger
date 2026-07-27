from __future__ import annotations

import dataclasses
import hashlib
import json
import shutil
import sys
import unittest
import uuid
from pathlib import Path
from unittest import mock


TOOL_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL_DIR))

import sqlite_compat as harness  # noqa: E402


class WorkspaceTestCase(unittest.TestCase):
    def setUp(self) -> None:
        root = TOOL_DIR / ".test-work"
        root.mkdir(parents=True, exist_ok=True)
        self.workspace = root / f"{self.__class__.__name__}-{uuid.uuid4().hex}"
        self.workspace.mkdir()

    def tearDown(self) -> None:
        shutil.rmtree(self.workspace, ignore_errors=True)


class FakeSqlEngine:
    def __init__(self, responses: list[tuple[str, harness.CommandResult]]):
        self.responses = responses

    def sql(self, database: Path | str, sql: str, *, timeout: int = 180) -> harness.CommandResult:
        del database, timeout
        for marker, response in self.responses:
            if marker in sql:
                return response
        raise AssertionError(f"unexpected SQL: {sql}")


class HarnessUnitTests(WorkspaceTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.spec = harness.load_manifest()[0]

    def test_manifest_pins_the_four_required_engines(self) -> None:
        specs = harness.load_manifest()
        self.assertEqual(["3.18.2", "3.26.0", "3.32.0", "3.33.0"], [spec.version for spec in specs])
        self.assertTrue(all(spec.archive_url.startswith("https://www.sqlite.org/") for spec in specs))
        self.assertTrue(all(len(spec.archive_sha256) == 64 for spec in specs))

    def test_checksum_mismatch_fails_closed(self) -> None:
        archive = self.workspace / "archive.tar.gz"
        archive.write_bytes(b"bad")
        spec = dataclasses.replace(self.spec, archive_size=3, archive_sha256="0" * 64)
        with self.assertRaisesRegex(harness.HarnessError, "SHA-256"):
            harness.verify_archive_file(archive, spec)

    def test_offline_missing_engine_is_unavailable(self) -> None:
        with self.assertRaises(harness.HarnessError) as context:
            harness.download_archive(self.spec, self.workspace / self.spec.archive_name, offline=True)
        self.assertEqual("UNAVAILABLE_ENGINE", context.exception.code)

    def test_redirects_are_rejected(self) -> None:
        handler = harness.RejectRedirects()
        with self.assertRaises(harness.HarnessError) as context:
            handler.redirect_request(None, None, 302, "Found", {}, "https://example.invalid/archive")
        self.assertEqual("UNEXPECTED_REDIRECT", context.exception.code)

    @mock.patch.object(harness.shutil, "which", return_value=None)
    def test_missing_compiler_fails_closed(self, unused_which: mock.Mock) -> None:
        del unused_which
        with self.assertRaises(harness.HarnessError) as context:
            harness.build_engine(
                self.spec,
                self.workspace / self.spec.archive_name,
                self.workspace / "cache",
                force_rebuild=False,
            )
        self.assertEqual("MISSING_COMPILER", context.exception.code)

    def test_wrong_engine_identity_is_rejected(self) -> None:
        with self.assertRaises(harness.HarnessError) as context:
            harness.validate_identity(
                self.spec,
                "3.45.0",
                self.spec.source_id,
                self.spec.version,
                self.spec.source_id,
            )
        self.assertEqual("ENGINE_VERSION_MISMATCH", context.exception.code)

    def test_wrong_source_identity_is_rejected(self) -> None:
        with self.assertRaises(harness.HarnessError) as context:
            harness.validate_identity(
                self.spec,
                self.spec.version,
                "fake source",
                self.spec.version,
                self.spec.source_id,
            )
        self.assertEqual("ENGINE_SOURCE_ID_MISMATCH", context.exception.code)

    def test_fake_host_profile_substitution_breaks_attestation(self) -> None:
        build = self.workspace / "build"
        binary = build / "bin" / "sqlite3"
        library = build / "bin" / "libsqlite3.a"
        driver = build / "bin" / "gate_driver"
        binary.parent.mkdir(parents=True)
        binary.write_bytes(b"exact CLI")
        library.write_bytes(b"exact library")
        driver.write_bytes(b"exact driver")

        def item(path: Path) -> dict[str, str]:
            return {
                "path": str(path.relative_to(build)),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }

        attestation = {
            "schema_version": harness.BUILD_ATTESTATION_SCHEMA,
            "build_recipe_version": harness.BUILD_RECIPE_VERSION,
            "requested_version": self.spec.version,
            "archive_sha256": self.spec.archive_sha256,
            "source_id": self.spec.source_id,
            "gate_driver_sha256": harness.sha256_file(harness.GATE_DRIVER_PATH),
            "compile_flags": [
                flag.replace(str(build / "source"), "sqlite-src")
                for flag in harness.build_flags(build / "source")[0]
            ],
            "link_flags": harness.build_flags(build / "source")[1],
            "artifacts": {
                "sqlite3": item(binary),
                "libsqlite3": item(library),
                "gate_driver": item(driver),
            },
        }
        (build / "build-attestation.json").write_text(json.dumps(attestation), encoding="utf-8")
        harness.verify_build_attestation(build, self.spec)
        binary.write_text("#!/bin/sh\nprintf '3.18.2\\n'\n", encoding="utf-8")
        with self.assertRaises(harness.HarnessError) as context:
            harness.verify_build_attestation(build, self.spec)
        self.assertEqual("BUILD_ARTIFACT_MISMATCH", context.exception.code)

    def test_missing_compile_diagnostics_are_unknown(self) -> None:
        engine = FakeSqlEngine([("PRAGMA compile_options", harness.CommandResult(0, "", ""))])
        self.assertEqual(
            {
                "diagnostics": "UNKNOWN",
                "options": [],
                "reason": "runtime query returned no rows",
            },
            harness.compile_capabilities(engine),  # type: ignore[arg-type]
        )

    def test_supported_fts_probe_passes(self) -> None:
        status, reason, capability = harness.classify_fts_probe(harness.CommandResult(0, "1\n", ""))
        self.assertEqual("PASS", status)
        self.assertIsNone(reason)
        self.assertEqual("SUPPORTED", capability["fts5"])

    def test_unsupported_fts_probe_is_na_only_after_runtime_error(self) -> None:
        status, reason, capability = harness.classify_fts_probe(
            harness.CommandResult(1, "", "Error: no such module: fts5\n")
        )
        self.assertEqual("N/A", status)
        self.assertIn("runtime probe", reason or "")
        self.assertEqual("UNSUPPORTED", capability["fts5"])

    def test_false_fts_na_is_rejected(self) -> None:
        with self.assertRaises(harness.HarnessError) as context:
            harness.classify_fts_probe(harness.CommandResult(1, "", "Error: database is locked\n"))
        self.assertEqual("FTS_PROBE_FAILED", context.exception.code)

    def test_api26_schema_fallback_is_runtime_selected(self) -> None:
        table_info = "\n".join(
            f"{index}\t{name}\tTEXT\t0\t\t{1 if index == 0 else 0}"
            for index, name in enumerate(harness.EXPECTED_ACTIVITY_COLUMNS)
        )
        engine = FakeSqlEngine(
            [
                ("sqlite_schema", harness.CommandResult(1, "", "Error: no such table: sqlite_schema\n")),
                ("sqlite_master", harness.CommandResult(0, "2\n", "")),
                ("table_xinfo", harness.CommandResult(0, "", "")),
                ("table_info", harness.CommandResult(0, table_info + "\n", "")),
            ]
        )
        capabilities = harness.probe_schema_paths(engine, self.workspace / "schema.db")  # type: ignore[arg-type]
        self.assertEqual("sqlite_master", capabilities["schema_catalog"]["selected"])  # type: ignore[index]
        self.assertEqual("table_info", capabilities["column_metadata"]["selected"])  # type: ignore[index]

    def test_interrupted_receipt_success_marker_is_required(self) -> None:
        class Engine:
            def driver(self, command: str, database: Path) -> harness.CommandResult:
                self.command = command
                self.database = database
                return harness.CommandResult(0, "receipt counts looked plausible\n", "")

        with self.assertRaises(harness.HarnessError) as context:
            harness.interrupted_gate(Engine(), self.workspace, {})  # type: ignore[arg-type]
        self.assertEqual("INTERRUPTED_RECEIPT_FAILED", context.exception.code)

    def test_corrupt_database_success_is_rejected(self) -> None:
        with self.assertRaises(harness.HarnessError) as context:
            harness.validate_corruption_results(
                harness.CommandResult(0, "0\n", ""),
                harness.CommandResult(1, "", "open failed"),
                False,
            )
        self.assertEqual("CORRUPTION_NOT_DETECTED", context.exception.code)

    def test_missing_readonly_database_creation_is_rejected(self) -> None:
        with self.assertRaises(harness.HarnessError) as context:
            harness.validate_corruption_results(
                harness.CommandResult(1, "", "Error: file is not a database"),
                harness.CommandResult(0, "readonly=ok", ""),
                True,
            )
        self.assertEqual("OPEN_FAILURE_NOT_DETECTED", context.exception.code)

    def test_large_point_count_mismatch_is_rejected(self) -> None:
        with self.assertRaises(harness.HarnessError) as context:
            harness.validate_large_metrics("99999\t1\t99999\t9999\t1\t99999\n", 100_000)
        self.assertEqual("LARGE_GATE_FAILED", context.exception.code)

    def test_large_point_exact_metrics_pass(self) -> None:
        harness.validate_large_metrics("100000\t1\t100000\t10000\t1\t100000\n", 100_000)


if __name__ == "__main__":
    unittest.main()
