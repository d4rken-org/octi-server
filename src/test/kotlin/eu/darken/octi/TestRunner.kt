package eu.darken.octi

import eu.darken.octi.server.App
import eu.darken.octi.server.AppComponent
import eu.darken.octi.server.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.octi.server.common.debug.logging.log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively

abstract class TestRunner {

    val baseConfig = App.Config(
        dataPath = Path("./build/tmp/testdatapath/${UUID.randomUUID()}"),
        port = 16023,
        isDebug = true,
        rateLimit = null,
        // Disable the disk-space gate; tests that need it set this explicitly.
        minFreeDiskSpaceBytes = 0L,
    )

    data class TestEnvironment(
        val config: App.Config,
        val app: App,
        val component: AppComponent,
        val http: HttpClient,
    )

    fun runTest2(
        appConfig: App.Config = baseConfig,
        keepData: Boolean = false,
        seed: (App.Config) -> Unit = {},
        before: (App.Config) -> TestEnvironment = { cfg ->
            Files.createDirectories(cfg.dataPath)
            seed(cfg)

            val component = App.createComponent(cfg)
            val app = component.application()

            val launchError = AtomicReference<Throwable?>()
            thread {
                try {
                    app.launch()
                } catch (t: Throwable) {
                    launchError.set(t)
                }
            }

            val deadlineMillis = System.currentTimeMillis() + 30_000
            while (!app.isRunning()) {
                launchError.get()?.let { throw AssertionError("App.launch() failed before server started", it) }
                if (System.currentTimeMillis() > deadlineMillis) {
                    throw AssertionError("App did not start within 30s")
                }
                Thread.sleep(50)
            }

            val client = HttpClient(CIO) {
                defaultRequest {
                    url {
                        protocol = URLProtocol.HTTP
                        host = "127.0.0.1"
                        port = cfg.port
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

            TestEnvironment(cfg, app, component, client)
        },
        after: TestEnvironment.() -> Unit = {
            http.close()
            app.shutdown()
            if (!keepData) {
                log(VERBOSE) { "Cleaning up stored data" }
                config.dataPath.deleteRecursively()
            }
        },
        test: suspend TestEnvironment.() -> Unit
    ) {
        val env = before(appConfig)
        log(VERBOSE) { "Running test with environment $env" }
        try {
            runTest { test(env) }
        } finally {
            after(env)
            log(VERBOSE) { "Test is done $env" }
        }
    }

    fun TestEnvironment.getAccountPath(credentials: Credentials): Path {
        return config.dataPath.resolve("accounts").resolve(credentials.account)
    }

    fun TestEnvironment.getSharesPath(credentials: Credentials): Path {
        return getAccountPath(credentials).resolve("shares")
    }

    fun TestEnvironment.getDevicePath(credentials: Credentials): Path {
        return getAccountPath(credentials).resolve("devices").resolve(credentials.deviceId.toString())
    }

    fun TestEnvironment.getModulesPath(credentials: Credentials): Path {
        return getDevicePath(credentials).resolve("modules")
    }
}
