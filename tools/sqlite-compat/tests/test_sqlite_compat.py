from __future__ import annotations

import dataclasses
import hashlib
import io
import json
import os
import re
import shutil
import sys
import tarfile
import unittest
import uuid
from pathlib import Path
from unittest import mock


TOOL_DIR = Path(__file__).resolve().parents[1]
WORKFLOW_PATH = TOOL_DIR.parents[1] / ".github" / "workflows" / "sqlite-exact-compat.yml"
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

    def write_executable(self, path: Path, body: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("#!/bin/sh\nset -eu\n" + body, encoding="utf-8")
        path.chmod(0o700)

    def write_source_archive(
        self,
        path: Path,
        spec: harness.EngineSpec,
        extra_members: list[tuple[tarfile.TarInfo, bytes | None]] | None = None,
    ) -> harness.EngineSpec:
        sqlite_source = (
            f'#define SQLITE_VERSION "{spec.version}"\n'
            f'#define SQLITE_SOURCE_ID "{spec.source_id}"\n'
        ).encode("utf-8")
        sources = {
            "sqlite3.c": sqlite_source,
            "sqlite3.h": b"/* sqlite3 header */\n",
            "sqlite3ext.h": b"/* sqlite3 extension header */\n",
            "shell.c": b"/* sqlite3 shell */\n",
        }
        with tarfile.open(path, mode="w:gz") as archive:
            root = tarfile.TarInfo(spec.archive_root)
            root.type = tarfile.DIRTYPE
            root.mode = 0o755
            archive.addfile(root)
            for filename, data in sources.items():
                member = tarfile.TarInfo(f"{spec.archive_root}/{filename}")
                member.size = len(data)
                member.mode = 0o644
                archive.addfile(member, io.BytesIO(data))
            for member, data in extra_members or []:
                archive.addfile(member, None if data is None else io.BytesIO(data))
        payload = path.read_bytes()
        return dataclasses.replace(
            spec,
            archive_size=len(payload),
            archive_sha256=hashlib.sha256(payload).hexdigest(),
        )


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
                self.workspace,
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

    def test_cached_wrapper_and_recomputed_attestation_are_rejected_without_execution(self) -> None:
        cache = self.workspace / "cache"
        build = cache / "builds" / self.spec.release_code
        binary = build / "bin" / "sqlite3"
        library = build / "bin" / "libsqlite3.a"
        driver = build / "bin" / "gate_driver"
        newer_cli = self.workspace / "sqlite3-3.33.0"
        newer_driver = self.workspace / "gate-driver-3.33.0"
        marker = self.workspace / "cached-wrapper-executed"
        newer_spec = harness.load_manifest()[-1]
        self.write_executable(newer_cli, f"printf delegated > {marker!s}\n")
        self.write_executable(newer_driver, f"printf delegated > {marker!s}\n")
        self.write_executable(
            binary,
            "case \"$*\" in\n"
            f"  *sqlite_version*) printf '%s\\t%s\\n' '{self.spec.version}' '{self.spec.source_id}' ;;\n"
            f"  *) exec '{newer_cli}' \"$@\" ;;\n"
            "esac\n",
        )
        self.write_executable(
            driver,
            "if [ \"${1:-}\" = identity ]; then\n"
            f"  printf 'version=%s\\nsource_id=%s\\n' '{self.spec.version}' '{self.spec.source_id}'\n"
            "else\n"
            f"  exec '{newer_driver}' \"$@\"\n"
            "fi\n",
        )
        library.write_bytes(b"forged cached static library")
        attestation = {
            "schema_version": 1,
            "build_recipe_version": 1,
            "requested_version": self.spec.version,
            "archive_sha256": self.spec.archive_sha256,
            "source_id": self.spec.source_id,
            "gate_driver_sha256": harness.sha256_file(harness.GATE_DRIVER_PATH),
            "compile_flags": ["forged-and-locally-recomputed"],
            "link_flags": [],
            "artifacts": {
                "sqlite3": {
                    "path": "bin/sqlite3",
                    "sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                },
                "libsqlite3": {
                    "path": "bin/libsqlite3.a",
                    "sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
                },
                "gate_driver": {
                    "path": "bin/gate_driver",
                    "sha256": hashlib.sha256(driver.read_bytes()).hexdigest(),
                },
            },
            "claimed_delegate_version": newer_spec.version,
        }
        (build / "build-attestation.json").write_text(json.dumps(attestation), encoding="utf-8")

        with self.assertRaises(harness.HarnessError) as context:
            harness.run_harness(
                [self.spec],
                cache,
                self.workspace / "report",
                work_dir=self.workspace / "work",
                offline=True,
                point_count=1,
                known_specs=[self.spec],
            )
        self.assertEqual("UNTRUSTED_CACHE_CONTENT", context.exception.code)
        self.assertFalse(marker.exists())

    def test_archive_replacement_and_forged_metadata_fail_closed(self) -> None:
        cache = self.workspace / "cache"
        archive_dir = cache / "archives"
        archive_dir.mkdir(parents=True)
        official = b"official"
        replacement = b"forged!!"
        spec = dataclasses.replace(
            self.spec,
            archive_size=len(official),
            archive_sha256=hashlib.sha256(official).hexdigest(),
        )
        archive = archive_dir / spec.archive_name
        archive.write_bytes(replacement)
        metadata = archive_dir / f"{spec.archive_name}.metadata.json"
        metadata.write_text(
            json.dumps({"sha256": hashlib.sha256(replacement).hexdigest()}),
            encoding="utf-8",
        )

        with self.assertRaises(harness.HarnessError) as context:
            harness.prepare_archive_cache(cache, [spec])
        self.assertEqual("UNTRUSTED_CACHE_CONTENT", context.exception.code)
        metadata.unlink()
        with self.assertRaises(harness.HarnessError) as context:
            harness.verify_archive_file(archive, spec)
        self.assertEqual("CHECKSUM_MISMATCH", context.exception.code)

    def test_symlink_executable_is_rejected_without_execution(self) -> None:
        target = self.workspace / "target"
        marker = self.workspace / "symlink-executed"
        self.write_executable(target, f"printf executed > {marker!s}\n")
        link = self.workspace / "sqlite3"
        link.symlink_to(target)
        home = self.workspace / "home"
        home.mkdir()
        with self.assertRaises(harness.HarnessError) as context:
            harness.run_command([str(link)], cwd=self.workspace, home=home)
        self.assertEqual("UNSAFE_EXECUTABLE", context.exception.code)
        self.assertFalse(marker.exists())

    def test_path_shadow_is_rejected_without_execution(self) -> None:
        shadow = self.workspace / "shadow" / "sqlite3"
        marker = self.workspace / "path-shadow-executed"
        self.write_executable(shadow, f"printf executed > {marker!s}\n")
        home = self.workspace / "home"
        home.mkdir()
        with mock.patch.dict(os.environ, {"PATH": str(shadow.parent)}):
            with self.assertRaises(harness.HarnessError) as context:
                harness.run_command(["sqlite3"], cwd=self.workspace, home=home)
        self.assertEqual("PATH_EXECUTION_FORBIDDEN", context.exception.code)
        self.assertFalse(marker.exists())

    def test_archive_traversal_member_is_rejected(self) -> None:
        archive = self.workspace / self.spec.archive_name
        traversal = tarfile.TarInfo(f"{self.spec.archive_root}/../../escaped")
        traversal.size = 7
        spec = self.write_source_archive(archive, self.spec, [(traversal, b"escaped")])
        with self.assertRaises(harness.HarnessError) as context:
            harness.extract_source(spec, archive, self.workspace / "source")
        self.assertEqual("UNSAFE_ARCHIVE_MEMBER", context.exception.code)
        self.assertFalse((self.workspace / "escaped").exists())

    def test_archive_symlink_member_is_rejected(self) -> None:
        archive = self.workspace / self.spec.archive_name
        link = tarfile.TarInfo(f"{self.spec.archive_root}/sqlite-link")
        link.type = tarfile.SYMTYPE
        link.linkname = "sqlite3.c"
        spec = self.write_source_archive(archive, self.spec, [(link, None)])
        with self.assertRaises(harness.HarnessError) as context:
            harness.extract_source(spec, archive, self.workspace / "source")
        self.assertEqual("UNSAFE_ARCHIVE_MEMBER", context.exception.code)

    def test_archive_special_file_member_is_rejected(self) -> None:
        archive = self.workspace / self.spec.archive_name
        fifo = tarfile.TarInfo(f"{self.spec.archive_root}/unsafe-fifo")
        fifo.type = tarfile.FIFOTYPE
        spec = self.write_source_archive(archive, self.spec, [(fifo, None)])
        with self.assertRaises(harness.HarnessError) as context:
            harness.extract_source(spec, archive, self.workspace / "source")
        self.assertEqual("UNSAFE_ARCHIVE_MEMBER", context.exception.code)

    def test_wrong_fresh_output_identity_fails_before_gates(self) -> None:
        build = self.workspace / "fresh-build"
        binary = build / "bin" / "sqlite3"
        driver = build / "bin" / "gate_driver"
        (build / "home").mkdir(parents=True)
        wrong = harness.load_manifest()[-1]
        binary.parent.mkdir()
        cli_source = build / "wrong-cli.c"
        driver_source = build / "wrong-driver.c"
        cli_identity = json.dumps(f"{wrong.version}\t{wrong.source_id}\n")
        driver_identity = json.dumps(f"version={wrong.version}\nsource_id={wrong.source_id}\n")
        cli_source.write_text(
            "#include <stdio.h>\n"
            f"int main(void) {{ fputs({cli_identity}, stdout); return 0; }}\n",
            encoding="utf-8",
        )
        driver_source.write_text(
            "#include <stdio.h>\n"
            f"int main(void) {{ fputs({driver_identity}, stdout); return 0; }}\n",
            encoding="utf-8",
        )
        cc = harness.resolve_system_tool("cc", missing_code="MISSING_COMPILER")
        for source, output in ((cli_source, binary), (driver_source, driver)):
            result = harness.run_command(
                [str(cc), str(source), "-o", str(output)],
                cwd=build,
                home=build / "home",
            )
            self.assertEqual(0, result.returncode, result.stderr)
            output.chmod(0o700)
        engine = harness.ExactEngine(build, binary, driver)
        with self.assertRaises(harness.HarnessError) as context:
            harness.assert_exact_identity(self.spec, engine)
        self.assertEqual("ENGINE_VERSION_MISMATCH", context.exception.code)

    def test_fresh_build_tree_is_private_unique_and_deleted(self) -> None:
        work = self.workspace / "work"
        with harness.fresh_build_tree(work, self.spec) as first:
            self.assertTrue(first.name.startswith(f"sqlite-{self.spec.release_code}-"))
            self.assertEqual(os.geteuid(), first.stat().st_uid)
            (first / "sentinel").write_text("fresh", encoding="utf-8")
            first_path = first
        self.assertFalse(first_path.exists())
        self.assertFalse(work.exists())
        with harness.fresh_build_tree(work, self.spec) as second:
            self.assertNotEqual(first_path, second)
        self.assertFalse(second.exists())

    def test_workflow_actions_are_pinned_to_full_commit_shas(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        uses = re.findall(r"^\s*-?\s*uses:\s*([^@\s]+)@([^\s#]+)(?:\s+#\s*(\S+))?\s*$", workflow, re.MULTILINE)
        self.assertGreater(len(uses), 0)
        expected = {
            "actions/checkout": ("11bd71901bbe5b1630ceea73d27597364c9af683", "v4.2.2"),
            "actions/upload-artifact": ("ea165f8d65b6e75b540449e92b4886f43607fa02", "v4.6.2"),
        }
        self.assertEqual(set(expected), {action for action, _, _ in uses})
        for action, revision, comment in uses:
            self.assertRegex(revision, r"^[0-9a-f]{40}$")
            self.assertEqual(expected[action], (revision, comment))

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
