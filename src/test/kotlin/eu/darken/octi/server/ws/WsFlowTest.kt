package eu.darken.octi.server.ws

import eu.darken.octi.*
import eu.darken.octi.server.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.octi.server.common.debug.logging.log
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.*
import kotlin.concurrent.thread
import kotlin.io.path.deleteRecursively

class WsFlowTest : TestRunner() {

    /** Like runTest2 but uses runBlocking — WebSocket tests need real time, not virtual time */
    private fun runWsTest(test: suspend TestEnvironment.() -> Unit) {
        val appConfig = baseConfig.copy(port = 16024)
        Files.createDirectories(appConfig.dataPath)
        val component = eu.darken.octi.server.App.createComponent(appConfig)
        val app = component.application()
        thread { app.launch() }
        while (!app.isRunning()) Thread.sleep(100)

        val client = HttpClient(CIO) {
            defaultRequest {
                url {
                    protocol = URLProtocol.HTTP
                    host = "127.0.0.1"
                    port = appConfig.port
                }
            }
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }

        val env = TestEnvironment(appConfig, app, component, client)
        try {
            runBlocking { test(env) }
        } finally {
            client.close()
            app.shutdown()
            log(VERBOSE) { "Cleaning up stored data" }
            appConfig.dataPath.deleteRecursively()
        }
    }

    private fun createWsClient(): HttpClient = HttpClient(CIO) {
        install(WebSockets)
    }

    private fun wsUrl(): String = "ws://127.0.0.1:16024/v1/ws"

    @Test
    fun `connect with valid credentials`() = runWsTest {
        val creds = createDevice()
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds) }) {
            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `connect without credentials fails`() = runWsTest {
        val wsClient = createWsClient()

        var closedWithPolicy = false
        wsClient.webSocket(urlString = wsUrl()) {
            val reason = closeReason.await()
            if (reason?.code == CloseReason.Codes.VIOLATED_POLICY.code) closedWithPolicy = true
        }
        closedWithPolicy shouldBe true
        wsClient.close()
    }

    @Test
    fun `connect with invalid credentials fails`() = runWsTest {
        createDevice()
        val wsClient = createWsClient()

        var closedWithPolicy = false
        wsClient.webSocket(
            urlString = wsUrl(),
            request = {
                addDeviceId(UUID.randomUUID())
                addAuth(Auth("fake", "fake"))
            }
        ) {
            val reason = closeReason.await()
            if (reason?.code == CloseReason.Codes.VIOLATED_POLICY.code) closedWithPolicy = true
        }
        closedWithPolicy shouldBe true
        wsClient.close()
    }

    @Test
    fun `receive notification when peer writes module`() = runWsTest {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds2) }) {
            Thread.sleep(500)

            writeModule(creds1, "eu.darken.octi.module.power", data = "battery_data")

            // Wait for debounce (500ms) + dispatch
            Thread.sleep(2000)
            val result = incoming.tryReceive()
            val frame = result.getOrNull()
            if (frame == null) {
                val err = result.exceptionOrNull()
                throw AssertionError("No frame received. Channel closed=${result.isClosed}, error=$err")
            }
            val text = (frame as Frame.Text).readText()
            text shouldContain "module_changed"
            text shouldContain "eu.darken.octi.module.power"

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `receive notification when peer deletes blob`() = runWsTest {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        val moduleId = "eu.darken.octi.module.blobdel"

        // Create module, upload + commit a blob, all before the WS connects so earlier debounced events fire.
        val initEtag = http.put("/v1/module/$moduleId") {
            url { parameters.append("device-id", creds1.deviceId.toString()) }
            addCredentials(creds1)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${Base64.getEncoder().encodeToString("root".toByteArray())}", "blobRefs": []}""")
        }.headers["ETag"]!!.trim('"')

        val blobData = "peer-delete-me".toByteArray()
        val blobHash = blobData.sha256Hex()
        val session = http.post("/v1/module/$moduleId/blob-sessions") {
            url { parameters.append("device-id", creds1.deviceId.toString()) }
            addCredentials(creds1)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${blobData.size}, "hashAlgorithm": "sha256", "hashHex": "$blobHash"}""")
        }.body<SessionInfoStub>()
        http.patch("/v1/module/$moduleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds1.deviceId.toString()) }
            addCredentials(creds1)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(blobData)
        }
        http.post("/v1/module/$moduleId/blob-sessions/${session.sessionId}/finalize") {
            url { parameters.append("device-id", creds1.deviceId.toString()) }
            addCredentials(creds1)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val commitEtag = http.put("/v1/module/$moduleId") {
            url { parameters.append("device-id", creds1.deviceId.toString()) }
            addCredentials(creds1)
            header("If-Match", initEtag)
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${Base64.getEncoder().encodeToString("root2".toByteArray())}", "blobRefs": [{"blobId": "${session.blobId}"}]}""")
        }.headers["ETag"]!!.trim('"')

        Thread.sleep(1000) // let earlier debounced events fire before WS connects

        val wsClient = createWsClient()
        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds2) }) {
            Thread.sleep(500)

            http.delete("/v1/module/$moduleId/blobs/${session.blobId}") {
                url { parameters.append("device-id", creds1.deviceId.toString()) }
                addCredentials(creds1)
                header("If-Match", commitEtag)
            }.status shouldBe HttpStatusCode.OK

            Thread.sleep(2000)
            val frame = incoming.tryReceive().getOrNull()
            frame shouldNotBe null
            val text = (frame as Frame.Text).readText()
            text shouldContain "module_changed"
            text shouldContain moduleId
            text shouldContain "updated"
            text shouldContain creds1.deviceId.toString() // sourceDeviceId = caller

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @kotlinx.serialization.Serializable
    private data class SessionInfoStub(
        val blobId: String = "",
        val sessionId: String = "",
    )

    @Test
    fun `receive notification when peer deletes module`() = runWsTest {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        writeModule(creds1, "eu.darken.octi.module.power", data = "data")
        Thread.sleep(1000) // Let debounce from write fire before WS connects

        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds2) }) {
            Thread.sleep(500)
            deleteModuleRaw(creds1, "eu.darken.octi.module.power")

            Thread.sleep(2000)
            val frame = incoming.tryReceive().getOrNull()
            frame shouldNotBe null
            val text = (frame as Frame.Text).readText()
            text shouldContain "deleted"

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `burst writes produce single batched notification`() = runWsTest {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds2) }) {
            Thread.sleep(500)

            writeModule(creds1, "eu.darken.octi.module.power", data = "d1")
            writeModule(creds1, "eu.darken.octi.module.meta", data = "d2")
            writeModule(creds1, "eu.darken.octi.module.wifi", data = "d3")

            Thread.sleep(2000)
            val frame = incoming.tryReceive().getOrNull()
            frame shouldNotBe null
            val payload = Json.decodeFromString<SyncNotifier.EventPayload>(
                (frame as Frame.Text).readText()
            )
            payload.events.size shouldBe 3

            val extra = withTimeoutOrNull(1500) { incoming.receive() }
            extra shouldBe null

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `writer does not receive own notifications`() = runWsTest {
        val creds1 = createDevice()
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds1) }) {
            Thread.sleep(500)
            writeModule(creds1, "eu.darken.octi.module.power", data = "data")

            val frame = withTimeoutOrNull(2000) { incoming.receive() }
            frame shouldBe null

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `different accounts do not receive each others notifications`() = runWsTest {
        val creds1 = createDevice()
        val creds2 = createDevice() // Different account
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds2) }) {
            Thread.sleep(500)
            writeModule(creds1, "eu.darken.octi.module.power", data = "data")

            val frame = withTimeoutOrNull(2000) { incoming.receive() }
            frame shouldBe null

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `frame rate limit closes connection when exceeded`() = runWsTest {
        val creds = createDevice()
        val wsClient = createWsClient()

        var closedWithPolicy = false
        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds) }) {
            Thread.sleep(500)

            // Send more frames than the limit allows within the window
            repeat(WsRoute.MAX_FRAMES_PER_WINDOW + 10) {
                send(Frame.Text("spam"))
            }

            val reason = withTimeoutOrNull(3000) { closeReason.await() }
            if (reason?.code == CloseReason.Codes.VIOLATED_POLICY.code) closedWithPolicy = true
        }
        closedWithPolicy shouldBe true
        wsClient.close()
    }

    @Test
    fun `frame rate within limit keeps connection open`() = runWsTest {
        val creds = createDevice()
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds) }) {
            Thread.sleep(500)

            // Send exactly the limit — should not close
            repeat(WsRoute.MAX_FRAMES_PER_WINDOW) {
                send(Frame.Text("ok"))
            }

            // Connection should still be open
            Thread.sleep(500)
            val reason = withTimeoutOrNull(500) { closeReason.await() }
            reason shouldBe null

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `oversized frame closes connection`() = runWsTest {
        val creds = createDevice()
        val wsClient = createWsClient()

        var connectionClosed = false
        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds) }) {
            Thread.sleep(500)

            // Send a frame larger than maxFrameSize (4096 bytes)
            val oversizedData = "x".repeat(5000)
            try {
                send(Frame.Text(oversizedData))
                // Wait for server to process and close
                val reason = withTimeoutOrNull(3000) { closeReason.await() }
                if (reason != null) connectionClosed = true
            } catch (_: Exception) {
                // Connection reset is also acceptable
                connectionClosed = true
            }
        }
        connectionClosed shouldBe true
        wsClient.close()
    }

    @Test
    fun `small frame does not close connection`() = runWsTest {
        val creds = createDevice()
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds) }) {
            Thread.sleep(500)

            // Send a frame within maxFrameSize (4096 bytes)
            send(Frame.Text("x".repeat(100)))
            Thread.sleep(500)

            val reason = withTimeoutOrNull(500) { closeReason.await() }
            reason shouldBe null

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `reconnecting same device stops notifications on first session`() = runWsTest {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1) // Same account, different device (the notifier)
        val wsClient1 = createWsClient()
        val wsClient2 = createWsClient()

        wsClient1.webSocket(urlString = wsUrl(), request = { addCredentials(creds1) }) {
            Thread.sleep(500)

            // Connect second WS with SAME device — evicts first session from registry
            wsClient2.webSocket(urlString = wsUrl(), request = { addCredentials(creds1) }) {
                Thread.sleep(500)

                // Trigger a notification from another device
                writeModule(creds2, "eu.darken.octi.module.power", data = "data")
                Thread.sleep(2000)

                // Second (new) session should receive the notification
                val frame = incoming.tryReceive().getOrNull()
                frame shouldNotBe null
                (frame as Frame.Text).readText() shouldContain "module_changed"

                close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
            }

            // First session should be disconnected (server closed it when second session connected)
            val reason = withTimeout(2000) { closeReason.await() }
            reason shouldNotBe null
            reason!!.code shouldBe CloseReason.Codes.GOING_AWAY.code
        }
        wsClient1.close()
        wsClient2.close()
    }
}
