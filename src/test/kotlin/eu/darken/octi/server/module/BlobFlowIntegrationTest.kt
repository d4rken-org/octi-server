package eu.darken.octi.server.module

import eu.darken.octi.Credentials
import eu.darken.octi.TestRunner
import eu.darken.octi.addCredentials
import eu.darken.octi.createDevice
import eu.darken.octi.readModuleRaw
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Integration tests covering the blob-backed module flow across multi-device interactions.
 *
 * Scope:
 *  1. Happy path — owner commits a blob-backed module, peer reads document + blob.
 *  2. Writer/writer 412 — concurrent commits against the same blob-backed module.
 *  3. Owner cleanup — commit v2 drops old blob, blob list and GET reflect the removal.
 *  4. Reader/writer race — many iterations of concurrent GET + PUT assert that the
 *     streamed body matches its ETag (no mixed old/new content).
 */
class BlobFlowIntegrationTest : TestRunner() {

    private val testModuleId = "eu.darken.octi.module.test"

    @Serializable
    private data class SessionInfo(
        val blobId: String = "",
        val sessionId: String = "",
        val offsetBytes: Long = 0,
        val expiresAt: String = "",
        val state: String = "",
    )

    @Serializable
    private data class BlobListInfo(
        val moduleEtag: String = "",
        val blobs: List<BlobEntry> = emptyList(),
    )

    @Serializable
    private data class BlobEntry(
        val blobId: String = "",
        val sizeBytes: Long = 0,
        val hashAlgorithm: String? = null,
        val hashHex: String? = null,
    )

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun base64Encode(data: ByteArray): String =
        Base64.getEncoder().encodeToString(data)

    private suspend fun TestEnvironment.createSession(
        creds: Credentials,
        targetDeviceId: java.util.UUID,
        moduleId: String,
        payload: ByteArray,
    ): SessionInfo {
        val hash = sha256Hex(payload)
        val session = http.post("/v1/module/$moduleId/blob-sessions") {
            url { parameters.append("device-id", targetDeviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${payload.size}, "hashAlgorithm": "sha256", "hashHex": "$hash"}""")
        }.body<SessionInfo>()

        if (payload.isNotEmpty()) {
            http.patch("/v1/module/$moduleId/blob-sessions/${session.sessionId}") {
                addCredentials(creds)
                header("Upload-Offset", "0")
                contentType(ContentType.Application.OctetStream)
                setBody(payload)
            }.status shouldBe HttpStatusCode.NoContent
        }

        http.post("/v1/module/$moduleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status shouldBe HttpStatusCode.OK

        return session
    }

    private suspend fun TestEnvironment.commitModule(
        creds: Credentials,
        targetDeviceId: java.util.UUID,
        moduleId: String,
        documentBytes: ByteArray,
        blobIds: List<String>,
        ifMatch: String? = null,
        ifNoneMatch: String? = null,
    ): HttpResponse = http.put("/v1/module/$moduleId") {
        url { parameters.append("device-id", targetDeviceId.toString()) }
        addCredentials(creds)
        if (ifMatch != null) header("If-Match", ifMatch)
        if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch)
        contentType(ContentType.Application.Json)
        val refsJson = blobIds.joinToString(",") { """{"blobId":"$it"}""" }
        setBody("""{"documentBase64":"${base64Encode(documentBytes)}","blobRefs":[$refsJson]}""")
    }

    @Test
    fun `happy path - peer reads blob-backed module and downloads blob`() = runTest2 {
        val ownerCreds = createDevice()
        val peerCreds = createDevice(ownerCreds)

        val blobPayload = "hello-from-owner".toByteArray()
        val session = createSession(ownerCreds, ownerCreds.deviceId, testModuleId, blobPayload)

        val document = "owner-document-v1".toByteArray()
        val commit = commitModule(
            creds = ownerCreds,
            targetDeviceId = ownerCreds.deviceId,
            moduleId = testModuleId,
            documentBytes = document,
            blobIds = listOf(session.blobId),
            ifNoneMatch = "*",
        )
        commit.status shouldBe HttpStatusCode.OK
        val committedEtag = commit.headers["ETag"]!!.trim('"')
        committedEtag.shouldNotBeEmpty()

        readModuleRaw(peerCreds, testModuleId, ownerCreds.deviceId).apply {
            status shouldBe HttpStatusCode.OK
            headers["ETag"]!!.trim('"') shouldBe committedEtag
            bodyAsText() shouldBe "owner-document-v1"
        }

        val peerList = http.get("/v1/module/$testModuleId/blobs") {
            url { parameters.append("device-id", ownerCreds.deviceId.toString()) }
            addCredentials(peerCreds)
        }.body<BlobListInfo>()
        peerList.moduleEtag shouldBe committedEtag
        peerList.blobs.size shouldBe 1
        peerList.blobs[0].blobId shouldBe session.blobId
        peerList.blobs[0].sizeBytes shouldBe blobPayload.size.toLong()
        peerList.blobs[0].hashHex shouldBe sha256Hex(blobPayload)

        http.get("/v1/module/$testModuleId/blobs/${session.blobId}") {
            url { parameters.append("device-id", ownerCreds.deviceId.toString()) }
            addCredentials(peerCreds)
        }.apply {
            status shouldBe HttpStatusCode.OK
            val received = body<ByteArray>()
            received.contentEquals(blobPayload) shouldBe true
            sha256Hex(received) shouldBe sha256Hex(blobPayload)
        }
    }

    @Test
    fun `concurrent writer-writer on blob-backed module — one commits, other retries`() = runTest2 {
        val ownerCreds = createDevice()
        val peerCreds = createDevice(ownerCreds)

        val initialBlob = "initial-blob".toByteArray()
        val initialSession = createSession(ownerCreds, ownerCreds.deviceId, testModuleId, initialBlob)
        val initialCommit = commitModule(
            creds = ownerCreds,
            targetDeviceId = ownerCreds.deviceId,
            moduleId = testModuleId,
            documentBytes = "doc-v1".toByteArray(),
            blobIds = listOf(initialSession.blobId),
            ifNoneMatch = "*",
        )
        initialCommit.status shouldBe HttpStatusCode.OK
        val initialEtag = initialCommit.headers["ETag"]!!.trim('"')

        val ownerBlobPayload = "owner-payload".toByteArray()
        val ownerSession = createSession(ownerCreds, ownerCreds.deviceId, testModuleId, ownerBlobPayload)
        val peerBlobPayload = "peer-payload".toByteArray()
        val peerSession = createSession(peerCreds, ownerCreds.deviceId, testModuleId, peerBlobPayload)

        val responses = coroutineScope {
            val ownerAttempt = async {
                commitModule(
                    creds = ownerCreds,
                    targetDeviceId = ownerCreds.deviceId,
                    moduleId = testModuleId,
                    documentBytes = "doc-v2-owner".toByteArray(),
                    blobIds = listOf(ownerSession.blobId),
                    ifMatch = initialEtag,
                )
            }
            val peerAttempt = async {
                commitModule(
                    creds = peerCreds,
                    targetDeviceId = ownerCreds.deviceId,
                    moduleId = testModuleId,
                    documentBytes = "doc-v2-peer".toByteArray(),
                    blobIds = listOf(peerSession.blobId),
                    ifMatch = initialEtag,
                )
            }
            listOf(ownerAttempt, peerAttempt).awaitAll()
        }

        val successes = responses.count { it.status == HttpStatusCode.OK }
        val failures = responses.count { it.status == HttpStatusCode.PreconditionFailed }
        successes shouldBe 1
        failures shouldBe 1

        val winner = responses.first { it.status == HttpStatusCode.OK }
        val winnerEtag = winner.headers["ETag"]!!.trim('"')
        winnerEtag shouldNotBe initialEtag

        val freshRead = readModuleRaw(ownerCreds, testModuleId, ownerCreds.deviceId)
        val freshEtag = freshRead.headers["ETag"]!!.trim('"')
        freshEtag shouldBe winnerEtag

        val retryBlob = "retry-payload".toByteArray()
        val retrySession = createSession(peerCreds, ownerCreds.deviceId, testModuleId, retryBlob)
        val retry = commitModule(
            creds = peerCreds,
            targetDeviceId = ownerCreds.deviceId,
            moduleId = testModuleId,
            documentBytes = "doc-v3-peer".toByteArray(),
            blobIds = listOf(retrySession.blobId),
            ifMatch = freshEtag,
        )
        retry.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `owner drops blob in new commit — peer GET returns 404 and list no longer references it`() = runTest2 {
        val ownerCreds = createDevice()
        val peerCreds = createDevice(ownerCreds)

        val droppedBlob = "dropped".toByteArray()
        val droppedSession = createSession(ownerCreds, ownerCreds.deviceId, testModuleId, droppedBlob)

        val v1 = commitModule(
            creds = ownerCreds,
            targetDeviceId = ownerCreds.deviceId,
            moduleId = testModuleId,
            documentBytes = "doc-v1".toByteArray(),
            blobIds = listOf(droppedSession.blobId),
            ifNoneMatch = "*",
        )
        v1.status shouldBe HttpStatusCode.OK
        val v1Etag = v1.headers["ETag"]!!.trim('"')

        http.get("/v1/module/$testModuleId/blobs/${droppedSession.blobId}") {
            url { parameters.append("device-id", ownerCreds.deviceId.toString()) }
            addCredentials(peerCreds)
        }.status shouldBe HttpStatusCode.OK

        val v2 = commitModule(
            creds = ownerCreds,
            targetDeviceId = ownerCreds.deviceId,
            moduleId = testModuleId,
            documentBytes = "doc-v2".toByteArray(),
            blobIds = emptyList(),
            ifMatch = v1Etag,
        )
        v2.status shouldBe HttpStatusCode.OK
        val v2Etag = v2.headers["ETag"]!!.trim('"')

        val peerList = http.get("/v1/module/$testModuleId/blobs") {
            url { parameters.append("device-id", ownerCreds.deviceId.toString()) }
            addCredentials(peerCreds)
        }.body<BlobListInfo>()
        peerList.moduleEtag shouldBe v2Etag
        peerList.blobs shouldBe emptyList()

        http.get("/v1/module/$testModuleId/blobs/${droppedSession.blobId}") {
            url { parameters.append("device-id", ownerCreds.deviceId.toString()) }
            addCredentials(peerCreds)
        }.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `reader-writer race — streamed body always matches returned ETag`() = runTest2 {
        val creds = createDevice()

        val sizeA = 200 * 1024
        val sizeB = 180 * 1024
        val fillerA = ByteArray(sizeA) { 0x41 } // 'A'
        val fillerB = ByteArray(sizeB) { 0x42 } // 'B'

        val initial = commitModule(
            creds = creds,
            targetDeviceId = creds.deviceId,
            moduleId = testModuleId,
            documentBytes = fillerA,
            blobIds = emptyList(),
            ifNoneMatch = "*",
        )
        initial.status shouldBe HttpStatusCode.OK

        // Run many read/write pairs in parallel. Each reader drains the body via the raw
        // channel to avoid client-side buffering that would mask a mid-stream replacement.
        val iterations = 20
        val results = coroutineScope {
            (0 until iterations).map { i ->
                val writerPayload = if (i % 2 == 0) fillerB else fillerA
                val readerJob = async {
                    val resp = readModuleRaw(creds, testModuleId, creds.deviceId)
                    resp.status shouldBe HttpStatusCode.OK
                    val etag = resp.headers["ETag"]!!.trim('"')
                    val bytes = resp.bodyAsChannel().readRemaining().readByteArray()
                    etag to bytes
                }
                val writerJob = async {
                    val currentEtag = readModuleRaw(creds, testModuleId, creds.deviceId)
                        .headers["ETag"]!!.trim('"')
                    commitModule(
                        creds = creds,
                        targetDeviceId = creds.deviceId,
                        moduleId = testModuleId,
                        documentBytes = writerPayload,
                        blobIds = emptyList(),
                        ifMatch = currentEtag,
                    )
                }
                readerJob to writerJob
            }.map { (readerJob, writerJob) ->
                val (etag, bytes) = readerJob.await()
                Triple(etag, bytes, writerJob.await())
            }
        }

        val etagToSize = mutableMapOf<String, Int>()
        for ((etag, bytes, writerResp) in results) {
            bytes.isNotEmpty() shouldBe true
            val first = bytes[0]
            // Body must be internally consistent — all one filler byte, no mixed old/new.
            val allSame = bytes.all { it == first }
            allSame shouldBe true
            // Across all readers, a given ETag must always map to the same content size.
            val existingSize = etagToSize[etag]
            if (existingSize != null) {
                existingSize shouldBe bytes.size
            } else {
                etagToSize[etag] = bytes.size
            }
            // Writer either succeeded with ETag match or saw a precondition-failed (lost race).
            (writerResp.status == HttpStatusCode.OK || writerResp.status == HttpStatusCode.PreconditionFailed) shouldBe true
        }
    }
}
