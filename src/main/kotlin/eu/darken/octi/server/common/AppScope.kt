package eu.darken.octi.server.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

@Singleton
class AppScope @Inject constructor() : CoroutineScope {
    private val job = SupervisorJob()

    override val coroutineContext: CoroutineContext = job + Dispatchers.Default

    fun cancel() {
        coroutineContext.cancel()
    }
}
