#!/usr/bin/env python3

import unittest
from pathlib import Path

from evidence_lib import (
    API_LEVELS,
    EvidenceError,
    build_union_summary,
    canonical_json_bytes,
    code_points,
    load_writer_contract,
    parse_code_points_strict,
    utf16_length,
    validate_all,
    validate_current_writer_text,
)


ROOT = Path(__file__).resolve().parent


class EvidenceValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.result = validate_all(ROOT)
        cls.toolchain = cls.result["toolchain"]
        cls.evidence = cls.result["evidence"]
        cls.summary = cls.result["summary"]
        cls.writer_provenance = cls.result["writer_provenance"]

    @staticmethod
    def current_writer_inputs() -> tuple[
        dict[str, object], str, str, str
    ]:
        repository_root = ROOT.parent.parent
        compatibility = load_writer_contract(ROOT)["current_compatibility"]
        config_source = (
            repository_root / compatibility["config_relative_path"]
        ).read_text(encoding="utf-8")
        writer_source = (
            repository_root / compatibility["writer_relative_path"]
        ).read_text(encoding="utf-8")
        schema_source = (
            repository_root / compatibility["schema_relative_path"]
        ).read_text(encoding="utf-8")
        return compatibility, config_source, writer_source, schema_source

    def test_candidate_union_is_exact(self) -> None:
        candidate_sets = {
            api_level: {
                row["candidate_name"]
                for row in self.evidence[api_level].candidates
            }
            for api_level in API_LEVELS
        }
        self.assertEqual(
            self.summary["candidate_union_names"],
            sorted(set.union(*candidate_sets.values())),
        )
        self.assertEqual(
            self.summary["candidate_intersection_names"],
            sorted(set.intersection(*candidate_sets.values())),
        )

    def test_union_summary_rendering_is_byte_repeatable(self) -> None:
        first = canonical_json_bytes(
            build_union_summary(self.toolchain, self.evidence)
        )
        second = canonical_json_bytes(
            build_union_summary(self.toolchain, self.evidence)
        )
        self.assertEqual(first, second)
        self.assertEqual(
            first, (ROOT / "evidence/union-summary.json").read_bytes()
        )

    def test_supported_decimal_blocks_strictly_round_trip(self) -> None:
        for api_level, evidence in self.evidence.items():
            formats_by_candidate: dict[str, list[dict[str, str]]] = {}
            for row in evidence.formats:
                if row["source_kind"] == "candidate":
                    formats_by_candidate.setdefault(
                        row["candidate_name"], []
                    ).append(row)
            candidates = {
                row["candidate_name"]: row for row in evidence.candidates
            }
            self.assertEqual(
                candidates["fullwide"]["category"],
                "supported_decimal_output",
            )
            for name, candidate in candidates.items():
                if candidate["category"] != "supported_decimal_output":
                    continue
                rows = formats_by_candidate[name]
                self.assertEqual(6, len(rows), (api_level, name))
                self.assertTrue(
                    all(row["result"] == "round_trip" for row in rows),
                    (api_level, name),
                )
                points = parse_code_points_strict(
                    candidate["description_code_points"]
                )
                if any(point > 0xFFFF for point in points):
                    emitted = {
                        point
                        for row in rows
                        for point in parse_code_points_strict(
                            row["formatted_code_points"]
                        )
                    }
                    self.assertTrue(set(points).issubset(emitted))

    def test_supplementary_definitions_are_explicit_strict_fallbacks(self) -> None:
        api36 = self.evidence[36]
        rows_by_candidate: dict[str, list[dict[str, str]]] = {}
        for row in api36.formats:
            if row["source_kind"] == "candidate":
                rows_by_candidate.setdefault(row["candidate_name"], []).append(row)
        supplementary = [
            row
            for row in api36.candidates
            if row["candidate_kind"] == "decimal"
            and any(
                point > 0xFFFF
                for point in parse_code_points_strict(
                    row["description_code_points"]
                )
            )
        ]
        self.assertGreater(len(supplementary), 0)
        for candidate in supplementary:
            name = candidate["candidate_name"]
            self.assertEqual("unsupported_fallback", candidate["category"])
            self.assertEqual("ascii_fallback", candidate["observed_output"])
            self.assertEqual("true", candidate["fallback"])
            self.assertEqual("true", candidate["strict_round_trip_all"])
            rows = rows_by_candidate[name]
            self.assertEqual(6, len(rows))
            self.assertTrue(
                all(row["result"] == "round_trip" for row in rows),
                name,
            )
            self.assertTrue(
                all(
                    set(row["formatted_text"])
                    <= set("0123456789")
                    for row in rows
                ),
                name,
            )

    def test_thai_and_risky_locales_cover_multiple_years(self) -> None:
        required = {
            "ar_sa",
            "fa_ir",
            "ja_jp_japanese",
            "th_th",
            "th_th_buddhist",
        }
        for api_level, evidence in self.evidence.items():
            rows_by_locale: dict[str, list[dict[str, str]]] = {}
            for row in evidence.formats:
                if row["source_kind"] == "representative_locale":
                    rows_by_locale.setdefault(row["source_id"], []).append(row)
            self.assertTrue(required.issubset(rows_by_locale))
            for locale_id in required:
                rows = rows_by_locale[locale_id]
                self.assertGreaterEqual(
                    len({row["instant_utc"][:4] for row in rows}), 4
                )
                self.assertTrue(all(row["calendar_class"] for row in rows))
                self.assertTrue(all(row["calendar_type"] for row in rows))
                self.assertTrue(
                    all(row["parsed_matches_input"] == "true" for row in rows),
                    (api_level, locale_id),
                )

    def test_metadata_contains_no_runtime_locator_fields(self) -> None:
        forbidden_fragments = (
            "adb_serial",
            "avd_name",
            "host_path",
            "run_timestamp",
            "wall_clock",
        )
        for evidence in self.evidence.values():
            keys = "\n".join(evidence.metadata)
            for fragment in forbidden_fragments:
                self.assertNotIn(fragment, keys)

    def test_immutable_capture_and_current_compatibility_are_distinct(self) -> None:
        capture = self.writer_provenance["capture"]
        current = self.writer_provenance["current"]
        self.assertEqual(
            "07e2df9f92373672da6f02d5ffef3abbf4836882f652473d5822da01627d7cfc",
            capture["config_sha256"],
        )
        self.assertEqual(
            "cd69c8c08315c7b237ec8fc2817db97dda6fa9939f52e0cc6dcc8f3dba662510",
            capture["writer_sha256"],
        )
        self.assertNotEqual(capture["config_sha256"], current["config_sha256"])
        self.assertNotEqual(capture["writer_sha256"], current["writer_sha256"])
        self.assertEqual("default_locale", capture["locale_policy"])
        self.assertEqual("locale_us", current["locale_policy"])
        self.assertEqual("Config.formatTimestamp", current["format_api"])
        self.assertEqual("Config.parseTimestamp", current["parse_api"])
        for evidence in self.evidence.values():
            self.assertEqual(
                capture["config_sha256"],
                evidence.metadata["pin.legacy_config_sha256"],
            )
            self.assertEqual(
                capture["writer_sha256"],
                evidence.metadata["pin.legacy_writer_sha256"],
            )

    def test_current_writer_gate_ignores_surrounding_refactors_but_rejects_contract_changes(
        self,
    ) -> None:
        compatibility, config_source, writer_source, schema_source = (
            self.current_writer_inputs()
        )
        arguments = (
            schema_source,
            compatibility["expected_pattern"],
            compatibility["allowed_locale_policies"],
            compatibility["required_timestamp_columns"],
        )

        unrelated_config = config_source.replace(
            "public final class Config {",
            "/* unrelated class-level documentation */\npublic final class Config {",
            1,
        )
        unrelated_writer = writer_source.replace(
            "public final class SqlLogger",
            "/* unrelated writer documentation */\npublic final class SqlLogger",
            1,
        )
        model = validate_current_writer_text(
            unrelated_config,
            unrelated_writer,
            *arguments,
            require_column_writes=True,
        )
        self.assertEqual("locale_us", model["locale_policy"])

        with self.assertRaises(EvidenceError):
            validate_current_writer_text(
                config_source.replace(
                    '"yyyyMMddHHmmss"',
                    '"yyyy-MM-dd HH:mm:ss"',
                    1,
                ),
                writer_source,
                *arguments,
                require_column_writes=True,
            )
        with self.assertRaises(EvidenceError):
            validate_current_writer_text(
                config_source,
                writer_source.replace(
                    "HEARTRATE REAL",
                    "HEARTRATE INTEGER",
                    1,
                ),
                *arguments,
                require_column_writes=True,
            )
        with self.assertRaises(EvidenceError):
            validate_current_writer_text(
                config_source.replace("Locale.US", "Locale.CANADA", 1),
                writer_source,
                *arguments,
                require_column_writes=True,
            )

    def test_current_writer_gate_rejects_broken_real_writes_with_unused_decoys(
        self,
    ) -> None:
        compatibility, config_source, writer_source, schema_source = (
            self.current_writer_inputs()
        )
        broken_writer = writer_source.replace(
            "values.put(T_TIMESTAMP_UTC, "
            "Config.formatTimestamp(legacyUtcNow()));",
            'values.put(T_TIMESTAMP_UTC, "BROKEN");',
            1,
        ).replace(
            "putDate(values, T_TIMESTAMP_UTC, trackPoint.TimeStampUTC);",
            'values.put(T_TIMESTAMP_UTC, "BROKEN");',
            1,
        )
        decoy = """
    private static final String UNUSED_TIMESTAMP_DECOY =
            "values.put(T_TIMESTAMP_UTC, Config.formatTimestamp(date));";
    // values.put(T_TIMESTAMP_UTC, Config.formatTimestamp(date));
    private static final class UnusedTimestampDecoy {
        private static void writeTimestamp(
                ContentValues values,
                Date date) {
            values.put(T_TIMESTAMP_UTC, Config.formatTimestamp(date));
        }
    }

"""
        broken_writer = broken_writer.replace(
            "    private static void putDate(",
            decoy + "    private static void putDate(",
            1,
        )
        self.assertEqual(2, broken_writer.count('"BROKEN"'))
        with self.assertRaises(EvidenceError):
            validate_current_writer_text(
                config_source,
                broken_writer,
                schema_source,
                compatibility["expected_pattern"],
                compatibility["allowed_locale_policies"],
                compatibility["required_timestamp_columns"],
                require_column_writes=True,
            )

    def test_current_config_gate_rejects_broken_helper_with_nested_decoy(
        self,
    ) -> None:
        compatibility, config_source, writer_source, schema_source = (
            self.current_writer_inputs()
        )
        broken_config = config_source.replace(
            "return TIMESTAMP_FORMAT.format(date);",
            'return "BROKEN";',
            1,
        )
        decoy = """
    private static final String UNUSED_FORMATTER_DECOY =
            "return TIMESTAMP_FORMAT.format(date);";
    // return TIMESTAMP_FORMAT.format(date);
    private static final class UnusedFormatterDecoy {
        private static String formatTimestamp(Date date) {
            return TIMESTAMP_FORMAT.format(date);
        }
    }

"""
        broken_config = broken_config.replace(
            "    private Config() {",
            decoy + "    private Config() {",
            1,
        )
        with self.assertRaises(EvidenceError):
            validate_current_writer_text(
                broken_config,
                writer_source,
                schema_source,
                compatibility["expected_pattern"],
                compatibility["allowed_locale_policies"],
                compatibility["required_timestamp_columns"],
                require_column_writes=True,
            )


class UnicodeHelperTest(unittest.TestCase):
    def test_code_points_preserve_fullwidth_and_supplementary_digits(self) -> None:
        value = "\uff10\U0001d7ce"
        self.assertEqual(code_points(value), "U+FF10 U+1D7CE")
        self.assertEqual(len(value), 2)
        self.assertEqual(utf16_length(value), 3)

    def test_invalid_code_point_tokens_are_rejected(self) -> None:
        with self.assertRaises(ValueError):
            parse_code_points_strict("U+D800")
        with self.assertRaises(ValueError):
            parse_code_points_strict("U+110000")
        with self.assertRaises(ValueError):
            parse_code_points_strict("0xFF10")


if __name__ == "__main__":
    unittest.main()
