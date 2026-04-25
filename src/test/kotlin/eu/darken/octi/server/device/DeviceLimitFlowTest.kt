package eu.darken.octi.server.device

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import org.junit.jupiter.api.Test

class DeviceLimitFlowTest : TestRunner() {

    @Test
    fun `account hits the device count cap and returns 409`() = runTest2(
        appConfig = baseConfig.copy(maxDevicesPerAccount = 2),
    ) {
        val device1 = createDevice() // creates account + device 1

        // Add device 2 via share — should succeed.
        val share2 = createShareCode(device1)
        val device2 = createDevice(shareCode = share2)
        device2.account shouldBe device1.account

        // Add device 3 — must hit the cap and return 409.
        val share3 = createShareCode(device1)
        createDeviceRaw(shareCode = share3).status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `share is restored after a 409 so the next attempt can re-consume it`() = runTest2(
        appConfig = baseConfig.copy(maxDevicesPerAccount = 1),
    ) {
        val device1 = createDevice()
        val share = createShareCode(device1)

        // Cap is 1; this attempt fails.
        createDeviceRaw(shareCode = share).status shouldBe HttpStatusCode.Conflict

        // Same share should still be consumable after the 409. It'd have been silently
        // burned without the restore-on-limit-exceeded path.
        createDeviceRaw(shareCode = share).status shouldBe HttpStatusCode.Conflict
        // (Still 409 because the cap is still 1, but the share itself wasn't consumed —
        // a 403 'ShareCode was already consumed' response would tell us it had been.)
    }
}
