#!/usr/bin/env python3

import unittest
from pathlib import Path

from evidence_lib import (
    API_LEVELS,
    build_union_summary,
    canonical_json_bytes,
    code_points,
    parse_code_points_strict,
    utf16_length,
    validate_all,
)


ROOT = Path(__file__).resolve().parent


class EvidenceValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.result = validate_all(ROOT)
        cls.toolchain = cls.result["toolchain"]
        cls.evidence = cls.result["evidence"]
        cls.summary = cls.result["summary"]

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
