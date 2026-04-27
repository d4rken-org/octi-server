package eu.darken.octi.server.module

import eu.darken.octi.server.App
import eu.darken.octi.server.account.AccountStorageTracker
import eu.darken.octi.server.common.DiskSpaceProbe
import eu.darken.octi.server.common.OctiResponseHeaders
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.debug.logging.shortId
import eu.darken.octi.server.common.parseStrongEtag
import eu.darken.octi.server.device.DeviceRepo
import eu.darken.octi.server.ws.SyncNotifier
import io.ktor.http.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlobRoute @Inject constructor(
    private val config: App.Config,
    private val deviceRepo: DeviceRepo,
    private val moduleRepo: ModuleRepo,
    private val sessionRepo: UploadSessionRepo,
    private val storageTracker: AccountStorageTracker,
    private val lifecycleService: ModuleLifecycleService,
    private val syncNotifier: SyncNotifier,
    private val diskSpaceProbe: DiskSpaceProbe,
) {

    private sealed interface CreateSessionOutcome {
        data class Created(val session: UploadSessionMeta) : CreateSessionOutcome
        data object QuotaExceeded : CreateSessionOutcome
        data object DiskSpaceLow : CreateSessionOutcome
        data object ModuleLimitExceeded : CreateSessionOutcome
        data class SessionLimitExceeded(val limit: Int) : CreateSessionOutcome
        data class Failed(val error: Exception) : CreateSessionOutcome
    }

    // region DTOs
    @Serializable
    data class BlobListResponse(
        val moduleEtag: String,
        val blobs: List<BlobInfo>,
    )

    @Serializable
    data class BlobInfo(
        val blobId: String,
        val sizeBytes: Long,
        val hashAlgorithm: String? = null,
        val hashHex: String? = null,
    )

    @Serializable
    data class CreateSessionRequest(
        val sizeBytes: Long,
        val hashAlgorithm: String? = null,
        val hashHex: String? = null,
    )

    @Serializable
    data class SessionResponse(
        val blobId: String,
        val sessionId: String,
        val offsetBytes: Long,
        val expiresAt: String,
        val state: String,
    )

    @Serializable
    data class FinalizeRequest(
        val hashAlgorithm: String? = null,
        val hashHex: String? = null,
    )

    @Serializable
    data class FinalizeResponse(
        val blobId: String,
        val sessionId: String,
        val sizeBytes: Long,
        val state: String,
    )

    @Serializable
    data class DeleteBlobResponse(
        val etag: String,
    )
    // endregion

    fun setup(rootRoute: Routing) {
        rootRoute.route("/v1/module/{moduleId}") {
            get("/blobs") { listBlobs() }
            get("/blobs/{blobId}") { downloadBlob() }
            delete("/blobs/{blobId}") { deleteBlob() }

            post("/blob-sessions") { createSession() }
            // Plan §"Resumable Upload Sessions" specifies HEAD /blob-sessions/{id}.
            // Registered as GET so Ktor's AutoHeadResponse plugin (installed in Server.kt)
            // can synthesize HEAD from the GET handler — installing an explicit `head { }`
            // route is shadowed by AutoHeadResponse's HEAD→GET method swap. Both verbs
            // therefore reach this handler; clients should use HEAD per the contract.
            get("/blob-sessions/{sessionId}") { sessionStatus() }

            // PATCH gets a larger body limit for blob chunks
            route("/blob-sessions/{sessionId}") {
                install(RequestBodyLimit) { bodyLimit { config.maxBlobPatchBytes } }
                patch { appendToSession() }
            }

            post("/blob-sessions/{sessionId}/finalize") { finalizeSession() }
            delete("/blob-sessions/{sessionId}") { abortSession() }
        }
    }

    // region Helpers
    // endregion

    // region Blob List & Download
    private suspend fun RoutingContext.listBlobs() {
        val ctx = resolveModuleContext(TAG, deviceRepo) ?: return
        val moduleId = ctx.moduleId
        val caller = ctx.caller
        val target = ctx.target

        val meta = moduleRepo.loadMetaSafe(target, moduleId)
        if (meta == null) {
            call.respond(BlobListResponse(moduleEtag = "", blobs = emptyList()))
            return
        }

        moduleRepo.touchAccess(target, moduleId)
        call.respond(
            BlobListResponse(
                moduleEtag = meta.etag,
                blobs = meta.blobRefs.map { ref ->
                    BlobInfo(blobId = ref.blobId, sizeBytes = ref.sizeBytes, hashAlgorithm = ref.hashAlgorithm, hashHex = ref.hashHex)
                },
            )
        )
    }

    private suspend fun RoutingContext.downloadBlob() {
        val ctx = resolveModuleContext(TAG, deviceRepo) ?: return
        val moduleId = ctx.moduleId
        val target = ctx.target
        val blobId = call.parameters["blobId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing blobId")
            return
        }

        val handle = moduleRepo.openBlobHandle(target, moduleId, blobId)
        if (handle == null) {
            call.respond(HttpStatusCode.NotFound, "Blob not found")
            return
        }

        try {
            moduleRepo.touchAccess(target, moduleId)

            val etag = strongEtagFor(handle, blobId)
            // Truncate to seconds so the wire-format date round-trips equality with itself
            // when the client echoes it back via If-Range / If-Modified-Since.
            val lastModified = handle.modifiedAt.truncatedTo(ChronoUnit.SECONDS)
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            call.response.header(HttpHeaders.ETag, "\"$etag\"")
            call.response.header(HttpHeaders.LastModified, RFC1123_FORMATTER.format(lastModified.atOffset(ZoneOffset.UTC)))

            // If-None-Match short-circuits the body. RFC 7232 §3.2: `*` matches if the
            // resource exists (we already opened the handle, so it does); a strong ETag
            // matches by string equality.
            val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]?.let { parseStrongEtag(it) }
            if (ifNoneMatch == "*" || (ifNoneMatch != null && ifNoneMatch == etag)) {
                call.respond(HttpStatusCode.NotModified)
                return
            }

            // RFC 7233 §3.2: If-Range may be either an entity-tag or an HTTP-date.
            // If it doesn't match the current resource, fall back to a full 200 (don't honor
            // the Range). Malformed If-Range is treated the same way — never reject with 400.
            val ifRangeRaw = call.request.headers[HttpHeaders.IfRange]
            val ifRangeMatches = when {
                ifRangeRaw == null -> true
                else -> {
                    val asEtag = parseStrongEtag(ifRangeRaw)
                    if (asEtag != null && asEtag != "*") {
                        asEtag == etag
                    } else {
                        val asDate = runCatching { RFC1123_FORMATTER.parse(ifRangeRaw, java.time.OffsetDateTime::from).toInstant() }.getOrNull()
                        asDate != null && asDate == lastModified
                    }
                }
            }
            val rangeRequest = call.request.headers[HttpHeaders.Range]?.takeIf { ifRangeMatches }
            val range = if (rangeRequest != null) parseSingleByteRange(rangeRequest, handle.sizeBytes) else null
            when (range) {
                is RangeParse.Unsatisfiable -> {
                    // Spec-compliant: include Content-Range with total so clients can recover.
                    call.response.header(HttpHeaders.ContentRange, "bytes */${handle.sizeBytes}")
                    call.respond(HttpStatusCode.RequestedRangeNotSatisfiable, "Range not satisfiable")
                    return
                }
                is RangeParse.Single -> {
                    val length = range.endInclusive - range.start + 1
                    call.response.header(
                        HttpHeaders.ContentRange,
                        "bytes ${range.start}-${range.endInclusive}/${handle.sizeBytes}",
                    )
                    call.response.status(HttpStatusCode.PartialContent)
                    call.respondOutputStream(
                        contentType = ContentType.Application.OctetStream,
                        contentLength = length,
                    ) {
                        handle.stream.use { input ->
                            // skip() is allowed to short-skip; loop until the full offset is consumed.
                            var remaining = range.start
                            while (remaining > 0) {
                                val skipped = input.skip(remaining)
                                if (skipped <= 0) error("InputStream skip returned $skipped at offset $remaining")
                                remaining -= skipped
                            }
                            copyExact(input, this, length)
                        }
                    }
                }
                null -> {
                    // No Range header (or multi-range, which we explicitly fall back to a 200 full body for
                    // per RFC 7233 §4.3 — we don't support multipart/byteranges).
                    call.respondOutputStream(
                        contentType = ContentType.Application.OctetStream,
                        contentLength = handle.sizeBytes,
                    ) {
                        handle.stream.use { it.copyTo(this) }
                    }
                }
            }
        } finally {
            try {
                handle.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun strongEtagFor(handle: BlobHandle, blobId: String): String {
        // Prefer the content hash (already computed on finalize) for cross-device cache consistency;
        // fall back to "<blobId>-<size>" for legacy blobs that don't carry a hash.
        val algo = handle.hashAlgorithm
        val hex = handle.hashHex
        return if (algo != null && hex != null) "$algo:$hex" else "$blobId-${handle.sizeBytes}"
    }

    private sealed interface RangeParse {
        data class Single(val start: Long, val endInclusive: Long) : RangeParse
        data object Unsatisfiable : RangeParse
    }

    /**
     * Parses a single-range `Range:` header against [size]. Returns:
     * - [RangeParse.Single] for a satisfiable single byte-range (handles `bytes=N-`, `bytes=N-M`,
     *   and suffix `bytes=-N`).
     * - [RangeParse.Unsatisfiable] when the spec indicates 416 (range entirely past EOF, or
     *   `bytes=0-0` against a zero-byte blob).
     * - `null` for syntactically invalid OR multi-range requests so the caller can fall back to a
     *   full-body 200 response (RFC 7233 §4.3 permits this and we don't support
     *   multipart/byteranges).
     */
    private fun parseSingleByteRange(header: String, size: Long): RangeParse? {
        val trimmed = header.trim()
        if (!trimmed.startsWith("bytes=", ignoreCase = true)) return null
        val spec = trimmed.substring("bytes=".length).trim()
        // Multi-range falls back to 200 full body. We don't reject it as 416.
        if (spec.contains(',')) return null

        val dashIdx = spec.indexOf('-')
        if (dashIdx < 0) return null
        val startStr = spec.substring(0, dashIdx).trim()
        val endStr = spec.substring(dashIdx + 1).trim()

        return when {
            // Suffix range: "bytes=-N" → last N bytes. N == 0 against any size is unsatisfiable.
            startStr.isEmpty() -> {
                val suffix = endStr.toLongOrNull() ?: return null
                if (suffix < 0) return null
                if (suffix == 0L) RangeParse.Unsatisfiable
                else if (size == 0L) RangeParse.Unsatisfiable
                else {
                    val effective = suffix.coerceAtMost(size)
                    RangeParse.Single(start = size - effective, endInclusive = size - 1)
                }
            }
            else -> {
                val start = startStr.toLongOrNull() ?: return null
                if (start < 0) return null
                if (size == 0L) return RangeParse.Unsatisfiable
                if (start >= size) return RangeParse.Unsatisfiable
                val end = if (endStr.isEmpty()) {
                    size - 1
                } else {
                    val parsed = endStr.toLongOrNull() ?: return null
                    if (parsed < start) return null
                    parsed.coerceAtMost(size - 1)
                }
                RangeParse.Single(start = start, endInclusive = end)
            }
        }
    }

    private fun copyExact(source: java.io.InputStream, sink: java.io.OutputStream, length: Long) {
        val buffer = ByteArray(8 * 1024)
        var remaining = length
        while (remaining > 0) {
            val cap = if (remaining > buffer.size) buffer.size else remaining.toInt()
            val read = source.read(buffer, 0, cap)
            if (read < 0) error("Unexpected EOF: $remaining bytes remaining")
            sink.write(buffer, 0, read)
            remaining -= read
        }
    }

    private suspend fun RoutingContext.deleteBlob() {
        val ctx = resolveModuleContext(TAG, deviceRepo) ?: return
        val moduleId = ctx.moduleId
        val caller = ctx.caller
        val target = ctx.target
        val blobId = call.parameters["blobId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing blobId")
            return
        }

        val ifMatchRaw = call.request.headers["If-Match"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "If-Match header is required for blob delete")
            return
        }
        val ifMatch = parseStrongEtag(ifMatchRaw) ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed If-Match header")
            return
        }
        // RFC 7232 §3.1: `If-Match: *` means "if the resource currently exists".
        // For blob delete this is the standard "delete if exists" idiom — pass through;
        // moduleRepo.removeBlobRefUnlocked treats `*` as match-any-etag.

        when (val result = lifecycleService.deleteBlob(caller, target, moduleId, blobId, ifMatch)) {
            is ModuleLifecycleService.DeleteBlobResult.ModuleNotFound ->
                call.respond(HttpStatusCode.NotFound, "Module not found")
            is ModuleLifecycleService.DeleteBlobResult.BlobNotFound ->
                call.respond(HttpStatusCode.NotFound, "Blob not found")
            is ModuleLifecycleService.DeleteBlobResult.EtagMismatch -> {
                call.response.header("ETag", "\"${result.currentEtag}\"")
                call.respond(HttpStatusCode.PreconditionFailed, "ETag mismatch")
            }
            is ModuleLifecycleService.DeleteBlobResult.Success -> {
                call.response.header("ETag", "\"${result.newEtag}\"")
                call.respond(DeleteBlobResponse(etag = result.newEtag))
                log(TAG) { "deleteBlob(${caller.id.shortId()}): $moduleId/$blobId deleted" }

                syncNotifier.enqueueModuleChanged(
                    accountId = caller.accountId,
                    sourceDeviceId = caller.id,
                    targetDeviceId = target.id,
                    moduleId = moduleId,
                    action = "updated",
                )
            }
        }
    }
    // endregion

    // region Upload Sessions
    private suspend fun RoutingContext.createSession() {
        val ctx = resolveModuleContext(TAG, deviceRepo) ?: return
        val moduleId = ctx.moduleId
        val caller = ctx.caller
        val target = ctx.target

        val request = call.receive<CreateSessionRequest>()

        if (request.sizeBytes < 0) {
            call.respond(HttpStatusCode.BadRequest, "sizeBytes must be non-negative")
            return
        }
        if (request.sizeBytes > config.maxBlobBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Blob size exceeds maximum (${config.maxBlobBytes})")
            return
        }
        if (request.hashAlgorithm != null && request.hashAlgorithm != "sha256") {
            call.respond(HttpStatusCode.BadRequest, "Only sha256 hash algorithm is supported")
            return
        }
        if (request.hashHex != null && !SHA256_HEX_REGEX.matches(request.hashHex)) {
            call.respond(HttpStatusCode.BadRequest, "hashHex must be exactly 64 lowercase hex characters")
            return
        }
        // hashHex without hashAlgorithm produces a session whose persisted shape is
        // rejected by StartupRecoveryService.hasValidCompleteHash on next restart —
        // the valid blob would be deleted. Either both fields are present, or neither.
        if (request.hashHex != null && request.hashAlgorithm == null) {
            call.respond(HttpStatusCode.BadRequest, "hashAlgorithm is required when hashHex is provided")
            return
        }

        // Module count cap + quota reservation + session creation must all happen under
        // target.sync so two concurrent requests can't both pass the count check and end up
        // creating distinct module dirs that together exceed the cap.
        val outcome = target.sync.withLock {
            if (!diskSpaceProbe.hasHeadroom(request.sizeBytes)) {
                return@withLock CreateSessionOutcome.DiskSpaceLow
            }
            if (!moduleRepo.moduleDirExists(target, moduleId) &&
                moduleRepo.countModuleDirs(target) >= config.maxModulesPerDevice) {
                return@withLock CreateSessionOutcome.ModuleLimitExceeded
            }
            if (request.sizeBytes > 0 && !storageTracker.tryReserve(caller.accountId, request.sizeBytes)) {
                return@withLock CreateSessionOutcome.QuotaExceeded
            }
            try {
                CreateSessionOutcome.Created(
                    sessionRepo.createSession(
                        accountId = caller.accountId,
                        deviceId = target.id,
                        moduleId = moduleId,
                        expectedSizeBytes = request.sizeBytes,
                        hashAlgorithm = request.hashAlgorithm,
                        hashHex = request.hashHex,
                    )
                )
            } catch (e: SessionLimitExceededException) {
                if (request.sizeBytes > 0) storageTracker.releaseReservation(caller.accountId, request.sizeBytes)
                CreateSessionOutcome.SessionLimitExceeded(e.limit)
            } catch (e: Exception) {
                if (request.sizeBytes > 0) storageTracker.releaseReservation(caller.accountId, request.sizeBytes)
                CreateSessionOutcome.Failed(e)
            }
        }

        when (outcome) {
            is CreateSessionOutcome.QuotaExceeded -> {
                call.response.header(OctiResponseHeaders.REASON, OctiResponseHeaders.ACCOUNT_QUOTA_EXCEEDED)
                call.respond(HttpStatusCode.InsufficientStorage, "Account storage quota exceeded")
                return
            }
            is CreateSessionOutcome.DiskSpaceLow -> {
                call.response.header(OctiResponseHeaders.REASON, OctiResponseHeaders.SERVER_DISK_LOW)
                call.respond(HttpStatusCode.InsufficientStorage, "Server is low on disk space")
                return
            }
            is CreateSessionOutcome.ModuleLimitExceeded -> {
                call.respond(HttpStatusCode.Conflict, "Module count limit reached (max ${config.maxModulesPerDevice} per device)")
                return
            }
            is CreateSessionOutcome.SessionLimitExceeded -> {
                call.respond(HttpStatusCode.Conflict, "Active session limit exceeded (max ${outcome.limit})")
                return
            }
            is CreateSessionOutcome.Failed -> throw outcome.error
            is CreateSessionOutcome.Created -> {
                val session = outcome.session
                // Plan §"Module Expiration Protection": session creation counts as module
                // activity and updates lastAccessedAt so module GC won't reap a fresh
                // upload mid-flight.
                moduleRepo.touchAccess(target, moduleId)
                call.respond(
                    HttpStatusCode.Created,
                    SessionResponse(
                        blobId = session.blobId,
                        sessionId = session.sessionId,
                        offsetBytes = 0,
                        expiresAt = session.expiresAt.toString(),
                        state = session.state.name.lowercase(),
                    )
                )
                log(TAG) { "createSession(${caller.id.shortId()}): session=${session.sessionId}, blob=${session.blobId}, size=${request.sizeBytes}" }
            }
        }
    }

    private suspend fun RoutingContext.sessionStatus() {
        val ctx = resolveModuleContext(TAG, deviceRepo) ?: return
        val moduleId = ctx.moduleId
        val caller = ctx.caller
        val target = ctx.target
        val sessionId = call.parameters["sessionId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing sessionId")
            return
        }

        val session = sessionRepo.getSession(sessionId, caller.accountId, target.id, moduleId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return
        }

        call.response.header("Upload-Offset", session.offsetBytes.toString())
        call.response.header("Upload-Length", session.expectedSizeBytes.toString())
        call.response.header("Upload-Expires", session.expiresAt.toString())
        call.response.header("Upload-State", session.state.name.lowercase())
        call.response.header("X-Blob-ID", session.blobId)
        call.respond(HttpStatusCode.OK)
    }

    private suspend fun RoutingContext.appendToSession() {
        val ctx = resolveModuleContext(TAG, deviceRepo) ?: return
        val moduleId = ctx.moduleId
        val caller = ctx.caller
        val target = ctx.target
        val sessionId = call.parameters["sessionId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing sessionId")
            return
        }

        val requestOffset = call.request.headers["Upload-Offset"]?.toLongOrNull()
        if (requestOffset == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid Upload-Offset header")
            return
        }
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength == null || contentLength < 0) {
            call.respond(HttpStatusCode.LengthRequired, "Content-Length header is required")
            return
        }
        if (contentLength > config.maxBlobPatchBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Chunk exceeds maximum patch size")
            return
        }

        // Existence/state/offset checks before the disk gate so a stale or wrong-offset
        // request routes to its proper 4xx instead of getting masked by 507 when disk is low.
        val session = sessionRepo.getSession(sessionId, caller.accountId, target.id, moduleId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return
        }
        if (session.state != UploadSessionMeta.State.ACTIVE) {
            call.respond(HttpStatusCode.Conflict, "Session is not active (state: ${session.state.name.lowercase()})")
            return
        }
        if (requestOffset != session.offsetBytes) {
            call.respond(HttpStatusCode.Conflict, "Upload offset mismatch: expected ${session.offsetBytes}")
            return
        }
        // Size against the server-side upper bound; client Content-Length isn't trusted
        // because it could only shrink the gate, weakening disk protection.
        val remainingBytes = (session.expectedSizeBytes - session.offsetBytes).coerceAtLeast(0L)
        if (contentLength > remainingBytes) {
            call.respond(HttpStatusCode.Conflict, "Upload would exceed declared size")
            return
        }
        val incomingUpperBound = minOf(config.maxBlobPatchBytes, remainingBytes)
        if (!diskSpaceProbe.hasHeadroom(incomingUpperBound)) {
            call.response.header(OctiResponseHeaders.REASON, OctiResponseHeaders.SERVER_DISK_LOW)
            call.respond(HttpStatusCode.InsufficientStorage, "Server is low on disk space")
            return
        }

        val channel = call.receiveChannel()
        val result = sessionRepo.appendToSession(
            sessionId = sessionId,
            accountId = caller.accountId,
            deviceId = target.id,
            moduleId = moduleId,
            requestOffset = requestOffset,
            channel = channel,
            // Tighter of the two bounds — the chunk-size cap and the remaining declared
            // bytes for the session. Defense-in-depth alongside the existing `> remaining`
            // check inside the write loop.
            maxChunkBytes = incomingUpperBound,
        )

        when (result) {
            is UploadSessionRepo.AppendResult.Success -> {
                // Plan §"Module Expiration Protection": PATCH counts as module activity.
                moduleRepo.touchAccess(target, moduleId)
                call.response.header("Upload-Offset", result.newOffset.toString())
                call.respond(HttpStatusCode.NoContent)
            }
            is UploadSessionRepo.AppendResult.OffsetMismatch -> {
                call.respond(HttpStatusCode.Conflict, "Upload offset mismatch: expected ${result.expectedOffset}")
            }
            is UploadSessionRepo.AppendResult.SizeExceeded -> {
                call.respond(HttpStatusCode.Conflict, "Upload would exceed declared size")
            }
            is UploadSessionRepo.AppendResult.ChunkTooLarge -> {
                call.respond(HttpStatusCode.PayloadTooLarge, "Chunk exceeds maximum patch size")
            }
            is UploadSessionRepo.AppendResult.SessionNotFound -> {
                call.respond(HttpStatusCode.NotFound, "Session not found")
            }
            is UploadSessionRepo.AppendResult.SessionNotActive -> {
                call.respond(HttpStatusCode.Conflict, "Session is not active (state: ${result.state})")
            }
        }
    }

    private suspend fun RoutingContext.finalizeSession() {
        val ctx = resolveModuleContext(TAG, deviceRepo) ?: return
        val moduleId = ctx.moduleId
        val caller = ctx.caller
        val target = ctx.target
        val sessionId = call.parameters["sessionId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing sessionId")
            return
        }

        val request = call.receive<FinalizeRequest>()

        if (request.hashAlgorithm != null && request.hashAlgorithm != "sha256") {
            call.respond(HttpStatusCode.BadRequest, "Only sha256 hash algorithm is supported")
            return
        }
        if (request.hashHex != null && !SHA256_HEX_REGEX.matches(request.hashHex)) {
            call.respond(HttpStatusCode.BadRequest, "hashHex must be exactly 64 lowercase hex characters")
            return
        }
        // hashHex without hashAlgorithm produces a session whose persisted shape is
        // rejected by StartupRecoveryService.hasValidCompleteHash on next restart —
        // the valid blob would be deleted. Either both fields are present, or neither.
        if (request.hashHex != null && request.hashAlgorithm == null) {
            call.respond(HttpStatusCode.BadRequest, "hashAlgorithm is required when hashHex is provided")
            return
        }

        val result = sessionRepo.finalizeSession(
            sessionId = sessionId,
            accountId = caller.accountId,
            deviceId = target.id,
            moduleId = moduleId,
            hashAlgorithm = request.hashAlgorithm,
            hashHex = request.hashHex,
        )

        when (result) {
            is UploadSessionRepo.FinalizeResult.Success -> {
                // Plan §"Module Expiration Protection": finalize counts as module activity.
                moduleRepo.touchAccess(target, moduleId)
                call.respond(FinalizeResponse(blobId = result.blobId, sessionId = sessionId, sizeBytes = result.sizeBytes, state = "complete"))
            }
            is UploadSessionRepo.FinalizeResult.AlreadyComplete -> {
                moduleRepo.touchAccess(target, moduleId)
                call.respond(FinalizeResponse(blobId = result.blobId, sessionId = sessionId, sizeBytes = result.sizeBytes, state = "complete"))
            }
            is UploadSessionRepo.FinalizeResult.SessionNotFound -> {
                call.respond(HttpStatusCode.NotFound, "Session not found")
            }
            is UploadSessionRepo.FinalizeResult.NotFullyUploaded -> {
                call.respond(HttpStatusCode.Conflict, "Upload not complete: ${result.currentOffset}/${result.expectedSize}")
            }
            is UploadSessionRepo.FinalizeResult.ChecksumMismatch -> {
                call.respond(HttpStatusCode.UnprocessableEntity, "Checksum mismatch")
            }
            is UploadSessionRepo.FinalizeResult.ChecksumConflict -> {
                call.respond(HttpStatusCode.BadRequest, "Checksum conflicts with value provided at session creation")
            }
            is UploadSessionRepo.FinalizeResult.MissingChecksum -> {
                call.respond(HttpStatusCode.BadRequest, "Checksum required at finalize (none provided at creation or now)")
            }
        }
    }

    private suspend fun RoutingContext.abortSession() {
        val ctx = resolveModuleContext(TAG, deviceRepo) ?: return
        val moduleId = ctx.moduleId
        val caller = ctx.caller
        val target = ctx.target
        val sessionId = call.parameters["sessionId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing sessionId")
            return
        }

        val session = sessionRepo.abortSession(sessionId, caller.accountId, target.id, moduleId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return
        }

        call.respond(HttpStatusCode.OK)
        log(TAG) { "abortSession(${caller.id.shortId()}): session=$sessionId aborted" }
    }
    // endregion

    companion object {
        private val SHA256_HEX_REGEX = "^[0-9a-f]{64}$".toRegex()
        private val RFC1123_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).withZone(ZoneOffset.UTC)
        private val TAG = logTag("Blob", "Route")
    }
}
