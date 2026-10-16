#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import platform
import re
import shlex
import shutil
import signal
import socket
import subprocess
import time
from pathlib import Path
from typing import Mapping, Sequence

from evidence_lib import (
    ADVERTISED_HEADER,
    API_LEVELS,
    CANDIDATE_HEADER,
    FORMAT_HEADER,
    ApiEvidence,
    EvidenceError,
    build_union_summary,
    canonical_json_bytes,
    load_api_evidence_from_dir,
    parse_tsv_bytes,
    sha256_file,
    validate_api_evidence,
    validate_no_volatile_data,
    validate_toolchain,
    write_tsv_bytes,
)


ROOT = Path(__file__).resolve().parent
REPOSITORY_ROOT = ROOT.parent.parent
WORK_ROOT = ROOT / ".work"
EVIDENCE_DIR = ROOT / "evidence"
PACKAGE_NAME = "com.long2know.sportlogger.formatterevidence"
INSTRUMENTATION_CLASS = (
    "com.long2know.sportlogger.formatterevidence."
    "FormatterEvidenceInstrumentation"
)
INSTRUMENTATION_COMPONENT = (
    f"{PACKAGE_NAME}/{INSTRUMENTATION_CLASS}"
)
BOOT_TIMEOUT_SECONDS = 900
INSTRUMENTATION_TIMEOUT_SECONDS = 120
BATCH_CURRENT_HEADER = ("index", "candidate_name", "state")


class RunnerError(RuntimeError):
    pass


class DeviceTransient(RunnerError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Regenerate pinned API 26/API 36 Android SimpleDateFormat evidence."
        )
    )
    action = parser.add_mutually_exclusive_group()
    action.add_argument(
        "--compare",
        action="store_true",
        help="compare regenerated evidence with committed bytes (default)",
    )
    action.add_argument(
        "--update",
        action="store_true",
        help="replace committed evidence after all validation and repeat checks pass",
    )
    action.add_argument(
        "--validate-only",
        action="store_true",
        help="validate committed evidence and tracked probe/toolchain without an emulator",
    )
    parser.add_argument(
        "--repeat",
        type=int,
        default=2,
        help="number of complete API 26/API 36 regenerations (default: 2)",
    )
    parser.add_argument(
        "--sdk-root",
        type=Path,
        default=None,
        help="Android SDK root; defaults to ANDROID_SDK_ROOT or ANDROID_HOME",
    )
    parser.add_argument(
        "--java-home",
        type=Path,
        default=None,
        help="pinned JDK root; defaults to JAVA_HOME",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.repeat < 2 and not args.validate_only:
        print("ERROR: --repeat must be at least 2 for the local regeneration gate")
        return 2

    try:
        if args.validate_only:
            from evidence_lib import validate_all

            validate_all(ROOT)
            print("Committed Android formatter evidence and probe pins are valid.")
            return 0

        sdk_root = resolve_required_path(
            args.sdk_root,
            os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME"),
            "Android SDK root",
        )
        java_home = resolve_required_path(
            args.java_home, os.environ.get("JAVA_HOME"), "JAVA_HOME"
        )
        toolchain = validate_toolchain(ROOT)
        verify_prerequisites(sdk_root, java_home, toolchain)
        cleanup_stale_work()
        WORK_ROOT.mkdir(parents=True, exist_ok=False)

        cycle_evidence_dirs: list[Path] = []
        for cycle_number in range(1, args.repeat + 1):
            print(f"Regeneration {cycle_number}/{args.repeat}")
            cycle_root = WORK_ROOT / f"cycle-{cycle_number}"
            cycle_root.mkdir(parents=True)
            apk = build_probe(
                cycle_root / "build", sdk_root, java_home, toolchain
            )
            evidence_dir = cycle_root / "evidence"
            evidence_dir.mkdir()
            run_cycle(
                evidence_dir, cycle_root, apk, sdk_root, java_home, toolchain
            )
            validate_generated_evidence(evidence_dir, toolchain)
            cycle_evidence_dirs.append(evidence_dir)

        for other in cycle_evidence_dirs[1:]:
            compare_evidence_directories(cycle_evidence_dirs[0], other)
        print(f"{args.repeat} complete regenerations are byte-identical.")

        if args.update:
            publish_evidence(cycle_evidence_dirs[0])
            from evidence_lib import validate_all

            validate_all(ROOT)
            print("Committed evidence was updated and revalidated.")
        else:
            compare_evidence_directories(EVIDENCE_DIR, cycle_evidence_dirs[0])
            from evidence_lib import validate_all

            validate_all(ROOT)
            print("Regenerated evidence byte-matches committed evidence.")
        return 0
    except (
        EvidenceError,
        RunnerError,
        OSError,
        subprocess.SubprocessError,
        ValueError,
    ) as error:
        print(f"ERROR: {error}")
        return 1
    finally:
        if not args.validate_only:
            cleanup_work()


def resolve_required_path(
    explicit: Path | None, environment_value: str | None, description: str
) -> Path:
    value = explicit or (Path(environment_value) if environment_value else None)
    if value is None:
        raise RunnerError(
            f"{description} is required; pass the option or set its environment variable"
        )
    resolved = value.expanduser().resolve()
    if not resolved.is_dir():
        raise RunnerError(f"{description} is not a directory: {resolved}")
    return resolved


def verify_prerequisites(
    sdk_root: Path,
    java_home: Path,
    toolchain: Mapping[str, object],
) -> None:
    if platform.system() != "Linux" or platform.machine() not in {
        "x86_64",
        "AMD64",
    }:
        raise RunnerError("the pinned runner requires Linux x86_64")

    java = java_home / "bin/java"
    javac = java_home / "bin/javac"
    jar = java_home / "bin/jar"
    keytool = java_home / "bin/keytool"
    for executable in (java, javac, jar, keytool):
        require_executable(executable)

    host_java = mapping(toolchain, "host_java")
    javac_output = run(
        [str(javac), "-version"], capture=True, merge_stderr=True
    ).stdout.strip()
    expected_javac = f"javac {host_java['javac_version']}"
    if javac_output != expected_javac:
        raise RunnerError(
            f"javac mismatch: expected {expected_javac!r}, got {javac_output!r}"
        )
    java_output = run(
        [str(java), "-XshowSettings:properties", "-version"],
        capture=True,
        merge_stderr=True,
    ).stdout
    properties = parse_java_properties(java_output)
    expected_properties = {
        "java.runtime.version": str(host_java["java_runtime_version"]),
        "java.vendor": str(host_java["java_vendor"]),
        "java.version": str(host_java["java_version"]),
    }
    for key, expected in expected_properties.items():
        if properties.get(key) != expected:
            raise RunnerError(
                f"host Java {key} mismatch: expected {expected!r}, "
                f"got {properties.get(key)!r}"
            )

    sdk_packages = mapping(toolchain, "sdk_packages")
    system_images = mapping(toolchain, "system_images")
    package_records: list[Mapping[str, object]] = [
        mapping(sdk_packages, name)
        for name in (
            "build_tools",
            "cmdline_tools",
            "compile_sdk",
            "emulator",
            "platform_tools",
        )
    ]
    package_records.extend(
        mapping(system_images, str(api_level)) for api_level in API_LEVELS
    )
    for package_record in package_records:
        package_path = sdk_root / str(package_record["relative_path"])
        if not package_path.is_dir():
            raise RunnerError(
                f"missing pinned SDK package {package_record['package']}"
            )
        revision = read_source_property(package_path, "Pkg.Revision")
        if revision != str(package_record["revision"]):
            raise RunnerError(
                f"{package_record['package']} revision mismatch: "
                f"expected {package_record['revision']}, got {revision}"
            )
        declared_path = read_optional_source_property(package_path, "Pkg.Path")
        if declared_path is not None and declared_path != package_record["package"]:
            raise RunnerError(
                f"{package_record['package']} identifier mismatch: "
                f"package metadata reports {declared_path}"
            )

    for api_level in API_LEVELS:
        image = mapping(system_images, str(api_level))
        image_path = sdk_root / str(image["relative_path"])
        if read_source_property(image_path, "AndroidVersion.ApiLevel") != str(
            api_level
        ):
            raise RunnerError(f"API {api_level} image reports wrong API level")
        if read_source_property(image_path, "SystemImage.Abi") != image["abi"]:
            raise RunnerError(f"API {api_level} image reports wrong ABI")
        if read_source_property(image_path, "SystemImage.TagId") != image["tag"]:
            raise RunnerError(f"API {api_level} image reports wrong tag")

    cmdline = mapping(sdk_packages, "cmdline_tools")
    sdkmanager = sdk_root / str(cmdline["relative_path"]) / "bin/sdkmanager"
    require_executable(sdkmanager)
    installed_output = run(
        [str(sdkmanager), f"--sdk_root={sdk_root}", "--list_installed"],
        capture=True,
        merge_stderr=True,
        timeout=120,
    ).stdout
    installed_identifiers = parse_sdkmanager_identifiers(installed_output)
    for package_record in package_records:
        package_id = str(package_record["package"])
        package_path = sdk_root / str(package_record["relative_path"])
        declared_path = read_optional_source_property(package_path, "Pkg.Path")
        if package_id not in installed_identifiers and declared_path != package_id:
            raise RunnerError(
                f"sdkmanager does not report pinned package {package_id}"
            )

    emulator = mapping(sdk_packages, "emulator")
    emulator_binary = sdk_root / str(emulator["relative_path"]) / "emulator"
    require_executable(emulator_binary)
    version_output = run(
        [str(emulator_binary), "-version"],
        capture=True,
        merge_stderr=True,
    ).stdout.splitlines()
    if not version_output:
        raise RunnerError("emulator -version returned no output")
    reported = normalize_emulator_version(version_output[0])
    if reported != emulator["reported_version"]:
        raise RunnerError(
            f"emulator reported version mismatch: expected "
            f"{emulator['reported_version']!r}, got {reported!r}"
        )

    build_tools = mapping(sdk_packages, "build_tools")
    build_tools_path = sdk_root / str(build_tools["relative_path"])
    for name in ("aapt", "aapt2", "apksigner", "d8", "zipalign"):
        require_executable(build_tools_path / name)
    platform_tools = mapping(sdk_packages, "platform_tools")
    require_executable(
        sdk_root / str(platform_tools["relative_path"]) / "adb"
    )
    compile_sdk = mapping(sdk_packages, "compile_sdk")
    if not (sdk_root / str(compile_sdk["relative_path"]) / "android.jar").is_file():
        raise RunnerError("pinned compile SDK android.jar is missing")


def build_probe(
    build_dir: Path,
    sdk_root: Path,
    java_home: Path,
    toolchain: Mapping[str, object],
) -> Path:
    sdk_packages = mapping(toolchain, "sdk_packages")
    build_tools = mapping(sdk_packages, "build_tools")
    compile_sdk = mapping(sdk_packages, "compile_sdk")
    bt = sdk_root / str(build_tools["relative_path"])
    android_jar = sdk_root / str(compile_sdk["relative_path"]) / "android.jar"
    probe = mapping(toolchain, "probe")
    source = (
        ROOT
        / "probe/src/com/long2know/sportlogger/formatterevidence"
        / "FormatterEvidenceInstrumentation.java"
    )
    manifest = ROOT / "probe/AndroidManifest.xml"
    if sha256_file(source) != probe["source_sha256"]:
        raise RunnerError("probe source changed without updating its pin")
    if sha256_file(manifest) != probe["manifest_sha256"]:
        raise RunnerError("probe manifest changed without updating its pin")

    classes = build_dir / "classes"
    dex = build_dir / "dex"
    classes.mkdir(parents=True)
    dex.mkdir()
    classes_jar = build_dir / "classes.jar"
    base_apk = build_dir / "base.apk"
    unsigned_apk = build_dir / "unsigned.apk"
    aligned_apk = build_dir / "aligned.apk"
    signed_apk = build_dir / "formatter-evidence.apk"
    keystore = build_dir / "evidence.jks"

    run(
        [
            str(java_home / "bin/javac"),
            "-source",
            "8",
            "-target",
            "8",
            "-bootclasspath",
            str(android_jar),
            "-d",
            str(classes),
            str(source),
        ]
    )
    run(
        [
            str(java_home / "bin/jar"),
            "--create",
            "--file",
            str(classes_jar),
            "-C",
            str(classes),
            ".",
        ]
    )
    run(
        [
            str(bt / "d8"),
            "--lib",
            str(android_jar),
            "--min-api",
            str(probe["min_sdk"]),
            "--output",
            str(dex),
            str(classes_jar),
        ],
        env={"JAVA_HOME": str(java_home)},
    )
    run(
        [
            str(bt / "aapt2"),
            "link",
            "-I",
            str(android_jar),
            "--manifest",
            str(manifest),
            "--min-sdk-version",
            str(probe["min_sdk"]),
            "--target-sdk-version",
            str(probe["target_sdk"]),
            "--version-code",
            "1",
            "--version-name",
            "1",
            "-o",
            str(base_apk),
        ]
    )
    shutil.copyfile(base_apk, unsigned_apk)
    run(
        [str(bt / "aapt"), "add", str(unsigned_apk), "classes.dex"],
        cwd=dex,
    )
    run(
        [str(bt / "zipalign"), "-f", "4", str(unsigned_apk), str(aligned_apk)]
    )
    run(
        [
            str(java_home / "bin/keytool"),
            "-genkeypair",
            "-alias",
            "formatter-evidence",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "36500",
            "-dname",
            "CN=Formatter Evidence,O=SportLogger,C=US",
            "-keystore",
            str(keystore),
            "-storetype",
            "JKS",
            "-storepass",
            "android",
            "-keypass",
            "android",
            "-noprompt",
        ],
        capture=True,
        merge_stderr=True,
    )
    run(
        [
            str(bt / "apksigner"),
            "sign",
            "--ks",
            str(keystore),
            "--ks-pass",
            "pass:android",
            "--key-pass",
            "pass:android",
            "--out",
            str(signed_apk),
            str(aligned_apk),
        ],
        env={"JAVA_HOME": str(java_home)},
    )
    run(
        [str(bt / "apksigner"), "verify", "--verbose", str(signed_apk)],
        env={"JAVA_HOME": str(java_home)},
        capture=True,
    )
    badging = run(
        [str(bt / "aapt"), "dump", "badging", str(signed_apk)],
        capture=True,
    ).stdout
    if f"package: name='{PACKAGE_NAME}'" not in badging:
        raise RunnerError("built APK package name mismatch")
    return signed_apk


def run_cycle(
    evidence_dir: Path,
    cycle_root: Path,
    apk: Path,
    sdk_root: Path,
    java_home: Path,
    toolchain: Mapping[str, object],
) -> None:
    for api_level in API_LEVELS:
        print(f"  API {api_level}: booting pinned image with -accel off")
        api_result = run_api_with_retries(
            api_level,
            cycle_root / f"api-{api_level}",
            apk,
            sdk_root,
            java_home,
            toolchain,
        )
        for suffix, contents in api_result.items():
            (evidence_dir / f"api{api_level}-{suffix}").write_bytes(contents)

    loaded = {
        api_level: load_api_evidence_from_dir(evidence_dir, api_level)
        for api_level in API_LEVELS
    }
    summary = build_union_summary(toolchain, loaded)
    (evidence_dir / "union-summary.json").write_bytes(
        canonical_json_bytes(summary)
    )


def run_api_with_retries(
    api_level: int,
    api_root: Path,
    apk: Path,
    sdk_root: Path,
    java_home: Path,
    toolchain: Mapping[str, object],
) -> dict[str, bytes]:
    last_error: Exception | None = None
    for attempt in range(1, 4):
        attempt_root = api_root / f"attempt-{attempt}"
        attempt_root.mkdir(parents=True, exist_ok=True)
        try:
            return run_api(
                api_level,
                attempt_root,
                apk,
                sdk_root,
                java_home,
                toolchain,
            )
        except DeviceTransient as error:
            last_error = error
            print(f"    boot/device attempt {attempt}/3 failed: {error}")
            if attempt < 3:
                time.sleep(2 ** attempt)
    raise RunnerError(
        f"API {api_level} did not complete after three attempts: {last_error}"
    )


def run_api(
    api_level: int,
    attempt_root: Path,
    apk: Path,
    sdk_root: Path,
    java_home: Path,
    toolchain: Mapping[str, object],
) -> dict[str, bytes]:
    sdk_packages = mapping(toolchain, "sdk_packages")
    image = mapping(mapping(toolchain, "system_images"), str(api_level))
    cmdline_tools = mapping(sdk_packages, "cmdline_tools")
    platform_tools = mapping(sdk_packages, "platform_tools")
    emulator_package = mapping(sdk_packages, "emulator")
    avdmanager = (
        sdk_root / str(cmdline_tools["relative_path"]) / "bin/avdmanager"
    )
    adb = sdk_root / str(platform_tools["relative_path"]) / "adb"
    emulator = sdk_root / str(emulator_package["relative_path"]) / "emulator"

    avd_home = attempt_root / "avd"
    emulator_home = attempt_root / "emulator-home"
    home = attempt_root / "home"
    for directory in (avd_home, emulator_home, home / ".android"):
        directory.mkdir(parents=True, exist_ok=True)
    (home / ".android/repositories.cfg").write_text("", encoding="utf-8")
    environment = {
        "ANDROID_AVD_HOME": str(avd_home),
        "ANDROID_EMULATOR_HOME": str(emulator_home),
        "ANDROID_HOME": str(sdk_root),
        "ANDROID_SDK_ROOT": str(sdk_root),
        "HOME": str(home),
        "JAVA_HOME": str(java_home),
    }
    avd_name = f"sportlogger-formatter-api{api_level}"
    run(
        [
            str(avdmanager),
            "create",
            "avd",
            "--force",
            "--name",
            avd_name,
            "--package",
            str(image["package"]),
            "--device",
            "pixel_2",
        ],
        env=environment,
        input_text="no\n",
        capture=True,
        merge_stderr=True,
        timeout=120,
    )
    config_path = avd_home / f"{avd_name}.avd/config.ini"
    if not config_path.is_file():
        raise DeviceTransient("avdmanager did not create config.ini")
    write_deterministic_avd_config(
        config_path, avd_name, api_level, image
    )

    port = choose_emulator_port()
    serial = f"emulator-{port}"
    process = subprocess.Popen(
        [
            str(emulator),
            "-avd",
            avd_name,
            "-port",
            str(port),
            "-no-window",
            "-no-audio",
            "-no-boot-anim",
            "-no-snapshot",
            "-wipe-data",
            "-accel",
            "off",
            "-gpu",
            "swiftshader_indirect",
            "-camera-back",
            "none",
            "-camera-front",
            "none",
            "-netdelay",
            "none",
            "-netspeed",
            "full",
            "-no-metrics",
        ],
        cwd=attempt_root,
        env=merged_environment(environment),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    (attempt_root / "emulator.pid").write_text(
        f"{process.pid}\n", encoding="ascii"
    )
    try:
        wait_for_boot(adb, serial, process)
        verify_device(adb, serial, api_level, str(image["abi"]))
        install_probe(adb, serial, apk)

        provenance_args = instrumentation_provenance(
            api_level, toolchain
        )
        run_instrumentation(
            adb,
            serial,
            {"mode": "discover", **provenance_args},
            require_success=True,
        )
        metadata = pull_internal(adb, serial, "metadata.tsv")
        advertised = pull_internal(adb, serial, "advertised.tsv")
        advertised_rows = parse_tsv_bytes(
            advertised, f"API {api_level} advertised", ADVERTISED_HEADER
        )
        candidate_names = sorted(
            {
                row["candidate_name"]
                for row in advertised_rows
                if row["value_kind"] == "name"
            }
        )

        candidate_rows, candidate_format_rows = run_candidates(
            adb, serial, candidate_names, api_level
        )
        format_rows: list[dict[str, str]] = list(candidate_format_rows)

        run_instrumentation(
            adb,
            serial,
            {"mode": "representatives"},
            require_success=True,
        )
        representative_bytes = pull_internal(
            adb, serial, "representative-formats.tsv"
        )
        representative_rows = parse_tsv_bytes(
            representative_bytes,
            f"API {api_level} representative formats",
            FORMAT_HEADER,
        )
        format_rows.extend(representative_rows)

        candidate_rows.sort(key=lambda row: row["candidate_name"])
        instant_order = {
            str(item["id"]): index
            for index, item in enumerate(toolchain["fixed_instants"])
            if isinstance(item, dict)
        }
        format_rows.sort(
            key=lambda row: (
                row["source_kind"],
                row["source_id"],
                instant_order[row["instant_id"]],
            )
        )
        return {
            "advertised.tsv": advertised,
            "candidates.tsv": write_tsv_bytes(
                CANDIDATE_HEADER, candidate_rows
            ),
            "formats.tsv": write_tsv_bytes(FORMAT_HEADER, format_rows),
            "metadata.tsv": metadata,
        }
    except (RunnerError, subprocess.SubprocessError, OSError) as error:
        if isinstance(error, DeviceTransient):
            raise
        if not device_available(adb, serial):
            raise DeviceTransient(f"device became unavailable: {error}") from error
        raise
    finally:
        try:
            adb_command(
                adb, serial, ["uninstall", PACKAGE_NAME], timeout=30
            )
        except Exception:
            pass
        stop_emulator(adb, serial, process)
        try:
            run(
                [str(avdmanager), "delete", "avd", "--name", avd_name],
                env=environment,
                capture=True,
                merge_stderr=True,
                timeout=60,
            )
        except Exception:
            pass
        safe_rmtree(avd_home)
        safe_rmtree(emulator_home)


def run_candidate(
    adb: Path,
    serial: str,
    candidate_name: str,
    start_attempt: int = 1,
) -> tuple[dict[str, str], tuple[dict[str, str], ...]]:
    for attempt in range(start_attempt, 4):
        adb_command(
            adb,
            serial,
            [
                "shell",
                "run-as",
                PACKAGE_NAME,
                "rm",
                "-f",
                "files/candidate.tsv",
                "files/candidate-formats.tsv",
            ],
            timeout=30,
        )
        succeeded = run_instrumentation(
            adb,
            serial,
            {"mode": "candidate", "candidate": candidate_name},
            require_success=False,
        )
        if succeeded:
            try:
                candidate_bytes = pull_internal(
                    adb, serial, "candidate.tsv"
                )
                format_bytes = pull_internal(
                    adb, serial, "candidate-formats.tsv"
                )
            except RunnerError:
                succeeded = False
            else:
                candidates = parse_tsv_bytes(
                    candidate_bytes,
                    f"candidate {candidate_name}",
                    CANDIDATE_HEADER,
                )
                if len(candidates) != 1:
                    raise RunnerError(
                        f"{candidate_name}: expected one candidate row"
                    )
                candidate = candidates[0]
                if candidate["candidate_name"] != candidate_name:
                    raise RunnerError(
                        f"{candidate_name}: probe returned another candidate"
                    )
                candidate["attempts"] = str(attempt)
                formats = parse_tsv_bytes(
                    format_bytes,
                    f"candidate {candidate_name} formats",
                    FORMAT_HEADER,
                )
                return candidate, formats
        if not device_available(adb, serial):
            raise DeviceTransient(
                f"device unavailable while probing {candidate_name}"
            )
        if attempt < 3:
            time.sleep(2 ** attempt)

    values = {field: "" for field in CANDIDATE_HEADER}
    values.update(
        {
            "attempts": "3",
            "candidate_kind": "unknown",
            "candidate_name": candidate_name,
            "category": "error",
            "error_class": "instrumentation_process_failure",
            "error_stage": "format",
            "fallback": "false",
            "observed_output": "no_output",
            "row_count": "0",
            "strict_round_trip_all": "false",
        }
    )
    return values, ()


def run_candidates(
    adb: Path,
    serial: str,
    candidate_names: Sequence[str],
    api_level: int,
) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    candidate_rows: list[dict[str, str]] = []
    format_rows: list[dict[str, str]] = []
    start_index = 0

    while start_index < len(candidate_names):
        adb_command(
            adb,
            serial,
            [
                "shell",
                "run-as",
                PACKAGE_NAME,
                "rm",
                "-f",
                "files/batch-current.tsv",
                "files/batch-candidates.tsv",
                "files/batch-formats.tsv",
            ],
            timeout=30,
        )
        succeeded = run_instrumentation(
            adb,
            serial,
            {"mode": "batch", "start_index": str(start_index)},
            require_success=False,
            timeout=240,
        )
        segment_candidates = parse_optional_probe_tsv(
            adb,
            serial,
            "batch-candidates.tsv",
            CANDIDATE_HEADER,
            f"API {api_level} batch candidates",
        )
        segment_formats = parse_optional_probe_tsv(
            adb,
            serial,
            "batch-formats.tsv",
            FORMAT_HEADER,
            f"API {api_level} batch formats",
        )
        segment_names = [row["candidate_name"] for row in segment_candidates]
        expected_names = list(
            candidate_names[start_index:start_index + len(segment_names)]
        )
        if segment_names != expected_names:
            raise RunnerError(
                f"API {api_level}: batch candidate sequence mismatch"
            )
        candidate_rows.extend(segment_candidates)
        format_rows.extend(segment_formats)

        current_rows = parse_optional_probe_tsv(
            adb,
            serial,
            "batch-current.tsv",
            BATCH_CURRENT_HEADER,
            f"API {api_level} batch checkpoint",
        )
        if len(current_rows) != 1:
            raise DeviceTransient(
                f"API {api_level}: missing batch checkpoint"
            )
        checkpoint = current_rows[0]
        checkpoint_index = int(checkpoint["index"])

        if succeeded:
            if (
                checkpoint["state"] != "complete"
                or checkpoint["candidate_name"]
                or checkpoint_index != start_index + len(segment_names)
                or checkpoint_index <= start_index
                or checkpoint_index > len(candidate_names)
            ):
                raise RunnerError(
                    f"API {api_level}: invalid completed batch checkpoint"
                )
            start_index = checkpoint_index
            continue

        if checkpoint["state"] != "probing":
            raise DeviceTransient(
                f"API {api_level}: invalid failed batch checkpoint"
            )
        if checkpoint_index < start_index or checkpoint_index >= len(candidate_names):
            raise DeviceTransient(
                f"API {api_level}: failed batch index outside candidate set"
            )
        failed_name = candidate_names[checkpoint_index]
        if checkpoint["candidate_name"] != failed_name:
            raise DeviceTransient(
                f"API {api_level}: failed batch name/index mismatch"
            )

        completed_through_failure = (
            segment_names
            and segment_names[-1] == failed_name
        )
        if not completed_through_failure:
            if checkpoint_index != start_index + len(segment_names):
                raise DeviceTransient(
                    f"API {api_level}: failed batch checkpoint gap"
                )
            candidate, formats = run_candidate(
                adb,
                serial,
                failed_name,
                start_attempt=2,
            )
            candidate_rows.append(candidate)
            format_rows.extend(formats)
        start_index = checkpoint_index + 1
        print(
            f"    API {api_level}: resumed after "
            f"{failed_name} ({start_index}/{len(candidate_names)})"
        )

    print(
        f"    API {api_level}: probed "
        f"{len(candidate_rows)}/{len(candidate_names)} candidates"
    )
    if [row["candidate_name"] for row in candidate_rows] != list(candidate_names):
        raise RunnerError(
            f"API {api_level}: final candidate sequence mismatch"
        )
    return candidate_rows, format_rows


def run_instrumentation(
    adb: Path,
    serial: str,
    arguments: Mapping[str, str],
    require_success: bool,
    timeout: int = INSTRUMENTATION_TIMEOUT_SECONDS,
) -> bool:
    adb_command(
        adb,
        serial,
        ["shell", "am", "force-stop", PACKAGE_NAME],
        timeout=30,
    )
    tokens = ["am", "instrument", "-w", "-r"]
    for key, value in arguments.items():
        tokens.extend(["-e", key, value])
    tokens.append(INSTRUMENTATION_COMPONENT)
    command = " ".join(shlex.quote(token) for token in tokens)
    try:
        completed = adb_command(
            adb,
            serial,
            ["shell", command],
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        adb_command(
            adb,
            serial,
            ["shell", "am", "force-stop", PACKAGE_NAME],
            timeout=30,
        )
        if require_success:
            raise RunnerError("instrumentation timed out")
        return False
    output = completed.stdout
    success = (
        completed.returncode == 0
        and "INSTRUMENTATION_RESULT: probe_status=ok" in output
        and "INSTRUMENTATION_CODE: -1" in output
    )
    if require_success and not success:
        raise RunnerError(
            "instrumentation failed: "
            + normalize_process_output(output, completed.stderr)
        )
    return success


def pull_internal(adb: Path, serial: str, name: str) -> bytes:
    completed = subprocess.run(
        [
            str(adb),
            "-s",
            serial,
            "exec-out",
            "run-as",
            PACKAGE_NAME,
            "cat",
            f"files/{name}",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=60,
        check=False,
    )
    if completed.returncode != 0 or not completed.stdout:
        raise RunnerError(
            f"unable to read probe output {name}: "
            f"{completed.stderr.decode('utf-8', errors='replace').strip()}"
        )
    return completed.stdout


def parse_optional_probe_tsv(
    adb: Path,
    serial: str,
    name: str,
    header: Sequence[str],
    source: str,
) -> tuple[dict[str, str], ...]:
    try:
        raw = pull_internal(adb, serial, name)
    except RunnerError:
        return ()
    return parse_tsv_bytes(raw, source, header)


def instrumentation_provenance(
    api_level: int, toolchain: Mapping[str, object]
) -> dict[str, str]:
    sdk = mapping(toolchain, "sdk_packages")
    host_java = mapping(toolchain, "host_java")
    legacy_writer = mapping(toolchain, "legacy_writer")
    probe = mapping(toolchain, "probe")
    image = mapping(mapping(toolchain, "system_images"), str(api_level))
    build_tools = mapping(sdk, "build_tools")
    cmdline_tools = mapping(sdk, "cmdline_tools")
    compile_sdk = mapping(sdk, "compile_sdk")
    emulator = mapping(sdk, "emulator")
    platform_tools = mapping(sdk, "platform_tools")
    return {
        "build_tools_package": str(build_tools["package"]),
        "build_tools_revision": str(build_tools["revision"]),
        "cmdline_tools_package": str(cmdline_tools["package"]),
        "cmdline_tools_revision": str(cmdline_tools["revision"]),
        "compile_sdk_package": str(compile_sdk["package"]),
        "compile_sdk_revision": str(compile_sdk["revision"]),
        "emulator_package": str(emulator["package"]),
        "emulator_reported_version": str(emulator["reported_version"]),
        "emulator_revision": str(emulator["revision"]),
        "host_java_runtime_version": str(host_java["java_runtime_version"]),
        "host_java_vendor": str(host_java["java_vendor"]),
        "host_java_version": str(host_java["java_version"]),
        "host_javac_version": str(host_java["javac_version"]),
        "legacy_config_sha256": str(legacy_writer["config_sha256"]),
        "legacy_writer_sha256": str(legacy_writer["writer_sha256"]),
        "manifest_sha256": str(probe["manifest_sha256"]),
        "platform_tools_package": str(platform_tools["package"]),
        "platform_tools_revision": str(platform_tools["revision"]),
        "probe_source_sha256": str(probe["source_sha256"]),
        "system_image_package": str(image["package"]),
        "system_image_revision": str(image["revision"]),
        "toolchain_sha256": sha256_file(ROOT / "toolchain.json"),
    }


def write_deterministic_avd_config(
    config_path: Path,
    avd_name: str,
    api_level: int,
    image: Mapping[str, object],
) -> None:
    image_sysdir = str(image["relative_path"]).rstrip("/") + "/"
    values = {
        "AvdId": avd_name,
        "PlayStore.enabled": "false",
        "abi.type": str(image["abi"]),
        "avd.ini.displayname": f"SportLogger Formatter API {api_level}",
        "avd.ini.encoding": "UTF-8",
        "disk.dataPartition.size": "2147483648",
        "fastboot.forceColdBoot": "yes",
        "fastboot.forceFastBoot": "no",
        "hw.accelerometer": "no",
        "hw.arc": "false",
        "hw.audioInput": "no",
        "hw.battery": "yes",
        "hw.camera.back": "none",
        "hw.camera.front": "none",
        "hw.cpu.arch": str(image["abi"]),
        "hw.cpu.ncore": "2",
        "hw.dPad": "no",
        "hw.gps": "no",
        "hw.gpu.enabled": "yes",
        "hw.gpu.mode": "swiftshader_indirect",
        "hw.keyboard": "yes",
        "hw.lcd.density": "420",
        "hw.lcd.height": "1920",
        "hw.lcd.width": "1080",
        "hw.mainKeys": "no",
        "hw.ramSize": "4096",
        "hw.sdCard": "no",
        "image.sysdir.1": image_sysdir,
        "runtime.network.latency": "none",
        "runtime.network.speed": "full",
        "showDeviceFrame": "no",
        "skin.dynamic": "no",
        "skin.name": "1080x1920",
        "tag.display": "Google APIs",
        "tag.id": str(image["tag"]),
        "vm.heapSize": "512",
    }
    config_path.write_text(
        "".join(f"{key}={values[key]}\n" for key in sorted(values)),
        encoding="utf-8",
        newline="\n",
    )


def wait_for_boot(
    adb: Path, serial: str, process: subprocess.Popen[bytes]
) -> None:
    deadline = time.monotonic() + BOOT_TIMEOUT_SECONDS
    delay = 2.0
    stable_package_checks = 0
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise DeviceTransient(
                f"emulator process exited with code {process.returncode}"
            )
        try:
            state = adb_command(adb, serial, ["get-state"], timeout=5)
            if state.returncode != 0 or state.stdout.strip() != "device":
                time.sleep(delay)
                delay = min(delay * 1.25, 8.0)
                continue
            completed = adb_command(
                adb,
                serial,
                [
                    "shell",
                    "getprop",
                    "sys.boot_completed",
                ],
                timeout=10,
            )
            boot_completed = completed.stdout.strip() == "1"
            boot_animation = adb_command(
                adb,
                serial,
                ["shell", "getprop", "init.svc.bootanim"],
                timeout=10,
            ).stdout.strip()
            package_manager = adb_command(
                adb,
                serial,
                ["shell", "pm", "path", "android"],
                timeout=30,
            )
            package_service = adb_command(
                adb,
                serial,
                ["shell", "service", "check", "package"],
                timeout=30,
            )
            package_command = adb_command(
                adb,
                serial,
                ["shell", "cmd", "package", "list", "packages", "android"],
                timeout=30,
            )
        except (OSError, subprocess.SubprocessError):
            stable_package_checks = 0
            time.sleep(delay)
            delay = min(delay * 1.25, 8.0)
            continue
        if (
            boot_completed
            and boot_animation in {"stopped", ""}
            and package_manager.returncode == 0
            and package_manager.stdout.startswith("package:")
            and package_service.returncode == 0
            and "Service package: found" in package_service.stdout
            and package_command.returncode == 0
            and "package:android" in package_command.stdout
        ):
            stable_package_checks += 1
            if stable_package_checks < 3:
                time.sleep(5)
                continue
            try:
                adb_command(
                    adb,
                    serial,
                    ["shell", "input", "keyevent", "82"],
                    timeout=30,
                )
            except (OSError, subprocess.SubprocessError):
                pass
            return
        stable_package_checks = 0
        time.sleep(delay)
        delay = min(delay * 1.25, 8.0)
    raise DeviceTransient(
        f"boot did not complete within {BOOT_TIMEOUT_SECONDS} seconds"
    )


def verify_device(
    adb: Path, serial: str, api_level: int, expected_abi: str
) -> None:
    observed_api = adb_checked(
        adb, serial, ["shell", "getprop", "ro.build.version.sdk"]
    ).stdout.strip()
    observed_abi = adb_checked(
        adb, serial, ["shell", "getprop", "ro.product.cpu.abi"]
    ).stdout.strip()
    fingerprint = adb_checked(
        adb, serial, ["shell", "getprop", "ro.build.fingerprint"]
    ).stdout.strip()
    if observed_api != str(api_level):
        raise DeviceTransient(
            f"expected API {api_level}, device reported {observed_api}"
        )
    if observed_abi != expected_abi:
        raise DeviceTransient(
            f"expected ABI {expected_abi}, device reported {observed_abi}"
        )
    if not fingerprint:
        raise DeviceTransient("device build fingerprint is empty")


def install_probe(adb: Path, serial: str, apk: Path) -> None:
    last_error: Exception | None = None
    for attempt in range(1, 4):
        try:
            completed = adb_checked(
                adb,
                serial,
                ["install", "--no-incremental", "-r", "-t", str(apk)],
                timeout=300,
            )
            if "Success" not in completed.stdout:
                raise RunnerError(
                    f"adb install returned unexpected output: {completed.stdout!r}"
                )
            return
        except (RunnerError, subprocess.SubprocessError) as error:
            last_error = error
            if not device_available(adb, serial):
                raise DeviceTransient(
                    f"device unavailable during APK install: {error}"
                ) from error
            if attempt < 3:
                time.sleep(2 ** attempt)
    raise DeviceTransient(
        f"APK install failed after three attempts: {last_error}"
    )


def stop_emulator(
    adb: Path, serial: str, process: subprocess.Popen[bytes]
) -> None:
    if process.poll() is not None:
        return
    try:
        adb_command(adb, serial, ["emu", "kill"], timeout=20)
    except Exception:
        pass
    try:
        process.wait(timeout=30)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.kill(process.pid, signal.SIGTERM)
        process.wait(timeout=15)
        return
    except (ProcessLookupError, subprocess.TimeoutExpired):
        pass
    if process.poll() is None:
        try:
            os.kill(process.pid, signal.SIGKILL)
            process.wait(timeout=10)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            pass


def choose_emulator_port() -> int:
    for port in range(5580, 5680, 2):
        if port_pair_available(port):
            return port
    raise RunnerError("no free deterministic emulator port in 5580..5679")


def port_pair_available(port: int) -> bool:
    sockets: list[socket.socket] = []
    try:
        for candidate in (port, port + 1):
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("127.0.0.1", candidate))
            sockets.append(sock)
        return True
    except OSError:
        return False
    finally:
        for sock in sockets:
            sock.close()


def device_available(adb: Path, serial: str) -> bool:
    try:
        completed = adb_command(
            adb, serial, ["get-state"], timeout=10
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return completed.returncode == 0 and completed.stdout.strip() == "device"


def adb_checked(
    adb: Path,
    serial: str,
    arguments: Sequence[str],
    timeout: int = 60,
) -> subprocess.CompletedProcess[str]:
    completed = adb_command(adb, serial, arguments, timeout=timeout)
    if completed.returncode != 0:
        raise RunnerError(
            f"adb command failed: "
            f"{normalize_process_output(completed.stdout, completed.stderr)}"
        )
    return completed


def adb_command(
    adb: Path,
    serial: str,
    arguments: Sequence[str],
    timeout: int = 60,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(adb), "-s", serial, *arguments],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=timeout,
        check=False,
    )


def validate_generated_evidence(
    evidence_dir: Path, toolchain: Mapping[str, object]
) -> None:
    evidence_by_api: dict[int, ApiEvidence] = {}
    for api_level in API_LEVELS:
        evidence = load_api_evidence_from_dir(evidence_dir, api_level)
        validate_api_evidence(ROOT, toolchain, evidence)
        evidence_by_api[api_level] = evidence
    validate_no_volatile_data(ROOT, toolchain, evidence_dir)
    expected_summary = canonical_json_bytes(
        build_union_summary(toolchain, evidence_by_api)
    )
    actual_summary = (evidence_dir / "union-summary.json").read_bytes()
    if actual_summary != expected_summary:
        raise RunnerError("generated union summary mismatch")


def compare_evidence_directories(expected: Path, actual: Path) -> None:
    expected_files = sorted(
        path.name for path in expected.iterdir() if path.is_file()
    )
    actual_files = sorted(
        path.name for path in actual.iterdir() if path.is_file()
    )
    if expected_files != actual_files:
        raise RunnerError(
            f"evidence file set mismatch: expected {expected_files}, "
            f"got {actual_files}"
        )
    for name in expected_files:
        expected_bytes = (expected / name).read_bytes()
        actual_bytes = (actual / name).read_bytes()
        if expected_bytes != actual_bytes:
            raise RunnerError(
                f"byte mismatch for {name}: "
                f"expected SHA-256 {sha256_file(expected / name)}, "
                f"got {sha256_file(actual / name)}"
            )


def publish_evidence(generated: Path) -> None:
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    generated_names = {
        path.name for path in generated.iterdir() if path.is_file()
    }
    for existing in EVIDENCE_DIR.iterdir():
        if existing.is_file() and existing.name not in generated_names:
            existing.unlink()
    for source in sorted(generated.iterdir()):
        if not source.is_file():
            continue
        destination = EVIDENCE_DIR / source.name
        temporary = EVIDENCE_DIR / f".{source.name}.new"
        temporary.write_bytes(source.read_bytes())
        temporary.replace(destination)


def parse_java_properties(output: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in output.splitlines():
        match = re.match(r"\s+([A-Za-z0-9_.]+) = (.*)", line)
        if match:
            result[match.group(1)] = match.group(2)
    return result


def parse_sdkmanager_identifiers(output: str) -> set[str]:
    result: set[str] = set()
    for line in output.splitlines():
        if "|" not in line:
            continue
        candidate = line.split("|", 1)[0].strip()
        if (candidate and ";" in candidate) or candidate in {
            "emulator",
            "platform-tools",
        }:
            result.add(candidate)
    return result


def normalize_emulator_version(first_line: str) -> str:
    prefix = "Android emulator version "
    if not first_line.startswith(prefix):
        raise RunnerError(f"unexpected emulator version line: {first_line!r}")
    value = first_line[len(prefix):]
    value = re.sub(r"\s+\(CL:[^)]*\)$", "", value)
    return value


def read_source_property(package_path: Path, key: str) -> str:
    source_properties = package_path / "source.properties"
    if not source_properties.is_file():
        raise RunnerError(f"missing {source_properties}")
    for line in source_properties.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{key}="):
            return line.split("=", 1)[1].strip()
    raise RunnerError(f"{source_properties}: missing {key}")


def read_optional_source_property(
    package_path: Path, key: str
) -> str | None:
    source_properties = package_path / "source.properties"
    if not source_properties.is_file():
        raise RunnerError(f"missing {source_properties}")
    for line in source_properties.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{key}="):
            return line.split("=", 1)[1].strip()
    return None


def normalize_process_output(stdout: str, stderr: str) -> str:
    combined = " ".join(
        part.strip() for part in (stdout, stderr) if part.strip()
    )
    return re.sub(r"\s+", " ", combined)[:1000]


def mapping(value: Mapping[str, object], key: str) -> Mapping[str, object]:
    nested = value.get(key)
    if not isinstance(nested, dict):
        raise RunnerError(f"toolchain {key} must be a mapping")
    return nested


def require_executable(path: Path) -> None:
    if not path.is_file() or not os.access(path, os.X_OK):
        raise RunnerError(f"required executable is missing: {path}")


def run(
    arguments: Sequence[str],
    *,
    capture: bool = False,
    cwd: Path | None = None,
    env: Mapping[str, str] | None = None,
    input_text: str | None = None,
    merge_stderr: bool = False,
    timeout: int = 120,
) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        list(arguments),
        cwd=cwd,
        env=merged_environment(env),
        input=input_text,
        stdout=subprocess.PIPE if capture else None,
        stderr=(
            subprocess.STDOUT
            if capture and merge_stderr
            else (subprocess.PIPE if capture else None)
        ),
        text=True,
        timeout=timeout,
        check=False,
    )
    if completed.returncode != 0:
        stdout = completed.stdout or ""
        stderr = completed.stderr or ""
        raise RunnerError(
            f"command failed ({completed.returncode}): "
            f"{' '.join(shlex.quote(item) for item in arguments)}\n"
            f"{normalize_process_output(stdout, stderr)}"
        )
    return completed


def merged_environment(
    additions: Mapping[str, str] | None,
) -> dict[str, str]:
    environment = os.environ.copy()
    if additions:
        environment.update({key: str(value) for key, value in additions.items()})
    return environment


def cleanup_stale_work() -> None:
    if not WORK_ROOT.exists():
        return
    for pid_path in WORK_ROOT.rglob("emulator.pid"):
        try:
            pid = int(pid_path.read_text(encoding="ascii").strip())
        except (OSError, ValueError):
            continue
        try:
            command_line = Path(f"/proc/{pid}/cmdline").read_bytes()
        except OSError:
            continue
        if b"/emulator" not in command_line:
            raise RunnerError(
                f"refusing to stop unexpected live PID recorded in {pid_path}"
            )
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            continue
        for _ in range(30):
            if not Path(f"/proc/{pid}").exists():
                break
            time.sleep(1)
        if Path(f"/proc/{pid}").exists():
            os.kill(pid, signal.SIGKILL)
    safe_rmtree(WORK_ROOT)


def cleanup_work() -> None:
    if WORK_ROOT.exists():
        cleanup_stale_work()


def safe_rmtree(path: Path) -> None:
    resolved = path.resolve()
    root = ROOT.resolve()
    if resolved == root or root not in resolved.parents:
        raise RunnerError(f"refusing to remove path outside tool directory: {path}")
    shutil.rmtree(resolved, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
