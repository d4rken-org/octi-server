package eu.darken.octi.server

import eu.darken.octi.TestRunner
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.io.path.Path

class AppTest : TestRunner() {

    @Test
    fun `sane config values`() {
        App.Config(
            dataPath = Path("./build/tmp/testdatapath"),
            port = 8080,
        ).apply {
            shareExpiration shouldBe Duration.ofMinutes(60)
            deviceExpiration shouldBe Duration.ofDays(90)
            moduleExpiration shouldBe Duration.ofDays(90)
        }
    }

    @Test
    fun `shutdown cancels app scope`() = runTest2 {
        app.shutdown()
        app.appScope.coroutineContext[Job]!!.isCancelled shouldBe true
    }
}
