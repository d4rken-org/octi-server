package eu.darken.octi.server.common

import eu.darken.octi.server.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

fun AppScope.launchPeriodicJob(
    tag: String,
    interval: Duration,
    initialDelay: Duration = interval,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    onErrorMessage: String = "Periodic job failed",
    block: suspend () -> Unit,
) = launch(dispatcher) {
    delay(initialDelay.toMillis().coerceAtLeast(0L))
    while (currentCoroutineContext().isActive) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "$onErrorMessage: ${e.asLog()}" }
        }
        delay(interval.toMillis().coerceAtLeast(1L))
    }
}
