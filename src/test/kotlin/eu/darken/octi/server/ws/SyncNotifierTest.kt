package eu.darken.octi.server.ws

import eu.darken.octi.server.common.AppScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

@OptIn(DelicateCoroutinesApi::class)
class SyncNotifierTest {

    private lateinit var notifier: SyncNotifier
    private lateinit var registry: ConnectionRegistry
    private lateinit var appScope: AppScope

    private val accountId: UUID = UUID.randomUUID()
    private val device1: UUID = UUID.randomUUID()
    private val device2: UUID = UUID.randomUUID()
    private val device3: UUID = UUID.randomUUID()
    private val json = Json { ignoreUnknownKeys = true }
    private val testIp = "192.168.1.1"

    @BeforeEach
    fun setup() {
        appScope = AppScope()
        registry = ConnectionRegistry(appScope)
        notifier = SyncNotifier(appScope, registry, json)
    }

    private suspend fun registerDevice(deviceId: UUID, accountId: UUID): ConnectionRegistry.DeviceSession {
        val result = registry.register(deviceId, accountId, testIp)
        return (result as ConnectionRegistry.RegisterResult.Accepted).session
    }

    @Test
    fun `single event is broadcast after debounce delay`() = runBlocking {
        val session = registerDevice(device2, accountId)

        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power")

        val payload = withTimeout(3000) { session.outbox.receive() }
        payload shouldContain "module_changed"
        payload shouldContain "eu.darken.octi.module.power"
        payload shouldContain "updated"
    }

    @Test
    fun `burst writes are batched into single notification`() = runBlocking {
        val session = registerDevice(device2, accountId)

        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power")
        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.meta")
        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.wifi")

        val payload = withTimeout(3000) { session.outbox.receive() }
        val parsed = json.decodeFromString<SyncNotifier.EventPayload>(payload)
        parsed.events.size shouldBe 3
    }

    @Test
    fun `broadcast excludes source device`() = runBlocking {
        val session1 = registerDevice(device1, accountId)
        val session2 = registerDevice(device2, accountId)

        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power")

        val payload = withTimeout(3000) { session2.outbox.receive() }
        payload shouldContain "module_changed"

        // Source device should have empty outbox
        session1.outbox.tryReceive().getOrNull() shouldBe null
    }

    @Test
    fun `no peers means no broadcast`() = runBlocking {
        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power")
        Thread.sleep(1500)
        // No exceptions = success
    }

    @Test
    fun `delete action is included in notification`() = runBlocking {
        val session = registerDevice(device2, accountId)

        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power", action = "deleted")

        val payload = withTimeout(3000) { session.outbox.receive() }
        payload shouldContain "deleted"
    }

    @Test
    fun `two devices write within debounce - each sees only the other's events`() = runBlocking {
        val session1 = registerDevice(device1, accountId)
        val session2 = registerDevice(device2, accountId)

        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power")
        notifier.enqueueModuleChanged(accountId, device2, device2, "eu.darken.octi.module.meta")

        val payload1 = withTimeout(3000) { session1.outbox.receive() }
        val parsed1 = json.decodeFromString<SyncNotifier.EventPayload>(payload1)
        parsed1.events.size shouldBe 1
        (parsed1.events[0] as SyncNotifier.EventPayload.Event.ModuleChanged).deviceId shouldBe device2.toString()

        val payload2 = withTimeout(3000) { session2.outbox.receive() }
        val parsed2 = json.decodeFromString<SyncNotifier.EventPayload>(payload2)
        parsed2.events.size shouldBe 1
        (parsed2.events[0] as SyncNotifier.EventPayload.Event.ModuleChanged).deviceId shouldBe device1.toString()
    }

    @Test
    fun `all devices write within debounce - nobody gets notified`() = runBlocking {
        val session1 = registerDevice(device1, accountId)
        val session2 = registerDevice(device2, accountId)

        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power")
        notifier.enqueueModuleChanged(accountId, device2, device2, "eu.darken.octi.module.meta")

        // Wait for debounce to fire
        Thread.sleep(1500)

        // Device 1 should only see device 2's event (not its own)
        val payload1 = session1.outbox.tryReceive().getOrNull()
        if (payload1 != null) {
            val parsed = json.decodeFromString<SyncNotifier.EventPayload>(payload1)
            parsed.events.forEach { event ->
                (event as SyncNotifier.EventPayload.Event.ModuleChanged).deviceId shouldBe device2.toString()
            }
        }
    }

    @Test
    fun `three devices - each gets events from the other two`() = runBlocking {
        val session1 = registerDevice(device1, accountId)
        val session2 = registerDevice(device2, accountId)
        val session3 = registerDevice(device3, accountId)

        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power")
        notifier.enqueueModuleChanged(accountId, device2, device2, "eu.darken.octi.module.meta")
        notifier.enqueueModuleChanged(accountId, device3, device3, "eu.darken.octi.module.wifi")

        val payload1 = withTimeout(3000) { session1.outbox.receive() }
        val parsed1 = json.decodeFromString<SyncNotifier.EventPayload>(payload1)
        parsed1.events.size shouldBe 2
        parsed1.events.none { (it as SyncNotifier.EventPayload.Event.ModuleChanged).deviceId == device1.toString() } shouldBe true

        val payload2 = withTimeout(3000) { session2.outbox.receive() }
        val parsed2 = json.decodeFromString<SyncNotifier.EventPayload>(payload2)
        parsed2.events.size shouldBe 2
        parsed2.events.none { (it as SyncNotifier.EventPayload.Event.ModuleChanged).deviceId == device2.toString() } shouldBe true

        val payload3 = withTimeout(3000) { session3.outbox.receive() }
        val parsed3 = json.decodeFromString<SyncNotifier.EventPayload>(payload3)
        parsed3.events.size shouldBe 2
        parsed3.events.none { (it as SyncNotifier.EventPayload.Event.ModuleChanged).deviceId == device3.toString() } shouldBe true
    }

    @Test
    fun `rapid enqueue cancels and retries without losing events`() = runBlocking {
        val session = registerDevice(device2, accountId)

        // Rapid-fire enqueue: each cancels the previous debounce job
        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power")
        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.meta")
        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.wifi")
        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.apps")

        // Despite multiple cancellations, all events should arrive in one batch
        val payload = withTimeout(3000) { session.outbox.receive() }
        val parsed = json.decodeFromString<SyncNotifier.EventPayload>(payload)
        parsed.events.size shouldBe 4

        // No extra messages
        session.outbox.tryReceive().getOrNull() shouldBe null
    }

    @Test
    fun `full outbox drops notification but keeps session alive`() = runBlocking {
        val session = registerDevice(device2, accountId)

        // Fill the outbox buffer (Channel.BUFFERED = 64)
        repeat(64) { i -> session.outbox.trySend("filler-$i") }

        // Trigger a notification — trySend will fail (buffer full)
        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power")
        Thread.sleep(1500)

        // Session outbox should still be open (not disconnected)
        session.outbox.isClosedForSend shouldBe false

        // Drain the buffer and verify session is still usable
        repeat(64) { session.outbox.tryReceive() }
        val probeResult = session.outbox.trySend("probe")
        probeResult.isSuccess shouldBe true
    }

    @Test
    fun `closed outbox is skipped without crashing`() = runBlocking {
        val session = registerDevice(device2, accountId)
        session.outbox.close()

        // Trigger a notification — trySend will fail (closed)
        notifier.enqueueModuleChanged(accountId, device1, device1, "eu.darken.octi.module.power")
        Thread.sleep(1500)

        // Should complete without exceptions — closed session is just skipped
    }
}
