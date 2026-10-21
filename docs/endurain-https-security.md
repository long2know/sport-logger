# Endurain HTTPS and credential foundation

Date: 2026-10-21
Scope: C-04/C-05 foundation for issue #11

This change is the standalone transport and credential boundary. It deliberately does not add a
Room ledger, canonical activity adapter, TCX exporter, settings UI, automatic scheduler, or Health
Connect publishing. Those pieces bind to this API after the canonical phone repository is available.

## Verified Endurain v0.19.0 contract

The implementation is tied to the released `v0.19.0` source, not an assumed future API:

- release: `endurain-project/endurain` tag `v0.19.0`, published 2026-10-07;
- `backend/app/core/config.py`: `API_VERSION = "v0.19.0"` and `ROOT_PATH = "/api/v1"`;
- `backend/app/core/router.py`: unauthenticated `GET /api/v1/about`;
- `backend/app/core/routes.py` and `backend/app/activities/activity/router.py`:
  `POST /api/v1/activities/create/upload`, multipart field `file`, HTTP 201, and
  `list[Activity]` response;
- `backend/app/activities/activity/schema.py`: the returned field is `id`, a positive integer;
- `backend/app/auth/internal_dependencies.py`: API-key header name `X-API-Key`;
- `backend/app/auth/api_keys/utils.py`: the supported API-key scope is `activities:upload`;
- `backend/app/core/config.py`: API-key query parameters are disabled by default.

SportLogger sends TCX only. It does not use JWT login, URL query credentials, native JSON activity
creation, edit, delete, or server-local bulk import.

## Public APIs for later binding

- `EndurainEndpoint` validates and normalizes an HTTPS base URL and supplies the exact about/upload
  URIs.
- `EndurainConnectionConfig` carries a stable connection identity separately from credentials.
  Capabilities are TCX publish and unknown-delivery reconciliation; delete is unsupported.
- `TcxUploadArtifact` accepts an already-created immutable file with artifact ID, revision, byte
  length, SHA-256, and a header-safe `.tcx` filename. It has no Room or canonical-model dependency.
- `EndurainHttpsTransport.probe(...)` validates the about response and reports the server version.
- `EndurainHttpsTransport.upload(...)` verifies local length/hash before network I/O and returns an
  `EndurainUploadResult`.
- Delivered IDs are returned as `EndurainRemoteActivityId`, scoped by normalized instance URL and
  connection identity. The future ledger must persist every returned value.
- `EndurainCredentialStore` stores, loads, resets, and resets all API-key credentials.

## Delivery classification

| Observation | Result | Automatic retry |
|---|---|---|
| Valid HTTP 201 JSON list containing one or more positive integer `id` fields | `DELIVERED` | No |
| HTTP 401/403 | `AUTHENTICATION_REQUIRED` | No; user must correct key/scope |
| HTTP 400/413/415/422 | `VALIDATION_REJECTED` | No; fix/regenerate TCX |
| Connection failure before request transmission | `RETRYABLE` | Caller may use bounded backoff |
| Probe HTTP 429 | `RATE_LIMITED` with bounded `Retry-After` | Safe for the caller to retry the GET after the returned delay |
| Upload HTTP 429 after POST transmission begins | `UNKNOWN_DELIVERY` / `RATE_LIMITED`, retaining bounded `Retry-After` as advisory data | Never automatically; the server may have applied the body |
| Redirect | `PERMANENT_FAILURE` / `REDIRECT_REJECTED` | No |
| Write/overall deadline or drop after transmission may have started | `UNKNOWN_DELIVERY` | Never automatically |
| HTTP 5xx, unexpected 2xx, or malformed/empty/oversized HTTP 201 body | `UNKNOWN_DELIVERY` | Never automatically |

`EndurainRetryPolicy` provides bounded exponential delays for pre-transmission failures and parses
both delta-seconds and HTTP-date `Retry-After` values. The transport never performs a retry itself.
OkHttp connection retries are disabled, request bodies are marked one-shot, and redirects are
disabled. A 429 from an upload is therefore not evidence that the POST was unapplied.
The later ledger/UI must require manual confirmation or an explicit duplicate-risk retry for
`UNKNOWN_DELIVERY`.

The transport has separate connect, read, write, and whole-call deadlines. The default values are
15 seconds, 30 seconds, 30 seconds, and 75 seconds. The compatibility constructor uses its read
timeout as the write timeout and derives a bounded whole-call timeout from connect plus two read
windows. Deadline cancellation closes the tracked raw TCP socket rather than waiting for a blocked
TLS writer to release an `SSLSocket` monitor. Expiry before request-body transmission is typed as a
retryable pre-transmission timeout; expiry after writing starts is unknown delivery. Deadline tasks
are cancelled and removed after every call, and the single daemon scheduler is allowed to time out
when idle.

Response bodies are size-bounded and strictly decoded as UTF-8 JSON. Results expose typed reasons,
status codes, retry delay, and scoped IDs only; untrusted server bodies are never returned or logged.
Valid supplementary Unicode code points, including raw UTF-8 emoji, are accepted; unmatched
surrogate halves remain invalid JSON.

## HTTPS decision

- The phone manifest retains `INTERNET`, rejects cleartext app traffic, and applies a network
  security configuration that trusts Android system CAs.
- Production uses OkHttp 4.12.0 with Android platform certificate-path and hostname verification.
- The fixed-length multipart request is streamed once from the verified file. Length and SHA-256
  are checked both before network I/O and while the body is written.
- Redirect following is disabled. Therefore `X-API-Key` is never forwarded across a host or scheme
  change.
- Base URLs reject non-HTTPS schemes, embedded credentials, query strings, fragments, malformed
  hosts, encoded separators, and dot segments.
- API keys can only be applied to `HttpsURLConnection`; they are never accepted in a URL.
- Multipart filenames are restricted to bounded ASCII `.tcx` names, preventing header injection.
- Private-CA enrollment and pin rotation are not implemented. There is no trust-all manager or
  hostname-verifier bypass.

The JVM fixture creates an ephemeral CA and localhost certificate with JDK `keytool`, trusts only
that CA through a test-only socket factory/trust manager, retains the default hostname verifier,
and removes the generated key material after the test class.

## Credential protection and restore decision

- API keys are encrypted with Android Keystore AES-256-GCM.
- Each write lets the platform create a fresh 96-bit IV and uses a 128-bit authentication tag.
- Associated data includes a format version, normalized instance URL, and connection identity, so a
  ciphertext cannot be moved to another connection.
- Encrypted envelopes are stored under `Context.getNoBackupFilesDir()`, not SharedPreferences.
  Filenames are SHA-256 connection-scope digests and reveal neither the endpoint nor credential.
- Credential writes are bounded and fsynced, request an atomic same-directory move, and use a
  same-directory replacement fallback where the filesystem cannot advertise atomic-move support.
- Store instances sharing the same Android Keystore alias also share one process-wide transaction
  monitor. Seal/write/load/reset/key deletion therefore cannot interleave across separately
  constructed application store instances.
- Startup construction removes only recognized
  `<scope>.credential.<uuid>.new` crash remnants. Per-connection reset removes the final envelope
  and matching scoped remnants; reset-all removes only recognized connector envelopes/remnants,
  retaining unrelated files.
- Missing/inaccessible Keystore keys and corrupt/tampered storage are separate typed read states.
  Loading never silently creates a replacement key for an existing ciphertext.
- Per-connection reset removes the ciphertext. `resetAll()` removes connector ciphertexts and the
  Keystore alias so the user can explicitly re-enter credentials.
- Cloud backup and device transfer are disabled for the phone app, with exclusion rules covering
  files, databases, preferences, external storage, and device-protected equivalents.

This protects connector credentials at rest. It does **not** encrypt the legacy SQLite store, the
future Room databases, TCX artifacts, or health data. Database encryption remains deferred until
the canonical persistence, ETL, backup, restore, ABI, and 16-KB page-size gates have an independently
approved design.

## Verification

JVM tests execute the real HTTPS code path and cover:

- exact about/upload paths and multipart field;
- one and multiple returned IDs;
- wrong-scope authentication and invalid-TCX rejection;
- body-accepted late 429 with advisory `Retry-After` and no automatic retry;
- failure/whole-call expiry before transmission, a stalled request-body reader that reaches the
  write deadline, a response dripped beneath the read-timeout interval that reaches the whole-call
  deadline, and dropped response after transmission;
- host/scheme redirects without credential forwarding;
- TLS trust and hostname verification;
- 5xx, malformed/empty/oversized 201 responses, integer range, UTF-8, supplementary Unicode,
  unmatched surrogate, and JSON bounds;
- local artifact length/hash validation;
- credential encryption, fresh IVs, scoped AAD, tamper, key loss, two-store store/reset-all
  serialization, recognized crash-temporary cleanup, scoped reset, and non-revealing filenames.

On 2026-10-21, the reserved API 36 phone lane executed the targeted
`EndurainCredentialStoreInstrumentedTest` against the real `AndroidKeyStore`: 2 tests passed in
1.026 seconds. Coverage includes round-trip, tamper, key loss, reset, credential re-entry, and two
separately constructed stores sharing one alias/reset boundary. Only synthetic aliases and files
were used. The existing emulator was left running; no wipe, global adb action, or additional AVD
was used. This is Android platform evidence, not external Endurain account/device acceptance.
