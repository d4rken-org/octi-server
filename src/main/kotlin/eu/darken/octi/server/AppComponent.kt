package eu.darken.octi.server

import dagger.BindsInstance
import dagger.Component
import eu.darken.octi.server.account.AccountRepo
import eu.darken.octi.server.account.AccountStorageTracker
import eu.darken.octi.server.common.serialization.SerializationModule
import eu.darken.octi.server.device.DeviceClientIdentityTracker
import eu.darken.octi.server.device.DeviceRepo
import eu.darken.octi.server.module.ModuleLifecycleService
import eu.darken.octi.server.module.ModuleRepo
import eu.darken.octi.server.module.UploadSessionRepo
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        SerializationModule::class
    ]
)
interface AppComponent {
    fun application(): App

    // Test-only accessors — tests exercise recovery, GC, and teardown paths that
    // aren't reachable via HTTP. Production code reaches these through Dagger injection.
    fun storageTracker(): AccountStorageTracker
    fun sessionRepo(): UploadSessionRepo
    fun moduleRepo(): ModuleRepo
    fun lifecycleService(): ModuleLifecycleService
    fun accountRepo(): AccountRepo
    fun deviceRepo(): DeviceRepo
    fun deviceClientIdentityTracker(): DeviceClientIdentityTracker
    fun json(): Json

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun config(config: App.Config): Builder

        fun build(): AppComponent
    }
}
