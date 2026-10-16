#!/usr/bin/env python3

from pathlib import Path

from evidence_lib import EvidenceError, validate_all


def main() -> int:
    root = Path(__file__).resolve().parent
    try:
        result = validate_all(root)
    except (EvidenceError, OSError) as error:
        print(f"ERROR: {error}")
        return 1

    summary = result["summary"]
    for api_level, counts in summary["apis"].items():
        categories = ", ".join(
            f"{name}={count}"
            for name, count in counts["category_counts"].items()
        )
        print(
            f"API {api_level}: candidates={counts['unique_candidate_count']}, "
            f"formats={counts['format_row_count']}, {categories}"
        )
    print(
        "Union: "
        f"candidates={len(summary['candidate_union_names'])}, "
        f"decimal-blocks={len(summary['emitted_decimal_zero_code_point_union'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
