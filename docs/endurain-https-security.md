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
| HTTP 429 | `RATE_LIMITED` with bounded `Retry-After` | Not before returned delay |
| Redirect | `PERMANENT_FAILURE` / `REDIRECT_REJECTED` | No |
| Drop/timeout after transmission may have started | `UNKNOWN_DELIVERY` | Never automatically |
| HTTP 5xx, unexpected 2xx, or malformed/empty/oversized HTTP 201 body | `UNKNOWN_DELIVERY` | Never automatically |

`EndurainRetryPolicy` provides bounded exponential delays for pre-transmission failures and parses
both delta-seconds and HTTP-date `Retry-After` values. The transport never performs a retry itself.
The later ledger/UI must require manual confirmation or an explicit duplicate-risk retry for
`UNKNOWN_DELIVERY`.

Response bodies are size-bounded and strictly decoded as UTF-8 JSON. Results expose typed reasons,
status codes, retry delay, and scoped IDs only; untrusted server bodies are never returned or logged.

## HTTPS decision

- The phone manifest retains `INTERNET`, rejects cleartext app traffic, and applies a network
  security configuration that trusts Android system CAs.
- Production uses `HttpsURLConnection` defaults for certificate-path and hostname verification.
- Redirect following is disabled. Therefore `X-API-Key` is never forwarded across a host or scheme
  change.
- Base URLs reject non-HTTPS schemes, embedded credentials, query strings, fragments, malformed
  hosts, encoded separators, and dot segments.
- API keys can only be applied to `HttpsURLConnection`; they are never accepted in a URL.
- Multipart filenames are restricted to bounded ASCII `.tcx` names, preventing header injection.
- Private-CA enrollment and pin rotation are not implemented. There is no trust-all manager or
  hostname-verifier bypass.

The JVM fixture creates an ephemeral CA and localhost certificate with JDK `keytool`, trusts only
that CA through a test-only socket factory, retains the default hostname verifier, and removes the
generated key material after the test class.

## Credential protection and restore decision

- API keys are encrypted with Android Keystore AES-256-GCM.
- Each write lets the platform create a fresh 96-bit IV and uses a 128-bit authentication tag.
- Associated data includes a format version, normalized instance URL, and connection identity, so a
  ciphertext cannot be moved to another connection.
- Encrypted envelopes are stored under `Context.getNoBackupFilesDir()`, not SharedPreferences.
  Filenames are SHA-256 connection-scope digests and reveal neither the endpoint nor credential.
- Credential writes are bounded and fsynced, request an atomic same-directory move, and use a
  same-directory replacement fallback where the filesystem cannot advertise atomic-move support.
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
- 429 `Retry-After`;
- failure before transmission and dropped response after transmission;
- host/scheme redirects without credential forwarding;
- TLS trust and hostname verification;
- 5xx, malformed/empty/oversized 201 responses, integer range, UTF-8, and JSON bounds;
- local artifact length/hash validation;
- credential encryption, fresh IVs, scoped AAD, tamper, key loss, reset, and non-revealing filenames.

An Android instrumentation test exercises the same credential store with the real
`AndroidKeyStore`. It is compile-verified here and requires the parent-owned phone instrumentation
lane to execute; no additional emulator is started by this change.
