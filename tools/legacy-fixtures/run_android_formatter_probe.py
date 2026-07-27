#!/usr/bin/env python3
"""Build and run the pinned Android formatter probe on API 26 and API 36."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, Mapping, Sequence, Tuple


TOOL_ROOT = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOL_ROOT.parent.parent
WORK_ROOT = TOOL_ROOT / "generated" / "android-formatter-probe"
SOURCE = TOOL_ROOT / "AndroidLocaleTimestampProbeTest.java"

TARGET_PACKAGE = "com.long2know.sportlogger.localeprobe"
TEST_PACKAGE = TARGET_PACKAGE + ".test"
TEST_CLASS = TARGET_PACKAGE + ".AndroidLocaleTimestampProbeTest"
OUTPUT_FILE = "android-locale-timestamp-probe.tsv"

COMPILE_SDK_PACKAGE = "platforms;android-36"
COMPILE_SDK_REVISION = "2.0.0"
BUILD_TOOLS_PACKAGE = "build-tools;36.0.0"
BUILD_TOOLS_REVISION = "36.0.0"
EMULATOR_PACKAGE = "emulator"
EMULATOR_PACKAGE_REVISION = "36.6.11"
EMULATOR_VERSION = "36.6.11.0"
RUNNER_VERSION = "1"
RUNNER_COMMAND = (
    "python3 tools/legacy-fixtures/run_android_formatter_probe.py --compare"
)


@dataclass(frozen=True)
class Platform:
    api_level: int
    system_image_package: str
    system_image_revision: str
    evidence_path: Path


PLATFORMS = (
    Platform(
        api_level=26,
        system_image_package="system-images;android-26;google_apis;x86_64",
        system_image_revision="16.0.0",
        evidence_path=TOOL_ROOT / "AndroidLocaleTimestampProbe.api26.tsv",
    ),
    Platform(
        api_level=36,
        system_image_package="system-images;android-36;google_apis;x86_64",
        system_image_revision="7.0.0",
        evidence_path=TOOL_ROOT / "AndroidLocaleTimestampProbe.api36.tsv",
    ),
)


class ProbeError(RuntimeError):
    pass


def command_text(command: Sequence[object]) -> str:
    return " ".join(str(value) for value in command)


def run(
    command: Sequence[object],
    *,
    env: Mapping[str, str],
    capture: bool = True,
    timeout: int = 180,
) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(
        [str(value) for value in command],
        cwd=REPOSITORY_ROOT,
        env=dict(env),
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
        timeout=timeout,
        check=False,
    )
    if result.returncode != 0:
        stdout = result.stdout.decode("utf-8", errors="replace") if result.stdout else ""
        stderr = result.stderr.decode("utf-8", errors="replace") if result.stderr else ""
        raise ProbeError(
            "Command failed ({}): {}\n{}{}".format(
                result.returncode,
                command_text(command),
                stdout,
                stderr,
            )
        )
    return result


def required_environment() -> Tuple[Path, Path, Path, Dict[str, str]]:
    missing = [
        name
        for name in ("ANDROID_SDK_ROOT", "ANDROID_AVD_HOME", "JAVA_HOME")
        if not os.environ.get(name)
    ]
    if missing:
        raise ProbeError(
            "Missing required environment variable(s): {}".format(", ".join(missing))
        )
    sdk_root = Path(os.environ["ANDROID_SDK_ROOT"]).expanduser().resolve()
    avd_home = Path(os.environ["ANDROID_AVD_HOME"]).expanduser().resolve()
    java_home = Path(os.environ["JAVA_HOME"]).expanduser().resolve()
    if not sdk_root.is_dir() or not avd_home.is_dir() or not java_home.is_dir():
        raise ProbeError("ANDROID_SDK_ROOT, ANDROID_AVD_HOME, and JAVA_HOME must exist")
    env = dict(os.environ)
    env.update(
        {
            "ANDROID_HOME": str(sdk_root),
            "ANDROID_SDK_ROOT": str(sdk_root),
            "ANDROID_AVD_HOME": str(avd_home),
            "JAVA_HOME": str(java_home),
        }
    )
    return sdk_root, avd_home, java_home, env


def package_directory(sdk_root: Path, package_name: str) -> Path:
    return sdk_root.joinpath(*package_name.split(";"))


def package_revision(sdk_root: Path, package_name: str) -> str:
    package_xml = package_directory(sdk_root, package_name) / "package.xml"
    if not package_xml.is_file():
        raise ProbeError("Missing installed SDK package {}".format(package_name))
    root = ET.parse(package_xml).getroot()
    local_package = root.find(".//localPackage")
    if local_package is None or local_package.get("path") != package_name:
        raise ProbeError("SDK package metadata mismatch for {}".format(package_name))
    revision = local_package.find("revision")
    if revision is None:
        raise ProbeError("SDK package {} has no revision".format(package_name))
    values = [
        int(revision.findtext(name, default="0"))
        for name in ("major", "minor", "micro")
    ]
    return "{}.{}.{}".format(*values)


def require_package(
    sdk_root: Path,
    package_name: str,
    expected_revision: str,
) -> None:
    actual = package_revision(sdk_root, package_name)
    if actual != expected_revision:
        raise ProbeError(
            "{} revision changed: expected {}, found {}".format(
                package_name,
                expected_revision,
                actual,
            )
        )


def executable(path: Path) -> Path:
    if not path.is_file() or not os.access(path, os.X_OK):
        raise ProbeError("Missing executable {}".format(path))
    return path


def parse_ini(path: Path) -> Mapping[str, str]:
    values: Dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def discover_avd(avd_home: Path, platform: Platform) -> str:
    expected_image = platform.system_image_package.replace(";", "/") + "/"
    matches = []
    for config in sorted(avd_home.glob("*.avd/config.ini")):
        values = parse_ini(config)
        if values.get("image.sysdir.1") != expected_image:
            continue
        if values.get("abi.type") != "x86_64" or values.get("hw.cpu.arch") != "x86_64":
            raise ProbeError("{} is not the pinned x86_64 ABI".format(config))
        matches.append(config.parent.name.removesuffix(".avd"))
    if len(matches) != 1:
        raise ProbeError(
            "Expected exactly one AVD for {}, found {}".format(
                platform.system_image_package,
                len(matches),
            )
        )
    return matches[0]


def read_emulator_version(
    emulator: Path,
    env: Mapping[str, str],
) -> str:
    result = run((emulator, "-version"), env=env)
    first_line = result.stdout.decode("utf-8").splitlines()[0]
    match = re.fullmatch(r"Android emulator version ([0-9.]+).*", first_line)
    if match is None:
        raise ProbeError("Could not parse Android emulator version")
    return match.group(1)


def write_manifests(work: Path) -> Tuple[Path, Path]:
    target_manifest = work / "AndroidManifest.target.xml"
    test_manifest = work / "AndroidManifest.test.xml"
    target_manifest.write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{target}">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
    <application
        android:allowBackup="false"
        android:debuggable="true"
        android:hasCode="true"
        android:label="SportLogger formatter probe" />
</manifest>
""".format(target=TARGET_PACKAGE),
        encoding="utf-8",
        newline="\n",
    )
    test_manifest.write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{test}">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
    <application
        android:allowBackup="false"
        android:debuggable="true"
        android:hasCode="true"
        android:label="SportLogger formatter probe tests">
        <uses-library android:name="android.test.runner" android:required="true" />
    </application>
    <instrumentation
        android:name="android.test.InstrumentationTestRunner"
        android:functionalTest="false"
        android:handleProfiling="false"
        android:targetPackage="{target}" />
</manifest>
""".format(test=TEST_PACKAGE, target=TARGET_PACKAGE),
        encoding="utf-8",
        newline="\n",
    )
    return target_manifest, test_manifest


def sign_apk(
    source: Path,
    destination: Path,
    *,
    zipalign: Path,
    apksigner: Path,
    keystore: Path,
    env: Mapping[str, str],
) -> None:
    aligned = destination.with_suffix(".aligned.apk")
    run((zipalign, "-f", "4", source, aligned), env=env)
    run(
        (
            apksigner,
            "sign",
            "--ks",
            keystore,
            "--ks-key-alias",
            "probe",
            "--ks-pass",
            "pass:android",
            "--key-pass",
            "pass:android",
            "--out",
            destination,
            aligned,
        ),
        env=env,
    )
    run((apksigner, "verify", destination), env=env)


def build_probe(
    sdk_root: Path,
    java_home: Path,
    env: Mapping[str, str],
) -> Tuple[Path, Path]:
    if WORK_ROOT.exists():
        shutil.rmtree(WORK_ROOT)
    WORK_ROOT.mkdir(parents=True)
    classes = WORK_ROOT / "classes"
    dex = WORK_ROOT / "dex"
    target_classes = WORK_ROOT / "target-classes"
    target_dex = WORK_ROOT / "target-dex"
    classes.mkdir()
    dex.mkdir()
    target_classes.mkdir()
    target_dex.mkdir()

    build_tools = package_directory(sdk_root, BUILD_TOOLS_PACKAGE)
    platform = package_directory(sdk_root, COMPILE_SDK_PACKAGE)
    android_jar = platform / "android.jar"
    test_base_jar = platform / "optional" / "android.test.base.jar"
    test_runner_jar = platform / "optional" / "android.test.runner.jar"
    aapt2 = executable(build_tools / "aapt2")
    d8 = executable(build_tools / "d8")
    zipalign = executable(build_tools / "zipalign")
    apksigner = executable(build_tools / "apksigner")
    javac = executable(java_home / "bin" / "javac")
    jar = executable(java_home / "bin" / "jar")
    keytool = executable(java_home / "bin" / "keytool")
    for required in (android_jar, test_base_jar, test_runner_jar, SOURCE):
        if not required.is_file():
            raise ProbeError("Missing build input {}".format(required))

    target_manifest, test_manifest = write_manifests(WORK_ROOT)
    target_source = WORK_ROOT / "ProbeTarget.java"
    target_source.write_text(
        """package {target};

public final class ProbeTarget {{
    private ProbeTarget() {{}}
}}
""".format(target=TARGET_PACKAGE),
        encoding="utf-8",
        newline="\n",
    )
    classpath = os.pathsep.join(
        str(path) for path in (android_jar, test_base_jar, test_runner_jar)
    )
    run(
        (
            javac,
            "--release",
            "8",
            "-encoding",
            "UTF-8",
            "-classpath",
            str(android_jar),
            "-d",
            target_classes,
            target_source,
        ),
        env=env,
    )
    target_classes_jar = WORK_ROOT / "target-classes.jar"
    run(
        (
            jar,
            "--create",
            "--file",
            target_classes_jar,
            "-C",
            target_classes,
            ".",
        ),
        env=env,
    )
    run(
        (
            d8,
            "--min-api",
            "26",
            "--lib",
            android_jar,
            "--output",
            target_dex,
            target_classes_jar,
        ),
        env=env,
    )
    run(
        (
            javac,
            "--release",
            "8",
            "-encoding",
            "UTF-8",
            "-classpath",
            classpath,
            "-d",
            classes,
            SOURCE,
        ),
        env=env,
    )
    classes_jar = WORK_ROOT / "classes.jar"
    run((jar, "--create", "--file", classes_jar, "-C", classes, "."), env=env)
    run(
        (
            d8,
            "--min-api",
            "26",
            "--lib",
            android_jar,
            "--lib",
            test_base_jar,
            "--lib",
            test_runner_jar,
            "--output",
            dex,
            classes_jar,
        ),
        env=env,
    )

    target_unsigned = WORK_ROOT / "target-unsigned.apk"
    test_unsigned = WORK_ROOT / "test-unsigned.apk"
    for manifest, output in (
        (target_manifest, target_unsigned),
        (test_manifest, test_unsigned),
    ):
        run(
            (
                aapt2,
                "link",
                "-I",
                android_jar,
                "--manifest",
                manifest,
                "--min-sdk-version",
                "26",
                "--target-sdk-version",
                "36",
                "--version-code",
                "1",
                "--version-name",
                "1",
                "-o",
                output,
            ),
            env=env,
        )
    with zipfile.ZipFile(target_unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(target_dex / "classes.dex", "classes.dex")
    with zipfile.ZipFile(test_unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(dex / "classes.dex", "classes.dex")

    keystore = WORK_ROOT / "probe.keystore"
    run(
        (
            keytool,
            "-genkeypair",
            "-keystore",
            keystore,
            "-storepass",
            "android",
            "-keypass",
            "android",
            "-alias",
            "probe",
            "-dname",
            "CN=SportLogger Formatter Probe,O=SportLogger,C=US",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-noprompt",
        ),
        env=env,
    )
    target_apk = WORK_ROOT / "target.apk"
    test_apk = WORK_ROOT / "test.apk"
    sign_apk(
        target_unsigned,
        target_apk,
        zipalign=zipalign,
        apksigner=apksigner,
        keystore=keystore,
        env=env,
    )
    sign_apk(
        test_unsigned,
        test_apk,
        zipalign=zipalign,
        apksigner=apksigner,
        keystore=keystore,
        env=env,
    )
    return target_apk, test_apk


def adb_text(
    adb: Path,
    serial: str,
    arguments: Iterable[object],
    env: Mapping[str, str],
    timeout: int = 180,
) -> str:
    result = run((adb, "-s", serial, *arguments), env=env, timeout=timeout)
    return result.stdout.decode("utf-8", errors="strict").strip()


def require_no_running_emulator(
    adb: Path,
    env: Mapping[str, str],
) -> None:
    output = run((adb, "devices"), env=env).stdout.decode("utf-8")
    serials = [
        line.split("\t", 1)[0]
        for line in output.splitlines()[1:]
        if "\t" in line and line.split("\t", 1)[0].startswith("emulator-")
    ]
    if serials:
        raise ProbeError(
            "Stop all running Android emulators before reproducing formatter evidence"
        )


def wait_for_boot(
    process: subprocess.Popen[bytes],
    adb: Path,
    serial: str,
    env: Mapping[str, str],
    timeout_seconds: int = 900,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ProbeError(
                "Android emulator exited before boot (status {})".format(
                    process.returncode
                )
            )
        result = subprocess.run(
            [str(adb), "-s", serial, "shell", "getprop", "sys.boot_completed"],
            cwd=REPOSITORY_ROOT,
            env=dict(env),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=30,
            check=False,
        )
        if result.returncode == 0 and result.stdout.strip() == b"1":
            return
        time.sleep(5)
    raise ProbeError("Timed out waiting for the software-emulated Android boot")


def add_runner_provenance(raw: bytes, platform: Platform) -> bytes:
    if not raw.endswith(b"\n") or b"\r" in raw:
        raise ProbeError("Probe output is not UTF-8/LF text")
    text = raw.decode("utf-8")
    lines = text.splitlines()
    if lines[:2] != [
        "meta\tformat_version\t2",
        "meta\tplatform\tAndroid",
    ]:
        raise ProbeError("Probe output metadata prefix changed")
    provenance = [
        "meta\tsystem_image_package\t{}".format(platform.system_image_package),
        "meta\tsystem_image_revision\t{}".format(platform.system_image_revision),
        "meta\temulator_package\t{}".format(EMULATOR_PACKAGE),
        "meta\temulator_package_revision\t{}".format(EMULATOR_PACKAGE_REVISION),
        "meta\temulator_version\t{}".format(EMULATOR_VERSION),
        "meta\temulator_acceleration\toff",
        "meta\tcompile_sdk_package\t{}".format(COMPILE_SDK_PACKAGE),
        "meta\tcompile_sdk_revision\t{}".format(COMPILE_SDK_REVISION),
        "meta\tbuild_tools_package\t{}".format(BUILD_TOOLS_PACKAGE),
        "meta\tbuild_tools_revision\t{}".format(BUILD_TOOLS_REVISION),
        "meta\tprobe_runner\trun_android_formatter_probe.py",
        "meta\tprobe_runner_version\t{}".format(RUNNER_VERSION),
        "meta\tprobe_runner_command\t{}".format(RUNNER_COMMAND),
    ]
    return ("\n".join(lines[:2] + provenance + lines[2:]) + "\n").encode("utf-8")


def run_platform(
    platform: Platform,
    *,
    avd_name: str,
    emulator: Path,
    adb: Path,
    target_apk: Path,
    test_apk: Path,
    env: Mapping[str, str],
    port: int,
) -> bytes:
    serial = "emulator-{}".format(port)
    command = (
        emulator,
        "-avd",
        avd_name,
        "-port",
        str(port),
        "-no-window",
        "-no-audio",
        "-no-boot-anim",
        "-no-snapshot-load",
        "-no-snapshot-save",
        "-read-only",
        "-accel",
        "off",
        "-no-metrics",
    )
    process = subprocess.Popen(
        [str(value) for value in command],
        cwd=REPOSITORY_ROOT,
        env=dict(env),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        wait_for_boot(process, adb, serial, env)
        api_level = adb_text(adb, serial, ("shell", "getprop", "ro.build.version.sdk"), env)
        abi = adb_text(adb, serial, ("shell", "getprop", "ro.product.cpu.abi"), env)
        fingerprint = adb_text(
            adb,
            serial,
            ("shell", "getprop", "ro.build.fingerprint"),
            env,
        )
        if api_level != str(platform.api_level) or abi != "x86_64" or not fingerprint:
            raise ProbeError(
                "Booted image does not match API {}/x86_64 provenance".format(
                    platform.api_level
                )
            )

        def install_apk(apk: Path) -> None:
            failures = []
            for attempt in range(2):
                try:
                    run(
                        (adb, "-s", serial, "install", "-r", "-t", apk),
                        env=env,
                        timeout=600,
                    )
                    if attempt:
                        print(
                            "API {} installed {} on one retry.".format(
                                platform.api_level,
                                apk.name,
                            ),
                            flush=True,
                        )
                    return
                except (ProbeError, subprocess.TimeoutExpired) as error:
                    failures.append(str(error))
                    time.sleep(5)
            raise ProbeError(
                "API {} could not install {} twice:\n{}".format(
                    platform.api_level,
                    apk.name,
                    "\n".join(failures),
                )
            )

        install_apk(target_apk)
        install_apk(test_apk)

        def remove_probe_output() -> None:
            run(
                (
                    adb,
                    "-s",
                    serial,
                    "shell",
                    "run-as",
                    TARGET_PACKAGE,
                    "rm",
                    "-f",
                    "files/" + OUTPUT_FILE,
                ),
                env=env,
            )

        def force_stop_probe() -> None:
            for package in (TARGET_PACKAGE, TEST_PACKAGE):
                subprocess.run(
                    [str(adb), "-s", serial, "shell", "am", "force-stop", package],
                    cwd=REPOSITORY_ROOT,
                    env=dict(env),
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=60,
                    check=False,
                )

        def instrument(
            arguments: Sequence[str],
            timeout_seconds: int = 300,
        ) -> str:
            command = [
                str(adb),
                "-s",
                serial,
                "shell",
                "am",
                "instrument",
                "-w",
                "-r",
                "-e",
                "class",
                TEST_CLASS,
            ]
            for key, value in zip(arguments[0::2], arguments[1::2]):
                command.extend(("-e", key, value))
            command.append(TEST_PACKAGE + "/android.test.InstrumentationTestRunner")
            result = subprocess.run(
                command,
                cwd=REPOSITORY_ROOT,
                env=dict(env),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=timeout_seconds,
                check=False,
            )
            return result.stdout.decode("utf-8", errors="replace")

        def instrument_candidate(candidate_name: str) -> str:
            arguments = (
                "probe_mode",
                "candidate",
                "numbering_candidate",
                candidate_name,
            )
            first = instrument(arguments)
            if (
                "OK (1 test)" in first
                or "shortMsg=Process crashed" in first
            ):
                return first
            force_stop_probe()
            time.sleep(2)
            second = instrument(arguments)
            if (
                "OK (1 test)" in second
                or "shortMsg=Process crashed" in second
            ):
                print(
                    "API {} candidate {} passed on one retry.".format(
                        platform.api_level,
                        candidate_name,
                    ),
                    flush=True,
                )
                return second
            raise ProbeError(
                "{} candidate instrumentation failed twice:\nfirst:\n{}\n"
                "second:\n{}".format(candidate_name, first, second)
            )

        def read_probe_output() -> bytes:
            return run(
                (
                    adb,
                    "-s",
                    serial,
                    "exec-out",
                    "run-as",
                    TARGET_PACKAGE,
                    "cat",
                    "files/" + OUTPUT_FILE,
                ),
                env=env,
            ).stdout

        remove_probe_output()
        instrumentation_text = instrument(
            ("probe_mode", "base"),
            timeout_seconds=1200,
        )
        if "OK (1 test)" not in instrumentation_text:
            logcat = subprocess.run(
                [
                    str(adb),
                    "-s",
                    serial,
                    "logcat",
                    "-d",
                    "-t",
                    "1000",
                    "AndroidRuntime:E",
                    "ActivityManager:I",
                    "libc:F",
                    "DEBUG:F",
                    "*:S",
                ],
                cwd=REPOSITORY_ROOT,
                env=dict(env),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=60,
                check=False,
            ).stdout.decode("utf-8", errors="replace")
            raise ProbeError(
                "Formatter instrumentation did not pass:\n{}\n{}".format(
                    instrumentation_text,
                    logcat,
                )
            )
        base = read_probe_output()
        text = base.decode("utf-8")
        metadata = dict(
            line.split("\t", 2)[1:]
            for line in text.splitlines()
            if line.startswith("meta\t") and len(line.split("\t", 2)) == 3
        )
        if (
            metadata.get("api_level") != str(platform.api_level)
            or metadata.get("abi") != abi
            or metadata.get("build_fingerprint") != fingerprint
        ):
            raise ProbeError("Probe/device provenance mismatch")
        candidate_names = [
            fields[1]
            for line in text.splitlines()
            if (fields := line.split("\t"))[0] == "candidate_definition"
            and len(fields) in (3, 7)
        ]
        if (
            candidate_names != sorted(candidate_names)
            or len(candidate_names) != len(set(candidate_names))
            or len(candidate_names)
            != int(metadata.get("numbering_candidate_count", "-1"))
        ):
            raise ProbeError("Numbering candidate definitions are incomplete")
        force_stop_probe()

        candidate_rows = []
        native_crashes = []
        for candidate_name in candidate_names:
            remove_probe_output()
            candidate_result = instrument_candidate(candidate_name)
            if "OK (1 test)" in candidate_result:
                candidate_output = read_probe_output()
                if (
                    not candidate_output.endswith(b"\n")
                    or b"\r" in candidate_output
                    or candidate_output.count(b"\n") != 1
                ):
                    raise ProbeError(
                        "{} candidate output is not one UTF-8/LF row".format(
                            candidate_name
                        )
                    )
                candidate_fields = candidate_output.decode("utf-8").rstrip("\n").split(
                    "\t"
                )
                if (
                    candidate_fields[0]
                    not in ("numbering_candidate", "candidate_failure")
                    or candidate_fields[1] != candidate_name
                ):
                    raise ProbeError(
                        "{} candidate output identity changed".format(candidate_name)
                    )
                candidate_rows.append(candidate_output)
            elif "shortMsg=Process crashed" in candidate_result:
                native_crashes.append(candidate_name)
                candidate_rows.append(
                    (
                        "candidate_failure\t{}\tnative_process_crash\n".format(
                            candidate_name
                        )
                    ).encode("utf-8")
                )
            else:
                raise ProbeError(
                    "{} candidate instrumentation returned an unknown result:\n{}".format(
                        candidate_name,
                        candidate_result,
                    )
                )
            force_stop_probe()

        print(
            "API {} probed {} numbering candidates ({} isolated native crashes).".format(
                platform.api_level,
                len(candidate_names),
                len(native_crashes),
            ),
            flush=True,
        )
        return add_runner_provenance(base + b"".join(candidate_rows), platform)
    finally:
        subprocess.run(
            [str(adb), "-s", serial, "uninstall", TEST_PACKAGE],
            cwd=REPOSITORY_ROOT,
            env=dict(env),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=60,
            check=False,
        )
        subprocess.run(
            [str(adb), "-s", serial, "uninstall", TARGET_PACKAGE],
            cwd=REPOSITORY_ROOT,
            env=dict(env),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=60,
            check=False,
        )
        subprocess.run(
            [str(adb), "-s", serial, "emu", "kill"],
            cwd=REPOSITORY_ROOT,
            env=dict(env),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=60,
            check=False,
        )
        try:
            process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=30)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Rebuild the formatter probe, boot pinned API26/API36 AVDs "
            "sequentially with software acceleration, and compare or update TSVs."
        )
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--compare",
        action="store_true",
        help="compare reproduced bytes with the committed TSVs (default)",
    )
    mode.add_argument(
        "--update",
        action="store_true",
        help="replace the committed TSVs with reproduced bytes",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=5580,
        help="even emulator console port used sequentially (default: 5580)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.port < 5554 or args.port > 5682 or args.port % 2:
        raise ProbeError("--port must be an even emulator port from 5554 through 5682")
    sdk_root, avd_home, java_home, env = required_environment()
    require_package(sdk_root, COMPILE_SDK_PACKAGE, COMPILE_SDK_REVISION)
    require_package(sdk_root, BUILD_TOOLS_PACKAGE, BUILD_TOOLS_REVISION)
    require_package(sdk_root, EMULATOR_PACKAGE, EMULATOR_PACKAGE_REVISION)
    for platform in PLATFORMS:
        require_package(
            sdk_root,
            platform.system_image_package,
            platform.system_image_revision,
        )

    emulator = executable(package_directory(sdk_root, EMULATOR_PACKAGE) / "emulator")
    adb = executable(sdk_root / "platform-tools" / "adb")
    actual_emulator_version = read_emulator_version(emulator, env)
    if actual_emulator_version != EMULATOR_VERSION:
        raise ProbeError(
            "Emulator version changed: expected {}, found {}".format(
                EMULATOR_VERSION,
                actual_emulator_version,
            )
        )
    require_no_running_emulator(adb, env)
    avds = {platform: discover_avd(avd_home, platform) for platform in PLATFORMS}

    try:
        target_apk, test_apk = build_probe(sdk_root, java_home, env)
        reproduced = {}
        for platform in PLATFORMS:
            print(
                "Probing Android API {} with -accel off...".format(
                    platform.api_level
                ),
                flush=True,
            )
            reproduced[platform] = run_platform(
                platform,
                avd_name=avds[platform],
                emulator=emulator,
                adb=adb,
                target_apk=target_apk,
                test_apk=test_apk,
                env=env,
                port=args.port,
            )

        for platform in PLATFORMS:
            data = reproduced[platform]
            if args.update:
                platform.evidence_path.write_bytes(data)
                if platform.evidence_path.read_bytes() != data:
                    raise ProbeError(
                        "Failed to persist {}".format(platform.evidence_path.name)
                    )
                print("Updated {}".format(platform.evidence_path.relative_to(REPOSITORY_ROOT)))
            else:
                if not platform.evidence_path.is_file():
                    raise ProbeError(
                        "Missing committed evidence {}".format(
                            platform.evidence_path.name
                        )
                    )
                if platform.evidence_path.read_bytes() != data:
                    raise ProbeError(
                        "{} does not match reproduced Android API {} bytes".format(
                            platform.evidence_path.name,
                            platform.api_level,
                        )
                    )
                print(
                    "Matched {}".format(
                        platform.evidence_path.relative_to(REPOSITORY_ROOT)
                    )
                )
        return 0
    finally:
        if WORK_ROOT.exists():
            shutil.rmtree(WORK_ROOT)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ProbeError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        print("formatter probe failed: {}".format(error), file=sys.stderr)
        sys.exit(1)
