package eu.darken.octi.server.common

import eu.darken.octi.server.device.Device
import eu.darken.octi.server.device.DeviceKey
import eu.darken.octi.server.device.DeviceRepo
import eu.darken.octi.server.device.DeviceRepo.MissingDeviceReason
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.*

/**
 * Pre-G4-fix this file tested authenticateDevice end-to-end including side effects.
 * Now that validation and metadata-touch are split (so over-rate-limit calls don't
 * write lastSeen) the tests are split too: validation-only here, side-effect coverage
 * in [TouchAuthenticatedDeviceTest].
 */
class AuthenticateDeviceTest {

    private val accountId: UUID = UUID.randomUUID()
    private val deviceId: UUID = UUID.randomUUID()
    private val password = "test-password-12345"

    private lateinit var deviceRepo: DeviceRepo
    private lateinit var device: Device

    @BeforeEach
    fun setup() {
        device = Device(
            data = Device.Data(id = deviceId, password = password, version = null),
            path = Path.of("/tmp/test"),
            accountId = accountId,
        )
        deviceRepo = mockk(relaxed = true) {
            coEvery { getDevice(DeviceKey(accountId, deviceId)) } returns device
        }
    }

    private fun basicAuth(account: UUID = accountId, pw: String = password): String {
        val encoded = Base64.getEncoder().encodeToString("$account:$pw".toByteArray())
        return "Basic $encoded"
    }

    @Test
    fun `valid headers return Success`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Success>()
        result.deviceId shouldBe deviceId
        result.device shouldBe device
    }

    @Test
    fun `validation does not touch device repo for updates`() = runTest {
        authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
        )

        // Pure validation must not write — that belongs to touchAuthenticatedDevice.
        coVerify(exactly = 0) { deviceRepo.updateDevice(any(), any()) }
    }

    @Test
    fun `missing device ID returns BadRequest`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = null,
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        result.status.value shouldBe 400
        result.tag shouldBe "missing-device-id"
    }

    @Test
    fun `blank or malformed device ID also tags missing-device-id`() = runTest {
        val blank = authenticateDevice(deviceIdHeader = "   ", authHeader = basicAuth(), deviceRepo = deviceRepo)
        blank.shouldBeInstanceOf<AuthResult.Failure>()
        blank.tag shouldBe "missing-device-id"

        val malformed = authenticateDevice(deviceIdHeader = "not-a-uuid", authHeader = basicAuth(), deviceRepo = deviceRepo)
        malformed.shouldBeInstanceOf<AuthResult.Failure>()
        malformed.tag shouldBe "missing-device-id"
    }

    @Test
    fun `missing auth header returns BadRequest`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = null,
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        result.status.value shouldBe 400
        result.tag shouldBe "missing-credentials"
    }

    @Test
    fun `non-Basic auth scheme tags missing-credentials`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = "Bearer some-token",
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        result.tag shouldBe "missing-credentials"
    }

    @Test
    fun `invalid base64 in Basic auth tags missing-credentials`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = "Basic ##not-base64##",
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        result.tag shouldBe "missing-credentials"
    }

    @Test
    fun `Basic auth payload without colon tags missing-credentials`() = runTest {
        val payload = Base64.getEncoder().encodeToString("no-colon-here".toByteArray())
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = "Basic $payload",
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        result.tag shouldBe "missing-credentials"
    }

    @Test
    fun `Basic auth with non-UUID account tags missing-credentials`() = runTest {
        val payload = Base64.getEncoder().encodeToString("not-a-uuid:password".toByteArray())
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = "Basic $payload",
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        result.tag shouldBe "missing-credentials"
    }

    @Test
    fun `unknown device returns NotFound`() = runTest {
        val unknownId = UUID.randomUUID()
        val unknownKey = DeviceKey(accountId, unknownId)
        coEvery { deviceRepo.getDevice(unknownKey) } returns null
        coEvery { deviceRepo.classifyMissingDevice(unknownKey) } returns MissingDeviceReason.UNKNOWN_DEVICE

        val result = authenticateDevice(
            deviceIdHeader = unknownId.toString(),
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        result.status.value shouldBe 404
        result.tag shouldBe "unknown-device"
    }

    @Test
    fun `unknown account returns NotFound with unknown-account tag`() = runTest {
        val unknownAccount = UUID.randomUUID()
        val unknownKey = DeviceKey(unknownAccount, deviceId)
        coEvery { deviceRepo.getDevice(unknownKey) } returns null
        coEvery { deviceRepo.classifyMissingDevice(unknownKey) } returns MissingDeviceReason.UNKNOWN_ACCOUNT

        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(account = unknownAccount),
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        result.status.value shouldBe 404
        result.tag shouldBe "unknown-account"
    }

    @Test
    fun `device account mismatch returns NotFound with mismatch tag`() = runTest {
        val otherAccount = UUID.randomUUID()
        val mismatchedKey = DeviceKey(otherAccount, deviceId)
        coEvery { deviceRepo.getDevice(mismatchedKey) } returns null
        coEvery { deviceRepo.classifyMissingDevice(mismatchedKey) } returns MissingDeviceReason.DEVICE_ACCOUNT_MISMATCH

        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(account = otherAccount),
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        result.status.value shouldBe 404
        result.tag shouldBe "device-account-mismatch"
    }

    @Test
    fun `wrong password returns Unauthorized`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(pw = "wrong-password"),
            deviceRepo = deviceRepo,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        result.status.value shouldBe 401
        result.tag shouldBe "bad-credentials"
    }
}

class TouchAuthenticatedDeviceTest {

    private val accountId: UUID = UUID.randomUUID()
    private val deviceId: UUID = UUID.randomUUID()
    private val clientIp = "10.0.0.1"

    private lateinit var deviceRepo: DeviceRepo
    private lateinit var ipTracker: IpDeviceTracker
    private lateinit var device: Device

    @BeforeEach
    fun setup() {
        device = Device(
            data = Device.Data(id = deviceId, password = "irrelevant", version = null),
            path = Path.of("/tmp/test"),
            accountId = accountId,
        )
        deviceRepo = mockk(relaxed = true) {
            coEvery { getDevice(DeviceKey(accountId, deviceId)) } returns device
        }
        ipTracker = mockk(relaxed = true)
    }

    @Test
    fun `tracker records on touch`() = runTest {
        touchAuthenticatedDevice(
            device = device,
            deviceRepo = deviceRepo,
            clientIp = clientIp,
            ipTracker = ipTracker,
        )

        verify(exactly = 1) { ipTracker.record(clientIp, accountId, deviceId) }
    }

    @Test
    fun `null clientIp skips tracker`() = runTest {
        touchAuthenticatedDevice(
            device = device,
            deviceRepo = deviceRepo,
            clientIp = null,
            ipTracker = ipTracker,
        )

        verify(exactly = 0) { ipTracker.record(any(), any(), any()) }
    }

    @Test
    fun `null tracker is tolerated`() = runTest {
        touchAuthenticatedDevice(
            device = device,
            deviceRepo = deviceRepo,
            clientIp = clientIp,
            ipTracker = null,
        )
        // No throw.
    }

    @Test
    fun `metadata label passed to updateDevice`() = runTest {
        val actionSlot = slot<(Device.Data) -> Device.Data>()
        coEvery { deviceRepo.updateDevice(any(), capture(actionSlot)) } returns Unit

        touchAuthenticatedDevice(
            device = device,
            deviceRepo = deviceRepo,
            metadata = DeviceMetadataPatch(label = "My Phone"),
        )

        coVerify(exactly = 1) { deviceRepo.updateDevice(any(), any()) }
        val updated = actionSlot.captured(device.data)
        updated.label shouldBe "My Phone"
    }

    @Test
    fun `null metadata fields preserve existing values`() = runTest {
        val deviceWithLabel = device.copy(data = device.data.copy(label = "Old Label"))
        coEvery { deviceRepo.getDevice(DeviceKey(accountId, deviceId)) } returns deviceWithLabel

        val actionSlot = slot<(Device.Data) -> Device.Data>()
        coEvery { deviceRepo.updateDevice(any(), capture(actionSlot)) } returns Unit

        touchAuthenticatedDevice(
            device = deviceWithLabel,
            deviceRepo = deviceRepo,
            metadata = DeviceMetadataPatch(),
        )

        val updated = actionSlot.captured(deviceWithLabel.data)
        updated.label shouldBe "Old Label"
    }

    @Test
    fun `tracker exception does not break the touch`() = runTest {
        io.mockk.every { ipTracker.record(any(), any(), any()) } throws RuntimeException("tracker broke")

        val updated = touchAuthenticatedDevice(
            device = device,
            deviceRepo = deviceRepo,
            clientIp = clientIp,
            ipTracker = ipTracker,
        )

        updated shouldBe device
    }
}
