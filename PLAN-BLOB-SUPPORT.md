# Blob Support Plan

## Status

Reviewed. Amendments from Codex (gpt-5.4) and manual review integrated.

**Implementation status (2026-04-16):** substantially complete, pending commit. All routes, metadata v1 + lazy migration, resumable upload sessions, per-account quota, checksum validation, GC/cleanup, WebSocket `sourceDeviceId`, startup auto-heal, and If-Match/If-None-Match preconditions are implemented and covered by server integration tests. See "Implementation Deferrals" below for items intentionally punted.

## Problem

The current server stores exactly one opaque binary payload per module at `POST /v1/module/{moduleId}`.

This is too limited for larger encrypted payloads:

- uploads and downloads are fully buffered in memory
- the server-wide body limit is global and small
- there is no resumable upload support for flaky mobile networks
- there is no first-class quota model for account storage
- there is no safe way to keep large child blobs outside the root encrypted module document

## Goals

- Keep existing clients working on the current module API.
- Add first-class module-owned blob storage for new clients.
- Support resumable uploads for mobile clients on unstable connections.
- Support full HTTP range requests for blob downloads.
- Enforce account-level quota with clear client-visible limits.
- Keep the server ignorant of decrypted module or blob contents.
- Write the implementation so larger blobs are still structurally viable later, even if current limits stay low.

## Non-Goals

- No cross-module or cross-account deduplication.
- No server-side understanding of encrypted payload contents.
- No per-module total quota in the first version.
- No arbitrary sparse upload patching; resumable uploads are append-only from the confirmed offset.

## Current State

- `GET/POST/DELETE /v1/module/{moduleId}` reads and writes a single raw payload.
- The current implementation buffers module payloads with `ByteArray`.
- The current payload limit is a global request-body limit.
- WebSocket notifications only announce `module_changed`.
- Module expiration is currently based on file timestamps, not explicit access metadata.

## High-Level Model

Each module will have:

- one current root document
- zero or more child blobs owned by that module
- one server-side manifest describing the current module revision

All payloads remain opaque encrypted bytes to the server.

The important distinction is logical, not cryptographic:

- the root document is the module's current authoritative state
- child blobs are larger opaque objects referenced by that root document

## Compatibility Model

Existing endpoints remain:

- `GET /v1/module/{moduleId}`
- `POST /v1/module/{moduleId}`
- `DELETE /v1/module/{moduleId}`

These continue to use the existing target-device query parameter model:

- `?device-id={targetDeviceId}` selects which device-owned module instance is being read or written
- the effective scope is `(accountId, targetDeviceId, moduleId)`, not just `moduleId`

Legacy behavior on the new backend:

- legacy `GET` returns the current root document bytes exactly as before
- legacy `POST` is internally translated to "replace the root document and clear external blob refs" only for targeted module instances that do not currently use external blobs
- legacy `DELETE` deletes the whole module, including live blobs and staged uploads

Safety rules:

- if the targeted `(accountId, targetDeviceId, moduleId)` currently has external blobs, legacy `POST /v1/module/{moduleId}?device-id={targetDeviceId}` returns `409 Conflict`
- a successful legacy `POST` that replaces the root document also aborts all outstanding upload sessions for that `(accountId, targetDeviceId, moduleId)` instance and releases their reserved quota

Reason:

- old clients cannot understand or preserve external blob references
- silently accepting the write would risk data loss
- outstanding sessions must be cleaned up because the old client has no awareness of them, and a new client's pending commit would reference a root document that no longer exists

## In-Place Upgrade Contract

This plan explicitly supports in-place server upgrades where existing accounts continue to use the same server with a mix of old and new clients.

### Mandatory Compatibility Outcomes

- the new server binary starts against existing on-disk data without a required offline migration step
- legacy modules remain readable and writable through legacy endpoints after upgrade
- new storage endpoints can be used on the same account and device namespace without breaking legacy-only modules
- no existing module payload bytes are rewritten during metadata migration unless a client performs a write

### Legacy On-Disk Module Format Accepted At Upgrade

An upgraded server must accept existing module-instance directories that contain:

- `payload.blob` with the current root document bytes
- `module.json` in the legacy shape `{ id, source }`

The upgraded server treats this legacy state as:

- one live root document
- zero live blob refs
- zero staged upload sessions

### Metadata Migration Strategy

Migration is lazy and per targeted module instance `(accountId, targetDeviceId, moduleId)`.

1. On first module access (`GET`, `POST`, `PUT`, blob list/read, or session creation), the server loads module metadata.
2. If metadata is legacy, the server synthesizes schema v1 metadata and atomically rewrites `module.json` without changing `payload.blob`.
3. Synthesis rules for legacy modules are:
- `moduleId`: from request path
- `sourceDeviceId`: legacy `source` if readable, else `targetDeviceId`
- `documentSizeBytes`: current `payload.blob` byte size
- `modifiedAt`: current `payload.blob` mtime
- `blobRefs`: empty list
- `access.json` `lastAccessedAt`: migration-time `now`
- `etag`: deterministic from on-disk state — derived as a hash of `(moduleId + payload.blob file size + payload.blob mtime)` so that repeated synthesis without successful persist always produces the same ETag
4. If `module.json` is missing or unreadable but `payload.blob` exists, the server recovers with the same synthesis rules instead of failing the request.
5. If the module directory is absent, behavior remains legacy-compatible (`204 No Content` for raw module `GET`).
6. Migration must not fail reads. If the server synthesizes v1 metadata in memory but the persist-to-disk step fails (low disk, permissions), the read still succeeds with the synthesized in-memory metadata. Migration persist is best-effort on reads and retries on next access.

### Mixed Legacy And New-Client Rules

- legacy `GET/POST/DELETE` remain available
- legacy `GET` on absent modules returns `204` with empty body
- legacy `GET` on existing modules returns `200` raw bytes plus `X-Modified-At`
- legacy `POST` remains allowed only while the targeted module instance has zero live `blobRefs`
- legacy `POST` returns `409 Conflict` when the targeted module instance is blob-backed
- new `PUT` on an existing module instance requires a matching `If-Match` `ETag`
- new `PUT` create-on-absent semantics require `If-None-Match: *`
- new `PUT` with `If-None-Match: *` returns `412 Precondition Failed` if the module already exists
- missing or invalid `If-Match` for `PUT` returns `412 Precondition Failed`

### Notification Semantics During Mixed Operation

- `module_changed` remains the only sync event type for commits and deletes
- event `deviceId` identifies the targeted module-owner device (refresh key), not the caller device
- `sourceDeviceId` is mandatory from day one and carries actor identity
- self-notification suppression filters on `sourceDeviceId`, not `deviceId`
- this is required because today's `SyncNotifier` filters on `event.deviceId != peer.deviceId` — repurposing `deviceId` to mean target device without adding `sourceDeviceId` would break self-suppression for cross-device writes
- **prerequisite**: verify that the Android client's kotlinx-serialization config uses `ignoreUnknownKeys = true` before adding `sourceDeviceId` to the event payload — if not, old clients will crash on the unknown field, and the new field must be gated behind a capability handshake or shipped as a separate event type

### Quota And Recovery Requirements With Legacy Data

- startup recovery must count legacy root-document bytes in `usedBytes`
- migrated legacy modules with no external blobs count only `documentSizeBytes`
- metadata migration alone must not make an account over-quota

## New API Surface

### Targeting Model

Blob and commit operations are scoped to a device-owned module instance, not only a module name.

Endpoints that operate on live module/blob state must include the existing target-device query parameter:

- `?device-id={targetDeviceId}`

This applies to:

- raw module reads and writes
- blob reads
- blob listing
- upload-session creation
- module commits

Follow-up upload-session endpoints do not need `device-id` because `sessionId` metadata already binds the session to its target account, device, and module.

### Blob Download

- `HEAD /v1/module/{moduleId}/blobs/{blobId}?device-id={targetDeviceId}`
- `GET /v1/module/{moduleId}/blobs/{blobId}?device-id={targetDeviceId}`

Behavior:

- serves committed live blobs only
- supports full HTTP range requests
- returns `ETag`, `Last-Modified`, `Content-Length`, `Accept-Ranges`

### Blob List

- `GET /v1/module/{moduleId}/blobs?device-id={targetDeviceId}`

Behavior:

- returns the current live blob set for the targeted module instance
- reads from `module.json`, not from a filesystem directory scan
- returns the same module revision `ETag` as the corresponding raw `GET /v1/module/{moduleId}?device-id={targetDeviceId}` read path

Suggested response body:

```json
{
  "moduleEtag": "current-module-etag",
  "blobs": [
    {
      "blobId": "server-generated-id",
      "sizeBytes": 5242880,
      "hashAlgorithm": "sha256",
      "hashHex": "optional-lowercase-hex"
    }
  ]
}
```

### Resumable Upload Sessions

- `POST /v1/module/{moduleId}/blob-sessions?device-id={targetDeviceId}`
- `HEAD /v1/module/{moduleId}/blob-sessions/{sessionId}`
- `PATCH /v1/module/{moduleId}/blob-sessions/{sessionId}`
- `POST /v1/module/{moduleId}/blob-sessions/{sessionId}/finalize`
- `DELETE /v1/module/{moduleId}/blob-sessions/{sessionId}`

Behavior:

- session creation reserves quota for the declared blob size and returns a server-generated `blobId` plus a server-generated `sessionId`
- session creation may accept an optional checksum for early validation and reuse decisions
- uploads are append-only from the server-confirmed offset
- session `HEAD` returns current offset and expiry info
- session `PATCH` appends bytes to a staged temp file
- session `finalize` verifies final size and checksum before the staged upload becomes committable
- session `DELETE` aborts the upload, deletes staged bytes, and releases reservation
- a completed upload is not live until a module commit references it

### New Module Commit

- `PUT /v1/module/{moduleId}?device-id={targetDeviceId}`

Behavior:

- replaces the current root document for new clients
- requires `If-Match`
- atomically installs the new root document and authoritative blob ref list
- promotes completed staged blobs into live blobs if referenced
- deletes old live blobs that are no longer referenced by the committed module revision
- decodes `documentBase64` and stores the raw root document bytes on disk
- preserves the existing raw read behavior for `GET /v1/module/{moduleId}?device-id={targetDeviceId}`

### Blob Session Request Schema

`POST /v1/module/{moduleId}/blob-sessions?device-id={targetDeviceId}`

Request body:

```json
{
  "sizeBytes": 5242880,
  "hashAlgorithm": "sha256",
  "hashHex": "optional-lowercase-hex"
}
```

Rules:

- `sizeBytes` is required and must be positive
- `hashAlgorithm` is optional at create time, but only `sha256` is supported in the first version
- `hashHex` is optional at create time, but if present it must be 64 lowercase hex characters
- each session creation always creates a new session; session reuse is deferred to a future version

Response body:

```json
{
  "blobId": "server-generated-id",
  "sessionId": "server-generated-id",
  "offsetBytes": 0,
  "expiresAt": "2026-04-11T12:34:56Z",
  "state": "active"
}
```

`HEAD /v1/module/{moduleId}/blob-sessions/{sessionId}`

Response headers:

- `Upload-Offset`
- `Upload-Length`
- `Upload-Expires`
- `Upload-State`
- `X-Blob-ID`

`PATCH /v1/module/{moduleId}/blob-sessions/{sessionId}`

Request headers:

- `Upload-Offset`

Request body:

- raw `application/octet-stream`

Rules:

- the request offset must match the server-confirmed offset exactly
- uploads are append-only; sparse or overlapping writes are rejected

### Upload Edge Cases

- `PATCH` where `offset + chunk > expectedSizeBytes`: reject with `409 Conflict` before writing any bytes
- `PATCH` without `Content-Length`: accepted — stream bytes and count them, abort with `409 Conflict` if accumulated bytes would exceed `expectedSizeBytes - currentOffset`, enforce a per-request max chunk size as a safety cap
- retried `finalize` after first success: idempotent — return the same `complete` response
- duplicate `blobId` values in commit `blobRefs`: reject with `400 Bad Request`
- empty `documentBase64` (zero-length root document): allowed — the server treats payloads as opaque, module implementations may have legitimate use cases for zero-length documents
- zero-length blob (`sizeBytes: 0` at session creation): allowed — quota reservation is zero bytes (no-op), the server should not enforce minimum sizes on opaque payloads

`POST /v1/module/{moduleId}/blob-sessions/{sessionId}/finalize`

Request body:

```json
{
  "hashAlgorithm": "sha256",
  "hashHex": "required-if-not-supplied-at-create"
}
```

Rules:

- a checksum must be known by finalize time
- if a checksum was supplied at session creation, finalize must either omit it or supply the exact same value
- finalize verifies both final size and checksum before marking the session `complete`

Response body:

```json
{
  "blobId": "server-generated-id",
  "sessionId": "server-generated-id",
  "sizeBytes": 5242880,
  "state": "complete"
}
```

### Module Commit Request Schema

`PUT /v1/module/{moduleId}?device-id={targetDeviceId}`

Request headers:

- `If-Match`

Request body:

```json
{
  "documentBase64": "base64-encoded-root-document-bytes",
  "blobRefs": [
    {
      "blobId": "server-generated-id"
    }
  ]
}
```

Rules:

- the root document remains opaque to the server
- `documentBase64` is required
- `blobRefs` is the authoritative live blob set for the new module revision
- every referenced `blobId` must already be live for that module or be in a finalized staged session for that module

### Module Read Semantics

`GET /v1/module/{moduleId}?device-id={targetDeviceId}` continues to return the raw root document bytes, not the JSON commit envelope.

Rules:

- the server decodes `documentBase64` during `PUT` and persists only the raw document bytes in `payload.blob`
- both legacy and new-client module reads return raw encrypted document bytes
- the response `ETag` identifies the current module revision
- `GET /v1/module/{moduleId}/blobs?device-id={targetDeviceId}` should expose the same revision via `moduleEtag` so clients can detect mismatches and refetch if needed

Persisted module revision metadata example (this is not the raw module `GET` response body):

```json
{
  "etag": "new-module-etag",
  "modifiedAt": "2026-04-11T12:34:56Z",
  "documentSizeBytes": 12345,
  "blobRefs": [
    {
      "blobId": "server-generated-id",
      "sizeBytes": 5242880
    }
  ]
}
```

Status codes used by the new storage API:

- `400 Bad Request` for malformed request shapes, invalid checksum syntax, or missing required fields
- `404 Not Found` for unknown module-scoped blob sessions or live blobs
- `409 Conflict` for upload offset mismatches and legacy writes to blob-backed modules
- `412 Precondition Failed` for `If-Match` failures
- `413 Payload Too Large` for document or chunk size violations
- `422 Unprocessable Content` for checksum mismatch at finalize time
- `507 Insufficient Storage` for quota exhaustion

## Why Upload Sessions Are Separate From Live Blob Endpoints

Committed blobs are immutable.

So these are different resource types:

- live blob: immutable, readable, referenced by the current module revision
- upload session: mutable, partial, resumable, not yet live

Keeping them separate makes quota, cleanup, and retry behavior much safer.

## Blob Identity And Integrity

`blobId` is a server-generated opaque identifier.

The client may also provide an integrity checksum for uploaded bytes:

- only `sha256` is supported in the first version
- `hashHex`, when provided, must be exactly 64 lowercase hex characters
- invalid checksum syntax is rejected with `400 Bad Request`
- finalized uploads are rejected with `422 Unprocessable Content` if the staged bytes do not match the expected checksum

Security note:

- the client never chooses an on-disk path
- API-visible `blobId` values are server-generated
- filesystem paths use a server-only `storageKey`

Example path layout:

```text
blobs/ab/cd/<storageKey>/payload.blob
```

This prevents path traversal and keeps directories scalable.

## Server-Side Plaintext Metadata

The server will persist plaintext metadata per module revision:

- root document size
- blob IDs
- server-only blob storage keys
- blob sizes
- optional checksum metadata
- `ETag` / generation
- timestamps needed for sync, expiry, and conditional writes

This is acceptable and necessary because:

- encrypted payload bytes do not tell the server which blobs are still live
- filesystem size alone cannot distinguish live blobs from orphaned blobs
- quota reservation needs authoritative accounting during concurrent uploads

The server still does not need any decrypted module contents.

## Storage Layout

Current module storage should evolve, not be replaced wholesale.

Proposed layout:

```text
dataPath/accounts/{accountId}/
└── devices/{deviceId}/
    └── modules/{sha1(moduleId)}/
        ├── payload.blob
        ├── module.json
        ├── access.json
        ├── blobs/
        │   └── {prefix shards}/{storageKey}/
        │       └── payload.blob
        └── sessions/
            └── {sessionId}/
                ├── payload.part
                ├── payload.blob   (after finalize, renamed from payload.part)
                └── session.json
```

Filesystem invariant: `blobs/` contains only committed live blobs. `sessions/` contains all transient state including finalized-but-uncommitted blobs. The commit path moves finalized blobs from `sessions/` to `blobs/`.

A module directory containing only `sessions/` (no `module.json`, no `payload.blob`) is not considered a live module. Legacy `GET` returns `204`. Session GC applies normally. If all sessions expire without a commit, the empty directory is cleaned up.

`module.json` becomes the authoritative module metadata record.

There is no separate live `blob.json` file in the first version; live blob metadata stays in `module.json`.

It should include at least:

- module ID
- source device ID
- current `ETag`
- modified timestamp
- root document size
- current live blob refs `{ blobId, storageKey, sizeBytes, hashAlgorithm?, hashHex? }`

`access.json` is a separate file tracking hot access metadata:

- last accessed timestamp

This separation avoids rewriting the full module manifest on every read and removes module-lock contention for access tracking. Access writes are coalesced on a fixed interval (e.g., every 5 minutes) to avoid excessive disk I/O.

`session.json` should include at least:

- session ID
- server-generated blob ID
- server-only storage key
- account ID
- device ID
- module ID
- expected total size
- current offset
- optional expected checksum algorithm
- optional expected checksum hex value
- created timestamp
- last activity timestamp
- absolute expiry timestamp
- state: `active`, `uploaded`, `complete`, `aborted`, or `expired`

### Persisted Metadata Schemas

The implementation should treat the following shapes as the initial persisted contract for metadata files.

`module.json`:

```json
{
  "schemaVersion": 1,
  "moduleId": "eu.darken.octi.module.example",
  "sourceDeviceId": "00000000-0000-0000-0000-000000000000",
  "etag": "module-etag",
  "modifiedAt": "2026-04-11T12:34:56Z",
  "documentSizeBytes": 12345,
  "blobRefs": [
    {
      "blobId": "server-generated-id",
      "storageKey": "server-only-storage-key",
      "sizeBytes": 5242880,
      "hashAlgorithm": "sha256",
      "hashHex": "optional-lowercase-hex"
    }
  ]
}
```

`session.json`:

```json
{
  "schemaVersion": 1,
  "sessionId": "server-generated-id",
  "blobId": "server-generated-id",
  "storageKey": "server-only-storage-key",
  "accountId": "00000000-0000-0000-0000-000000000000",
  "deviceId": "00000000-0000-0000-0000-000000000000",
  "moduleId": "eu.darken.octi.module.example",
  "expectedSizeBytes": 5242880,
  "offsetBytes": 2621440,
  "hashAlgorithm": "sha256",
  "hashHex": "optional-lowercase-hex",
  "createdAt": "2026-04-11T12:34:56Z",
  "lastActivityAt": "2026-04-11T12:35:56Z",
  "expiresAt": "2026-04-11T18:34:56Z",
  "state": "active"
}
```

Kotlin model sketch for implementation:

```kotlin
@Serializable
data class ModuleMeta(
    val schemaVersion: Int = 1,
    val moduleId: String,
    val sourceDeviceId: UUID,
    val etag: String,
    val modifiedAt: Instant,
    val documentSizeBytes: Long,
    val blobRefs: List<BlobRef>,
)

@Serializable
data class BlobRef(
    val blobId: String,
    val storageKey: String,
    val sizeBytes: Long,
    val hashAlgorithm: String? = null,
    val hashHex: String? = null,
)

@Serializable
data class UploadSessionMeta(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val blobId: String,
    val storageKey: String,
    val accountId: UUID,
    val deviceId: UUID,
    val moduleId: String,
    val expectedSizeBytes: Long,
    val offsetBytes: Long,
    val hashAlgorithm: String? = null,
    val hashHex: String? = null,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val expiresAt: Instant,
    val state: State,
) {
    @Serializable
    enum class State { ACTIVE, UPLOADED, COMPLETE, ABORTED, EXPIRED }
}
```

Notes:

- `schemaVersion` is included from the start to make later metadata migrations explicit.
- `storageKey` is persisted but never exposed to clients.
- `hashAlgorithm` and `hashHex` stay nullable because checksum declaration is optional at session creation.
- the implementation may choose UUID or ULID for `blobId`, `sessionId`, and `storageKey`, but they must be server-generated and fixed-format.

## Quota Model

Quota is primarily per account.

Why per account:

- storage ownership is account-level
- paid plans will map to accounts, not devices
- multiple devices on one account should share one pool

Initial quota controls:

- `accountQuotaBytes`
- `maxBlobBytes`
- `maxModuleDocumentBytes`
- `maxActiveUploadSessionsPerDevice`

Not in the first version:

- per-device storage quota
- per-module total quota

### Accounted Bytes

Quota accounting uses logical payload sizes, not filesystem block usage.

Counted as used bytes:

- live root document bytes
- live blob bytes

Counted as reserved bytes:

- declared size of active or completed-but-uncommitted upload sessions

Not counted:

- JSON metadata files
- filesystem overhead

### Reservation Rules

At session creation:

- check `expectedSize <= maxBlobBytes`
- check account `usedBytes + reservedBytes + expectedSize <= accountQuotaBytes`
- reserve `expectedSize`

Note on replacement near quota: replacing a large blob with a same-sized blob temporarily requires double the space (old blob is live, new blob is reserved). This means an account needs headroom equal to its largest blob for in-place replacements. The `/v1/account/storage` response should expose `availableBytes` accounting for this so clients can plan uploads accordingly. A future version may add replacement-aware reservation tied to the `If-Match` revision to avoid this limitation.

At session abort or expiry:

- delete staged bytes
- release reservation

At successful module commit:

- referenced completed uploads become live bytes
- referenced bytes move from reserved to used
- bytes for newly orphaned old blobs are removed from used

## Capability And Limit Discovery

Add an authenticated endpoint:

- `GET /v1/account/storage`

Purpose:

- tell new clients whether the storage API is supported
- expose effective account limits and current usage
- avoid hardcoding server limits in clients

Suggested response fields:

- `storageApiVersion`
- `accountQuotaBytes`
- `usedBytes`
- `reservedBytes`
- `availableBytes`
- `maxBlobBytes`
- `maxModuleDocumentBytes`
- `maxActiveUploadSessionsPerDevice`
- `idleSessionTtlSeconds`
- `absoluteSessionTtlSeconds`

This is not a generic feature-flag system. It is a concrete storage capability and limit endpoint.

## Resumable Upload Semantics

Upload sessions have two timeouts:

- idle TTL: reset on each successful append
- absolute TTL: hard cap on total session lifetime

If a session expires:

- the session becomes invalid
- the partial staged file is deleted
- reserved quota is released
- the client must create a new session

Reason:

- stale sessions must not reserve quota forever
- current blob sizes are small enough that restart-from-zero is acceptable
- the structure still supports longer TTLs later if blob limits increase

## Download Semantics

Blob downloads support:

- `HEAD`
- `GET`
- `Range`
- `If-Range`
- `206 Partial Content`
- `416 Range Not Satisfiable`

Implementation should use Ktor's partial-content support on file responses rather than manual range parsing where possible.

The root module document does not need range support in the first version because it is expected to stay small.

## Conditional Writes

New module commits require `If-Match`.

Rules:

- `GET /v1/module/{moduleId}?device-id={targetDeviceId}` returns the current `ETag`
- `PUT /v1/module/{moduleId}?device-id={targetDeviceId}` must include `If-Match`
- if the module changed since the client read it, return `412 Precondition Failed`

Legacy `POST /v1/module/{moduleId}?device-id={targetDeviceId}` remains last-write-wins only for legacy-compatible targeted module instances without external blobs.

## Commit Semantics

The new module commit is the only operation that changes the authoritative live blob set.

Commit flow:

1. Validate caller and target device.
2. Validate `If-Match` against current module revision.
3. Validate root document size against `maxModuleDocumentBytes`.
4. Validate that every referenced blob either already exists live or has a completed finalized staged upload.
5. Validate no duplicate `blobId` values in `blobRefs`.
6. Write the new root document to a temp file.
7. Move finalized blobs from `sessions/{sessionId}/payload.blob` to `blobs/{prefix}/{storageKey}/payload.blob` for each newly referenced blob.
8. Atomic rename of temp `payload.blob` into place.
9. Write updated `module.json` with the new `ETag`, timestamps, sizes, and live blob refs to a temp file.
10. Atomic rename of temp `module.json` into place — this is the commit point.
11. Update quota counters (move referenced sessions from reserved to used, subtract orphaned blobs from used).
12. Orphaned old blobs are cleaned up asynchronously by GC, not deleted in the commit path.

Crash safety: `module.json` rename is the single commit point. If the process crashes before step 10, the old module revision is still intact. Blob files moved in step 7 but not yet referenced by `module.json` are orphans — GC reclaims them via the `blobs/ not in module.json` rule. The new `payload.blob` written in step 8 is harmless — it will be overwritten by the next successful commit, or normalized by startup recovery. Old blobs left on disk after a successful commit are reclaimed by GC as orphans.

Finalized blobs stay in `sessions/` until the commit path moves them. This maintains the filesystem invariant that `blobs/` contains only committed live blobs, simplifying GC and orphan detection.

This must be atomic from the perspective of readers.

## WebSocket Notifications

Keep WebSocket sync at module granularity.

Behavior:

- upload session changes do not emit sync notifications
- successful module commit emits `module_changed`
- module delete emits `module_changed` with `deleted`
- the event payload continues to include both `deviceId` and `moduleId` so clients can refresh the targeted module instance only

This keeps sync behavior aligned with "the module revision changed" rather than low-level upload progress.

## Cleanup And Expiration

### Upload Sessions

GC removes:

- idle expired sessions (including their staged blob files in `sessions/`)
- absolutely expired sessions
- staged files for aborted sessions
- completed but never committed uploads after TTL expiry

Session cleanup is self-contained: everything under `sessions/{sessionId}/` is deleted together.

### Live Blobs

The `blobs/` directory contains only committed live blobs. GC orphan rule: any file in `blobs/` not referenced by `module.json` `blobRefs` is an orphan (single source check).

Live blobs are removed when:

- a successful module commit no longer references them (cleaned up asynchronously by GC)
- the module is deleted
- the device is deleted
- the account is deleted
- the module expires by retention policy

### Module Expiration Protection

Session creation, `PATCH`, `finalize`, and commit all count as module activity and update `lastAccessedAt`. Module GC must skip modules that have any active or complete-but-uncommitted upload sessions to prevent expiration during in-flight uploads.

### Access Tracking

If retention is meant to be access-based, explicit metadata must be updated on reads.

The implementation should stop relying on file mtimes for retention decisions.

`access.json` (separate from `module.json`) carries the explicit `lastAccessedAt` value. Blob reads and module reads update this file. Writes are coalesced on a fixed interval (e.g., every 5 minutes) to avoid excessive disk I/O.

This separation is important for two reasons:

- `lastAccessedAt` must NOT affect the module `etag`. The `etag` is derived only from content-affecting fields (`documentSizeBytes`, `blobRefs`, `modifiedAt`). Access timestamp updates do not create a new module revision.
- Access tracking does not require the module lock. Upload appends (`PATCH`) can update access timestamps without contending for the module lock, which would otherwise contradict the locking model.

## Concurrency And Locking

The current per-device lock is too coarse for larger transfers.

Planned locking model:

- per-module lifecycle flag (atomic) for destructive operation coordination
- per-session lock for upload append operations
- per-module lock for root document and live blob set changes (replaces current per-device Mutex)
- account-level quota lock for reservation and usage updates (held briefly)

Strict acquisition order: lifecycle check -> session lock -> module lock -> quota lock.

A commit acquires the module lock, then briefly acquires the quota lock for accounting updates. Session appends only hold the session lock and never acquire module or quota locks. Session creation acquires the quota lock for reservation but not the module lock. This ordering prevents deadlocks.

### Destructive Operation Coordination

Destructive operations (delete module, delete device, delete account, module GC) must coordinate with in-flight uploads. Before a destructive operation:

1. Set the per-module lifecycle flag to `deleting` (atomic write)
2. New session creation, `PATCH`, and `finalize` check the flag and reject with `409 Conflict` if set
3. Wait briefly for active operations to drain (short grace period, e.g., 5 seconds)
4. Proceed with deletion under the module lock

The lifecycle flag is a fast atomic check, not a held lock. It prevents new work from starting while existing work finishes.

Goals:

- one large blob upload should not block unrelated module operations on the same device
- quota enforcement must remain race-free
- commit promotion and cleanup must remain atomic
- no deadlocks due to strict lock ordering
- destructive operations can freeze new work and drain active operations safely

## Startup Recovery

On restart:

- load current module metadata from disk
- rebuild live used-byte accounting from module metadata and live blob file sizes
- load non-expired upload sessions from `session.json`
- rebuild reserved bytes from active and complete staged sessions
- delete clearly invalid or expired staged sessions during startup recovery

Crash-state recovery (auto-heal all inconsistencies, log structured warnings):

- `session.json` says `offsetBytes=N` but `payload.part` is shorter than N bytes: truncate offset to actual file size, session remains resumable from the actual offset — emit `recovery.session_offset_truncated`
- `module.json` references a live blob whose file is missing on disk: remove that `blobRef` from metadata, subtract its size from `usedBytes` — emit `recovery.blobref_removed`
- blob files on disk not referenced by any `module.json` or active `session.json`: reclaim as orphans after a grace period (e.g. 1 hour) to avoid racing with in-progress commits — emit `recovery.orphan_reclaimed`
- `payload.blob` size does not match `module.json` `documentSizeBytes`: update `documentSizeBytes` to actual file size — emit `recovery.document_size_normalized`

Rationale for auto-heal: the server admin has no way to reconstruct missing encrypted blobs or fix user data. Failing with an error permanently bricks the module with no recovery path. Auto-heal with structured log events allows monitoring corruption rates without blocking users.

The low current limits make startup rescans acceptable.

## Migration Strategy

Phase 0:

- implement the in-place upgrade contract for legacy module metadata
- preserve legacy module `GET` wire behavior (`204`/raw bytes + `X-Modified-At`)
- implement explicit `PUT` precondition rules for existing-vs-absent modules
- add test helpers that can seed legacy on-disk module fixtures before server startup

Phase 0.5 — Server plumbing:

- install Ktor `PartialContent` plugin for range responses on blob downloads
- install Ktor `ConditionalHeaders` plugin for ETag / `If-Match` / `If-None-Match` handling
- verify `HEAD` auto-response support (Ktor generates HEAD from GET, but confirm for file responses)
- add per-route body limit override so blob `PATCH` can accept larger chunks than the global 128KB limit
- add `sourceDeviceId` field to `SyncNotifier.EventPayload.Event.ModuleChanged` and update self-suppression filter

Phase 1:

- refactor module I/O to stream rather than buffer whole payloads
- add explicit module `ETag`
- add explicit last-access metadata

Phase 2:

- add account storage tracking and `/v1/account/storage`
- add session storage and quota reservation

Phase 3:

- add blob download endpoints with range support
- add resumable upload session endpoints

Phase 4:

- add new `PUT /v1/module/{moduleId}?device-id={targetDeviceId}` commit path
- keep legacy `GET/POST/DELETE`
- return `409` for legacy writes to blob-backed modules

Phase 5:

- add integration tests for mixed legacy/new-client behavior, quota edge cases, resume behavior, and cleanup

## Test Plan

Testing should explicitly cover both:

- focused unit tests for migration, precondition, quota-accounting, and notification-shaping logic
- fixture-driven integration tests that start the upgraded server against legacy on-disk data

### Legacy Fixture Setup

Compatibility tests should seed legacy module-instance directories on disk before server startup using the current storage shape:

- `accounts/{accountId}/devices/{deviceId}/modules/{sha1(moduleId)}/payload.blob`
- `accounts/{accountId}/devices/{deviceId}/modules/{sha1(moduleId)}/module.json`

The legacy `module.json` fixture should use the current `{ id, source }` payload shape. Tests should also cover:

- missing `module.json` with present `payload.blob`
- unreadable or malformed legacy `module.json` with present `payload.blob`
- multiple devices within one account where only one targeted module instance is migrated or blob-backed

### Unit Tests

Unit tests should cover at least:

- legacy metadata synthesis from `{ id, source }` plus `payload.blob` into schema v1 metadata
- synthesis uses current `payload.blob` size and mtime for `documentSizeBytes` and `modifiedAt`
- synthesized ETag is deterministic — repeated synthesis from same on-disk state produces the same ETag
- synthesis leaves `payload.blob` bytes unchanged
- missing legacy `module.json` with present `payload.blob` recovers successfully
- malformed legacy `module.json` with present `payload.blob` recovers successfully
- `PUT` on an absent module with `If-None-Match: *` succeeds
- `PUT` on an existing module with `If-None-Match: *` fails with `412`
- `PUT` on an existing module with a matching `ETag` succeeds
- `PUT` on an existing module with a stale `ETag` fails with `412`
- `PUT` with missing or invalid `If-Match` fails with `412`
- legacy `POST` allows overwrite when live `blobRefs` is empty
- legacy `POST` returns `409` when live `blobRefs` is non-empty
- legacy `POST` aborts all outstanding upload sessions for that module instance
- quota startup accounting includes legacy root-document bytes
- metadata migration alone does not reserve bytes or fail solely because the account is already near quota
- WebSocket event shaping uses the targeted module-owner `deviceId`, not the caller device id, for cross-device writes
- WebSocket self-suppression filters on `sourceDeviceId`, not `deviceId`

### Integration Tests

- legacy clients still reading and writing legacy-only modules
- upgraded server boots against seeded legacy on-disk module data without offline migration
- first access to a seeded legacy module lazily migrates `module.json` while preserving `payload.blob`
- seeded legacy module `GET` still returns `204` when absent and `200` raw bytes plus `X-Modified-At` when present
- seeded legacy module remains writable through legacy `POST` after upgrade when it has no external blobs
- missing or malformed legacy `module.json` with present `payload.blob` is recovered on first access instead of failing the request
- mixed old/new-client flow where an old client first reads a legacy-only module and a new client later commits a blob-backed revision for one targeted module instance
- after that mixed flow, an old client still reads raw document bytes from the blob-backed targeted module instance
- after that mixed flow, old-client `POST` to the blob-backed targeted module instance fails with `409`
- after that mixed flow, old-client `POST` to a different device-owned or legacy-only module instance still succeeds
- legacy write rejected with `409` only for the targeted device+module instance once that instance has external blobs
- creating a resumable session reserves quota
- session `HEAD` returns correct offset and expiry data
- interrupted upload can resume from last confirmed offset
- invalid checksum syntax returns `400`
- checksum mismatch at finalize returns `422`
- completed upload does not become visible until module commit
- `GET /v1/module/{moduleId}?device-id={targetDeviceId}` still returns raw document bytes after a successful `PUT`
- commit installs new root document and blob refs atomically
- blob download and blob listing work across devices using `?device-id={targetDeviceId}`
- range `GET` and `HEAD` for blobs
- `If-Match` conflict returns `412`
- WebSocket `module_changed` continues to include both `deviceId` and `moduleId`
- WebSocket `module_changed.deviceId` matches the targeted module-owner device for cross-device writes
- aborting or expiring sessions releases quota
- deleting module/device/account removes live and staged blob data
- startup recovery counts seeded legacy root-document bytes in `usedBytes`
- startup recovery rebuilds usage and reservations correctly
- startup recovery handles `session.json` offset exceeding actual `payload.part` size
- startup recovery handles `module.json` referencing a missing blob file
- startup recovery reclaims orphaned blob files not referenced by any module
- legacy `POST` to a module with outstanding upload sessions aborts those sessions and releases reserved quota
- retried `finalize` after first success returns the same `complete` response (idempotent)
- `PATCH` that would exceed declared `sizeBytes` is rejected before writing bytes
- commit with duplicate `blobId` values in `blobRefs` returns `400`
- commit with empty `documentBase64` succeeds (zero-length root documents are allowed)
- session creation with `sizeBytes: 0` succeeds (zero-length blobs are allowed, quota reservation is zero)
- module with active upload sessions is not expired by GC
- `PUT` on an absent module with `If-None-Match: *` succeeds
- `PUT` on an existing module with `If-None-Match: *` fails with `412`
- WebSocket `module_changed` event includes both `deviceId` (target) and `sourceDeviceId` (caller)
- WebSocket self-suppression uses `sourceDeviceId`, not `deviceId`

### Concurrency And Race Condition Tests

- `DELETE` module during active `PATCH` upload — upload rejects after lifecycle flag is set
- `DELETE` device during active upload session — sessions are aborted, staged files cleaned
- `finalize` racing with `commit` for the same session — only one succeeds
- session expiry firing during an in-flight `PATCH` — PATCH rejects, partial bytes cleaned
- `GET` during the commit rename window — reader sees consistent old or new revision, never mixed
- request with both `If-Match` and `If-None-Match` present — rejected with `400`
- invalid base64 in `documentBase64` (not just empty — malformed) — rejected with `400`
- blob list on an absent module — returns empty list, not error
- repeated calls to committed/deleted/expired sessions — returns stable status codes
- account delete during in-flight blob upload — upload rejects, quota released

## API Versioning

All new endpoints stay under `/v1/`. No `/v2/` prefix is needed because:

- the new endpoints are purely additive — new paths (`/blobs/`, `/blob-sessions/`, `/account/storage`) that do not exist today
- existing `GET/POST/DELETE /v1/module/{moduleId}` keep their current wire behavior
- the only behavioral change to existing endpoints is the `409` safety guard for legacy writes to blob-backed modules, which is a backward-compatible error (old clients never encounter blob-backed modules unless a new client creates them)
- `/v1/account/storage` already serves as capability discovery — clients that understand blobs check this endpoint; clients that don't never call it
- a `/v2/` prefix would falsely signal that all `/v1/` endpoints are deprecated, which they are not

If a future change genuinely breaks existing `/v1/` wire semantics (different response shapes, removed fields), that is when `/v2/` becomes appropriate. This change does not qualify.

## Open Questions To Revisit During Implementation

- ~~exact idle and absolute TTL values for upload sessions~~ — resolved: idle 1 h, absolute 24 h (defaults in `App.Config`; exposed via `GET /v1/account/storage`).
- ~~whether to keep quota counters persisted eagerly or rebuilt from disk on each startup only~~ — resolved: rebuilt at startup in `StartupRecoveryService`; no persisted quota file.
- exact coalescing interval for `access.json` writes — still open; see "Implementation Deferrals" below.

## Implementation Deferrals

The following items from the plan were intentionally not implemented in the initial landing. None affect correctness of the core blob-storage contract.

- **Per-module lifecycle flag** for destructive-operation coordination — the per-device `Mutex` acquired during commit/delete already serialises destructive work against in-flight writes on that device. The atomic-flag-plus-drain design becomes useful if commits ever need to coexist with non-locking concurrent readers; revisit when that tension appears.
- **`access.json` coalescing interval** — access timestamps are written on every touching operation rather than on a fixed interval. At current traffic this is negligible; add coalescing if logs show access writes dominating disk I/O.
- **Legacy `DELETE` guard on blob-backed modules** — the spec describes legacy `DELETE` as "delete the whole module, including live blobs and staged uploads", and that is the implemented behaviour (no extra guard). Left as-is intentionally; documenting here so the absence is clearly a decision and not an oversight.
- **Persistent client-side upload resumption** — out of scope for the server (server already supports it via `HEAD /blob-sessions/{id}` + `PATCH` from confirmed offset). The current client does chunked-but-not-resumable uploads; when the client grows session persistence, no server change is needed.

## Decisions Already Made

- keep compatibility with old clients
- support in-place upgrade with no required offline migration
- support mixed old and new clients on the same upgraded server
- reject legacy writes with `409` if the targeted device-owned module instance already uses external blobs
- use module-scoped blobs only
- no cross-module deduplication
- use server-generated `blobId` values and server-only `storageKey` values
- allow optional checksum declaration at session creation and require checksum verification by finalize time
- persist plaintext blob IDs, storage keys, and blob sizes for quota and cleanup
- keep target-device addressing via `?device-id={targetDeviceId}` for module-scoped blob and commit operations
- use `If-None-Match: *` for new-client create-on-absent `PUT` commits
- expose a blob list endpoint backed by `module.json`
- support resumable uploads
- support full HTTP range for blob downloads
- use account-level quota
- add `If-Match` for new commits
- separate `lastAccessedAt` into `access.json` to avoid module-lock contention
- finalized blobs stay in `sessions/` until commit moves them to `blobs/` (clean filesystem invariant)
- commit order: `payload.blob` first, then `module.json` as single commit point
- synthesized ETags for legacy migration are deterministic from on-disk state
- auto-heal all startup recovery inconsistencies with structured logging
- allow zero-length blobs and root documents (server is opaque to payload semantics)
- accept PATCH without Content-Length, stream with byte counting and max chunk size
- per-module lifecycle flag for destructive operation coordination
- verify Android client `ignoreUnknownKeys` before adding `sourceDeviceId` to WebSocket events
- accounts need headroom equal to largest blob for in-place replacement (replacement-aware reservation deferred)
