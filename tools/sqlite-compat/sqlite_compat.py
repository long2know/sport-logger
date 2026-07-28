#!/usr/bin/env python3
"""Build and exercise pinned, exact SQLite engines without host substitution."""

from __future__ import annotations

import argparse
import contextlib
import dataclasses
import hashlib
import io
import json
import os
import re
import shutil
import ssl
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath
from typing import Callable, Iterable, Iterator, Sequence


TOOL_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOL_DIR.parents[1]
MANIFEST_PATH = TOOL_DIR / "engines.json"
LEGACY_SCHEMA_PATH = TOOL_DIR / "legacy_schema.sql"
GATE_DRIVER_PATH = TOOL_DIR / "native" / "gate_driver.c"
DEFAULT_CACHE_DIR = TOOL_DIR / ".cache"
DEFAULT_WORK_DIR = TOOL_DIR / ".work"
DEFAULT_REPORT_DIR = TOOL_DIR / "out"
REPORT_SCHEMA = "sport-logger.sqlite-exact-compat/v1"
REQUIRED_SOURCE_FILES = ("sqlite3.c", "sqlite3.h", "sqlite3ext.h", "shell.c")
MAX_ARCHIVE_MEMBERS = 256
MAX_ARCHIVE_FILE_SIZE = 64 * 1024 * 1024
MAX_ARCHIVE_TOTAL_SIZE = 128 * 1024 * 1024
NATIVE_EXECUTABLE_MAGICS = {
    b"\x7fELF",
    b"\xfe\xed\xfa\xce",
    b"\xfe\xed\xfa\xcf",
    b"\xce\xfa\xed\xfe",
    b"\xcf\xfa\xed\xfe",
    b"\xca\xfe\xba\xbe",
    b"\xbe\xba\xfe\xca",
    b"\xca\xfe\xba\xbf",
    b"\xbf\xba\xfe\xca",
}
EXPECTED_ACTIVITY_COLUMNS = (
    "ID",
    "GMTSTART",
    "GMTEND",
    "NAME",
    "DESCRIPTION",
    "DISTANCE",
    "TIME",
    "PACE",
)
EXPECTED_POINT_COLUMNS = (
    "ID",
    "ACTIVITYID",
    "GMTTIMESTAMP",
    "LATITUDE",
    "LONGITUDE",
    "ALTITUDE",
    "ACCURACY",
    "SPEED",
    "BEARING",
    "HEARTRATE",
)
GATE_IDS = (
    "legacy_schema",
    "table_index",
    "api26_schema_fallback",
    "fts5_runtime",
    "readonly_reopen",
    "wal_snapshot",
    "interrupted_wal_receipt",
    "corruption_open_failure",
    "large_point_transaction_query",
)


class HarnessError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclasses.dataclass(frozen=True)
class EngineSpec:
    version: str
    release_code: str
    archive_url: str
    archive_sha256: str
    archive_size: int
    source_id: str

    @property
    def archive_name(self) -> str:
        return f"sqlite-autoconf-{self.release_code}.tar.gz"

    @property
    def archive_root(self) -> str:
        return f"sqlite-autoconf-{self.release_code}"


@dataclasses.dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


@dataclasses.dataclass
class GateResult:
    gate_id: str
    status: str
    duration_class: str
    reason: str | None = None
    failure_cause: dict[str, str] | None = None

    def as_dict(self) -> dict[str, object]:
        return {
            "id": self.gate_id,
            "status": self.status,
            "duration_class": self.duration_class,
            "reason": self.reason,
            "failure_cause": self.failure_cause,
        }


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        raise HarnessError("UNEXPECTED_REDIRECT", f"archive request returned HTTP redirect {code}")


def require_regular_file(
    path: Path,
    *,
    code: str,
    label: str,
    executable: bool = False,
    single_link: bool = False,
) -> os.stat_result:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise HarnessError(code, f"{label} is unavailable") from error
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise HarnessError(code, f"{label} must be a regular non-symlink file")
    if executable and metadata.st_mode & 0o111 == 0:
        raise HarnessError(code, f"{label} is not executable")
    if single_link and metadata.st_nlink != 1:
        raise HarnessError(code, f"{label} must not be hard-linked")
    return metadata


def require_private_directory(path: Path, *, create: bool, label: str) -> None:
    if create:
        try:
            path.mkdir(parents=True, mode=0o700, exist_ok=True)
        except OSError as error:
            raise HarnessError("UNSAFE_WORK_DIRECTORY", f"{label} cannot be created") from error
    try:
        metadata = path.lstat()
    except OSError as error:
        raise HarnessError("UNSAFE_WORK_DIRECTORY", f"{label} is unavailable") from error
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        raise HarnessError("UNSAFE_WORK_DIRECTORY", f"{label} must be a real directory")
    if metadata.st_uid != os.geteuid():
        raise HarnessError("UNSAFE_WORK_DIRECTORY", f"{label} is not owned by the current user")
    try:
        path.chmod(0o700)
    except OSError as error:
        raise HarnessError("UNSAFE_WORK_DIRECTORY", f"{label} permissions cannot be secured") from error


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    require_regular_file(path, code="UNSAFE_FILE", label=path.name)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise HarnessError("UNSAFE_FILE", f"{path.name} cannot be opened safely") from error
    with os.fdopen(descriptor, "rb") as stream:
        metadata = os.fstat(stream.fileno())
        if not stat.S_ISREG(metadata.st_mode):
            raise HarnessError("UNSAFE_FILE", f"{path.name} changed before it could be read")
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_verified_archive(path: Path, spec: EngineSpec) -> bytes:
    metadata = require_regular_file(
        path,
        code="UNAVAILABLE_ENGINE",
        label=f"{spec.version} archive",
        single_link=True,
    )
    if metadata.st_size != spec.archive_size:
        raise HarnessError(
            "ARCHIVE_SIZE_MISMATCH",
            f"{spec.version} archive size {metadata.st_size} does not match pinned size {spec.archive_size}",
        )
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise HarnessError("UNAVAILABLE_ENGINE", f"{spec.version} archive cannot be opened safely") from error
    with os.fdopen(descriptor, "rb") as stream:
        opened = os.fstat(stream.fileno())
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_nlink != 1
            or opened.st_size != spec.archive_size
        ):
            raise HarnessError("UNAVAILABLE_ENGINE", f"{spec.version} archive changed before verification")
        data = stream.read(spec.archive_size + 1)
    if len(data) != spec.archive_size:
        raise HarnessError("ARCHIVE_SIZE_MISMATCH", f"{spec.version} archive size is not pinned")
    actual_hash = hashlib.sha256(data).hexdigest()
    if actual_hash != spec.archive_sha256:
        raise HarnessError(
            "CHECKSUM_MISMATCH",
            f"{spec.version} archive SHA-256 {actual_hash} does not match the pinned SHA-256",
        )
    return data


def atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".partial")
    with partial.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(partial, path)


def atomic_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".partial")
    with partial.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(partial, path)


def load_manifest(path: Path = MANIFEST_PATH) -> list[EngineSpec]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise HarnessError("INVALID_MANIFEST", "unable to read engine manifest") from error
    if document.get("schema_version") != 1 or not isinstance(document.get("engines"), list):
        raise HarnessError("INVALID_MANIFEST", "unsupported engine manifest schema")

    specs: list[EngineSpec] = []
    versions: set[str] = set()
    for item in document["engines"]:
        try:
            spec = EngineSpec(
                version=str(item["version"]),
                release_code=str(item["release_code"]),
                archive_url=str(item["archive_url"]),
                archive_sha256=str(item["archive_sha256"]),
                archive_size=int(item["archive_size"]),
                source_id=str(item["source_id"]),
            )
        except (KeyError, TypeError, ValueError) as error:
            raise HarnessError("INVALID_MANIFEST", "engine manifest entry is incomplete") from error
        parsed = urllib.parse.urlparse(spec.archive_url)
        expected_name = f"sqlite-autoconf-{spec.release_code}.tar.gz"
        if (
            parsed.scheme != "https"
            or parsed.hostname != "www.sqlite.org"
            or Path(parsed.path).name != expected_name
            or parsed.query
            or parsed.fragment
            or parsed.username
            or parsed.password
        ):
            raise HarnessError("INVALID_MANIFEST", f"{spec.version} does not use a pinned official HTTPS URL")
        if not re.fullmatch(r"[0-9a-f]{64}", spec.archive_sha256):
            raise HarnessError("INVALID_MANIFEST", f"{spec.version} has an invalid SHA-256")
        if spec.archive_size <= 0 or spec.version in versions:
            raise HarnessError("INVALID_MANIFEST", f"{spec.version} has invalid immutable metadata")
        versions.add(spec.version)
        specs.append(spec)
    if not specs:
        raise HarnessError("INVALID_MANIFEST", "engine manifest is empty")
    return specs


def select_specs(specs: Sequence[EngineSpec], versions: Sequence[str] | None) -> list[EngineSpec]:
    if not versions:
        return list(specs)
    by_version = {spec.version: spec for spec in specs}
    missing = [version for version in versions if version not in by_version]
    if missing:
        raise HarnessError("UNKNOWN_ENGINE", f"unrecognized engine version: {', '.join(missing)}")
    requested = set(versions)
    return [spec for spec in specs if spec.version in requested]


def verify_archive_file(path: Path, spec: EngineSpec) -> None:
    read_verified_archive(path, spec)


def prepare_archive_cache(cache_dir: Path, specs: Sequence[EngineSpec]) -> Path:
    allowed_archives = {spec.archive_name for spec in specs}
    allowed_partials = {f"{name}.partial" for name in allowed_archives}
    if cache_dir.exists() or cache_dir.is_symlink():
        try:
            metadata = cache_dir.lstat()
        except OSError as error:
            raise HarnessError("UNTRUSTED_CACHE_CONTENT", "archive cache cannot be inspected") from error
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
            raise HarnessError("UNTRUSTED_CACHE_CONTENT", "archive cache must be a real directory")
    else:
        try:
            cache_dir.mkdir(parents=True, mode=0o700)
        except OSError as error:
            raise HarnessError("UNTRUSTED_CACHE_CONTENT", "archive cache cannot be created") from error
    try:
        cache_dir.chmod(0o700)
        cache_entries = list(cache_dir.iterdir())
    except OSError as error:
        raise HarnessError("UNTRUSTED_CACHE_CONTENT", "archive cache cannot be secured") from error

    for entry in cache_entries:
        if entry.name != "archives":
            raise HarnessError(
                "UNTRUSTED_CACHE_CONTENT",
                f"archive cache contains forbidden entry {entry.name!r}; cached builds and metadata are never trusted",
            )

    archive_dir = cache_dir / "archives"
    if archive_dir.exists() or archive_dir.is_symlink():
        try:
            metadata = archive_dir.lstat()
        except OSError as error:
            raise HarnessError("UNTRUSTED_CACHE_CONTENT", "archive directory cannot be inspected") from error
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
            raise HarnessError("UNTRUSTED_CACHE_CONTENT", "archive directory must be a real directory")
    else:
        try:
            archive_dir.mkdir(mode=0o700)
        except OSError as error:
            raise HarnessError("UNTRUSTED_CACHE_CONTENT", "archive directory cannot be created") from error
    try:
        archive_dir.chmod(0o700)
        archive_entries = list(archive_dir.iterdir())
    except OSError as error:
        raise HarnessError("UNTRUSTED_CACHE_CONTENT", "archive directory cannot be secured") from error

    for entry in archive_entries:
        if entry.name in allowed_partials:
            try:
                metadata = entry.lstat()
                if not stat.S_ISLNK(metadata.st_mode) and not stat.S_ISREG(metadata.st_mode):
                    raise HarnessError(
                        "UNTRUSTED_CACHE_CONTENT",
                        f"stale partial archive {entry.name!r} is not a file",
                    )
                entry.unlink()
            except HarnessError:
                raise
            except OSError as error:
                raise HarnessError(
                    "UNTRUSTED_CACHE_CONTENT",
                    f"stale partial archive {entry.name!r} cannot be removed",
                ) from error
            continue
        if entry.name not in allowed_archives:
            raise HarnessError(
                "UNTRUSTED_CACHE_CONTENT",
                f"archive cache contains untracked metadata or payload {entry.name!r}",
            )
        require_regular_file(
            entry,
            code="UNTRUSTED_CACHE_CONTENT",
            label=f"cached archive {entry.name}",
            single_link=True,
        )
    return archive_dir


def download_archive(spec: EngineSpec, archive_path: Path, offline: bool) -> None:
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    if archive_path.exists() or archive_path.is_symlink():
        verify_archive_file(archive_path, spec)
        return
    if offline:
        raise HarnessError("UNAVAILABLE_ENGINE", f"{spec.version} archive is absent from the offline cache")

    partial = archive_path.with_name(archive_path.name + ".partial")
    partial.unlink(missing_ok=True)
    tls_context = ssl.create_default_context()
    tls_context.minimum_version = ssl.TLSVersion.TLSv1_2
    opener = urllib.request.build_opener(
        RejectRedirects(),
        urllib.request.HTTPSHandler(context=tls_context),
    )
    request = urllib.request.Request(
        spec.archive_url,
        headers={
            "Accept": "application/gzip, application/octet-stream",
            "User-Agent": "sport-logger-exact-sqlite-harness/1",
        },
        method="GET",
    )
    digest = hashlib.sha256()
    total = 0
    try:
        with opener.open(request, timeout=60) as response:
            status_code = getattr(response, "status", response.getcode())
            if status_code != 200 or response.geturl() != spec.archive_url:
                raise HarnessError("UNEXPECTED_DOWNLOAD", f"{spec.version} archive response was not exact HTTP 200")
            content_type = response.headers.get("Content-Type", "").lower()
            if "text/html" in content_type:
                raise HarnessError("UNEXPECTED_DOWNLOAD", f"{spec.version} archive response was HTML")
            content_length = response.headers.get("Content-Length")
            if content_length is not None and int(content_length) != spec.archive_size:
                raise HarnessError("ARCHIVE_SIZE_MISMATCH", f"{spec.version} response size is not pinned")
            with partial.open("xb") as stream:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    stream.write(chunk)
                    digest.update(chunk)
                    total += len(chunk)
                stream.flush()
                os.fsync(stream.fileno())
    except HarnessError:
        partial.unlink(missing_ok=True)
        raise
    except (OSError, urllib.error.URLError, ValueError) as error:
        partial.unlink(missing_ok=True)
        raise HarnessError("DOWNLOAD_FAILED", f"{spec.version} official archive download failed") from error

    if total != spec.archive_size:
        partial.unlink(missing_ok=True)
        raise HarnessError("ARCHIVE_SIZE_MISMATCH", f"{spec.version} downloaded archive size is not pinned")
    if digest.hexdigest() != spec.archive_sha256:
        partial.unlink(missing_ok=True)
        raise HarnessError("CHECKSUM_MISMATCH", f"{spec.version} downloaded archive checksum mismatch")
    os.replace(partial, archive_path)
    verify_archive_file(archive_path, spec)


def extract_source(spec: EngineSpec, archive_path: Path, source_dir: Path) -> dict[str, str]:
    if source_dir.exists() or source_dir.is_symlink():
        raise HarnessError("UNSAFE_WORK_DIRECTORY", f"{spec.version} source destination already exists")
    source_dir.mkdir(parents=True, mode=0o700)
    source_hashes: dict[str, str] = {}
    try:
        archive_bytes = read_verified_archive(archive_path, spec)
        with tarfile.open(fileobj=io.BytesIO(archive_bytes), mode="r:gz") as archive:
            members: dict[str, tarfile.TarInfo] = {}
            total_size = 0
            for member in archive.getmembers():
                raw_name = member.name
                normalized_name = raw_name[:-1] if member.isdir() and raw_name.endswith("/") else raw_name
                parts = normalized_name.split("/")
                posix_path = PurePosixPath(normalized_name)
                if (
                    not normalized_name
                    or "\x00" in normalized_name
                    or "\\" in normalized_name
                    or posix_path.is_absolute()
                    or any(part in ("", ".", "..") for part in parts)
                    or parts[0] != spec.archive_root
                ):
                    raise HarnessError(
                        "UNSAFE_ARCHIVE_MEMBER",
                        f"{spec.version} archive contains an unsafe member path",
                    )
                if normalized_name in members:
                    raise HarnessError(
                        "UNSAFE_ARCHIVE_MEMBER",
                        f"{spec.version} archive contains a duplicate member",
                    )
                if not member.isdir() and not member.isfile():
                    raise HarnessError(
                        "UNSAFE_ARCHIVE_MEMBER",
                        f"{spec.version} archive contains a link or special file",
                    )
                if member.isfile():
                    if member.size < 0 or member.size > MAX_ARCHIVE_FILE_SIZE:
                        raise HarnessError(
                            "UNSAFE_ARCHIVE_MEMBER",
                            f"{spec.version} archive contains an oversized member",
                        )
                    total_size += member.size
                    if total_size > MAX_ARCHIVE_TOTAL_SIZE:
                        raise HarnessError(
                            "UNSAFE_ARCHIVE_MEMBER",
                            f"{spec.version} archive expands beyond the safe size limit",
                        )
                members[normalized_name] = member
                if len(members) > MAX_ARCHIVE_MEMBERS:
                    raise HarnessError(
                        "UNSAFE_ARCHIVE_MEMBER",
                        f"{spec.version} archive contains too many members",
                    )

            for filename in REQUIRED_SOURCE_FILES:
                member_name = f"{spec.archive_root}/{filename}"
                try:
                    member = members[member_name]
                except KeyError as error:
                    raise HarnessError("INVALID_ARCHIVE", f"{spec.version} archive lacks {filename}") from error
                if not member.isfile() or member.size <= 0 or member.size > 20 * 1024 * 1024:
                    raise HarnessError("INVALID_ARCHIVE", f"{spec.version} archive has unsafe {filename}")
                extracted = archive.extractfile(member)
                if extracted is None:
                    raise HarnessError("INVALID_ARCHIVE", f"{spec.version} archive cannot read {filename}")
                data = extracted.read()
                if len(data) != member.size:
                    raise HarnessError("INVALID_ARCHIVE", f"{spec.version} archive truncated {filename}")
                destination = source_dir / filename
                with destination.open("xb") as stream:
                    stream.write(data)
                    stream.flush()
                    os.fsync(stream.fileno())
                destination.chmod(0o600)
                source_hashes[filename] = hashlib.sha256(data).hexdigest()
    except (OSError, tarfile.TarError) as error:
        raise HarnessError("INVALID_ARCHIVE", f"{spec.version} archive cannot be extracted") from error

    sqlite_source = (source_dir / "sqlite3.c").read_text(encoding="utf-8", errors="strict")
    version_match = re.search(r'^#define SQLITE_VERSION\s+"([^"]+)"', sqlite_source, re.MULTILINE)
    source_match = re.search(r'^#define SQLITE_SOURCE_ID\s+"([^"]+)"', sqlite_source, re.MULTILINE)
    if version_match is None or version_match.group(1) != spec.version:
        raise HarnessError("SOURCE_VERSION_MISMATCH", f"{spec.version} archive declares another SQLite version")
    if source_match is None or source_match.group(1) != spec.source_id:
        raise HarnessError("SOURCE_ID_MISMATCH", f"{spec.version} archive declares another SQLite source ID")
    return source_hashes


def minimal_environment(home: Path) -> dict[str, str]:
    require_private_directory(home, create=True, label="command home")
    temporary = home / "tmp"
    require_private_directory(temporary, create=True, label="command temporary directory")
    return {
        "PATH": os.defpath,
        "HOME": str(home),
        "LC_ALL": "C",
        "LANG": "C",
        "TZ": "UTC",
        "SOURCE_DATE_EPOCH": "0",
        "ZERO_AR_DATE": "1",
        "TMPDIR": str(temporary),
        "TMP": str(temporary),
        "TEMP": str(temporary),
    }


def resolve_system_tool(name: str, *, missing_code: str) -> Path:
    candidate = shutil.which(name, path=os.defpath)
    if candidate is None:
        raise HarnessError(missing_code, f"required system tool {name!r} is unavailable")
    try:
        resolved = Path(candidate).resolve(strict=True)
    except OSError as error:
        raise HarnessError(missing_code, f"required system tool {name!r} cannot be resolved") from error
    require_regular_file(
        resolved,
        code="UNSAFE_EXECUTABLE",
        label=f"system tool {name}",
        executable=True,
    )
    return resolved


def run_command(
    arguments: Sequence[str],
    *,
    cwd: Path,
    home: Path,
    stdin: str | None = None,
    timeout: int = 180,
) -> CommandResult:
    if not arguments:
        raise HarnessError("MISSING_TOOL", "command has no executable")
    executable = Path(arguments[0])
    if not executable.is_absolute():
        raise HarnessError(
            "PATH_EXECUTION_FORBIDDEN",
            f"refusing PATH-selected executable {arguments[0]!r}",
        )
    require_regular_file(
        executable,
        code="UNSAFE_EXECUTABLE",
        label=f"executable {executable.name}",
        executable=True,
    )
    if not cwd.is_absolute() or not home.is_absolute():
        raise HarnessError("UNSAFE_WORK_DIRECTORY", "command directories must be absolute")
    for directory, label in ((cwd, "command working directory"), (home, "command home")):
        try:
            metadata = directory.lstat()
        except OSError as error:
            raise HarnessError("UNSAFE_WORK_DIRECTORY", f"{label} is unavailable") from error
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
            raise HarnessError("UNSAFE_WORK_DIRECTORY", f"{label} must be a real directory")
    try:
        completed = subprocess.run(
            [os.fspath(argument) for argument in arguments],
            cwd=cwd,
            env=minimal_environment(home),
            input=stdin,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError as error:
        raise HarnessError("MISSING_TOOL", f"required executable {executable.name} is unavailable") from error
    except subprocess.TimeoutExpired as error:
        raise HarnessError("COMMAND_TIMEOUT", f"command {executable.name} timed out") from error
    return CommandResult(completed.returncode, completed.stdout, completed.stderr)


def require_success(result: CommandResult, code: str, stage: str) -> None:
    if result.returncode == 0:
        return
    diagnostic = (result.stderr or result.stdout).strip().splitlines()
    suffix = sanitize_message(diagnostic[-1] if diagnostic else "no diagnostic")
    raise HarnessError(code, f"{stage} failed: {suffix[:240]}")


def build_flags(source_dir: Path) -> tuple[list[str], list[str]]:
    compile_flags = [
        "-std=gnu99",
        "-O2",
        "-g0",
        "-fPIC",
        "-fno-strict-aliasing",
        "-fstack-protector-strong",
        "-D_FORTIFY_SOURCE=2",
        "-D_REENTRANT=1",
        "-DSQLITE_THREADSAFE=1",
        "-DSQLITE_ENABLE_COLUMN_METADATA",
        "-DSQLITE_ENABLE_FTS4",
        "-DSQLITE_ENABLE_FTS5",
        "-DSQLITE_ENABLE_JSON1",
        "-DSQLITE_ENABLE_RTREE",
        "-Wformat",
        "-Werror=format-security",
        f"-ffile-prefix-map={source_dir}=sqlite-src",
    ]
    link_flags = ["-pthread", "-lm"]
    if sys.platform.startswith("linux"):
        link_flags.extend(["-ldl", "-Wl,-z,relro", "-Wl,-z,now"])
    return compile_flags, link_flags


def require_fresh_output(path: Path, build_dir: Path, label: str, *, executable: bool) -> None:
    if not path.is_absolute() or not build_dir.is_absolute():
        raise HarnessError("UNSAFE_BUILD_OUTPUT", f"{label} path is not absolute")
    require_regular_file(
        path,
        code="UNSAFE_BUILD_OUTPUT",
        label=label,
        executable=executable,
        single_link=True,
    )
    try:
        resolved = path.resolve(strict=True)
        resolved.relative_to(build_dir.resolve(strict=True))
    except (OSError, ValueError) as error:
        raise HarnessError("UNSAFE_BUILD_OUTPUT", f"{label} is outside the fresh build tree") from error
    if resolved != path:
        raise HarnessError("UNSAFE_BUILD_OUTPUT", f"{label} path traverses a symlink")
    if executable:
        require_native_executable(path, label)


def source_tree_sha256(source_hashes: dict[str, str]) -> str:
    digest = hashlib.sha256()
    for filename in sorted(source_hashes):
        digest.update(filename.encode("utf-8"))
        digest.update(b"\0")
        digest.update(source_hashes[filename].encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def require_native_executable(path: Path, label: str) -> None:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise HarnessError("UNSAFE_BUILD_OUTPUT", f"{label} cannot be inspected safely") from error
    with os.fdopen(descriptor, "rb") as stream:
        magic = stream.read(4)
    if magic not in NATIVE_EXECUTABLE_MAGICS:
        raise HarnessError("UNSAFE_BUILD_OUTPUT", f"{label} is not a native executable")


@contextlib.contextmanager
def fresh_build_tree(work_dir: Path, spec: EngineSpec) -> Iterator[Path]:
    if not work_dir.is_absolute():
        raise HarnessError("UNSAFE_WORK_DIRECTORY", "fresh-build parent must be absolute")
    require_private_directory(work_dir, create=True, label="fresh-build parent")
    try:
        build_dir = Path(tempfile.mkdtemp(prefix=f"sqlite-{spec.release_code}-", dir=work_dir))
    except OSError as error:
        raise HarnessError("UNSAFE_WORK_DIRECTORY", "fresh-build tree cannot be created") from error
    require_private_directory(build_dir, create=False, label="fresh-build tree")
    try:
        yield build_dir
    finally:
        try:
            shutil.rmtree(build_dir)
        except OSError as error:
            raise HarnessError("BUILD_CLEANUP_FAILED", "fresh-build tree could not be deleted") from error
        if build_dir.exists() or build_dir.is_symlink():
            raise HarnessError("BUILD_CLEANUP_FAILED", "fresh-build tree remained after deletion")
        try:
            work_dir.rmdir()
        except OSError:
            pass


def build_engine(
    spec: EngineSpec,
    archive_path: Path,
    build_dir: Path,
) -> tuple["ExactEngine", dict[str, object]]:
    cc = resolve_system_tool("cc", missing_code="MISSING_COMPILER")
    require_private_directory(build_dir, create=False, label="fresh-build tree")

    source_dir = build_dir / "source"
    object_dir = build_dir / "obj"
    binary_dir = build_dir / "bin"
    home_dir = build_dir / "home"
    object_dir.mkdir(mode=0o700)
    binary_dir.mkdir(mode=0o700)
    home_dir.mkdir(mode=0o700)
    source_hashes = extract_source(spec, archive_path, source_dir)
    compile_flags, link_flags = build_flags(source_dir)

    require_regular_file(
        GATE_DRIVER_PATH,
        code="UNSAFE_BUILD_INPUT",
        label="tracked gate driver source",
        single_link=True,
    )
    sqlite_object = object_dir / "sqlite3.o"
    sqlite_binary = binary_dir / "sqlite3"
    gate_driver = binary_dir / "gate_driver"
    compile_sqlite = [
        str(cc),
        *compile_flags,
        "-c",
        str(source_dir / "sqlite3.c"),
        "-o",
        str(sqlite_object),
    ]
    result = run_command(compile_sqlite, cwd=build_dir, home=home_dir, timeout=300)
    require_success(result, "BUILD_FAILED", f"{spec.version} amalgamation compile")
    require_fresh_output(sqlite_object, build_dir, "fresh SQLite object", executable=False)
    sqlite_object.chmod(0o600)

    compile_shell = [
        str(cc),
        *compile_flags,
        str(source_dir / "shell.c"),
        str(sqlite_object),
        "-o",
        str(sqlite_binary),
        *link_flags,
    ]
    result = run_command(compile_shell, cwd=build_dir, home=home_dir, timeout=300)
    require_success(result, "BUILD_FAILED", f"{spec.version} CLI link")
    require_fresh_output(sqlite_binary, build_dir, "fresh SQLite CLI", executable=True)
    sqlite_binary.chmod(0o700)

    compile_driver = [
        str(cc),
        *compile_flags,
        "-I",
        str(source_dir),
        str(GATE_DRIVER_PATH),
        str(sqlite_object),
        "-o",
        str(gate_driver),
        *link_flags,
    ]
    result = run_command(compile_driver, cwd=build_dir, home=home_dir, timeout=300)
    require_success(result, "BUILD_FAILED", f"{spec.version} gate driver link")
    require_fresh_output(gate_driver, build_dir, "fresh gate driver", executable=True)
    gate_driver.chmod(0o700)

    engine = ExactEngine(build_dir, sqlite_binary, gate_driver)
    evidence: dict[str, object] = {
        "mode": "fresh_from_verified_archive",
        "cache_artifacts_used": False,
        "compiler": cc.name,
        "compile_flags": [flag.replace(str(source_dir), "sqlite-src") for flag in compile_flags],
        "link_flags": link_flags,
        "gate_driver_source_sha256": sha256_file(GATE_DRIVER_PATH),
        "source_sha256": source_hashes,
        "source_tree_sha256": source_tree_sha256(source_hashes),
        "artifacts": {
            "sqlite3_sha256": sha256_file(sqlite_binary),
            "gate_driver_sha256": sha256_file(gate_driver),
        },
    }
    return engine, evidence


class ExactEngine:
    def __init__(self, build_dir: Path, binary: Path, driver_binary: Path):
        self.build_dir = build_dir
        self.home_dir = build_dir / "home"
        self.binary = binary
        self.driver_binary = driver_binary
        self._verify_outputs()

    def _verify_outputs(self) -> None:
        require_fresh_output(self.binary, self.build_dir, "fresh SQLite CLI", executable=True)
        require_fresh_output(self.driver_binary, self.build_dir, "fresh gate driver", executable=True)

    def sql(self, database: Path | str, sql: str, *, timeout: int = 180) -> CommandResult:
        self._verify_outputs()
        return run_command(
            [
                str(self.binary),
                "-batch",
                "-bail",
                "-noheader",
                "-separator",
                "\t",
                str(database),
                sql,
            ],
            cwd=self.build_dir,
            home=self.home_dir,
            timeout=timeout,
        )

    def script(self, database: Path | str, sql: str, *, timeout: int = 180) -> CommandResult:
        self._verify_outputs()
        return run_command(
            [str(self.binary), "-batch", "-bail", str(database)],
            cwd=self.build_dir,
            home=self.home_dir,
            stdin=sql,
            timeout=timeout,
        )

    def driver(self, command: str, database: Path | None = None, *, timeout: int = 180) -> CommandResult:
        self._verify_outputs()
        arguments = [str(self.driver_binary), command]
        if database is not None:
            arguments.append(str(database))
        return run_command(arguments, cwd=self.build_dir, home=self.home_dir, timeout=timeout)


def parse_driver_identity(result: CommandResult) -> tuple[str, str]:
    require_success(result, "ENGINE_IDENTITY_MISMATCH", "static library identity check")
    values: dict[str, str] = {}
    for line in result.stdout.splitlines():
        key, separator, value = line.partition("=")
        if separator:
            values[key.strip()] = value.strip()
    if not values.get("version") or not values.get("source_id"):
        raise HarnessError("ENGINE_IDENTITY_MISMATCH", "static library identity output is incomplete")
    return values["version"], values["source_id"]


def validate_identity(
    spec: EngineSpec,
    cli_version: str,
    cli_source_id: str,
    library_version: str,
    library_source_id: str,
) -> None:
    if cli_version != spec.version or library_version != spec.version:
        raise HarnessError(
            "ENGINE_VERSION_MISMATCH",
            f"requested {spec.version} but CLI/library reported {cli_version}/{library_version}",
        )
    if cli_source_id != spec.source_id or library_source_id != spec.source_id:
        raise HarnessError("ENGINE_SOURCE_ID_MISMATCH", f"{spec.version} runtime source ID is not pinned")
    if cli_version != library_version or cli_source_id != library_source_id:
        raise HarnessError("ENGINE_IDENTITY_MISMATCH", f"{spec.version} CLI and static library disagree")


def assert_exact_identity(spec: EngineSpec, engine: ExactEngine) -> tuple[str, str]:
    cli = engine.sql(":memory:", "SELECT sqlite_version(), sqlite_source_id();")
    require_success(cli, "ENGINE_IDENTITY_MISMATCH", f"{spec.version} CLI identity check")
    rows = [line for line in cli.stdout.splitlines() if line.strip()]
    if len(rows) != 1:
        raise HarnessError("ENGINE_IDENTITY_MISMATCH", f"{spec.version} CLI identity output is incomplete")
    fields = rows[0].split("\t")
    if len(fields) != 2:
        raise HarnessError("ENGINE_IDENTITY_MISMATCH", f"{spec.version} CLI identity output is malformed")
    library_version, library_source_id = parse_driver_identity(engine.driver("identity"))
    validate_identity(spec, fields[0], fields[1], library_version, library_source_id)
    return fields[0], fields[1]


def duration_class(elapsed_seconds: float) -> str:
    if elapsed_seconds < 1.0:
        return "QUICK"
    if elapsed_seconds < 10.0:
        return "NORMAL"
    return "EXTENDED"


def failure_cause(error: BaseException) -> dict[str, str]:
    if isinstance(error, HarnessError):
        return {"code": error.code, "message": sanitize_message(error.message)}
    return {
        "code": "INTERNAL_ERROR",
        "message": sanitize_message(str(error)[:240] or error.__class__.__name__),
    }


def sanitize_message(message: str) -> str:
    sanitized = message.replace(str(REPOSITORY_ROOT), "<repo>")
    sanitized = sanitized.replace(str(TOOL_DIR), "<tool>")
    return re.sub(r"(?<!https:)(?<!http:)/(?:[^ \t\r\n:]+/)*[^ \t\r\n:]+", "<path>", sanitized)


def timed_gate(gate_id: str, action: Callable[[], tuple[str, str | None]]) -> GateResult:
    started = time.perf_counter()
    try:
        status, reason = action()
        return GateResult(gate_id, status, duration_class(time.perf_counter() - started), reason=reason)
    except BaseException as error:
        return GateResult(
            gate_id,
            "FAIL",
            duration_class(time.perf_counter() - started),
            failure_cause=failure_cause(error),
        )


def remove_database(path: Path) -> None:
    for suffix in ("", "-wal", "-shm", "-journal"):
        Path(str(path) + suffix).unlink(missing_ok=True)


def fresh_workspace(root: Path, name: str) -> Path:
    workspace = root / name
    if workspace.exists():
        shutil.rmtree(workspace)
    workspace.mkdir(parents=True)
    return workspace


def create_legacy_database(engine: ExactEngine, path: Path) -> None:
    remove_database(path)
    script = LEGACY_SCHEMA_PATH.read_text(encoding="utf-8")
    result = engine.script(path, script)
    require_success(result, "LEGACY_SCHEMA_FAILED", "legacy schema execution")


def legacy_schema_gate(engine: ExactEngine, workspace: Path) -> tuple[str, str | None]:
    database = workspace / "legacy.db"
    create_legacy_database(engine, database)
    result = engine.sql(
        database,
        "SELECT "
        "(SELECT count(*) FROM ACTIVITY),"
        "(SELECT count(*) FROM GPS_POINTS),"
        "(SELECT count(*) FROM sqlite_master WHERE type='table' AND name='ACTIVITY'),"
        "(SELECT count(*) FROM sqlite_master WHERE type='table' AND name='GPS_POINTS');",
    )
    require_success(result, "LEGACY_SCHEMA_FAILED", "legacy schema verification")
    if result.stdout.strip() != "2\t3\t1\t1":
        raise HarnessError("LEGACY_SCHEMA_FAILED", f"legacy schema counts were {result.stdout.strip()!r}")
    return "PASS", None


def table_index_gate(engine: ExactEngine, workspace: Path) -> tuple[str, str | None]:
    database = workspace / "table-index.db"
    create_legacy_database(engine, database)
    result = engine.script(
        database,
        "BEGIN IMMEDIATE;\n"
        "CREATE INDEX IDX_GPS_POINTS_ACTIVITY_TIMESTAMP "
        "ON GPS_POINTS(ACTIVITYID, GMTTIMESTAMP);\n"
        "COMMIT;\n",
    )
    require_success(result, "INDEX_PROBE_FAILED", "runtime index creation")
    result = engine.sql(
        database,
        "SELECT count(*) FROM GPS_POINTS INDEXED BY IDX_GPS_POINTS_ACTIVITY_TIMESTAMP "
        "WHERE ACTIVITYID=1;",
    )
    require_success(result, "INDEX_PROBE_FAILED", "runtime indexed query")
    if result.stdout.strip() != "2":
        raise HarnessError("INDEX_PROBE_FAILED", "runtime indexed query returned an unexpected count")
    return "PASS", None


def parse_pragma_columns(output: str) -> tuple[str, ...]:
    columns: list[str] = []
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) >= 2:
            columns.append(fields[1])
    return tuple(columns)


def probe_schema_paths(engine: ExactEngine, database: Path) -> dict[str, object]:
    modern_catalog = engine.sql(
        database,
        "SELECT count(*) FROM sqlite_schema WHERE type='table' AND name IN ('ACTIVITY','GPS_POINTS');",
    )
    if modern_catalog.returncode == 0:
        if modern_catalog.stdout.strip() != "2":
            raise HarnessError("SCHEMA_CATALOG_FAILED", "sqlite_schema returned an unexpected table count")
        catalog = {"selected": "sqlite_schema", "sqlite_schema": "SUPPORTED"}
    elif "no such table: sqlite_schema" in modern_catalog.stderr.lower():
        fallback = engine.sql(
            database,
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('ACTIVITY','GPS_POINTS');",
        )
        require_success(fallback, "SCHEMA_CATALOG_FAILED", "sqlite_master fallback")
        if fallback.stdout.strip() != "2":
            raise HarnessError("SCHEMA_CATALOG_FAILED", "sqlite_master returned an unexpected table count")
        catalog = {"selected": "sqlite_master", "sqlite_schema": "UNSUPPORTED"}
    else:
        raise HarnessError("SCHEMA_CATALOG_FAILED", "sqlite_schema runtime probe failed unexpectedly")

    xinfo = engine.sql(database, "PRAGMA table_xinfo('ACTIVITY');")
    if xinfo.returncode == 0 and xinfo.stdout.strip():
        columns = parse_pragma_columns(xinfo.stdout)
        if columns != EXPECTED_ACTIVITY_COLUMNS:
            raise HarnessError("SCHEMA_METADATA_FAILED", "table_xinfo did not describe the writer schema")
        metadata = {"selected": "table_xinfo", "table_xinfo": "SUPPORTED"}
    elif xinfo.returncode == 0 and not xinfo.stdout.strip():
        table_info = engine.sql(database, "PRAGMA table_info('ACTIVITY');")
        require_success(table_info, "SCHEMA_METADATA_FAILED", "table_info fallback")
        columns = parse_pragma_columns(table_info.stdout)
        if columns != EXPECTED_ACTIVITY_COLUMNS:
            raise HarnessError("SCHEMA_METADATA_FAILED", "table_info did not describe the writer schema")
        metadata = {"selected": "table_info", "table_xinfo": "UNSUPPORTED"}
    else:
        raise HarnessError("SCHEMA_METADATA_FAILED", "table_xinfo runtime probe failed unexpectedly")
    return {"schema_catalog": catalog, "column_metadata": metadata}


def schema_fallback_gate(
    engine: ExactEngine,
    workspace: Path,
    runtime_capabilities: dict[str, object],
) -> tuple[str, str | None]:
    database = workspace / "schema-fallback.db"
    create_legacy_database(engine, database)
    runtime_capabilities.update(probe_schema_paths(engine, database))
    return "PASS", None


def classify_fts_probe(result: CommandResult) -> tuple[str, str | None, dict[str, str]]:
    if result.returncode == 0:
        if result.stdout.strip() != "1":
            raise HarnessError("FTS_PROBE_FAILED", "FTS5 runtime query returned an unexpected result")
        return "PASS", None, {"fts5": "SUPPORTED", "evidence": "runtime_module_probe"}
    diagnostic = (result.stderr + "\n" + result.stdout).lower()
    if "no such module: fts5" in diagnostic:
        return (
            "N/A",
            "runtime probe reported no such module: fts5",
            {"fts5": "UNSUPPORTED", "evidence": "runtime_module_probe"},
        )
    raise HarnessError("FTS_PROBE_FAILED", "FTS5 runtime probe failed for a reason other than unsupported module")


def fts_gate(
    engine: ExactEngine,
    workspace: Path,
    runtime_capabilities: dict[str, object],
) -> tuple[str, str | None]:
    database = workspace / "fts.db"
    remove_database(database)
    try:
        result = engine.script(
            database,
            "CREATE VIRTUAL TABLE fts_probe USING fts5(body);\n"
            "INSERT INTO fts_probe(body) VALUES('exact sqlite engine');\n"
            "SELECT count(*) FROM fts_probe WHERE fts_probe MATCH 'sqlite';\n"
            "DROP TABLE fts_probe;\n",
        )
        status, reason, capability = classify_fts_probe(result)
        runtime_capabilities["fts"] = capability
        return status, reason
    finally:
        remove_database(database)


def readonly_gate(engine: ExactEngine, workspace: Path) -> tuple[str, str | None]:
    database = workspace / "readonly.db"
    create_legacy_database(engine, database)
    before = sha256_file(database)
    result = engine.driver("readonly", database)
    require_success(result, "READONLY_REOPEN_FAILED", "read-only reopen")
    if "readonly=ok" not in result.stdout:
        raise HarnessError("READONLY_REOPEN_FAILED", "read-only driver did not confirm the expected failure")
    if sha256_file(database) != before:
        raise HarnessError("READONLY_REOPEN_FAILED", "read-only reopen changed the legacy database")
    return "PASS", None


def classify_driver_runtime_probe(
    result: CommandResult,
    capability_name: str,
    success_marker: str,
) -> tuple[str, str | None, str]:
    if result.returncode == 0:
        if success_marker not in result.stdout:
            raise HarnessError(f"{capability_name.upper()}_PROBE_FAILED", f"{capability_name} probe omitted success")
        return "PASS", None, "SUPPORTED"
    if result.returncode == 20 and result.stderr.startswith(f"unsupported:{capability_name}"):
        return "N/A", f"runtime probe reported {capability_name} unsupported", "UNSUPPORTED"
    raise HarnessError(f"{capability_name.upper()}_PROBE_FAILED", f"{capability_name} runtime probe failed")


def wal_gate(
    engine: ExactEngine,
    workspace: Path,
    runtime_capabilities: dict[str, object],
) -> tuple[str, str | None]:
    database = workspace / "wal.db"
    remove_database(database)
    try:
        status, reason, capability = classify_driver_runtime_probe(
            engine.driver("wal", database),
            "wal",
            "wal_snapshot=ok",
        )
        runtime_capabilities["wal"] = {"status": capability, "evidence": "runtime_journal_probe"}
        return status, reason
    finally:
        remove_database(database)


def interrupted_gate(
    engine: ExactEngine,
    workspace: Path,
    runtime_capabilities: dict[str, object],
) -> tuple[str, str | None]:
    database = workspace / "interrupted.db"
    remove_database(database)
    try:
        result = engine.driver("interrupt", database)
        if result.returncode == 20 and result.stderr.startswith("unsupported:wal"):
            runtime_capabilities["wal_receipt_replay"] = {
                "status": "UNSUPPORTED",
                "evidence": "runtime_journal_probe",
            }
            return "N/A", "runtime probe reported WAL unsupported"
        require_success(result, "INTERRUPTED_RECEIPT_FAILED", "interrupted WAL/receipt replay")
        if "interrupted_receipt=ok" not in result.stdout:
            raise HarnessError("INTERRUPTED_RECEIPT_FAILED", "receipt driver omitted success")
        runtime_capabilities["wal_receipt_replay"] = {
            "status": "SUPPORTED",
            "evidence": "runtime_process_interruption",
        }
        return "PASS", None
    finally:
        remove_database(database)


def validate_corruption_results(
    corrupt_result: CommandResult,
    missing_result: CommandResult,
    missing_created: bool,
) -> None:
    if corrupt_result.returncode == 0:
        raise HarnessError("CORRUPTION_NOT_DETECTED", "corrupt SQLite input opened successfully")
    diagnostic = (corrupt_result.stderr + "\n" + corrupt_result.stdout).lower()
    if not any(
        expected in diagnostic
        for expected in ("is not a database", "database disk image is malformed", "malformed")
    ):
        raise HarnessError("CORRUPTION_NOT_DETECTED", "corrupt SQLite input failed without a corruption diagnostic")
    if missing_result.returncode == 0 or missing_created:
        raise HarnessError("OPEN_FAILURE_NOT_DETECTED", "missing read-only SQLite input was created or opened")


def corruption_gate(engine: ExactEngine, workspace: Path) -> tuple[str, str | None]:
    corrupt = workspace / "corrupt.db"
    corrupt.write_bytes((b"not a sqlite database\n" * 32)[:512])
    corrupt_before = sha256_file(corrupt)
    corrupt_result = engine.sql(corrupt, "SELECT count(*) FROM sqlite_master;")
    missing = workspace / "missing.db"
    missing_result = engine.driver("readonly", missing)
    validate_corruption_results(corrupt_result, missing_result, missing.exists())
    if sha256_file(corrupt) != corrupt_before:
        raise HarnessError("CORRUPTION_NOT_DETECTED", "corruption probe changed the source bytes")
    return "PASS", None


def large_gate_sql(point_count: int) -> str:
    return f"""
BEGIN IMMEDIATE;
CREATE TABLE ACTIVITY
 (ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR, DESCRIPTION VARCHAR,
  DISTANCE REAL, TIME REAL, PACE REAL);
CREATE TABLE GPS_POINTS
 (ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ACTIVITYID INTEGER, GMTTIMESTAMP VARCHAR, LATITUDE REAL, LONGITUDE REAL,
  ALTITUDE REAL, ACCURACY REAL, SPEED REAL, BEARING REAL, HEARTRATE REAL);
INSERT INTO ACTIVITY(ID, GMTSTART, NAME) VALUES(1, '2020-01-01T00:00:00.000Z', 'large gate');
WITH RECURSIVE sequence(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM sequence WHERE n < {point_count}
)
INSERT INTO GPS_POINTS
 (ACTIVITYID, GMTTIMESTAMP, LATITUDE, LONGITUDE, ALTITUDE, ACCURACY, SPEED, BEARING, HEARTRATE)
SELECT
  1,
  printf('2020-01-01T00:00:%06dZ', n),
  47.0 + (n * 0.000000001),
  -122.0 - (n * 0.000000001),
  CASE WHEN n % 10 = 0 THEN NULL ELSE 20.0 + (n % 100) END,
  3.0 + (n % 5),
  2.0 + (n % 7),
  n % 360,
  100 + (n % 80)
FROM sequence;
CREATE INDEX IDX_GPS_POINTS_ACTIVITY_TIMESTAMP
 ON GPS_POINTS(ACTIVITYID, GMTTIMESTAMP);
COMMIT;
"""


def validate_large_metrics(output: str, point_count: int) -> None:
    rows = [line for line in output.splitlines() if line.strip()]
    if len(rows) != 1:
        raise HarnessError("LARGE_GATE_FAILED", "large gate metrics were absent")
    fields = rows[0].split("\t")
    if len(fields) != 6:
        raise HarnessError("LARGE_GATE_FAILED", "large gate metrics were malformed")
    expected = [
        point_count,
        1,
        point_count,
        point_count // 10,
        1,
        point_count,
    ]
    try:
        observed = [int(field) for field in fields]
    except ValueError as error:
        raise HarnessError("LARGE_GATE_FAILED", "large gate metrics were not integers") from error
    if observed != expected:
        raise HarnessError("LARGE_GATE_FAILED", f"large gate metrics {observed} did not match {expected}")


def large_point_gate(engine: ExactEngine, workspace: Path, point_count: int) -> tuple[str, str | None]:
    database = workspace / "large.db"
    remove_database(database)
    result = engine.script(database, large_gate_sql(point_count), timeout=300)
    require_success(result, "LARGE_GATE_FAILED", f"{point_count}-point transaction")
    metrics = engine.sql(
        database,
        "SELECT "
        "count(*),"
        "min(ID),"
        "max(ID),"
        "sum(CASE WHEN ALTITUDE IS NULL THEN 1 ELSE 0 END),"
        "count(DISTINCT ACTIVITYID),"
        "(SELECT count(*) FROM GPS_POINTS INDEXED BY IDX_GPS_POINTS_ACTIVITY_TIMESTAMP WHERE ACTIVITYID=1) "
        "FROM GPS_POINTS;",
        timeout=300,
    )
    require_success(metrics, "LARGE_GATE_FAILED", f"{point_count}-point query")
    validate_large_metrics(metrics.stdout, point_count)
    return "PASS", None


def compile_capabilities(engine: ExactEngine) -> dict[str, object]:
    result = engine.sql(":memory:", "PRAGMA compile_options;")
    if result.returncode != 0:
        return {"diagnostics": "UNKNOWN", "options": [], "reason": "runtime query failed"}
    options = sorted(line.strip() for line in result.stdout.splitlines() if line.strip())
    if not options:
        return {"diagnostics": "UNKNOWN", "options": [], "reason": "runtime query returned no rows"}
    normalized = ["COMPILER=<reported>" if option.startswith("COMPILER=") else option for option in options]
    return {"diagnostics": "KNOWN", "options": normalized, "reason": None}


def gate_summary(gates: Iterable[GateResult]) -> dict[str, int]:
    summary = {"pass": 0, "fail": 0, "not_applicable": 0}
    for gate in gates:
        if gate.status == "PASS":
            summary["pass"] += 1
        elif gate.status == "N/A":
            summary["not_applicable"] += 1
        else:
            summary["fail"] += 1
    return summary


def run_engine(
    spec: EngineSpec,
    cache_dir: Path,
    work_dir: Path,
    *,
    offline: bool,
    point_count: int,
) -> dict[str, object]:
    started = time.perf_counter()
    archive_path = cache_dir / "archives" / spec.archive_name
    base: dict[str, object] = {
        "requested_version": spec.version,
        "observed_version": None,
        "observed_source_id": None,
        "archive": {
            "url": spec.archive_url,
            "sha256": spec.archive_sha256,
            "size": spec.archive_size,
            "verified": False,
        },
        "build": {
            "mode": "not_started",
            "cache_artifacts_used": False,
            "evidence": None,
        },
        "compile_capabilities": {"diagnostics": "UNKNOWN", "options": [], "reason": "engine not started"},
        "runtime_capabilities": {},
        "gates": [],
        "gate_summary": {"pass": 0, "fail": 0, "not_applicable": 0},
        "status": "FAIL",
        "duration_class": "QUICK",
        "failure_cause": None,
    }
    try:
        download_archive(spec, archive_path, offline)
        base["archive"]["verified"] = True  # type: ignore[index]
        with fresh_build_tree(work_dir, spec) as build_dir:
            engine, build_evidence = build_engine(spec, archive_path, build_dir)
            base["build"] = build_evidence

            observed_version, observed_source_id = assert_exact_identity(spec, engine)
            base["observed_version"] = observed_version
            base["observed_source_id"] = observed_source_id
            base["compile_capabilities"] = compile_capabilities(engine)

            run_root = fresh_workspace(build_dir / "runs", spec.release_code)
            runtime_capabilities: dict[str, object] = {
                "table_ddl": {"status": "UNKNOWN", "evidence": "gate_pending"},
                "index": {"status": "UNKNOWN", "evidence": "gate_pending"},
            }
            gates: list[GateResult] = []
            gates.append(
                timed_gate(
                    "legacy_schema",
                    lambda: legacy_schema_gate(engine, fresh_workspace(run_root, "legacy-schema")),
                )
            )
            if gates[-1].status == "PASS":
                runtime_capabilities["table_ddl"] = {
                    "status": "SUPPORTED",
                    "evidence": "runtime_schema_gate",
                }
            gates.append(
                timed_gate(
                    "table_index",
                    lambda: table_index_gate(engine, fresh_workspace(run_root, "table-index")),
                )
            )
            if gates[-1].status == "PASS":
                runtime_capabilities["index"] = {
                    "status": "SUPPORTED",
                    "evidence": "runtime_index_gate",
                }
            gates.append(
                timed_gate(
                    "api26_schema_fallback",
                    lambda: schema_fallback_gate(
                        engine,
                        fresh_workspace(run_root, "schema-fallback"),
                        runtime_capabilities,
                    ),
                )
            )
            gates.append(
                timed_gate(
                    "fts5_runtime",
                    lambda: fts_gate(engine, fresh_workspace(run_root, "fts"), runtime_capabilities),
                )
            )
            gates.append(
                timed_gate(
                    "readonly_reopen",
                    lambda: readonly_gate(engine, fresh_workspace(run_root, "readonly")),
                )
            )
            gates.append(
                timed_gate(
                    "wal_snapshot",
                    lambda: wal_gate(engine, fresh_workspace(run_root, "wal"), runtime_capabilities),
                )
            )
            gates.append(
                timed_gate(
                    "interrupted_wal_receipt",
                    lambda: interrupted_gate(
                        engine,
                        fresh_workspace(run_root, "interrupted"),
                        runtime_capabilities,
                    ),
                )
            )
            gates.append(
                timed_gate(
                    "corruption_open_failure",
                    lambda: corruption_gate(engine, fresh_workspace(run_root, "corruption")),
                )
            )
            gates.append(
                timed_gate(
                    "large_point_transaction_query",
                    lambda: large_point_gate(
                        engine,
                        fresh_workspace(run_root, "large"),
                        point_count,
                    ),
                )
            )
            summary = gate_summary(gates)
            base["runtime_capabilities"] = runtime_capabilities
            base["gates"] = [gate.as_dict() for gate in gates]
            base["gate_summary"] = summary
            if summary["fail"] == 0:
                base["status"] = "PASS"
            else:
                base["failure_cause"] = {
                    "code": "GATE_FAILURE",
                    "message": f"{summary['fail']} compatibility gate(s) failed",
                }
    except BaseException as error:
        base["failure_cause"] = failure_cause(error)
        if not base["gates"]:
            base["gates"] = [
                GateResult(
                    gate_id,
                    "FAIL",
                    "QUICK",
                    failure_cause={
                        "code": "ENGINE_UNAVAILABLE",
                        "message": "engine identity was not established before gates",
                    },
                ).as_dict()
                for gate_id in GATE_IDS
            ]
            base["gate_summary"] = {"pass": 0, "fail": len(GATE_IDS), "not_applicable": 0}
    base["duration_class"] = duration_class(time.perf_counter() - started)
    return base


def human_report(report: dict[str, object]) -> str:
    lines = [
        "Exact SQLite Compatibility Harness",
        f"Overall: {report['status']}",
        f"Report schema: {report['schema']}",
        f"Large-point gate: {report['large_point_count']}",
        "",
    ]
    for engine in report["engines"]:  # type: ignore[assignment]
        assert isinstance(engine, dict)
        observed = engine.get("observed_version") or "UNAVAILABLE"
        summary = engine["gate_summary"]
        assert isinstance(summary, dict)
        lines.append(
            f"SQLite {engine['requested_version']} -> {observed}: {engine['status']} "
            f"[{engine['duration_class']}]"
        )
        if engine.get("observed_source_id"):
            lines.append(f"  observed source ID: {engine['observed_source_id']}")
        archive = engine["archive"]
        assert isinstance(archive, dict)
        lines.append(f"  archive SHA-256: {archive['sha256']} ({'verified' if archive['verified'] else 'FAILED'})")
        build = engine["build"]
        assert isinstance(build, dict)
        lines.append(
            f"  build mode: {build['mode']} "
            f"(cached artifacts used: {str(build['cache_artifacts_used']).lower()})"
        )
        artifacts = build.get("artifacts")
        if isinstance(artifacts, dict):
            lines.append(
                "  fresh output SHA-256: "
                f"sqlite3={artifacts['sqlite3_sha256']}, "
                f"gate_driver={artifacts['gate_driver_sha256']}"
            )
        compile_info = engine["compile_capabilities"]
        assert isinstance(compile_info, dict)
        lines.append(f"  compile diagnostics: {compile_info['diagnostics']}")
        if compile_info.get("options"):
            lines.append(f"  compile options: {', '.join(compile_info['options'])}")  # type: ignore[arg-type]
        runtime_info = engine["runtime_capabilities"]
        assert isinstance(runtime_info, dict)
        if runtime_info:
            lines.append(
                "  runtime capabilities: "
                + json.dumps(runtime_info, sort_keys=True, separators=(",", ":"))
            )
        lines.append(
            "  gates: "
            f"{summary['pass']} PASS, {summary['fail']} FAIL, "
            f"{summary['not_applicable']} N/A"
        )
        for gate in engine["gates"]:  # type: ignore[assignment]
            assert isinstance(gate, dict)
            suffix = ""
            if gate.get("failure_cause"):
                cause = gate["failure_cause"]
                assert isinstance(cause, dict)
                suffix = f" ({cause['code']})"
            elif gate.get("reason"):
                suffix = f" ({gate['reason']})"
            lines.append(f"    {gate['id']}: {gate['status']} [{gate['duration_class']}]{suffix}")
        if engine.get("failure_cause"):
            cause = engine["failure_cause"]
            assert isinstance(cause, dict)
            lines.append(f"  failure: {cause['code']} - {cause['message']}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def run_harness(
    specs: Sequence[EngineSpec],
    cache_dir: Path,
    report_dir: Path,
    *,
    work_dir: Path,
    offline: bool,
    point_count: int,
    known_specs: Sequence[EngineSpec] | None = None,
) -> dict[str, object]:
    if point_count <= 0:
        raise HarnessError("INVALID_POINT_COUNT", "large-point count must be positive")
    prepare_archive_cache(cache_dir, known_specs or specs)
    report_dir.mkdir(parents=True, exist_ok=True)
    engines = [
        run_engine(
            spec,
            cache_dir,
            work_dir,
            offline=offline,
            point_count=point_count,
        )
        for spec in specs
    ]
    report: dict[str, object] = {
        "schema": REPORT_SCHEMA,
        "status": "PASS" if all(engine["status"] == "PASS" for engine in engines) else "FAIL",
        "large_point_count": point_count,
        "engines": engines,
    }
    text = human_report(report)
    atomic_json(report_dir / "report.json", report)
    atomic_text(report_dir / "report.txt", text)
    print(text, end="")
    return report


def fetch_archives(
    specs: Sequence[EngineSpec],
    cache_dir: Path,
    offline: bool,
    *,
    known_specs: Sequence[EngineSpec] | None = None,
) -> None:
    prepare_archive_cache(cache_dir, known_specs or specs)
    for spec in specs:
        archive_path = cache_dir / "archives" / spec.archive_name
        download_archive(spec, archive_path, offline)
        print(f"{spec.version}\t{spec.archive_sha256}\tVERIFIED")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    pins = subparsers.add_parser("pins", help="print the immutable engine manifest")
    pins.add_argument("--manifest", type=Path, default=MANIFEST_PATH)

    fetch = subparsers.add_parser("fetch", help="download and verify pinned source archives")
    fetch.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE_DIR)
    fetch.add_argument("--offline", action="store_true")
    fetch.add_argument("--version", action="append", dest="versions")

    run = subparsers.add_parser("run", help="build exact engines and run every compatibility gate")
    run.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE_DIR)
    run.add_argument("--work-dir", type=Path, default=DEFAULT_WORK_DIR)
    run.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    run.add_argument("--offline", action="store_true")
    run.add_argument("--version", action="append", dest="versions")
    run.add_argument("--large-points", type=int, default=100_000)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    try:
        specs = load_manifest(getattr(arguments, "manifest", MANIFEST_PATH))
        if arguments.command == "pins":
            print(arguments.manifest.read_text(encoding="utf-8"), end="")
            return 0
        selected = select_specs(specs, arguments.versions)
        cache_dir = Path(os.path.abspath(arguments.cache_dir))
        if arguments.command == "fetch":
            fetch_archives(selected, cache_dir, arguments.offline, known_specs=specs)
            return 0
        report = run_harness(
            selected,
            cache_dir,
            Path(os.path.abspath(arguments.report_dir)),
            work_dir=Path(os.path.abspath(arguments.work_dir)),
            offline=arguments.offline,
            point_count=arguments.large_points,
            known_specs=specs,
        )
        return 0 if report["status"] == "PASS" else 1
    except HarnessError as error:
        print(f"{error.code}: {error.message}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
