# Blob Support Plan

## Status

Draft for review before implementation.

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

Legacy behavior on the new backend:

- legacy `GET` returns the current root document bytes exactly as before
- legacy `POST` is internally translated to "replace the root document and clear external blob refs" only for modules that do not currently use external blobs
- legacy `DELETE` deletes the whole module, including live blobs and staged uploads

Safety rule:

- if a module currently has external blobs, legacy `POST /v1/module/{moduleId}` returns `409 Conflict`

Reason:

- old clients cannot understand or preserve external blob references
- silently accepting the write would risk data loss

## New API Surface

### Blob Download

- `HEAD /v1/module/{moduleId}/blobs/{blobId}`
- `GET /v1/module/{moduleId}/blobs/{blobId}`

Behavior:

- serves committed live blobs only
- supports full HTTP range requests
- returns `ETag`, `Last-Modified`, `Content-Length`, `Accept-Ranges`

### Resumable Upload Sessions

- `POST /v1/module/{moduleId}/blob-sessions`
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

- `PUT /v1/module/{moduleId}`

Behavior:

- replaces the current root document for new clients
- requires `If-Match`
- atomically installs the new root document and authoritative blob ref list
- promotes completed staged blobs into live blobs if referenced
- deletes old live blobs that are no longer referenced by the committed module revision

### Blob Session Request Schema

`POST /v1/module/{moduleId}/blob-sessions`

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
- if `hashHex` is provided, the server may reuse an existing compatible active session instead of creating a new one

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

`PUT /v1/module/{moduleId}`

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

Response body:

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
        ├── blobs/
        │   └── {prefix shards}/{storageKey}/
        │       └── payload.blob
        └── sessions/
            └── {sessionId}/
                ├── payload.part
                └── session.json
```

`module.json` becomes the authoritative module metadata record.

There is no separate live `blob.json` file in the first version; live blob metadata stays in `module.json`.

It should include at least:

- module ID
- source device ID
- current `ETag`
- modified timestamp
- last accessed timestamp
- root document size
- current live blob refs `{ blobId, storageKey, sizeBytes, hashAlgorithm?, hashHex? }`

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
  "lastAccessedAt": "2026-04-11T12:34:56Z",
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
    val lastAccessedAt: Instant,
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

- `GET /v1/module/{moduleId}` returns the current `ETag`
- `PUT /v1/module/{moduleId}` must include `If-Match`
- if the module changed since the client read it, return `412 Precondition Failed`

Legacy `POST /v1/module/{moduleId}` remains last-write-wins only for legacy-compatible modules without external blobs.

## Commit Semantics

The new module commit is the only operation that changes the authoritative live blob set.

Commit flow:

1. Validate caller and target device.
2. Validate `If-Match` against current module revision.
3. Validate root document size against `maxModuleDocumentBytes`.
4. Validate that every referenced blob either already exists live or has a completed finalized staged upload.
5. Write the new root document to a temp file.
6. Promote referenced staged uploads into live blob locations.
7. Write updated `module.json` with the new `ETag`, timestamps, sizes, and live blob refs.
8. Atomically swap temp files into place.
9. Delete old live blobs no longer referenced by the new module revision.
10. Update quota counters.

This must be atomic from the perspective of readers.

## WebSocket Notifications

Keep WebSocket sync at module granularity.

Behavior:

- upload session changes do not emit sync notifications
- successful module commit emits `module_changed`
- module delete emits `module_changed` with `deleted`

This keeps sync behavior aligned with "the module revision changed" rather than low-level upload progress.

## Cleanup And Expiration

### Upload Sessions

GC removes:

- idle expired sessions
- absolutely expired sessions
- staged files for aborted sessions
- completed but never committed uploads after TTL expiry

### Live Blobs

Live blobs are removed when:

- a successful module commit no longer references them
- the module is deleted
- the device is deleted
- the account is deleted
- the module expires by retention policy

### Access Tracking

If retention is meant to be access-based, explicit metadata must be updated on reads.

The implementation should stop relying on file mtimes for retention decisions.

`module.json` should carry explicit `lastAccessedAt` values, and blob reads should update the owning module's access metadata with coalescing to avoid excessive writes.

## Concurrency And Locking

The current per-device lock is too coarse for larger transfers.

Planned locking model:

- per-module lock for root document and live blob set changes
- per-session lock for upload append operations
- account-level quota lock for reservation and usage updates

Goals:

- one large blob upload should not block unrelated module operations on the same device
- quota enforcement must remain race-free
- commit promotion and cleanup must remain atomic

## Startup Recovery

On restart:

- load current module metadata from disk
- rebuild live used-byte accounting from module metadata and live blob file sizes
- load non-expired upload sessions from `session.json`
- rebuild reserved bytes from active and complete staged sessions
- delete clearly invalid or expired staged sessions during startup recovery

The low current limits make startup rescans acceptable.

## Migration Strategy

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

- add new `PUT /v1/module/{moduleId}` commit path
- keep legacy `GET/POST/DELETE`
- return `409` for legacy writes to blob-backed modules

Phase 5:

- add integration tests for mixed legacy/new-client behavior, quota edge cases, resume behavior, and cleanup

## Test Plan

Integration tests should cover at least:

- legacy clients still reading and writing legacy-only modules
- legacy write rejected with `409` once a module has external blobs
- creating a resumable session reserves quota
- session `HEAD` returns correct offset and expiry data
- interrupted upload can resume from last confirmed offset
- invalid checksum syntax returns `400`
- checksum mismatch at finalize returns `422`
- completed upload does not become visible until module commit
- commit installs new root document and blob refs atomically
- range `GET` and `HEAD` for blobs
- `If-Match` conflict returns `412`
- aborting or expiring sessions releases quota
- deleting module/device/account removes live and staged blob data
- startup recovery rebuilds usage and reservations correctly

## Open Questions To Revisit During Implementation

- exact idle and absolute TTL values for upload sessions
- whether to keep quota counters persisted eagerly or rebuilt from disk on each startup only
- whether to coalesce module access timestamp updates on a fixed interval or only when crossing a retention threshold

## Decisions Already Made

- keep compatibility with old clients
- reject legacy writes with `409` if a module already uses external blobs
- use module-scoped blobs only
- no cross-module deduplication
- use server-generated `blobId` values and server-only `storageKey` values
- allow optional checksum declaration at session creation and require checksum verification by finalize time
- persist plaintext blob IDs, storage keys, and blob sizes for quota and cleanup
- support resumable uploads
- support full HTTP range for blob downloads
- use account-level quota
- add `If-Match` for new commits
