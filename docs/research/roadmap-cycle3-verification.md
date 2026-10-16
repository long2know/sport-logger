# Modernization Roadmap Cycle 3 Re-verification

**Reviewer:** Fact Checker
**Review date:** 2026-07-26
**Artifact:** `docs/modernization-roadmap.md` Cycle 3
**Verdict:** **APPROVE**
**Blocking findings:** **0**

Cycle 3 resolves all four groups left partial by the Cycle 2 review. All 14 original blocker
groups and all 18 required corrections are now resolved, with no regression found in the previously
accepted groups.

## Result counts

| Set | Verified resolved | Partial | Unresolved |
|---|---:|---:|---:|
| Cycle 3 deltas | 4 | 0 | 0 |
| B-01 through B-14 | 14 | 0 | 0 |
| T-01 through T-18 | 18 | 0 | 0 |

## Cycle 3 delta verification

| Delta | Rating | Roadmap evidence | Re-verification |
|---|---|---|---|
| Stable Health Services swim identifiers | ✅ **Verified** | §5.1, especially lines 230-256 | The stable-core table names `SWIMMING_STROKES`, `SWIMMING_STROKES_TOTAL`, and `SWIMMING_LAP_COUNT`, and no longer names RC-only `SWIMMING_LAP_COUNT_TOTAL`. Direct inspection of the released `health-services-client:1.0.0` source confirms those three symbols and the other listed core identifiers exist; `SWIMMING_LAP_COUNT_TOTAL` and `HEART_RATE_VARIABILITY` do not. |
| API-31+ BLE and Samsung target-35-or-lower wording | ✅ **Verified** | §5.3, lines 276-290 | The BLE row now applies `BLUETOOTH_SCAN` and/or `BLUETOOTH_CONNECT` whenever the app targets API 31+, including both planned Wear targets. The Samsung rows name `BODY_SENSORS` for the relevant trackers at target 35 or lower and the tracker-specific target-36 permissions. This matches the official Android and Samsung permission tables. |
| Durable bidirectional tombstones | ✅ **Verified** | §7.5, lines 405-449; §12.2 items 10-14; V-05 | Phone deletion transactionally creates a tombstone and durable outbox; retry state survives process death; the same transfer ID and bytes are retried; the watch transactionally stores the winning tombstone and receipt; matching commit ACK and lost-ACK replay are defined; covered activity resends receive terminal `TOMBSTONED` rejection/ACK; watch-origin tombstones use the reciprocal durable protocol; and cross-origin ordering is deterministic. V-05 owns the reverse-delivery implementation, schema additions, and matching tests. |
| F-01/F-02 and C-06 dependencies | ✅ **Verified** | §13.1 lines 664-679; §13.4 lines 703-716; §18 | `F-01` is the sole dependency root and sole immediate start. `F-02` depends on accepted `F-01`. `C-06` depends only on the persisted phone activity (`V-03`), connector port/composition (`C-01`), and credential/HTTPS foundation (`C-05`); the unrelated Health Connect gate is gone. The complete 30-node graph is acyclic. |

## B-01 through B-14 regression check

| ID | Rating | Cycle 3 finding |
|---|---|---|
| B-01 | ✅ **Resolved; no regression** | §3 retains an evidence-selected API-36-capable tuple, separate phone/Wear targets, and atomic F-03 application only after F-02 verification. |
| B-02 | ✅ **Resolved** | §5.1 is now strictly stable-1.0 for core Health Services identifiers and removes the RC-only lap-total symbol. Types and units remain corrected. |
| B-03 | ✅ **Resolved** | §5.3 retains the accepted activity, heart-rate, background, FGS, and location transitions and now fixes BLE and Samsung target-regime wording. |
| B-04 | ✅ **Resolved; no regression** | §8.2 retains the released Health Connect record names, per-record permissions, route-write permission, and collection/publication separation. |
| B-05 | ✅ **Resolved; no regression** | §8.1-§8.3 retain availability checks, actively recorded metadata, per-record IDs/versions, route-safe updates, ledgers, and explicit child deletion. |
| B-06 | ✅ **Resolved; no regression** | §6 retains explicit read-only, on-watch, idempotent ETL into a separately named Room database with copied-fixture precision and checksum gates. |
| B-07 | ✅ **Resolved** | §7 now specifies durable activity transfer and durable tombstone transfer in both directions, transactional receipts, matching ACKs, retries, rejection, deterministic ordering, indefinite retention, field authority, and cloud-path disclosure. |
| B-08 | ✅ **Resolved; no regression** | §4 retains Google protobuf lite, exact versions from F-02, reservations, binary-message retention, and old/new/old fixture tests. |
| B-09 | ✅ **Resolved; no regression** | §10 and C-06/C-07 retain the released Endurain `v0.19.0` multipart upload contract, scoped API key, list response, integer IDs, `UNKNOWN_DELIVERY`, and duplicate reconciliation. |
| B-10 | ✅ **Resolved; no regression** | §11 retains platform trust and hostname verification, safe redirects/logging, host-scoped custom trust, and a separate reversible encryption ADR gate. |
| B-11 | ✅ **Resolved; no regression** | §5.4 keeps HRV, SpO2, skin temperature, and proprietary metrics outside core v1 and behind hardware, SDK, entitlement, and release-identity gates. |
| B-12 | ✅ **Resolved; no regression** | §2 and §9 retain the compilable three-module direction, both app composition roots, and one UI-neutral Connector port with phone-side implementations. |
| B-13 | ✅ **Resolved** | All 30 primaries remain routing-correct; F-01/F-02 sequencing is consistent; V-05 now owns the missing tombstone scope; and C-06 no longer depends on C-03. |
| B-14 | ✅ **Resolved; no regression** | The three-module Groovy strangler and runnable record→persist→transfer→persist→display milestone remain first; broad framework/module expansion remains later. |

## T-01 through T-18 regression check

| ID | Rating | Cycle 3 finding |
|---|---|---|
| T-01 | ✅ **Resolved; no regression** | Exact tuple and separate phone/Wear target policy remain gated by F-02/F-05. |
| T-02 | ✅ **Resolved; no regression** | Atomic build recovery and separate AndroidX, permissions, protobuf/Room, Kotlin, Compose, Hilt, and extraction changes remain intact. |
| T-03 | ✅ **Resolved** | The stable table contains released names/types/units and excludes `SWIMMING_LAP_COUNT_TOTAL`. |
| T-04 | ✅ **Resolved** | The matrix now covers target-31+ BLE permissions and both Samsung target regimes accurately. |
| T-05 | ✅ **Resolved; no regression** | Vendor-only measurements remain outside core v1. |
| T-06 | ✅ **Resolved; no regression** | Health Connect records and permissions remain correct. |
| T-07 | ✅ **Resolved; no regression** | Health Connect availability, IDs, versions, metadata, update, and deletion behavior remain specified. |
| T-08 | ✅ **Resolved; no regression** | Explicit on-watch ETL and copied-fixture validation remain required. |
| T-09 | ✅ **Resolved** | §7.5 supplies the formerly missing reverse tombstone outbox, retry, receipt, ACK, rejection, and deterministic ordering contract. |
| T-10 | ✅ **Resolved; no regression** | Google protobuf lite and old/new/old tests remain explicit. |
| T-11 | ✅ **Resolved; no regression** | The Endurain contract remains aligned to canonical `v0.19.0`. |
| T-12 | ✅ **Resolved; no regression** | Platform trust and host-scoped custom trust remain the network baseline. |
| T-13 | ✅ **Resolved; no regression** | The concrete encryption ADR contents and separate migration boundary remain intact. |
| T-14 | ✅ **Resolved; no regression** | Dependency direction, one Connector port, and app composition roots remain coherent. |
| T-15 | ✅ **Resolved; no regression** | Scope remains reduced and the vertical slice remains first. |
| T-16 | ✅ **Resolved; no regression** | All primaries match routing, required partners remain named, and Mouse owns integration/release-quality gates. |
| T-17 | ✅ **Resolved** | Counts, scopes, F-01-before-F-02 ordering, V-05 tombstone coverage, and C-06 prerequisites are repaired. |
| T-18 | ✅ **Resolved; no regression** | Primary links, exact-tuple evidence, copied fixtures, and rerunnable contract/device gates remain required before affected merges/releases. |

## Independent primary-source checks

### Health Services identifiers and permissions

Checked:

- [`health-services-client:1.0.0` released sources](https://dl.google.com/dl/android/maven2/androidx/health/health-services-client/1.0.0/health-services-client-1.0.0-sources.jar)
- [AndroidX Health release page](https://developer.android.com/jetpack/androidx/releases/health)
- [Health Services permission table](https://developer.android.com/health-and-fitness/health-services/permissions)

The release page still identifies `1.0.0` as stable and `1.1.0-rc02` as the pre-stable line.
Direct source inspection confirmed every Health Services identifier in the Cycle 3 stable-core table.
In particular:

- `SWIMMING_STROKES`, `SWIMMING_STROKES_TOTAL`, and `SWIMMING_LAP_COUNT` exist in stable 1.0.0;
- `SWIMMING_LAP_COUNT_TOTAL` does not exist in stable 1.0.0 and is no longer in the table;
- `HEART_RATE_VARIABILITY` does not exist;
- `PACE` is `Double` milliseconds/kilometre and is zero when stopped;
- `CALORIES` includes basal plus activity calories;
- `FLOORS` is `Double` and permits fractional floors.

The official permission table continues to map the activity-derived metrics listed in §5.3 to
`ACTIVITY_RECOGNITION`, heart rate to the heart-rate permission regime, and `LOCATION` /
`ABSOLUTE_ELEVATION` to `ACCESS_FINE_LOCATION`. It also documents the
`BODY_SENSORS_BACKGROUND` to `READ_HEALTH_DATA_IN_BACKGROUND` transition.

### Bluetooth and Samsung permissions

Checked:

- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Samsung Health Sensor SDK permission request](https://developer.samsung.com/health/sensor/guide/permission-request.html)

Android's official guide says apps **targeting API 31 or higher** declare `BLUETOOTH_SCAN` when
scanning and `BLUETOOTH_CONNECT` when communicating with paired devices, with legacy Bluetooth
declarations capped at API 30. The corrected row therefore applies equally to Wear target 35 and
target 36.

Samsung's official table says target 35 or lower uses `BODY_SENSORS` for the relevant SpO2,
skin-temperature, heart-rate, PPG, ECG, BIA, and related trackers. For target 36 or higher it names
`HealthPermissions.READ_OXYGEN_SATURATION`, `HealthPermissions.READ_SKIN_TEMPERATURE`,
`HealthPermissions.READ_HEART_RATE`, and the additional-health-data permission where applicable.
The Cycle 3 wording is consistent with that table and retains the entitlement gate.

### Previously resolved high-risk APIs

No drift or textual regression was found:

- Direct inspection of
  [`connect-client:1.1.0` sources](https://dl.google.com/dl/android/maven2/androidx/health/connect/connect-client/1.1.0/connect-client-1.1.0-sources.jar)
  reconfirmed the roadmap's record classes, `PERMISSION_WRITE_EXERCISE_ROUTE`,
  history/background constants, `ExerciseRouteRequestContract`, `activelyRecorded`,
  `clientRecordId`, and `clientRecordVersion`. Stable 1.1.0 still does not contain
  `PERMISSION_READ_EXERCISE_ROUTES`.
- The official [Data Layer overview](https://developer.android.com/training/wearables/data/overview)
  still says any Data Layer client may use Bluetooth or a Google-owned cloud intermediary and that
  the cloud route is end-to-end encrypted.
- Canonical Endurain
  [`v0.19.0`](https://codeberg.org/endurain-project/endurain/releases/tag/v0.19.0) source
  reconfirmed `/api/v1/activities/create/upload`, multipart `file`, HTTP 201 with a list response,
  `activities:upload` as the only API-key scope, integer auto-increment activity IDs, hidden
  same-start duplicates, and the `120/minute` default rate limit.

## V-05 scope and acceptance-test audit

V-05 explicitly owns both device sync packages, the existing phone listener, tombstone-only
entities/DAOs and schema exports in both persistence packages, and matching tests. It depends on
both databases and the completion enqueue hook, so its added persistence scope is ordered after
V-02/V-03 rather than colliding with them.

The acceptance surface covers the required failure modes:

1. phone tombstone outbox survives process death;
2. reverse disconnect leaves it uncommitted;
3. matching ACK is required for commit;
4. a lost ACK causes duplicate-safe resend and the same ACK body;
5. covered activity resend is rejected/ACKed as `TOMBSTONED`;
6. equal-revision phone/watch tombstones delivered in opposite orders converge;
7. ordering uses `(delete_revision, origin_rank, transfer_id)`, not device time;
8. watch-origin discard uses the reciprocal durable outbox/receipt/ACK protocol.

This is sufficient coverage of reverse tombstone delivery and deterministic delete ordering for the
roadmap gate. Actual implementation remains subject to V-05/V-07 tests.

## Work-plan audit

- **Scheduled items:** 30 unique — 7 Foundation + 7 Vertical Slice + 5 Data/Acquisition +
  7 Connector/Security + 4 Later Modernization.
- **Prior dispositions:** all 26 original W0-W4 items are present exactly once.
- **Ownership:** all 30 primary owners match `.squad/routing.md`; required domain, quality,
  verification, and health-data-safety partners remain present.
- **Dependency roots:** only `F-01`.
- **F-02:** depends on `F-01`; both §13.1 and §18 state that it starts only after F-01 acceptance.
- **C-06:** direct prerequisites are `V-03`, `C-01`, and `C-05`. Each is a real implementation
  prerequisite; `C-03` is absent.
- **Graph validity:** 30 known nodes, no missing/self dependencies, and a complete topological order
  exists. The graph is acyclic.
- **Concurrent file scope:** no new unordered collision was found. The expanded V-05 persistence
  scope is ordered after V-02/V-03, and removing C-03 from C-06 does not introduce a shared scope.

## Final merge gate

`F-01` is the only immediate start. `F-02` follows its acceptance, and `F-03` remains blocked until
Fact Checker accepts the exact tuple produced by the spike. Physical-device, Samsung entitlement,
Health Connect publication, and disposable Endurain-instance gates continue to block only their
affected capabilities.

At review time, PR #13 was open, draft, and reported mergeable. The reviewed roadmap files were
still local working-tree additions, so they and this report must be included in the PR before it is
published for review; that is a publication step, not a roadmap-content blocker.

## Binary verdict

# **APPROVE**

**Blocker count: 0. PR #13 is explicitly authorized to leave draft after the reviewed roadmap and
this report are included and normal repository validation completes successfully.**
