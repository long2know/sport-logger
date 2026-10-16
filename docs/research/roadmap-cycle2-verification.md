# Modernization Roadmap Cycle 2 Re-verification

**Reviewer:** Fact Checker
**Review date:** 2026-07-26
**Artifact:** `docs/modernization-roadmap.md` Cycle 2
**Verdict:** **REJECT**

Cycle 2 resolves most of the original rejection, including the build-tuple gate, Health Connect
record names, explicit legacy ETL, protobuf choice, Endurain upload contract, security defaults,
composition roots, ownership, work-item counts, and the first vertical slice. Four correction
groups remain partial, so PR #13 must remain draft.

## Result counts

| Set | Resolved | Partially Resolved | Unresolved |
|---|---:|---:|---:|
| B-01 through B-14 | 10 | 4 | 0 |
| T-01 through T-18 | 14 | 4 | 0 |

“Partially Resolved” remains blocking for this binary gate.

## Blocker re-verification

| ID | Rating | Exact Cycle 2 reference | Re-verification |
|---|---|---|---|
| B-01 | **Resolved** | §3, lines 109-162; F-02 through F-05, lines 619-623; §18, lines 849-856 | The roadmap no longer freezes independently current versions as a compatible tuple. F-02 must prove one exact API-36-capable tuple, F-03 applies the real build change atomically, and phone/Wear targets are distinct. |
| B-02 | **Partially Resolved** | §3.1 line 126; §5.1, lines 225-249, especially line 244 | The former nonexistent HRV/SpO2 names and incorrect units are removed. However, `SWIMMING_LAP_COUNT_TOTAL` exists in `health-services-client:1.1.0-rc02` but not stable `1.0.0`. The table does not mark it pre-stable even though §3.1 excludes preview dependencies without an approved exception. |
| B-03 | **Partially Resolved** | §5.3, lines 269-286, especially lines 279-282 | The heart-rate, activity, FGS, location, and API-36 transitions are substantially corrected. The BLE row incorrectly places `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` only in the “Target 36+” column; they apply when targeting API 31+, including the planned Wear target 35. The Samsung <=35 cells also say only “depends on selected SDK” instead of naming the documented `BODY_SENSORS` transition. |
| B-04 | **Resolved** | §8.2, lines 422-442 | All named Health Connect records exist in stable 1.1.0, including `TotalCaloriesBurnedRecord`, `ElevationGainedRecord`, and `SpeedRecord`. Route publication uses `PERMISSION_WRITE_EXERCISE_ROUTE`, and collection permissions are separated from publication permissions. |
| B-05 | **Resolved** | §8.1-§8.3, lines 413-457 | Availability, `Metadata.activelyRecorded`, per-record deterministic IDs, monotonic versions, route-safe updates, ledger state, and explicit child deletion are defined. |
| B-06 | **Resolved** | §6, lines 302-336; F-01/D-01/D-02, lines 618 and 644-645 | The raw watch database is no longer called a Room schema. Conversion is explicit, read-only, on-watch, idempotent ETL into a separately named database with copied fixtures and precision/checksum checks. |
| B-07 | **Partially Resolved** | §2.2, lines 95-105; §7, lines 340-407; V-05, line 636 | Session transfer now has durable outbox/inbox state, hash/length verification, commit ACK, retries, field authority, indefinite tombstones, and cloud-intermediary disclosure. Phone-created tombstones still have no specified durable phone outbox, watch receipt/ACK, retry state, or explicit phone rule rejecting a resent session covered by a local tombstone. `delete_revision` ordering across origins is also undefined. |
| B-08 | **Resolved** | §4, lines 166-219; F-07, line 624 | Google protobuf lite is selected; exact versions come from F-02; JSON/field rebuilding is excluded from unknown-field forwarding; reservations and old/new/old binary fixtures are required. |
| B-09 | **Resolved** | §10, lines 480-523; C-06/C-07, lines 659-660 | The roadmap now uses the released Endurain `v0.19.0` multipart upload endpoint, scoped `X-API-Key`, list response, integer IDs, TCX, `UNKNOWN_DELIVERY`, and explicit duplicate reconciliation. JWT edit/delete and route replacement are deferred. |
| B-10 | **Resolved** | §11, lines 527-560; C-04/C-05, lines 657-658 | Platform trust and hostname verification are the default. Trust-all behavior is prohibited, custom trust is host-scoped, and encryption requires a separate ADR covering AEAD, Keystore loss, SQLCipher conversion, backup, and recovery. |
| B-11 | **Resolved** | §5.4, lines 288-298; §12.3, lines 593-604 | HRV, SpO2, skin temperature, and proprietary vendor metrics are outside core v1 and gated on hardware, exact SDK tests, partner approval, and registered release identity. |
| B-12 | **Resolved** | §2, lines 61-105; §9, lines 461-476; C-01, line 654 | The three-module dependency direction is compilable, both apps are composition roots, connector implementations remain on the mobile runtime classpath, and only one UI-neutral `Connector` port exists. |
| B-13 | **Partially Resolved** | §13-§14, lines 608-706; F-02 line 619; C-06 line 659; §18 line 854 | Primary ownership and required partners now match routing, Mouse owns the test gates, and no concurrent file-scope collision was found. Two ordering defects remain: §18 says F-02 may start immediately although §13.1 is sequential and F-02 depends on F-01; C-06 unnecessarily depends on the unrelated Health Connect gate C-03. |
| B-14 | **Resolved** | §0, lines 9-31; §2, lines 61-93; §13.2, lines 628-639 | The 23-module big bang is withdrawn. Groovy and the three existing modules remain through a runnable record→persist→transfer→persist→display milestone, with Kotlin, Compose, Hilt, and extraction deferred. |

## Required-correction re-verification

| ID | Rating | Exact Cycle 2 reference | Re-verification |
|---|---|---|---|
| T-01 | **Resolved** | §3.1-§3.2, lines 111-147; F-02/F-05, lines 619 and 622 | Exact tuple selection and separate phone/Wear target policy are gated. |
| T-02 | **Resolved** | §3.3, lines 149-162; F-03 through F-07; M-01 through M-04 | AGP/Gradle recovery is atomic; AndroidX, permissions, protobuf/Room, Kotlin, Compose, Hilt, build-script ergonomics, and extraction are separate changes. |
| T-03 | **Partially Resolved** | §5.1, lines 225-249 | Names, types, units, and conversions are corrected except that the RC-only `SWIMMING_LAP_COUNT_TOTAL` is not version-labelled. |
| T-04 | **Partially Resolved** | §5.3, lines 269-286 | The matrix is not yet complete/correct for target-31+ BLE permissions and the exact Samsung <=35 permission transition. |
| T-05 | **Resolved** | §5.4, lines 288-298 | Vendor-only measurements are outside core v1. |
| T-06 | **Resolved** | §8.2, lines 422-442 | Health Connect records and permissions are corrected. |
| T-07 | **Resolved** | §8.1-§8.3, lines 413-457 | Availability, metadata, IDs, versions, update behavior, and deletion are specified. |
| T-08 | **Resolved** | §6, lines 302-336; F-01/D-01/D-02 | Explicit on-watch ETL and copied-fixture validation replace auto-migration. |
| T-09 | **Partially Resolved** | §7, lines 340-407; V-05, line 636 | The activity state machine is sound, but the reverse phone→watch tombstone delivery/commit protocol and delete-revision ordering are incomplete. |
| T-10 | **Resolved** | §4, lines 166-219; F-07, line 624 | Google protobuf lite and old/new/old tests are explicit. |
| T-11 | **Resolved** | §10, lines 480-523 | Endurain endpoints, auth, IDs, formats, duplicate behavior, rate-limit handling, and route-update limits are accurate for `v0.19.0`. |
| T-12 | **Resolved** | §11.1, lines 527-541 | Platform trust and host-scoped custom trust are defined. |
| T-13 | **Resolved** | §11.2, lines 543-560; C-04, line 657 | The required encryption ADR contents and reversible implementation boundary are concrete. |
| T-14 | **Resolved** | §2 and §9, lines 61-105 and 461-476 | Dependency direction, one connector port, and app composition roots are coherent. |
| T-15 | **Resolved** | §0, §2, and §13.2 | Scope is reduced and the vertical slice is first. |
| T-16 | **Resolved** | §13-§14, lines 608-706 | All 30 primaries align with `.squad/routing.md`; domain/security partners are named; Mouse is accountable for integration and release-quality gates. |
| T-17 | **Partially Resolved** | §13, lines 608-673; §18 line 854 | Counts and scopes are repaired, but the F-02 sequencing contradiction and unrelated C-06→C-03 dependency remain. V-05 also needs explicit tombstone delivery scope/tests. |
| T-18 | **Resolved** | §3.2, §12, §16; F-01/F-02/V-07/D-02/D-05/C-03/C-07 | Primary links, reproducible tuple evidence, copied fixtures, and rerunnable contract/device gates are required before affected implementation or release. |

## Independent primary-source checks

### Health Services and platform permissions

Checked the released source artifacts:

- [`health-services-client:1.0.0` sources](https://dl.google.com/dl/android/maven2/androidx/health/health-services-client/1.0.0/health-services-client-1.0.0-sources.jar)
- [`health-services-client:1.1.0-rc02` sources](https://dl.google.com/dl/android/maven2/androidx/health/health-services-client/1.1.0-rc02/health-services-client-1.1.0-rc02-sources.jar)

`HEART_RATE_BPM`, steps/cadence, distance, pace, speed, location, absolute elevation,
gain/loss, calories, floors, VO2 max, swimming strokes/laps, repetitions, and their listed total
variants exist in RC02. `HEART_RATE_VARIABILITY` does not. Pace is stored as milliseconds per
kilometre, calories are kilocalories including basal plus activity, and floors are `Double`.

Stable 1.0.0 contains every identifier listed in §5.1 except
`SWIMMING_LAP_COUNT_TOTAL`; that symbol is present in 1.1.0-rc02. This must be made conditional on
the F-02-selected artifact.

The official
[Bluetooth permissions guide](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
requires `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` when the app targets API 31+, not only target 36.
Samsung's official
[permission table](https://developer.samsung.com/health/sensor/guide/permission-request.html)
uses `BODY_SENSORS` for the relevant trackers at target 35 or lower and
`HealthPermissions.READ_OXYGEN_SATURATION` /
`HealthPermissions.READ_SKIN_TEMPERATURE` at target 36+.

### Health Connect

Checked
[`connect-client:1.1.0` sources](https://dl.google.com/dl/android/maven2/androidx/health/connect/connect-client/1.1.0/connect-client-1.1.0-sources.jar).
The roadmap's named records and constants exist:

- `ExerciseSessionRecord`, `HeartRateRecord`, `StepsRecord`, `DistanceRecord`,
  `TotalCaloriesBurnedRecord`, `ElevationGainedRecord`, `SpeedRecord`, and nested
  `ExerciseRoute`;
- `HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE`;
- `HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY`;
- `HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND`;
- stable `ExerciseRouteRequestContract`;
- `Metadata.activelyRecorded`, `clientRecordId`, and `clientRecordVersion`.

`TotalCaloriesRecord` and stable-1.1
`HealthPermission.PERMISSION_READ_EXERCISE_ROUTES` do not exist, and the Cycle 2 roadmap no longer
uses them.

### Endurain

Checked canonical Codeberg release
[`v0.19.0`](https://codeberg.org/endurain-project/endurain/releases/tag/v0.19.0) and its tagged
router, auth, model, duplicate, configuration, and rate-limit sources.

Verified:

- `GET /api/v1/about`;
- `POST /api/v1/activities/create/upload`, multipart `file`, HTTP 201, list response;
- `.gpx`, `.tcx`, `.fit`, and `.gz` support;
- upload accepts JWT or `X-API-Key` with `activities:upload`;
- API-key query delivery is disabled by default;
- API keys expose only `activities:upload`;
- activity IDs are auto-increment integers;
- same-user/same-start upload still inserts a hidden duplicate;
- edit/delete are JWT `activities:write` operations and edit does not replace streams/routes;
- global default rate limit is 120/minute with 429 backoff headers.

No remaining Endurain endpoint, authentication, ID, idempotency, or update claim in the roadmap was
contradicted.

## Work-plan audit

- **Scheduled item count:** 30, all unique: 7 Foundation + 7 Vertical Slice + 5 Data/Acquisition +
  7 Connector/Security + 4 Later Modernization.
- **Prior-item disposition count:** all 26 Cycle 1 items are accounted for in §14.
- **Initial module count:** exactly three (`mobile`, `wear`, `utilities`) until M-04.
- **Ownership:** all 30 primary owners match `.squad/routing.md`; required cross-domain partners are
  present.
- **File scopes:** no collision remains among items that can run concurrently. Reused build,
  manifest, schema, and composition-root files are ordered by dependencies.
- **Dependency defects:** F-02's “start immediately” wording conflicts with its F-01 dependency;
  C-06 has no contract dependency on C-03; V-05 lacks explicit reverse tombstone delivery scope.

## Exact-tuple gate

The essential F-03 merge gate is correct:

- F-02 acceptance requires independent verification of the coherent tuple
  (`docs/modernization-roadmap.md:619`).
- F-03 depends on F-02 (`:620`).
- The final statement explicitly says F-03 cannot merge until Fact Checker accepts the exact tuple
  (`:854-855`).

Thus real build recovery cannot merge early. Only F-01/F-02 belong to the pre-acceptance phase, but
the sentence saying both may “start immediately” must be reconciled with F-02's F-01 dependency.

## Required Cycle 3 deltas

**Revision owner:** **Neo**. Tank authored the rejected artifact and is locked out from revising it.
Trinity should supply the Wear permission correction.
**Due:** before PR #13 leaves draft or any F-03 change merges.

1. Mark `SWIMMING_LAP_COUNT_TOTAL` as requiring the 1.1 pre-stable line, or remove it from the
   stable core table unless F-02 explicitly approves that dependency.
2. Rewrite the BLE row so target-31+ permissions apply to both target-35 and target-36 builds.
   Name the exact Samsung <=35 `BODY_SENSORS` transition and retain the target-36 vendor constants.
3. Specify durable phone tombstone outbox/retry state, watch receipt and commit ACK, phone rejection
   of tombstoned session resends, and globally deterministic delete ordering/tie-breaks. Add these
   behaviors to V-05 scope and acceptance tests.
4. Reconcile F-02's dependency with §18's “start immediately” wording, and remove C-03 from C-06's
   dependencies unless a real shared prerequisite is documented.

## Binary verdict

## **REJECT**

PR #13 may not proceed out of draft. Re-submit Cycle 3 after the four deltas above are made by the
named replacement revision owner and independently re-verified.
