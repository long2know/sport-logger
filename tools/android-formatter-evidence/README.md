# Android formatter runtime evidence

This directory independently records what the Android runtime emits for the production call shape
used by `Config.TimestampFormat` and `SqlLogger`:

```java
new java.text.SimpleDateFormat("yyyyMMddHHmmss")
```

The probe targets the legacy source-runtime question only. It does **not** contain a migration parser,
acceptance oracle, SQLite ETL, fixture importer, or migration policy.

## Pinned local prerequisites

The local regeneration gate requires Linux x86_64, Python 3, and these exact tools:

| Tool | Exact pin |
|---|---|
| JDK | Eclipse Temurin `17.0.20+8` |
| Android command-line tools | `cmdline-tools;22.0`, revision `22.0` |
| Android platform tools | `platform-tools`, revision `37.0.0` |
| Android emulator | revision `36.6.11`; reported `36.6.11.0 (build_id 15507667)` |
| Android build tools | `build-tools;36.0.0`, revision `36.0.0` |
| Compile platform | `platforms;android-36`, revision `2` |
| API 26 image | `system-images;android-26;google_apis;x86_64`, revision `16` |
| API 36 image | `system-images;android-36;google_apis;x86_64`, revision `7` |

All pins, production-source hashes, probe-source hashes, fixed instants, and representative locales are in
[`toolchain.json`](toolchain.json). The probe has no Gradle, Maven, or third-party dependency.
It is compiled and packaged only with the pinned JDK and Android SDK tools.

## Regenerate and compare

Set repository-external tool locations explicitly:

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/temurin-17.0.20

python3 tools/android-formatter-evidence/run_probe.py --compare --repeat 2
```

The command:

1. verifies every package identifier and installed revision;
2. builds the isolated instrumentation APK;
3. creates disposable deterministic AVD state below ignored `.work/`;
4. boots API 26 and then API 36 sequentially with `-accel off`;
5. waits for boot completion and a responsive package manager;
6. enumerates `android.icu.text.NumberingSystem.getAvailableNames()` on-device;
7. records every unique advertised definition, formats decimal definitions through deterministic
   eight-candidate checkpoint batches, isolates any process-crashing decimal candidate, and counts
   the failed batch attempt toward its maximum of three;
8. probes ordinary and historically risky locales over six fixed Gregorian instants in UTC;
9. validates and byte-compares two complete regenerations and the committed evidence; and
10. uninstalls the probe, stops the exact emulator serial/PID, removes each AVD, and deletes all
    build/output state.

KVM is neither used nor required. The software-emulated double run is intentionally a slow,
explicit local gate. To replace evidence after an intentional, reviewed pin change:

```bash
python3 tools/android-formatter-evidence/run_probe.py --update --repeat 2
```

## Host-only validation

Ordinary CI does not boot an emulator:

```bash
python3 tools/android-formatter-evidence/validate_evidence.py
python3 -m unittest discover \
  -s tools/android-formatter-evidence -p 'test_*.py' -v
python3 tools/android-formatter-evidence/run_probe.py --validate-only
```

These checks enforce schema, canonical bytes, sorting, uniqueness, complete candidate and instant
coverage, candidate union accuracy, provenance pins, UTF-8 and Unicode scalar validity, code-point
sequences, strict parse consumption, parsed instant equality, reformat equality, calendar metadata,
fixed-instant semantics, sanitized content, and deterministic union rendering.

## Evidence layout

For each API, committed evidence contains:

- `api*-metadata.tsv`: sanitized runtime and pinned host/tool provenance;
- `api*-advertised.tsv`: every advertised ICU value, including duplicate/null accounting;
- `api*-candidates.tsv`: decimal support, algorithmic/non-decimal, fallback, and error categories;
- `api*-formats.tsv`: exact UTF-8 formatter text, full Unicode code points, calendar class/type, and
  strict parse/reformat results for every successful candidate and representative locale.

`union-summary.json` reports per-API counts, API-only candidates, the conservative candidate union,
definition differences, and the union of actually emitted decimal blocks. It deliberately makes no
migration-acceptance decision.

The pinned runtimes show a material boundary that consumers must not erase: both APIs emit 37 BMP
decimal blocks, including fullwidth digits. API 36 additionally resolves 39 supplementary-plane
decimal definitions but `java.text.SimpleDateFormat` emits strict round-tripping ASCII fallback for
all of them; API 26 rejects its 21 corresponding names during locale construction. No committed
row claims that a supplementary-plane digit was emitted.

Tracked evidence excludes host paths, AVD names, adb serials, wall-clock timestamps, random IDs,
logs, credentials, volatile boot properties, APKs, and emulator userdata.
