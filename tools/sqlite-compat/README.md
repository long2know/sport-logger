# Exact SQLite compatibility harness

This standalone harness builds and runs the real upstream SQLite engines required by issue #17.
It does not use the host SQLite library, emulate releases with version profiles, implement the
migration oracle, or normalize Android timestamps.

## Immutable engine pins

The source is downloaded only from the year-specific official SQLite HTTPS archive URL. Redirects,
size changes, checksum changes, missing source members, and source/runtime identity changes fail
closed.

| Requested engine | Official autoconf archive | SHA-256 |
|---|---|---|
| 3.18.2 | `https://www.sqlite.org/2017/sqlite-autoconf-3180200.tar.gz` | `0d40222ea818a559590c51994ac85570eff5c5f754cbe08c2d34a3791942c1ba` |
| 3.26.0 | `https://www.sqlite.org/2018/sqlite-autoconf-3260000.tar.gz` | `5daa6a3fb7d1e8c767cd59c4ded8da6e4b00c61d3b466d0685e35c4dd6d7bf5d` |
| 3.32.0 | `https://www.sqlite.org/2020/sqlite-autoconf-3320000.tar.gz` | `598317fd74f5dcc8921949c47665b9e512d0d9c6a445a2e843430f04dc10bda4` |
| 3.33.0 | `https://www.sqlite.org/2020/sqlite-autoconf-3330000.tar.gz` | `106a2c48c7f75a298a7557bcc0d5f4f454e5b43811cc738b7ca294d6956bbb15` |

`engines.json` also pins archive byte sizes and upstream `SQLITE_SOURCE_ID` values. The harness
accepts only regular, non-symlink archive files and rejects local sidecars, metadata, build trees,
wrappers, and other cache content. On every run it re-verifies the archive bytes, scans every tar
member for traversal, links, duplicate paths, special files, and expansion limits, then copies only
`sqlite3.c`, `sqlite3.h`, `sqlite3ext.h`, and `shell.c` into a new private build tree.

The CLI and native gate driver are compiled fresh in that tree with explicit hardening and
capability flags. They are invoked only by absolute path, must remain regular non-symlink,
single-link files inside the private tree, and are deleted with the source and gate databases after
the run. No locally recomputable attestation authorizes an executable. Before any compatibility
gate, both the fresh CLI and independently linked driver must report the requested
`sqlite_version()` and pinned `sqlite_source_id()`.

The compile recipe is recorded as non-authorizing report evidence and includes `-O2`, `-g0`, `-fPIC`,
`-fno-strict-aliasing`, `-fstack-protector-strong`, `_FORTIFY_SOURCE=2`, format-security checking,
thread-safe SQLite, column metadata, FTS4/FTS5, JSON1, and R-Tree. Linux links use RELRO and immediate
binding. The amalgamation object is linked directly into both outputs; `-lsqlite3` is never used.
Compiler lookup uses the fixed system path rather than the caller's `PATH`, and child processes get
a minimal environment with private temporary storage.

## Requirements

- Linux or macOS with POSIX process semantics
- Python 3.9 or newer
- system `cc`
- HTTPS access to `www.sqlite.org` for a cold cache

No Python packages, host `sqlite3` command, or host SQLite development library are used.

## Run locally

All commands are repository-relative. The only persistent cache entries are the four official
archives under ignored `tools/sqlite-compat/.cache/archives/`. Fresh sources, binaries, and gate
databases exist only under an ignored private `tools/sqlite-compat/.work/` tree while a run is
active; that tree is removed afterward. Reports live under ignored `tools/sqlite-compat/out/`.

Unit tests do not use the network:

```bash
python3 -m unittest discover -s tools/sqlite-compat/tests -p 'test_*.py' -v
```

Cold-cache download, build, exact identity check, and four-engine matrix:

```bash
rm -rf tools/sqlite-compat/.cache tools/sqlite-compat/.work tools/sqlite-compat/out
python3 tools/sqlite-compat/sqlite_compat.py run
```

Verified offline-cache rebuild and full rerun:

```bash
python3 tools/sqlite-compat/sqlite_compat.py run --offline
```

`--offline` never attempts a request. Every cached archive is rechecked against its pinned byte
size and SHA-256 before use, and every engine is rebuilt fresh. A missing or altered required
archive fails closed. To prepopulate an offline machine, run `fetch` on a connected machine, copy
only the verified `archives/*.tar.gz` files into the same cache directory, then run with
`--offline`. Any copied metadata, executable, attestation, symlink, or legacy `builds/`/`runs/`
directory is rejected as `UNTRUSTED_CACHE_CONTENT`.

Useful diagnostics:

```bash
python3 tools/sqlite-compat/sqlite_compat.py pins
python3 tools/sqlite-compat/sqlite_compat.py fetch
python3 tools/sqlite-compat/sqlite_compat.py run --version 3.18.2
```

The default large gate is exactly 100,000 points. `--large-points` exists only for fast development
smoke tests; CI and acceptance runs use the default.

## Gates

Every exact engine runs the same capability-driven gates:

1. the production legacy `ACTIVITY` and `GPS_POINTS` schema derived from
   `utilities/.../SqlLogger.java`;
2. runtime table creation, index creation, and forced indexed query;
3. independent runtime probes for `sqlite_schema` and `table_xinfo`, with the API-26-compatible
   `sqlite_master` and `table_info` fallbacks;
4. an isolated FTS5 module create/insert/query/drop probe;
5. read-only reopen plus a verified write rejection and unchanged database hash;
6. concurrent WAL reader snapshot behavior;
7. process interruption before commit, committed WAL replay, and atomic point/receipt visibility;
8. corrupt-file and missing read-only open failures; and
9. one transaction inserting exactly 100,000 points followed by count, null-pattern, identity, and
   forced-index queries.

Unsupported FTS or WAL is `N/A` only when its runtime probe reports that exact unsupported
capability. Other errors are failures. Empty or unavailable `PRAGMA compile_options` output is
reported as `UNKNOWN`, never as evidence that a module is absent.

## Consumer interface and reports

On every run the harness writes:

- `tools/sqlite-compat/out/report.json`
- `tools/sqlite-compat/out/report.txt`

The JSON schema identifier is `sport-logger.sqlite-exact-compat/v1`. It contains requested and
observed engine identities, archive provenance, normalized compile diagnostics, runtime
capabilities, every gate result, duration classes, and stable failure codes. Fresh source-tree and
output hashes are recorded only as run evidence; they are not an authorization or reusable trust
root. Reports contain no run timestamp or host path. A consumer such as issue #19 must require
overall `PASS`, the four requested versions, exact observed identities, the
`fresh_from_verified_archive` build mode with `cache_artifacts_used: false`, and zero failed gates.

Reports are disposable and ignored. A deterministic sanitized golden may be added later only with a
specific test and review.
