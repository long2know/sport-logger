#!/usr/bin/env python3

from __future__ import annotations

import csv
import gzip
import hashlib
import io
import json
import re
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Mapping, Sequence


API_LEVELS = (26, 36)
CURRENT_SCHEMA_RELATIVE_PATH = "tools/sqlite-compat/legacy_schema.sql"
CURRENT_WRITER_LOCALE_POLICIES = ("locale_us",)
REQUIRED_TIMESTAMP_COLUMN_SYMBOLS = (
    "A_END_TIME_UTC",
    "A_START_TIME_UTC",
    "T_TIMESTAMP_UTC",
)

ADVERTISED_HEADER = (
    "sorted_index",
    "value_kind",
    "candidate_name",
    "occurrence_index",
)

CANDIDATE_HEADER = (
    "candidate_name",
    "resolved_name",
    "locale_tag",
    "candidate_kind",
    "radix",
    "algorithmic",
    "description_text",
    "description_code_points",
    "description_digit_values",
    "locale_numbering_system_name",
    "number_format_class",
    "formatter_zero_digit_code_point",
    "category",
    "observed_output",
    "fallback",
    "observed_digit_code_points",
    "strict_round_trip_all",
    "row_count",
    "error_stage",
    "error_class",
    "attempts",
)

FORMAT_HEADER = (
    "source_kind",
    "source_id",
    "locale_tag",
    "candidate_name",
    "instant_id",
    "instant_utc",
    "epoch_ms",
    "formatted_text",
    "formatted_code_points",
    "formatted_code_point_count",
    "formatted_utf16_length",
    "formatter_class",
    "formatter_pattern",
    "timezone_id",
    "calendar_class",
    "calendar_type",
    "number_format_class",
    "formatter_zero_digit_code_point",
    "parse_index",
    "parse_error_index",
    "parse_consumed",
    "parsed_epoch_ms",
    "parsed_matches_input",
    "reformat_text",
    "reformat_code_points",
    "reformat_equal",
    "result",
)

METADATA_HEADER = ("key", "value")

REQUIRED_METADATA_KEYS = {
    "abi.list",
    "android.api_level",
    "android.release",
    "build.fingerprint",
    "candidate.advertised_count",
    "candidate.algorithm",
    "candidate.algorithm.detail",
    "candidate.duplicate_count",
    "candidate.null_count",
    "candidate.sorted_occurrence_sha256",
    "candidate.unique_count",
    "default.locale.observed",
    "default.locale.requested",
    "fixed_instant.count",
    "formatter.class",
    "formatter.constructor",
    "formatter.pattern",
    "icu.version",
    "java.class.version",
    "java.runtime.name",
    "java.runtime.version",
    "java.specification.version",
    "java.vendor",
    "java.version",
    "java.vm.name",
    "java.vm.vendor",
    "java.vm.version",
    "output.encoding",
    "pin.build_tools_package",
    "pin.build_tools_revision",
    "pin.cmdline_tools_package",
    "pin.cmdline_tools_revision",
    "pin.compile_sdk_package",
    "pin.compile_sdk_revision",
    "pin.emulator_package",
    "pin.emulator_reported_version",
    "pin.emulator_revision",
    "pin.host_java_runtime_version",
    "pin.host_java_vendor",
    "pin.host_java_version",
    "pin.host_javac_version",
    "pin.legacy_config_sha256",
    "pin.legacy_writer_sha256",
    "pin.manifest_sha256",
    "pin.platform_tools_package",
    "pin.platform_tools_revision",
    "pin.probe_source_sha256",
    "pin.system_image_package",
    "pin.system_image_revision",
    "pin.toolchain_sha256",
    "probe.instrumentation_class",
    "probe.package",
    "representative_locale.count",
    "timezone.observed",
    "timezone.requested",
    "unicode.version",
}

CATEGORY_VALUES = {
    "algorithmic_non_decimal",
    "error",
    "supported_decimal_output",
    "unsupported_fallback",
}

FORBIDDEN_EVIDENCE_PATTERNS = (
    (re.compile(r"(?i)(?:^|[\t ])/(?:home|data|Users|private|var/tmp|tmp)/"), "host path"),
    (re.compile(r"(?i)[A-Z]:\\\\"), "Windows host path"),
    (re.compile(r"\bemulator-\d+\b"), "adb serial"),
    (re.compile(r"(?i)\.avd(?:/|\\|$)"), "AVD path"),
    (re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b", re.I), "UUID"),
    (re.compile(r"(?i)[A-Z_]+(?:KEY|TOKEN|SECRET)=[^\s]+"), "secret assignment"),
    (re.compile(r"\b(?:ghp|github_pat|sk-proj)-[A-Za-z0-9_-]+\b"), "credential"),
)


class EvidenceError(RuntimeError):
    pass


@dataclass(frozen=True)
class ApiEvidence:
    api_level: int
    metadata_rows: tuple[dict[str, str], ...]
    metadata: Mapping[str, str]
    advertised: tuple[dict[str, str], ...]
    candidates: tuple[dict[str, str], ...]
    formats: tuple[dict[str, str], ...]


def canonical_json_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def load_toolchain(root: Path) -> dict[str, object]:
    path = root / "toolchain.json"
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EvidenceError(f"{path}: invalid UTF-8 JSON: {error}") from error
    if raw != canonical_json_bytes(value):
        raise EvidenceError(f"{path}: JSON is not canonical")
    return value


def load_writer_contract(root: Path) -> dict[str, object]:
    path = root / "writer-contract.json"
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EvidenceError(f"{path}: invalid UTF-8 JSON: {error}") from error
    if raw != canonical_json_bytes(value):
        raise EvidenceError(f"{path}: JSON is not canonical")
    if not isinstance(value, dict) or value.get("schema_version") != 1:
        raise EvidenceError(f"{path}: unsupported schema")
    return value


def resolve_safe_file(
    base: Path,
    relative_path: str,
    boundary: Path,
    context: str,
) -> Path:
    relative = Path(relative_path)
    if relative.is_absolute():
        raise EvidenceError(f"{context}: absolute path is prohibited")
    resolved = (base / relative).resolve()
    boundary = boundary.resolve()
    if (
        resolved == boundary
        or boundary not in resolved.parents
        or resolved.is_symlink()
        or not resolved.is_file()
    ):
        raise EvidenceError(f"{context}: path is unavailable or unsafe")
    return resolved


def read_captured_source(path: Path) -> bytes:
    try:
        value = gzip.decompress(path.read_bytes())
    except (OSError, EOFError) as error:
        raise EvidenceError(f"{path}: invalid captured-source gzip") from error
    if not value:
        raise EvidenceError(f"{path}: captured source is empty")
    return value


def strip_java_comments(value: str) -> str:
    output: list[str] = []
    index = 0
    state = "code"
    while index < len(value):
        character = value[index]
        next_character = value[index + 1] if index + 1 < len(value) else ""
        if state == "code":
            if character == '"':
                state = "string"
                output.append(character)
            elif character == "'":
                state = "character"
                output.append(character)
            elif character == "/" and next_character == "/":
                state = "line_comment"
                output.extend((" ", " "))
                index += 1
            elif character == "/" and next_character == "*":
                state = "block_comment"
                output.extend((" ", " "))
                index += 1
            else:
                output.append(character)
        elif state == "string":
            output.append(character)
            if character == "\\" and next_character:
                output.append(next_character)
                index += 1
            elif character == '"':
                state = "code"
        elif state == "character":
            output.append(character)
            if character == "\\" and next_character:
                output.append(next_character)
                index += 1
            elif character == "'":
                state = "code"
        elif state == "line_comment":
            output.append("\n" if character == "\n" else " ")
            if character == "\n":
                state = "code"
        else:
            output.append("\n" if character == "\n" else " ")
            if character == "*" and next_character == "/":
                output.append(" ")
                index += 1
                state = "code"
        index += 1
    if state in {"string", "character", "block_comment"}:
        raise EvidenceError("Java source contains an unterminated token")
    return "".join(output)


def mask_java_non_code(value: str) -> str:
    value = strip_java_comments(value)
    output: list[str] = []
    index = 0
    state = "code"
    while index < len(value):
        character = value[index]
        next_character = value[index + 1] if index + 1 < len(value) else ""
        if state == "code":
            if character == '"':
                state = "string"
                output.append(" ")
            elif character == "'":
                state = "character"
                output.append(" ")
            else:
                output.append(character)
        else:
            output.append("\n" if character == "\n" else " ")
            if character == "\\" and next_character:
                output.append("\n" if next_character == "\n" else " ")
                index += 1
            elif (
                state == "string"
                and character == '"'
                or state == "character"
                and character == "'"
            ):
                state = "code"
        index += 1
    return "".join(output)


def find_matching_java_delimiter(
    source: str,
    opening: int,
    opening_character: str,
    closing_character: str,
) -> int:
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == opening_character:
            depth += 1
        elif source[index] == closing_character:
            depth -= 1
            if depth == 0:
                return index
    raise EvidenceError(
        f"Java source has an unterminated {opening_character!r} delimiter"
    )


def extract_java_class_span(
    source: str,
    class_name: str,
) -> tuple[int, int]:
    masked = mask_java_non_code(source)
    matches = list(
        re.finditer(
            rf"\bpublic\s+(?:final\s+)?class\s+"
            rf"{re.escape(class_name)}\b",
            masked,
        )
    )
    if len(matches) != 1:
        raise EvidenceError(
            f"Java source must define exactly one public class {class_name}"
        )
    opening = masked.find("{", matches[0].end())
    if opening < 0:
        raise EvidenceError(f"Java class {class_name} has no body")
    return opening, find_matching_java_delimiter(
        masked, opening, "{", "}"
    )


def java_brace_depth(source: str, start: int, end: int) -> int:
    return source.count("{", start, end) - source.count("}", start, end)


def extract_java_top_level_method(
    source: str,
    class_name: str,
    method_name: str,
    parameter_pattern: str,
) -> tuple[str, str]:
    masked = mask_java_non_code(source)
    class_opening, class_closing = extract_java_class_span(
        source, class_name
    )
    pattern = re.compile(
        rf"\b{re.escape(method_name)}\s*\("
        rf"(?P<parameters>{parameter_pattern})\)\s*"
        rf"(?:throws\s+[A-Za-z0-9_.$,\s<>]+)?\{{"
    )
    matches = [
        match
        for match in pattern.finditer(
            masked, class_opening + 1, class_closing
        )
        if java_brace_depth(
            masked, class_opening + 1, match.start()
        )
        == 0
    ]
    if len(matches) != 1:
        raise EvidenceError(
            f"{class_name}.{method_name} must match its reviewed signature "
            "exactly once"
        )
    match = matches[0]
    body_opening = masked.rfind("{", match.start(), match.end())
    body_closing = find_matching_java_delimiter(
        masked, body_opening, "{", "}"
    )
    return (
        source[match.start("parameters"):match.end("parameters")],
        source[body_opening + 1:body_closing],
    )


def top_level_java_class_matches(
    source: str,
    class_name: str,
    pattern: re.Pattern[str],
) -> tuple[re.Match[str], ...]:
    stripped = strip_java_comments(source)
    masked = mask_java_non_code(source)
    class_opening, class_closing = extract_java_class_span(
        source, class_name
    )
    return tuple(
        match
        for match in pattern.finditer(
            stripped, class_opening + 1, class_closing
        )
        if masked[match.start()] == stripped[match.start()]
        and java_brace_depth(
            masked, class_opening + 1, match.start()
        )
        == 0
    )


def compact_java_code(value: str) -> str:
    return re.sub(r"\s+", "", mask_java_non_code(value))


JAVA_STRING_TOKEN = r'"(?:\\.|[^"\\])*"'


def decode_java_string(token: str) -> str:
    try:
        value = json.loads(token)
    except json.JSONDecodeError as error:
        raise EvidenceError(f"invalid Java string literal {token!r}") from error
    if not isinstance(value, str):
        raise EvidenceError("Java string literal did not decode to text")
    return value


def java_string_constants(source: str) -> dict[str, str]:
    constants: dict[str, str] = {}
    pattern = re.compile(
        rf"\bstatic\s+final\s+String\s+([A-Za-z_][A-Za-z0-9_]*)"
        rf"\s*=\s*({JAVA_STRING_TOKEN})\s*;"
    )
    for match in pattern.finditer(strip_java_comments(source)):
        constants[match.group(1)] = decode_java_string(match.group(2))
    return constants


def evaluate_java_string_concatenation(
    expression: str,
    constants: Mapping[str, str],
) -> str:
    token_pattern = re.compile(
        rf"{JAVA_STRING_TOKEN}|[A-Za-z_][A-Za-z0-9_]*|\+|\s+"
    )
    parts: list[str] = []
    position = 0
    for match in token_pattern.finditer(expression):
        if match.start() != position:
            raise EvidenceError("execSQL expression contains a non-constant token")
        token = match.group(0)
        position = match.end()
        if token.isspace() or token == "+":
            continue
        if token.startswith('"'):
            parts.append(decode_java_string(token))
        elif token in constants:
            parts.append(constants[token])
        else:
            raise EvidenceError(f"execSQL expression uses unknown constant {token}")
    if position != len(expression):
        raise EvidenceError("execSQL expression contains trailing non-constant text")
    return "".join(parts)


def normalize_sql(value: str) -> str:
    normalized = re.sub(r"\s+", " ", value).strip()
    normalized = re.sub(r"\s*([(),;])\s*", r"\1", normalized)
    return normalized.upper()


def extract_java_call_arguments(
    source: str,
    method_name: str,
) -> tuple[str, ...]:
    arguments: list[str] = []
    pattern = re.compile(
        rf"\b[A-Za-z_][A-Za-z0-9_]*\.{re.escape(method_name)}\s*\("
    )
    for match in pattern.finditer(source):
        opening = source.find("(", match.start())
        depth = 1
        state = "code"
        index = opening + 1
        while index < len(source):
            character = source[index]
            next_character = source[index + 1] if index + 1 < len(source) else ""
            if state == "code":
                if character == '"':
                    state = "string"
                elif character == "'":
                    state = "character"
                elif character == "(":
                    depth += 1
                elif character == ")":
                    depth -= 1
                    if depth == 0:
                        arguments.append(source[opening + 1:index])
                        break
            elif state == "string":
                if character == "\\" and next_character:
                    index += 1
                elif character == '"':
                    state = "code"
            else:
                if character == "\\" and next_character:
                    index += 1
                elif character == "'":
                    state = "code"
            index += 1
        else:
            raise EvidenceError(f"Java call {method_name} has unbalanced parentheses")
    return tuple(arguments)


def extract_create_table_statements(source: str) -> tuple[str, ...]:
    stripped = strip_java_comments(source)
    constants = java_string_constants(stripped)
    statements: list[str] = []
    for expression in extract_java_call_arguments(stripped, "execSQL"):
        try:
            statement = evaluate_java_string_concatenation(
                expression, constants
            )
        except EvidenceError:
            continue
        if re.match(r"\s*CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\b", statement, re.I):
            statements.append(normalize_sql(statement))
    return tuple(sorted(statements))


def expected_create_table_statements(schema_source: str) -> tuple[str, ...]:
    statements = re.findall(
        r"CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\b.*?;",
        schema_source,
        flags=re.IGNORECASE | re.DOTALL,
    )
    return tuple(sorted(normalize_sql(statement) for statement in statements))


def extract_formatter_model(
    config_source: str,
    expected_pattern: str,
    allowed_locale_policies: Sequence[str],
    *,
    require_helpers: bool,
) -> dict[str, str]:
    assignment_pattern = re.compile(
        rf"\b(?P<visibility>public|private|protected)\s+static\s+final\s+"
        rf"DateFormat\s+(?P<field>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*"
        rf"new\s+SimpleDateFormat\s*\(\s*"
        rf"(?P<pattern>{JAVA_STRING_TOKEN})\s*"
        rf"(?P<locale>,\s*[^)]*?)?\)\s*;"
    )
    matches = [
        match
        for match in top_level_java_class_matches(
            config_source, "Config", assignment_pattern
        )
        if decode_java_string(match.group("pattern")) == expected_pattern
    ]
    if len(matches) != 1:
        raise EvidenceError(
            "current Config.java must define exactly one compatible timestamp formatter"
        )
    match = matches[0]
    field = match.group("field")
    trailing = re.sub(r"\s+", "", match.group("locale") or "")
    if not trailing:
        locale_policy = "default_locale"
        constructor = "java.text.SimpleDateFormat(String)"
    elif trailing == ",Locale.US":
        locale_policy = "locale_us"
        constructor = "java.text.SimpleDateFormat(String,java.util.Locale)"
    else:
        raise EvidenceError(
            "current Config.java uses an unsupported timestamp locale policy"
        )
    if locale_policy not in allowed_locale_policies:
        raise EvidenceError(
            f"current timestamp locale policy {locale_policy!r} is not allowed"
        )

    if not require_helpers:
        if match.group("visibility") != "public":
            raise EvidenceError(
                "captured Config.java formatter field is not public"
            )
        format_api = f"Config.{field}.format"
        parse_api = f"Config.{field}.parse"
    else:
        identifier = r"[A-Za-z_][A-Za-z0-9_]*"
        format_parameters, format_body = extract_java_top_level_method(
            config_source,
            "Config",
            "formatTimestamp",
            rf"\s*Date\s+{identifier}\s*",
        )
        parse_parameters, parse_body = extract_java_top_level_method(
            config_source,
            "Config",
            "parseTimestamp",
            rf"\s*String\s+{identifier}\s*",
        )
        format_parameter = re.search(
            rf"({identifier})\s*$", format_parameters
        ).group(1)
        parse_parameter = re.search(
            rf"({identifier})\s*$", parse_parameters
        ).group(1)
        if compact_java_code(format_body) != (
            f"return{field}.format({format_parameter});"
        ):
            raise EvidenceError(
                "Config.formatTimestamp no longer directly uses the "
                "compatible formatter"
            )
        if compact_java_code(parse_body) != (
            f"return{field}.parse({parse_parameter});"
        ):
            raise EvidenceError(
                "Config.parseTimestamp no longer directly uses the "
                "compatible formatter"
            )
        format_api = "Config.formatTimestamp"
        parse_api = "Config.parseTimestamp"
    return {
        "constructor": constructor,
        "format_api": format_api,
        "locale_policy": locale_policy,
        "parse_api": parse_api,
        "pattern": expected_pattern,
    }


def java_parameter_names(parameters: str) -> tuple[str, ...]:
    return tuple(
        re.search(
            r"([A-Za-z_][A-Za-z0-9_]*)\s*$", parameter
        ).group(1)
        for parameter in parameters.split(",")
    )


def java_qualified_name_pattern(qualified_name: str) -> str:
    return r"\s*\.\s*".join(
        re.escape(part) for part in qualified_name.split(".")
    )


def exact_java_call(expression: str, qualified_name: str) -> bool:
    masked = mask_java_non_code(expression)
    match = re.match(
        rf"\s*{java_qualified_name_pattern(qualified_name)}\s*\(",
        masked,
    )
    if match is None:
        return False
    opening = masked.rfind("(", match.start(), match.end())
    closing = find_matching_java_delimiter(masked, opening, "(", ")")
    return not masked[closing + 1:].strip()


def require_sink_values(
    body: str,
    method_name: str,
    pattern: str,
) -> str:
    matches = re.findall(
        pattern, mask_java_non_code(body), flags=re.DOTALL
    )
    if len(matches) != 1:
        raise EvidenceError(
            f"SqlLogger.{method_name} legacy table sink changed"
        )
    return matches[0]


def validate_timestamp_column_write(
    body: str,
    method_name: str,
    values: str,
    column: str,
    format_api: str,
) -> None:
    masked = mask_java_non_code(body)
    if len(
        re.findall(
            rf"\bContentValues\s+{re.escape(values)}\s*=\s*"
            rf"new\s+ContentValues\s*\(\s*\)\s*;",
            masked,
        )
    ) != 1:
        raise EvidenceError(
            f"SqlLogger.{method_name} no longer constructs the "
            "ContentValues sent to the database"
        )
    direct = re.findall(
        rf"\b{re.escape(values)}\s*\.\s*put\s*\(\s*{re.escape(column)}"
        rf"\s*,\s*(.*?)\)\s*;",
        masked,
        flags=re.DOTALL,
    )
    helpers = re.findall(
        rf"(?<![A-Za-z0-9_.])putDate\s*\(\s*{re.escape(values)}"
        rf"\s*,\s*{re.escape(column)}\s*,.*?\)\s*;",
        masked,
        flags=re.DOTALL,
    )
    nulls = re.findall(
        rf"\b{re.escape(values)}\s*\.\s*putNull\s*\(\s*"
        rf"{re.escape(column)}\s*\)\s*;",
        masked,
    )
    if len(direct) + len(helpers) + len(nulls) != 1:
        raise EvidenceError(
            f"SqlLogger.{method_name} must write timestamp column "
            f"{column} exactly once on the database ContentValues"
        )
    if helpers:
        return
    if len(direct) != 1 or not exact_java_call(direct[0], format_api):
        raise EvidenceError(
            f"SqlLogger.{method_name} writes timestamp column {column} "
            f"without {format_api}"
        )


def validate_current_sql_logger(
    writer_source: str,
    format_api: str,
    parse_api: str,
) -> None:
    sink_pattern = re.compile(
        r"\.\s*(insert|insertOrThrow|replace|replaceOrThrow|update)"
        r"\s*\(\s*(ACTIVITY_TABLE_NAME|GPS_TABLE_NAME)\b"
    )
    observed_sinks = Counter(
        sink_pattern.findall(mask_java_non_code(writer_source))
    )
    expected_sinks = Counter(
        {
            ("insertOrThrow", "ACTIVITY_TABLE_NAME"): 2,
            ("insertOrThrow", "GPS_TABLE_NAME"): 2,
            ("update", "ACTIVITY_TABLE_NAME"): 2,
            ("update", "GPS_TABLE_NAME"): 1,
        }
    )
    if observed_sinks != expected_sinks:
        raise EvidenceError(
            "SqlLogger.java legacy table producer set changed"
        )

    identifier = r"[A-Za-z_][A-Za-z0-9_]*"
    specs = {
        "write_data": ("writeData", rf"\s*int\s+{identifier}\s*"),
        "create_date": (
            "createActivity",
            rf"\s*Context\s+{identifier}\s*,\s*Date\s+{identifier}\s*",
        ),
        "create_model": (
            "createActivity",
            rf"\s*SportActivity\s+{identifier}\s*",
        ),
        "update_activity": (
            "updateSportActivity",
            rf"\s*SportActivity\s+{identifier}\s*",
        ),
        "complete": (
            "completeActivity",
            rf"\s*int\s+{identifier}\s*,\s*Date\s+{identifier}\s*",
        ),
        "create_point": (
            "createTrackPoint",
            rf"\s*SportTrackPoint\s+{identifier}\s*,\s*"
            rf"int\s+{identifier}\s*",
        ),
        "update_point": (
            "updateTrackPoint",
            rf"\s*SportTrackPoint\s+{identifier}\s*,\s*"
            rf"int\s+{identifier}\s*",
        ),
        "activity_values": (
            "activityValues",
            rf"\s*SportActivity\s+{identifier}\s*",
        ),
        "point_values": (
            "trackPointValues",
            rf"\s*SportTrackPoint\s+{identifier}\s*,\s*"
            rf"int\s+{identifier}\s*",
        ),
        "put_date": (
            "putDate",
            rf"\s*ContentValues\s+{identifier}\s*,\s*"
            rf"String\s+{identifier}\s*,\s*Date\s+{identifier}\s*",
        ),
        "parse": (
            "parseTimestamp",
            rf"\s*String\s+{identifier}\s*",
        ),
    }
    methods = {
        key: extract_java_top_level_method(
            writer_source, "SqlLogger", method_name, parameters
        )
        for key, (method_name, parameters) in specs.items()
    }
    for key, (_, body) in methods.items():
        if re.search(
            r"\b(?:class|interface|enum|record)\b",
            mask_java_non_code(body),
        ):
            raise EvidenceError(
                f"SqlLogger.{specs[key][0]} contains an unsupported nested type"
            )

    direct_paths = (
        (
            "write_data",
            r"\.insertOrThrow\s*\(\s*GPS_TABLE_NAME\s*,\s*null\s*,"
            rf"\s*({identifier})\s*\)",
            "T_TIMESTAMP_UTC",
        ),
        (
            "create_date",
            r"\.insertOrThrow\s*\(\s*ACTIVITY_TABLE_NAME\s*,\s*null\s*,"
            rf"\s*({identifier})\s*\)",
            "A_START_TIME_UTC",
        ),
        (
            "complete",
            r"\.update\s*\(\s*ACTIVITY_TABLE_NAME\s*,"
            rf"\s*({identifier})\s*,",
            "A_END_TIME_UTC",
        ),
    )
    for key, sink, column in direct_paths:
        validate_timestamp_column_write(
            methods[key][1],
            specs[key][0],
            require_sink_values(methods[key][1], specs[key][0], sink),
            column,
            format_api,
        )

    create_model = compact_java_code(methods["create_model"][1])
    create_model_values = re.findall(
        rf"ContentValues({identifier})=activityValues\([^;]+\);",
        create_model,
    )
    if (
        len(create_model_values) != 1
        or f"insertOrThrow(ACTIVITY_TABLE_NAME,null,{create_model_values[0]})"
        not in create_model
    ):
        raise EvidenceError(
            "SqlLogger.createActivity no longer sends "
            "activityValues(...) to the database"
        )
    for key, pattern, message in (
        (
            "update_activity",
            r"\.update\s*\(\s*ACTIVITY_TABLE_NAME\s*,\s*"
            r"activityValues\s*\(",
            "activityValues",
        ),
        (
            "create_point",
            r"\.insertOrThrow\s*\(\s*GPS_TABLE_NAME\s*,\s*null\s*,\s*"
            r"trackPointValues\s*\(",
            "trackPointValues",
        ),
        (
            "update_point",
            r"\.update\s*\(\s*GPS_TABLE_NAME\s*,\s*"
            r"trackPointValues\s*\(",
            "trackPointValues",
        ),
    ):
        if re.search(pattern, mask_java_non_code(methods[key][1])) is None:
            raise EvidenceError(
                f"SqlLogger.{specs[key][0]} no longer sends "
                f"{message}(...) to the database"
            )

    for key, columns in (
        ("activity_values", ("A_START_TIME_UTC", "A_END_TIME_UTC")),
        ("point_values", ("T_TIMESTAMP_UTC",)),
    ):
        body = methods[key][1]
        returned = re.findall(
            rf"\breturn\s+({identifier})\s*;",
            mask_java_non_code(body),
        )
        if len(returned) != 1:
            raise EvidenceError(
                f"SqlLogger.{specs[key][0]} must return one "
                "ContentValues variable"
            )
        for column in columns:
            validate_timestamp_column_write(
                body, specs[key][0], returned[0], column, format_api
            )

    put_parameters, put_body = methods["put_date"]
    values, key, date = java_parameter_names(put_parameters)
    if compact_java_code(put_body) != (
        f"if({date}==null){{{values}.putNull({key});}}"
        f"else{{{values}.put({key},{format_api}({date}));}}"
    ):
        raise EvidenceError(
            "SqlLogger.putDate must format the value written by its "
            "non-null branch"
        )

    parse_parameters, parse_body = methods["parse"]
    timestamp = java_parameter_names(parse_parameters)[0]
    masked_parse = mask_java_non_code(parse_body)
    if (
        len(re.findall(r"\breturn\b", masked_parse)) != 1
        or re.search(
            rf"\breturn\s+{java_qualified_name_pattern(parse_api)}\s*"
            rf"\(\s*{re.escape(timestamp)}\s*\)\s*;",
            masked_parse,
        )
        is None
    ):
        raise EvidenceError(
            "SqlLogger.parseTimestamp no longer returns the compatible parser"
        )


def validate_current_writer_text(
    config_source: str,
    writer_source: str,
    schema_source: str,
    expected_pattern: str,
    allowed_locale_policies: Sequence[str],
    required_timestamp_columns: Sequence[str],
    *,
    require_column_writes: bool,
) -> dict[str, object]:
    model = extract_formatter_model(
        config_source,
        expected_pattern,
        allowed_locale_policies,
        require_helpers=require_column_writes,
    )
    format_api = str(model["format_api"])
    parse_api = str(model["parse_api"])
    if require_column_writes:
        if tuple(required_timestamp_columns) != REQUIRED_TIMESTAMP_COLUMN_SYMBOLS:
            raise EvidenceError(
                "current timestamp column contract changed"
            )
        validate_current_sql_logger(
            writer_source, format_api, parse_api
        )
    else:
        writer = mask_java_non_code(writer_source)
        if not re.search(rf"\b{re.escape(format_api)}\s*\(", writer):
            raise EvidenceError(
                "SqlLogger.java no longer uses the compatible formatter"
            )
        if not re.search(rf"\b{re.escape(parse_api)}\s*\(", writer):
            raise EvidenceError(
                "SqlLogger.java no longer uses the compatible parser"
            )

    observed_schema = extract_create_table_statements(writer_source)
    expected_schema = expected_create_table_statements(schema_source)
    if len(expected_schema) != 2 or observed_schema != expected_schema:
        raise EvidenceError(
            "current SqlLogger.java schema no longer matches legacy_schema.sql"
        )
    return {
        **model,
        "schema_statements": list(observed_schema),
    }


def validate_writer_boundaries(
    root: Path,
    toolchain: Mapping[str, object],
) -> dict[str, object]:
    contract = load_writer_contract(root)
    capture = require_mapping(contract, "capture")
    current = require_mapping(contract, "current_compatibility")
    legacy_writer = require_mapping(toolchain, "legacy_writer")
    formatter = require_mapping(toolchain, "formatter")
    repository_root = root.parent.parent.resolve()

    for contract_key, toolchain_key in (
        ("config_logical_path", "config_relative_path"),
        ("config_sha256", "config_sha256"),
        ("writer_logical_path", "writer_relative_path"),
        ("writer_sha256", "writer_sha256"),
        ("pattern", "pattern"),
    ):
        if capture.get(contract_key) != legacy_writer.get(toolchain_key):
            raise EvidenceError(
                f"writer-contract.json: capture {contract_key} disagrees with toolchain.json"
            )
    if capture.get("formatter_constructor") != formatter.get("constructor"):
        raise EvidenceError(
            "writer-contract.json: captured constructor disagrees with toolchain.json"
        )
    if not re.fullmatch(
        r"[0-9a-f]{40}",
        str(capture.get("base_commit", "")),
    ):
        raise EvidenceError(
            "writer-contract.json: capture base commit must be a full object ID"
        )
    if (
        capture.get("config_snapshot_relative_path")
        != f"captured-source/{capture.get('config_logical_path')}.gz"
        or capture.get("writer_snapshot_relative_path")
        != f"captured-source/{capture.get('writer_logical_path')}.gz"
    ):
        raise EvidenceError(
            "writer-contract.json: captured snapshots do not mirror logical source paths"
        )

    config_snapshot = resolve_safe_file(
        root,
        require_nonempty_string(
            capture, "config_snapshot_relative_path", "capture"
        ),
        root,
        "captured Config.java",
    )
    writer_snapshot = resolve_safe_file(
        root,
        require_nonempty_string(
            capture, "writer_snapshot_relative_path", "capture"
        ),
        root,
        "captured SqlLogger.java",
    )
    captured_config = read_captured_source(config_snapshot)
    captured_writer = read_captured_source(writer_snapshot)
    if sha256_bytes(captured_config) != capture.get("config_sha256"):
        raise EvidenceError("captured Config.java SHA-256 mismatch")
    if sha256_bytes(captured_writer) != capture.get("writer_sha256"):
        raise EvidenceError("captured SqlLogger.java SHA-256 mismatch")

    schema_path = resolve_safe_file(
        repository_root,
        require_nonempty_string(
            current, "schema_relative_path", "current_compatibility"
        ),
        repository_root,
        "legacy schema",
    )
    if (
        current.get("config_relative_path") != capture.get("config_logical_path")
        or current.get("writer_relative_path")
        != capture.get("writer_logical_path")
    ):
        raise EvidenceError(
            "writer-contract.json: current writer paths must retain the captured logical paths"
        )
    if current.get("schema_relative_path") != CURRENT_SCHEMA_RELATIVE_PATH:
        raise EvidenceError(
            "writer-contract.json: current schema path is not the exact harness schema"
        )
    required_columns = require_sequence(
        current, "required_timestamp_columns"
    )
    if not all(isinstance(item, str) and item for item in required_columns):
        raise EvidenceError(
            "writer-contract.json: timestamp columns must be non-empty strings"
        )
    if tuple(required_columns) != REQUIRED_TIMESTAMP_COLUMN_SYMBOLS:
        raise EvidenceError(
            "writer-contract.json: required timestamp columns changed"
        )
    allowed_policies = require_sequence(current, "allowed_locale_policies")
    if not all(isinstance(item, str) and item for item in allowed_policies):
        raise EvidenceError(
            "writer-contract.json: locale policies must be non-empty strings"
        )
    if tuple(allowed_policies) != CURRENT_WRITER_LOCALE_POLICIES:
        raise EvidenceError(
            "writer-contract.json: current locale policy changed"
        )
    expected_pattern = require_nonempty_string(
        current, "expected_pattern", "current_compatibility"
    )
    if expected_pattern != legacy_writer.get("pattern"):
        raise EvidenceError(
            "writer-contract.json: current pattern disagrees with captured pattern"
        )
    capture_model = validate_current_writer_text(
        captured_config.decode("utf-8"),
        captured_writer.decode("utf-8"),
        schema_path.read_text(encoding="utf-8"),
        expected_pattern,
        ("default_locale",),
        tuple(str(item) for item in required_columns),
        require_column_writes=False,
    )
    if capture_model["constructor"] != formatter.get("constructor"):
        raise EvidenceError(
            "captured formatter constructor no longer matches evidence"
        )

    current_config = resolve_safe_file(
        repository_root,
        require_nonempty_string(
            current, "config_relative_path", "current_compatibility"
        ),
        repository_root,
        "current Config.java",
    )
    current_writer = resolve_safe_file(
        repository_root,
        require_nonempty_string(
            current, "writer_relative_path", "current_compatibility"
        ),
        repository_root,
        "current SqlLogger.java",
    )
    current_model = validate_current_writer_text(
        current_config.read_text(encoding="utf-8"),
        current_writer.read_text(encoding="utf-8"),
        schema_path.read_text(encoding="utf-8"),
        expected_pattern,
        tuple(str(item) for item in allowed_policies),
        tuple(str(item) for item in required_columns),
        require_column_writes=True,
    )
    return {
        "capture": {
            "base_commit": require_nonempty_string(
                capture, "base_commit", "capture"
            ),
            "config_sha256": str(capture["config_sha256"]),
            "constructor": str(capture_model["constructor"]),
            "locale_policy": str(capture_model["locale_policy"]),
            "pattern": expected_pattern,
            "writer_sha256": str(capture["writer_sha256"]),
        },
        "contract_sha256": sha256_file(root / "writer-contract.json"),
        "current": {
            "config_sha256": sha256_file(current_config),
            "constructor": str(current_model["constructor"]),
            "format_api": str(current_model["format_api"]),
            "locale_policy": str(current_model["locale_policy"]),
            "parse_api": str(current_model["parse_api"]),
            "pattern": expected_pattern,
            "writer_sha256": sha256_file(current_writer),
        },
    }


def validate_toolchain(root: Path) -> dict[str, object]:
    toolchain = load_toolchain(root)
    if toolchain.get("schema_version") != 1:
        raise EvidenceError("toolchain.json: schema_version must be 1")

    probe = require_mapping(toolchain, "probe")
    source_path = (
        root
        / "probe/src/com/long2know/sportlogger/formatterevidence"
        / "FormatterEvidenceInstrumentation.java"
    )
    manifest_path = root / "probe/AndroidManifest.xml"
    if probe.get("source_sha256") != sha256_file(source_path):
        raise EvidenceError("toolchain.json: probe source SHA-256 mismatch")
    if probe.get("manifest_sha256") != sha256_file(manifest_path):
        raise EvidenceError("toolchain.json: manifest SHA-256 mismatch")

    legacy_writer = require_mapping(toolchain, "legacy_writer")
    validate_writer_boundaries(root, toolchain)
    legacy_pattern = require_nonempty_string(
        legacy_writer, "pattern", "legacy_writer"
    )

    sdk_packages = require_mapping(toolchain, "sdk_packages")
    for name in (
        "build_tools",
        "cmdline_tools",
        "compile_sdk",
        "emulator",
        "platform_tools",
    ):
        package = require_mapping(sdk_packages, name)
        for field in ("package", "relative_path", "revision"):
            require_nonempty_string(package, field, f"sdk_packages.{name}")
        revision = str(package["revision"])
        if "+" in revision or revision.endswith("-SNAPSHOT"):
            raise EvidenceError(f"sdk_packages.{name}: dynamic revision")

    system_images = require_mapping(toolchain, "system_images")
    for api_level in API_LEVELS:
        image = require_mapping(system_images, str(api_level))
        for field in ("abi", "package", "relative_path", "revision", "tag"):
            require_nonempty_string(image, field, f"system_images.{api_level}")
        if image["abi"] != "x86_64":
            raise EvidenceError(f"system_images.{api_level}: ABI must be x86_64")

    fixed_instants = require_sequence(toolchain, "fixed_instants")
    if len(fixed_instants) < 4:
        raise EvidenceError("toolchain.json: at least four fixed instants required")
    seen_ids: set[str] = set()
    for item in fixed_instants:
        if not isinstance(item, dict):
            raise EvidenceError("toolchain.json: invalid fixed instant")
        instant_id = require_nonempty_string(item, "id", "fixed_instants")
        if instant_id in seen_ids:
            raise EvidenceError(f"toolchain.json: duplicate instant {instant_id}")
        seen_ids.add(instant_id)
        expected_epoch = iso_to_epoch_ms(
            require_nonempty_string(item, "utc", f"fixed_instants.{instant_id}")
        )
        if item.get("epoch_ms") != expected_epoch:
            raise EvidenceError(
                f"toolchain.json: {instant_id} UTC/epoch mismatch"
            )

    representative_locales = require_sequence(toolchain, "representative_locales")
    locale_ids = [str(item.get("id", "")) for item in representative_locales if isinstance(item, dict)]
    if locale_ids != sorted(locale_ids) or len(locale_ids) != len(set(locale_ids)):
        raise EvidenceError(
            "toolchain.json: representative locale IDs must be sorted and unique"
        )
    required_locale_ids = {
        "ar_sa",
        "fa_ir",
        "ja_jp_japanese",
        "th_th",
        "th_th_buddhist",
    }
    if not required_locale_ids.issubset(locale_ids):
        raise EvidenceError("toolchain.json: risky representative locales missing")

    source_text = source_path.read_text(encoding="utf-8")
    required_source_tokens = (
        f'private static final String PATTERN = "{legacy_pattern}";',
        "NumberingSystem.getAvailableNames()",
        "UCharacter.digit(codePoints[index], 10)",
        "new SimpleDateFormat(PATTERN)",
        "value.codePointAt(index)",
        "Character.charCount(codePoint)",
        "ParsePosition",
    )
    for token in required_source_tokens:
        if token not in source_text:
            raise EvidenceError(f"probe source: missing deterministic token {token!r}")
    if re.search(r"(?m)\b(?:TODO|FIXME)\b", source_text):
        raise EvidenceError("probe source: TODO/FIXME is prohibited")
    return toolchain


def read_tsv(path: Path, expected_header: Sequence[str]) -> tuple[dict[str, str], ...]:
    return parse_tsv_bytes(path.read_bytes(), path, expected_header)


def parse_tsv_bytes(
    raw: bytes, source: object, expected_header: Sequence[str]
) -> tuple[dict[str, str], ...]:
    if not raw.endswith(b"\n"):
        raise EvidenceError(f"{source}: missing final newline")
    if b"\r" in raw:
        raise EvidenceError(f"{source}: CR bytes are prohibited")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise EvidenceError(f"{source}: invalid UTF-8: {error}") from error
    reject_surrogates(text, source)
    reader = csv.DictReader(io.StringIO(text, newline=""), delimiter="\t")
    if tuple(reader.fieldnames or ()) != tuple(expected_header):
        raise EvidenceError(
            f"{source}: header mismatch: {reader.fieldnames!r}"
        )
    rows: list[dict[str, str]] = []
    for line_number, row in enumerate(reader, start=2):
        if None in row:
            raise EvidenceError(f"{source}:{line_number}: excess columns")
        if any(value is None for value in row.values()):
            raise EvidenceError(f"{source}:{line_number}: missing columns")
        rows.append({key: str(value) for key, value in row.items()})
    return tuple(rows)


def write_tsv_bytes(header: Sequence[str], rows: Iterable[Mapping[str, str]]) -> bytes:
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(
        stream,
        fieldnames=list(header),
        delimiter="\t",
        lineterminator="\n",
        extrasaction="raise",
    )
    writer.writeheader()
    for row in rows:
        writer.writerow(dict(row))
    return stream.getvalue().encode("utf-8")


def load_api_evidence(root: Path, api_level: int) -> ApiEvidence:
    return load_api_evidence_from_dir(root / "evidence", api_level)


def load_api_evidence_from_dir(
    evidence_dir: Path, api_level: int
) -> ApiEvidence:
    prefix = f"api{api_level}"
    metadata_rows = read_tsv(
        evidence_dir / f"{prefix}-metadata.tsv", METADATA_HEADER
    )
    metadata: dict[str, str] = {}
    for row in metadata_rows:
        key = row["key"]
        if key in metadata:
            raise EvidenceError(f"API {api_level}: duplicate metadata key {key}")
        metadata[key] = row["value"]
    return ApiEvidence(
        api_level=api_level,
        metadata_rows=metadata_rows,
        metadata=metadata,
        advertised=read_tsv(
            evidence_dir / f"{prefix}-advertised.tsv", ADVERTISED_HEADER
        ),
        candidates=read_tsv(
            evidence_dir / f"{prefix}-candidates.tsv", CANDIDATE_HEADER
        ),
        formats=read_tsv(
            evidence_dir / f"{prefix}-formats.tsv", FORMAT_HEADER
        ),
    )


def validate_api_evidence(
    root: Path, toolchain: Mapping[str, object], evidence: ApiEvidence
) -> None:
    errors: list[str] = []
    api_level = evidence.api_level
    metadata = evidence.metadata

    metadata_keys = [row["key"] for row in evidence.metadata_rows]
    if metadata_keys != sorted(metadata_keys):
        errors.append("metadata keys are not sorted")
    missing_metadata = sorted(REQUIRED_METADATA_KEYS - set(metadata))
    if missing_metadata:
        errors.append(f"missing metadata keys: {missing_metadata}")

    fixed_instants = fixed_instant_map(toolchain)
    representative_locales = representative_locale_map(toolchain)
    expected_metadata = expected_pin_metadata(root, toolchain, api_level)
    for key, expected in expected_metadata.items():
        if metadata.get(key) != expected:
            errors.append(
                f"metadata {key}: expected {expected!r}, got {metadata.get(key)!r}"
            )

    if metadata.get("abi.list", "").split(",")[0] != "x86_64":
        errors.append("primary ABI is not x86_64")
    if not metadata.get("build.fingerprint"):
        errors.append("build fingerprint missing")
    for key in (
        "icu.version",
        "unicode.version",
        "java.runtime.name",
        "java.runtime.version",
        "java.version",
        "java.vm.name",
        "java.vm.version",
    ):
        if not metadata.get(key) or metadata.get(key) == "<unavailable>":
            errors.append(f"required runtime provenance unavailable: {key}")

    advertised_names: list[str] = []
    occurrence_counts: Counter[str] = Counter()
    for expected_index, row in enumerate(evidence.advertised):
        if parse_int(row["sorted_index"], "advertised.sorted_index", errors) != expected_index:
            errors.append("advertised sorted_index is not contiguous")
        if row["value_kind"] == "null":
            if row["candidate_name"]:
                errors.append("null advertisement has a candidate name")
            continue
        if row["value_kind"] != "name" or not row["candidate_name"]:
            errors.append("invalid advertised value_kind/name")
            continue
        name = row["candidate_name"]
        occurrence_counts[name] += 1
        if parse_int(row["occurrence_index"], "advertised.occurrence_index", errors) != occurrence_counts[name]:
            errors.append(f"advertised occurrence index mismatch for {name}")
        advertised_names.append(name)

    if advertised_names != sorted(advertised_names):
        errors.append("advertised names are not sorted by code point")
    advertised_count = parse_int(
        metadata.get("candidate.advertised_count", ""),
        "candidate.advertised_count",
        errors,
    )
    null_count = sum(row["value_kind"] == "null" for row in evidence.advertised)
    if advertised_count != len(evidence.advertised):
        errors.append("advertised count metadata mismatch")
    if parse_int(metadata.get("candidate.null_count", ""), "candidate.null_count", errors) != null_count:
        errors.append("null count metadata mismatch")
    duplicate_count = len(advertised_names) - len(set(advertised_names))
    if parse_int(metadata.get("candidate.duplicate_count", ""), "candidate.duplicate_count", errors) != duplicate_count:
        errors.append("duplicate count metadata mismatch")
    expected_hash = sha256_bytes(
        "".join(f"{name}\n" for name in advertised_names).encode("utf-8")
    )
    if metadata.get("candidate.sorted_occurrence_sha256") != expected_hash:
        errors.append("advertised candidate hash mismatch")

    candidate_names = [row["candidate_name"] for row in evidence.candidates]
    expected_candidate_names = sorted(set(advertised_names))
    if candidate_names != expected_candidate_names:
        errors.append("candidate rows do not exactly match advertised unique names")
    if len(candidate_names) != len(set(candidate_names)):
        errors.append("candidate names are not unique")
    if parse_int(metadata.get("candidate.unique_count", ""), "candidate.unique_count", errors) != len(candidate_names):
        errors.append("unique candidate count metadata mismatch")

    rows_by_candidate: dict[str, list[dict[str, str]]] = {
        name: [] for name in candidate_names
    }
    representative_rows: dict[str, list[dict[str, str]]] = {
        locale_id: [] for locale_id in representative_locales
    }
    seen_row_keys: set[tuple[str, str, str]] = set()
    instant_order = {instant_id: index for index, instant_id in enumerate(fixed_instants)}

    expected_sort_keys: list[tuple[str, str, int]] = []
    for row in evidence.formats:
        source_kind = row["source_kind"]
        source_id = row["source_id"]
        instant_id = row["instant_id"]
        key = (source_kind, source_id, instant_id)
        if key in seen_row_keys:
            errors.append(f"duplicate format row {key}")
        seen_row_keys.add(key)
        if instant_id not in fixed_instants:
            errors.append(f"unknown fixed instant {instant_id}")
            continue
        expected_sort_keys.append(
            (source_kind, source_id, instant_order[instant_id])
        )
        if source_kind == "candidate":
            if source_id not in rows_by_candidate:
                errors.append(f"format row for unknown candidate {source_id}")
            else:
                rows_by_candidate[source_id].append(row)
            if row["candidate_name"] != source_id:
                errors.append(f"candidate_name mismatch for {source_id}")
        elif source_kind == "representative_locale":
            if source_id not in representative_rows:
                errors.append(f"format row for unknown representative {source_id}")
            else:
                representative_rows[source_id].append(row)
            if row["candidate_name"]:
                errors.append(f"representative row {source_id} has candidate name")
        else:
            errors.append(f"unknown source_kind {source_kind}")
        validate_format_row(row, fixed_instants, toolchain, errors)

    if expected_sort_keys != sorted(expected_sort_keys):
        errors.append("format rows are not deterministically sorted")

    candidate_by_name = {
        row["candidate_name"]: row for row in evidence.candidates
    }
    for candidate_name, candidate in candidate_by_name.items():
        category = candidate["category"]
        if category not in CATEGORY_VALUES:
            errors.append(f"{candidate_name}: invalid category {category}")
        attempts = parse_int(candidate["attempts"], f"{candidate_name}.attempts", errors)
        if attempts < 1 or attempts > 3:
            errors.append(f"{candidate_name}: attempts outside 1..3")
        rows = rows_by_candidate[candidate_name]
        declared_rows = parse_int(
            candidate["row_count"], f"{candidate_name}.row_count", errors
        )
        if declared_rows != len(rows):
            errors.append(f"{candidate_name}: row_count mismatch")
        if category == "error":
            if rows:
                errors.append(f"{candidate_name}: error candidate has format rows")
            if not candidate["error_stage"] or not candidate["error_class"]:
                errors.append(f"{candidate_name}: error details missing")
            continue
        if candidate["error_stage"] or candidate["error_class"]:
            errors.append(f"{candidate_name}: non-error candidate has error details")
        if candidate["candidate_kind"] != "decimal":
            if rows:
                errors.append(f"{candidate_name}: non-decimal candidate has format rows")
            if category != "algorithmic_non_decimal":
                errors.append(f"{candidate_name}: non-decimal category mismatch")
            if candidate["observed_output"] != "not_decimal":
                errors.append(f"{candidate_name}: non-decimal output label mismatch")
            if candidate["fallback"] != "false":
                errors.append(f"{candidate_name}: non-decimal candidate marked fallback")
            if candidate["strict_round_trip_all"] != "false":
                errors.append(f"{candidate_name}: non-decimal candidate marked round-trip")
            continue
        if len(rows) != len(fixed_instants):
            errors.append(f"{candidate_name}: incomplete fixed-instant rows")
        if {row["instant_id"] for row in rows} != set(fixed_instants):
            errors.append(f"{candidate_name}: fixed-instant coverage mismatch")

        description_points = parse_code_points(
            candidate["description_code_points"],
            f"{candidate_name}.description_code_points",
            errors,
        )
        if len(description_points) != 10:
            errors.append(f"{candidate_name}: decimal description is not 10 code points")
        if candidate["description_digit_values"] != "0 1 2 3 4 5 6 7 8 9":
            errors.append(
                f"{candidate_name}: Android ICU digit metadata mismatch"
            )
        if category == "supported_decimal_output":
            if candidate["observed_output"] != "requested_decimal":
                errors.append(f"{candidate_name}: supported output label mismatch")
            if candidate["fallback"] != "false":
                errors.append(f"{candidate_name}: supported output marked fallback")
            if candidate["strict_round_trip_all"] != "true":
                errors.append(f"{candidate_name}: supported output did not round-trip")
            observed_points = parse_code_points(
                candidate["observed_digit_code_points"],
                f"{candidate_name}.observed_digit_code_points",
                errors,
            )
            if set(observed_points) != set(description_points):
                errors.append(
                    f"{candidate_name}: emitted digits do not match definition"
                )

    if "fullwide" in candidate_by_name:
        fullwide = candidate_by_name["fullwide"]
        if fullwide["category"] != "supported_decimal_output":
            errors.append("fullwide is advertised but not emitted")
        if not fullwide["description_code_points"].startswith("U+FF10 "):
            errors.append("fullwide definition does not start at U+FF10")

    for locale_id, expected_tag in representative_locales.items():
        rows = representative_rows[locale_id]
        if len(rows) != len(fixed_instants):
            errors.append(f"{locale_id}: incomplete representative rows")
        if {row["instant_id"] for row in rows} != set(fixed_instants):
            errors.append(f"{locale_id}: representative instant coverage mismatch")
        if any(row["locale_tag"] != expected_tag for row in rows):
            errors.append(f"{locale_id}: representative locale tag mismatch")
        if any(row["result"] != "round_trip" for row in rows):
            errors.append(f"{locale_id}: representative locale did not round-trip")

    for thai_id in ("th_th", "th_th_buddhist"):
        years = {
            row["instant_utc"][:4] for row in representative_rows.get(thai_id, [])
        }
        if len(years) < 4:
            errors.append(f"{thai_id}: insufficient multi-year calendar evidence")
        if any(not row["calendar_class"] or not row["calendar_type"] for row in representative_rows.get(thai_id, [])):
            errors.append(f"{thai_id}: calendar provenance missing")

    if errors:
        raise EvidenceError(
            f"API {api_level} evidence validation failed:\n- "
            + "\n- ".join(errors)
        )


def validate_format_row(
    row: Mapping[str, str],
    fixed_instants: Mapping[str, Mapping[str, object]],
    toolchain: Mapping[str, object],
    errors: list[str],
) -> None:
    key = f"{row['source_kind']}:{row['source_id']}:{row['instant_id']}"
    instant = fixed_instants.get(row["instant_id"])
    if instant is None:
        return
    if row["instant_utc"] != instant["utc"]:
        errors.append(f"{key}: instant UTC mismatch")
    epoch_ms = parse_int(row["epoch_ms"], f"{key}.epoch_ms", errors)
    if epoch_ms != instant["epoch_ms"]:
        errors.append(f"{key}: epoch mismatch")

    formatter = require_mapping(toolchain, "formatter")
    if row["formatter_class"] != formatter["class"]:
        errors.append(f"{key}: formatter class mismatch")
    if row["formatter_pattern"] != formatter["pattern"]:
        errors.append(f"{key}: formatter pattern mismatch")
    if row["timezone_id"] != formatter["timezone"]:
        errors.append(f"{key}: timezone mismatch")
    if not row["calendar_class"] or not row["calendar_type"]:
        errors.append(f"{key}: calendar provenance missing")
    if not row["number_format_class"]:
        errors.append(f"{key}: number format class missing")

    text = row["formatted_text"]
    reject_surrogates(text, key)
    expected_points = code_points(text)
    if row["formatted_code_points"] != expected_points:
        errors.append(f"{key}: formatted code-point sequence mismatch")
    if parse_int(row["formatted_code_point_count"], f"{key}.code_point_count", errors) != len(text):
        errors.append(f"{key}: code-point count mismatch")
    if parse_int(row["formatted_utf16_length"], f"{key}.utf16_length", errors) != utf16_length(text):
        errors.append(f"{key}: UTF-16 length mismatch")

    reformatted = row["reformat_text"]
    reject_surrogates(reformatted, key)
    if row["reformat_code_points"] != code_points(reformatted):
        errors.append(f"{key}: reformat code-point sequence mismatch")
    if row["parse_consumed"] not in {"true", "false"}:
        errors.append(f"{key}: invalid parse_consumed")
    if row["parsed_matches_input"] not in {"true", "false"}:
        errors.append(f"{key}: invalid parsed_matches_input")
    if row["reformat_equal"] not in {"true", "false"}:
        errors.append(f"{key}: invalid reformat_equal")
    if row["result"] != "round_trip":
        errors.append(f"{key}: strict parse/reformat failed")
    if row["parse_consumed"] == "true":
        if parse_int(row["parse_index"], f"{key}.parse_index", errors) != utf16_length(text):
            errors.append(f"{key}: parse did not consume all UTF-16 units")
        if parse_int(row["parse_error_index"], f"{key}.parse_error_index", errors) != -1:
            errors.append(f"{key}: parse error index set")
    if row["parsed_epoch_ms"] != str(epoch_ms):
        errors.append(f"{key}: parsed epoch mismatch")
    if row["parsed_matches_input"] != "true":
        errors.append(f"{key}: parsed instant mismatch")
    if row["reformat_equal"] != "true" or reformatted != text:
        errors.append(f"{key}: reformat mismatch")


def validate_no_volatile_data(
    root: Path,
    toolchain: Mapping[str, object],
    evidence_dir: Path | None = None,
) -> None:
    if evidence_dir is None:
        evidence_dir = root / "evidence"
    fixed_timestamps = {
        str(item["utc"]) for item in require_sequence(toolchain, "fixed_instants")
        if isinstance(item, dict)
    }
    timestamp_pattern = re.compile(
        r"\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z\b"
    )
    for path in sorted(evidence_dir.glob("*")):
        if not path.is_file():
            continue
        raw = path.read_bytes()
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as error:
            raise EvidenceError(f"{path}: invalid UTF-8: {error}") from error
        for pattern, description in FORBIDDEN_EVIDENCE_PATTERNS:
            if pattern.search(text):
                raise EvidenceError(f"{path}: contains prohibited {description}")
        unexpected_timestamps = sorted(
            set(timestamp_pattern.findall(text)) - fixed_timestamps
        )
        if unexpected_timestamps:
            raise EvidenceError(
                f"{path}: contains wall-clock timestamps {unexpected_timestamps}"
            )


def expected_pin_metadata(
    root: Path, toolchain: Mapping[str, object], api_level: int
) -> dict[str, str]:
    sdk = require_mapping(toolchain, "sdk_packages")
    image = require_mapping(require_mapping(toolchain, "system_images"), str(api_level))
    host_java = require_mapping(toolchain, "host_java")
    probe = require_mapping(toolchain, "probe")
    formatter = require_mapping(toolchain, "formatter")
    candidate = require_mapping(toolchain, "candidate_algorithm")
    legacy_writer = require_mapping(toolchain, "legacy_writer")
    representatives = require_sequence(toolchain, "representative_locales")
    fixed_instants = require_sequence(toolchain, "fixed_instants")
    emulator = require_mapping(sdk, "emulator")
    build_tools = require_mapping(sdk, "build_tools")
    cmdline_tools = require_mapping(sdk, "cmdline_tools")
    compile_sdk = require_mapping(sdk, "compile_sdk")
    platform_tools = require_mapping(sdk, "platform_tools")

    expected = {
        "android.api_level": str(api_level),
        "candidate.algorithm": str(candidate["id"]),
        "candidate.algorithm.detail": str(candidate["detail"]),
        "default.locale.observed": "en-US",
        "default.locale.requested": "en-US",
        "fixed_instant.count": str(len(fixed_instants)),
        "formatter.class": str(formatter["class"]),
        "formatter.constructor": str(formatter["constructor"]),
        "formatter.pattern": str(formatter["pattern"]),
        "output.encoding": "UTF-8",
        "pin.build_tools_package": str(build_tools["package"]),
        "pin.build_tools_revision": str(build_tools["revision"]),
        "pin.cmdline_tools_package": str(cmdline_tools["package"]),
        "pin.cmdline_tools_revision": str(cmdline_tools["revision"]),
        "pin.compile_sdk_package": str(compile_sdk["package"]),
        "pin.compile_sdk_revision": str(compile_sdk["revision"]),
        "pin.emulator_package": str(emulator["package"]),
        "pin.emulator_reported_version": str(emulator["reported_version"]),
        "pin.emulator_revision": str(emulator["revision"]),
        "pin.host_java_runtime_version": str(host_java["java_runtime_version"]),
        "pin.host_java_vendor": str(host_java["java_vendor"]),
        "pin.host_java_version": str(host_java["java_version"]),
        "pin.host_javac_version": str(host_java["javac_version"]),
        "pin.legacy_config_sha256": str(legacy_writer["config_sha256"]),
        "pin.legacy_writer_sha256": str(legacy_writer["writer_sha256"]),
        "pin.manifest_sha256": str(probe["manifest_sha256"]),
        "pin.platform_tools_package": str(platform_tools["package"]),
        "pin.platform_tools_revision": str(platform_tools["revision"]),
        "pin.probe_source_sha256": str(probe["source_sha256"]),
        "pin.system_image_package": str(image["package"]),
        "pin.system_image_revision": str(image["revision"]),
        "pin.toolchain_sha256": sha256_file(root / "toolchain.json"),
        "probe.instrumentation_class": str(probe["instrumentation_class"]),
        "probe.package": str(probe["package"]),
        "representative_locale.count": str(len(representatives)),
        "timezone.observed": str(formatter["timezone"]),
        "timezone.requested": str(formatter["timezone"]),
    }
    for item in fixed_instants:
        if not isinstance(item, dict):
            continue
        instant_id = str(item["id"])
        expected[f"fixed_instant.{instant_id}.epoch_ms"] = str(item["epoch_ms"])
        expected[f"fixed_instant.{instant_id}.utc"] = str(item["utc"])
    for item in representatives:
        if not isinstance(item, dict):
            continue
        expected[f"representative_locale.{item['id']}"] = str(item["language_tag"])
    return expected


def build_union_summary(
    toolchain: Mapping[str, object],
    evidence_by_api: Mapping[int, ApiEvidence],
) -> dict[str, object]:
    api_summary: dict[str, object] = {}
    candidate_sets: dict[int, set[str]] = {}
    supported_by_api: dict[int, dict[str, dict[str, str]]] = {}

    for api_level in API_LEVELS:
        evidence = evidence_by_api[api_level]
        candidates = {
            row["candidate_name"]: row for row in evidence.candidates
        }
        candidate_sets[api_level] = set(candidates)
        supported = {
            name: row
            for name, row in candidates.items()
            if row["category"] == "supported_decimal_output"
        }
        supported_by_api[api_level] = supported
        category_counts = Counter(row["category"] for row in candidates.values())
        supplementary_supported = sorted(
            name
            for name, row in supported.items()
            if any(
                code_point > 0xFFFF
                for code_point in parse_code_points_strict(
                    row["description_code_points"]
                )
            )
        )
        api_summary[str(api_level)] = {
            "advertised_count": int(evidence.metadata["candidate.advertised_count"]),
            "category_counts": dict(sorted(category_counts.items())),
            "format_row_count": len(evidence.formats),
            "representative_row_count": sum(
                row["source_kind"] == "representative_locale"
                for row in evidence.formats
            ),
            "supplementary_supported_names": supplementary_supported,
            "supported_decimal_names": sorted(supported),
            "unique_candidate_count": len(candidates),
        }

    union_names = sorted(set.union(*(candidate_sets[api] for api in API_LEVELS)))
    intersection_names = sorted(
        set.intersection(*(candidate_sets[api] for api in API_LEVELS))
    )
    supported_union_names = sorted(
        set.union(*(set(supported_by_api[api]) for api in API_LEVELS))
    )
    supported_union: list[dict[str, object]] = []
    zero_code_points: set[str] = set()
    definition_differences: list[dict[str, object]] = []

    for name in supported_union_names:
        definitions: dict[str, str] = {}
        present_apis: list[int] = []
        for api_level in API_LEVELS:
            row = supported_by_api[api_level].get(name)
            if row is None:
                continue
            present_apis.append(api_level)
            definitions[str(api_level)] = row["description_code_points"]
            points = parse_code_points_strict(row["description_code_points"])
            if points:
                zero_code_points.add(f"U+{points[0]:04X}")
        unique_definitions = sorted(set(definitions.values()))
        supported_union.append(
            {
                "apis": present_apis,
                "definition_code_points": definitions,
                "name": name,
            }
        )
        if len(unique_definitions) > 1:
            definition_differences.append(
                {
                    "definition_code_points": definitions,
                    "name": name,
                }
            )

    return {
        "api_only_candidate_names": {
            str(api): sorted(candidate_sets[api] - set.intersection(
                *(candidate_sets[level] for level in API_LEVELS)
            ))
            for api in API_LEVELS
        },
        "apis": api_summary,
        "candidate_algorithm": require_mapping(
            toolchain, "candidate_algorithm"
        )["id"],
        "candidate_intersection_names": intersection_names,
        "candidate_union_names": union_names,
        "definition_differences": definition_differences,
        "emitted_decimal_zero_code_point_union": sorted(zero_code_points),
        "schema_version": 1,
        "supported_decimal_union": supported_union,
    }


def validate_all(root: Path) -> dict[str, object]:
    toolchain = validate_toolchain(root)
    writer_provenance = validate_writer_boundaries(root, toolchain)
    evidence_by_api = {
        api_level: load_api_evidence(root, api_level) for api_level in API_LEVELS
    }
    for evidence in evidence_by_api.values():
        validate_api_evidence(root, toolchain, evidence)
        validate_canonical_tsv_bytes(root, evidence)
    validate_no_volatile_data(root, toolchain)

    expected_summary = canonical_json_bytes(
        build_union_summary(toolchain, evidence_by_api)
    )
    summary_path = root / "evidence/union-summary.json"
    if summary_path.read_bytes() != expected_summary:
        raise EvidenceError(
            f"{summary_path}: union summary is stale or non-canonical"
        )
    if expected_summary != canonical_json_bytes(
        build_union_summary(toolchain, evidence_by_api)
    ):
        raise EvidenceError("union summary rendering is not repeatable")
    return {
        "toolchain": toolchain,
        "evidence": evidence_by_api,
        "summary": json.loads(expected_summary.decode("utf-8")),
        "writer_provenance": writer_provenance,
    }


def validate_canonical_tsv_bytes(root: Path, evidence: ApiEvidence) -> None:
    prefix = root / "evidence" / f"api{evidence.api_level}"
    expected = {
        Path(f"{prefix}-metadata.tsv"): write_tsv_bytes(
            METADATA_HEADER, evidence.metadata_rows
        ),
        Path(f"{prefix}-advertised.tsv"): write_tsv_bytes(
            ADVERTISED_HEADER, evidence.advertised
        ),
        Path(f"{prefix}-candidates.tsv"): write_tsv_bytes(
            CANDIDATE_HEADER, evidence.candidates
        ),
        Path(f"{prefix}-formats.tsv"): write_tsv_bytes(
            FORMAT_HEADER, evidence.formats
        ),
    }
    for path, rendered in expected.items():
        if path.read_bytes() != rendered:
            raise EvidenceError(f"{path}: TSV bytes are not canonical")


def fixed_instant_map(
    toolchain: Mapping[str, object],
) -> dict[str, Mapping[str, object]]:
    result: dict[str, Mapping[str, object]] = {}
    for item in require_sequence(toolchain, "fixed_instants"):
        if isinstance(item, dict):
            result[str(item["id"])] = item
    return result


def representative_locale_map(
    toolchain: Mapping[str, object],
) -> dict[str, str]:
    result: dict[str, str] = {}
    for item in require_sequence(toolchain, "representative_locales"):
        if isinstance(item, dict):
            result[str(item["id"])] = str(item["language_tag"])
    return result


def code_points(value: str) -> str:
    return " ".join(f"U+{ord(character):04X}" for character in value)


def utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def reject_surrogates(value: str, source: object) -> None:
    for character in value:
        if 0xD800 <= ord(character) <= 0xDFFF:
            raise EvidenceError(f"{source}: isolated surrogate code point")


def parse_code_points(
    value: str, field: str, errors: list[str]
) -> tuple[int, ...]:
    try:
        return parse_code_points_strict(value)
    except ValueError as error:
        errors.append(f"{field}: {error}")
        return ()


def parse_code_points_strict(value: str) -> tuple[int, ...]:
    if not value:
        return ()
    result: list[int] = []
    for token in value.split(" "):
        if not re.fullmatch(r"U\+[0-9A-F]{4,6}", token):
            raise ValueError(f"invalid code-point token {token!r}")
        code_point = int(token[2:], 16)
        if code_point > 0x10FFFF or 0xD800 <= code_point <= 0xDFFF:
            raise ValueError(f"invalid Unicode scalar {token}")
        result.append(code_point)
    return tuple(result)


def parse_int(value: str, field: str, errors: list[str]) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        errors.append(f"{field}: invalid integer {value!r}")
        return 0


def iso_to_epoch_ms(value: str) -> int:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z", value):
        raise EvidenceError(f"invalid fixed UTC instant {value!r}")
    parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    if parsed.tzinfo != timezone.utc:
        raise EvidenceError(f"fixed instant is not UTC: {value}")
    return int(parsed.timestamp() * 1000)


def require_mapping(
    value: Mapping[str, object], key: str
) -> Mapping[str, object]:
    nested = value.get(key)
    if not isinstance(nested, dict):
        raise EvidenceError(f"{key}: mapping required")
    return nested


def require_sequence(
    value: Mapping[str, object], key: str
) -> Sequence[object]:
    nested = value.get(key)
    if not isinstance(nested, list):
        raise EvidenceError(f"{key}: list required")
    return nested


def require_nonempty_string(
    value: Mapping[str, object], key: str, context: str
) -> str:
    nested = value.get(key)
    if not isinstance(nested, str) or not nested:
        raise EvidenceError(f"{context}.{key}: non-empty string required")
    return nested
