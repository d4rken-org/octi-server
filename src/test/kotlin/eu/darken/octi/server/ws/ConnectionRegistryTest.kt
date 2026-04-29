package eu.darken.octi.server.ws

import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.device.DeviceKey
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.instanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.*

@OptIn(DelicateCoroutinesApi::class)
class ConnectionRegistryTest {

    private lateinit var registry: ConnectionRegistry
    private lateinit var appScope: AppScope
    private val accountA: UUID = UUID.randomUUID()
    private val accountB: UUID = UUID.randomUUID()
    private val device1: UUID = UUID.randomUUID()
    private val device2: UUID = UUID.randomUUID()
    private val device3: UUID = UUID.randomUUID()
    private val testIp = "192.168.1.1"

    @BeforeEach
    fun setup() {
        appScope = AppScope()
        registry = ConnectionRegistry(appScope)
    }

    private suspend fun registerDevice(deviceId: UUID, accountId: UUID, clientIp: String = testIp): ConnectionRegistry.DeviceSession {
        val result = registry.register(deviceId, accountId, clientIp)
        return (result as ConnectionRegistry.RegisterResult.Accepted).session
    }

    @Test
    fun `empty registry returns no peers`() {
        registry.getAccountPeers(accountA, device1).shouldBeEmpty()
    }

    @Test
    fun `register and get peers excludes self`() = runBlocking {
        registerDevice(device1, accountA)
        registerDevice(device2, accountA)

        val peers = registry.getAccountPeers(accountA, excludeDevice = device1)
        peers shouldHaveSize 1
        peers.first().deviceId shouldBe device2
    }

    @Test
    fun `get peers filters by account`() = runBlocking {
        registerDevice(device1, accountA)
        registerDevice(device2, accountB)

        registry.getAccountPeers(accountA, excludeDevice = UUID.randomUUID()) shouldHaveSize 1
        registry.getAccountPeers(accountB, excludeDevice = UUID.randomUUID()) shouldHaveSize 1
    }

    @Test
    fun `unregister removes session`() = runBlocking {
        val session = registerDevice(device1, accountA)
        registry.getAccountPeers(accountA, excludeDevice = device2) shouldHaveSize 1

        registry.unregister(session)
        registry.getAccountPeers(accountA, excludeDevice = device2).shouldBeEmpty()
    }

    @Test
    fun `unregister with stale session does not remove newer session`() = runBlocking {
        val sessionA = registerDevice(device1, accountA)
        val sessionB = registerDevice(device1, accountA)

        registry.unregister(sessionA)

        val peers = registry.getAccountPeers(accountA, excludeDevice = device2)
        peers shouldHaveSize 1
        peers.first().sessionId shouldBe sessionB.sessionId
        sessionB.outbox.isClosedForSend shouldBe false
    }

    @Test
    fun `re-register replaces existing session and closes old outbox`() = runBlocking {
        val oldSession = registerDevice(device1, accountA)
        val newSession = registerDevice(device1, accountA)

        val peers = registry.getAccountPeers(accountA, excludeDevice = device2)
        peers shouldHaveSize 1
        oldSession.outbox.isClosedForSend shouldBe true
        newSession.outbox.isClosedForSend shouldBe false
    }

    @Test
    fun `multiple devices on same account`() = runBlocking {
        registerDevice(device1, accountA)
        registerDevice(device2, accountA)
        registerDevice(device3, accountA)

        registry.getAccountPeers(accountA, excludeDevice = device1) shouldHaveSize 2
        registry.getAccountPeers(accountA, excludeDevice = device2) shouldHaveSize 2
    }

    @Test
    fun `stats returns correct counts`() = runBlocking {
        registry.stats() shouldBe ConnectionRegistry.Stats(totalDevices = 0, totalAccounts = 0)

        registerDevice(device1, accountA)
        registry.stats() shouldBe ConnectionRegistry.Stats(totalDevices = 1, totalAccounts = 1)

        registerDevice(device2, accountA)
        registry.stats() shouldBe ConnectionRegistry.Stats(totalDevices = 2, totalAccounts = 1)

        registerDevice(device3, accountB)
        registry.stats() shouldBe ConnectionRegistry.Stats(totalDevices = 3, totalAccounts = 2)
    }

    @Test
    fun `activeDeviceKeys returns connected device snapshot`() = runBlocking {
        val session = registerDevice(device1, accountA)
        registerDevice(device2, accountA)

        registry.activeDeviceKeys() shouldBe setOf(
            DeviceKey(accountA, device1),
            DeviceKey(accountA, device2),
        )

        registry.unregister(session)

        registry.activeDeviceKeys() shouldBe setOf(DeviceKey(accountA, device2))
    }

    @Nested
    inner class `composite key` {

        @Test
        fun `same deviceId under different accounts are independent sessions`() = runBlocking {
            val sharedDeviceId = UUID.randomUUID()
            val sessionA = registerDevice(sharedDeviceId, accountA)
            val sessionB = registerDevice(sharedDeviceId, accountB)

            sessionA.outbox.isClosedForSend shouldBe false
            sessionB.outbox.isClosedForSend shouldBe false
            registry.getAccountSessions(accountA) shouldHaveSize 1
            registry.getAccountSessions(accountB) shouldHaveSize 1
            registry.stats() shouldBe ConnectionRegistry.Stats(totalDevices = 2, totalAccounts = 2)
        }

        @Test
        fun `unregister only removes from correct account`() = runBlocking {
            val sharedDeviceId = UUID.randomUUID()
            val sessionA = registerDevice(sharedDeviceId, accountA)
            val sessionB = registerDevice(sharedDeviceId, accountB)

            registry.unregister(sessionA)

            registry.getAccountSessions(accountA).shouldBeEmpty()
            registry.getAccountSessions(accountB) shouldHaveSize 1
            sessionB.outbox.isClosedForSend shouldBe false
        }
    }

    @Nested
    inner class `zombie cleanup` {

        @Test
        fun `session with closed outbox is cleaned up`() = runBlocking {
            val session = registerDevice(device1, accountA)
            session.outbox.close()

            registry.cleanupStaleSessions()

            registry.getAccountPeers(accountA, excludeDevice = UUID.randomUUID()).shouldBeEmpty()
        }

        @Test
        fun `open session is not cleaned up`() = runBlocking {
            registerDevice(device1, accountA)

            registry.cleanupStaleSessions()

            registry.getAccountPeers(accountA, excludeDevice = UUID.randomUUID()) shouldHaveSize 1
        }

        @Test
        fun `cleanup after replacement does not remove new session`() = runBlocking {
            registerDevice(device1, accountA) // sessionA — outbox gets closed by re-register
            val sessionB = registerDevice(device1, accountA)

            registry.cleanupStaleSessions()

            val peers = registry.getAccountPeers(accountA, excludeDevice = device2)
            peers shouldHaveSize 1
            peers.first().sessionId shouldBe sessionB.sessionId
            sessionB.outbox.isClosedForSend shouldBe false
        }

        @Test
        fun `only closed outbox sessions are cleaned up`() = runBlocking {
            registerDevice(device1, accountA)
            registerDevice(device2, accountA)

            val closed = registerDevice(device3, accountB)
            closed.outbox.close()

            registry.cleanupStaleSessions()

            registry.stats().totalDevices shouldBe 2
            registry.getAccountPeers(accountA, excludeDevice = UUID.randomUUID()) shouldHaveSize 2
            registry.getAccountPeers(accountB, excludeDevice = UUID.randomUUID()).shouldBeEmpty()
        }
    }

    @Nested
    inner class `connection limits` {

        private lateinit var limitedRegistry: ConnectionRegistry

        @BeforeEach
        fun setupLimited() {
            limitedRegistry = ConnectionRegistry(
                appScope,
                ConnectionRegistry.Config(maxPerAccount = 3, maxPerIp = 2, maxGlobal = 5)
            )
        }

        private suspend fun registerLimited(
            deviceId: UUID = UUID.randomUUID(),
            accountId: UUID = accountA,
            clientIp: String = testIp,
        ): ConnectionRegistry.DeviceSession {
            val result = limitedRegistry.register(deviceId, accountId, clientIp)
            return (result as ConnectionRegistry.RegisterResult.Accepted).session
        }

        @Test
        fun `per-account at limit accepts without eviction`() = runBlocking {
            val sessions = (1..3).map { i ->
                registerLimited(clientIp = "10.0.$i.1")
            }
            sessions.forEach { it.outbox.isClosedForSend shouldBe false }
            limitedRegistry.stats().totalDevices shouldBe 3
        }

        @Test
        fun `per-account over limit evicts oldest session`() = runBlocking {
            val sessions = (1..3).map { i ->
                registerLimited(clientIp = "10.0.$i.1")
            }

            val result = limitedRegistry.register(UUID.randomUUID(), accountA, "10.0.4.1")
            result shouldBe instanceOf(ConnectionRegistry.RegisterResult.Accepted::class)

            sessions.first().outbox.isClosedForSend shouldBe true
            limitedRegistry.stats().totalDevices shouldBe 3
        }

        @Test
        fun `per-IP limit rejects connection`() = runBlocking {
            val sameIp = "10.0.0.1"
            repeat(2) { registerLimited(accountId = UUID.randomUUID(), clientIp = sameIp) }

            val result = limitedRegistry.register(UUID.randomUUID(), UUID.randomUUID(), sameIp)
            result shouldBe instanceOf(ConnectionRegistry.RegisterResult.Rejected::class)
            (result as ConnectionRegistry.RegisterResult.Rejected).reason shouldBe "Too many connections from this IP"
        }

        @Test
        fun `per-IP limit does not affect different IPs`() = runBlocking {
            repeat(2) { registerLimited(accountId = UUID.randomUUID(), clientIp = "10.0.0.1") }

            val result = limitedRegistry.register(UUID.randomUUID(), UUID.randomUUID(), "10.0.0.2")
            result shouldBe instanceOf(ConnectionRegistry.RegisterResult.Accepted::class)
        }

        @Test
        fun `global limit rejects connection`() = runBlocking {
            repeat(5) { i ->
                registerLimited(accountId = UUID.randomUUID(), clientIp = "10.0.$i.1")
            }
            limitedRegistry.stats().totalDevices shouldBe 5

            val result = limitedRegistry.register(UUID.randomUUID(), UUID.randomUUID(), "10.0.99.1")
            result shouldBe instanceOf(ConnectionRegistry.RegisterResult.Rejected::class)
            (result as ConnectionRegistry.RegisterResult.Rejected).reason shouldBe "Server connection limit reached"
        }

        @Test
        fun `global limit boundary - accepts at limit minus one`() = runBlocking {
            repeat(4) { i ->
                registerLimited(accountId = UUID.randomUUID(), clientIp = "10.0.$i.1")
            }

            val result = limitedRegistry.register(UUID.randomUUID(), UUID.randomUUID(), "10.0.99.1")
            result shouldBe instanceOf(ConnectionRegistry.RegisterResult.Accepted::class)
            limitedRegistry.stats().totalDevices shouldBe 5
        }

        @Test
        fun `reconnecting device at global limit is accepted`() = runBlocking {
            val reconnectDevice = UUID.randomUUID()
            limitedRegistry.register(reconnectDevice, accountA, "10.0.0.1")
            repeat(4) { i ->
                registerLimited(accountId = UUID.randomUUID(), clientIp = "10.0.${i + 1}.1")
            }
            limitedRegistry.stats().totalDevices shouldBe 5

            val result = limitedRegistry.register(reconnectDevice, accountA, "10.0.0.1")
            result shouldBe instanceOf(ConnectionRegistry.RegisterResult.Accepted::class)
            limitedRegistry.stats().totalDevices shouldBe 5
        }

        @Test
        fun `reconnecting device at per-IP limit is accepted`() = runBlocking {
            val sameIp = "10.0.0.1"
            val reconnectDevice = UUID.randomUUID()
            val reconnectAccount = UUID.randomUUID()
            limitedRegistry.register(reconnectDevice, reconnectAccount, sameIp)
            registerLimited(accountId = UUID.randomUUID(), clientIp = sameIp)
            limitedRegistry.stats().totalDevices shouldBe 2

            val result = limitedRegistry.register(reconnectDevice, reconnectAccount, sameIp)
            result shouldBe instanceOf(ConnectionRegistry.RegisterResult.Accepted::class)
        }

        @Test
        fun `per-account eviction does not kill concurrently reconnected device`() = runBlocking {
            val d1 = UUID.randomUUID()
            val d2 = UUID.randomUUID()
            val d3 = UUID.randomUUID()
            val d4 = UUID.randomUUID()
            limitedRegistry.register(d1, accountA, "10.0.1.1")
            limitedRegistry.register(d2, accountA, "10.0.2.1")
            limitedRegistry.register(d3, accountA, "10.0.3.1")
            // d1 is oldest — reconnect it before eviction triggers
            val reconnected = limitedRegistry.register(d1, accountA, "10.0.1.1")
            reconnected shouldBe instanceOf(ConnectionRegistry.RegisterResult.Accepted::class)

            // d4 triggers eviction — d2 is now oldest (d1 was refreshed)
            val result = limitedRegistry.register(d4, accountA, "10.0.4.1")
            result shouldBe instanceOf(ConnectionRegistry.RegisterResult.Accepted::class)

            // d1's reconnected session must survive
            val peers = limitedRegistry.getAccountSessions(accountA)
            peers.any { it.deviceId == d1 } shouldBe true
            peers.first { it.deviceId == d1 }.outbox.isClosedForSend shouldBe false
        }
    }
}
