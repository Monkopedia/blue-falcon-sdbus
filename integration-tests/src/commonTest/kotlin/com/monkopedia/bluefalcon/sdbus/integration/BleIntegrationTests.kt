package com.monkopedia.bluefalcon.sdbus.integration

import com.monkopedia.bluefalcon.sdbus.SdbusEngine
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.PrintLnLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Integration tests against the BF-Test ESP32-C6 peripheral.
 *
 * Each test spins up a fresh [SdbusEngine], scans, connects, and discovers
 * services in [setUp]; [tearDown] disconnects and shuts down the D-Bus
 * event loop so the next test gets a clean connection.
 *
 * Prerequisites:
 * - BF-Test peripheral is powered on and advertising
 * - BlueZ is running and user has permission to access the system bus
 *
 * @see <a href="https://github.com/Monkopedia/bf-test-peripheral">bf-test-peripheral</a>
 */
class BleIntegrationTests {

    private var engine: SdbusEngine? = null
    private var harness: EngineTestHarness? = null
    private var peripheral: BluetoothPeripheral? = null

    @BeforeTest
    fun setUp() = runBlocking {
        val e = SdbusEngine(logger = PrintLnLogger)
        engine = e
        val h = EngineTestHarness(e)
        harness = h

        val found = h.scanForDevice(timeoutMs = 120_000L) { device ->
            device.name == BfTestConstants.DEVICE_NAME
        }
        // BlueZ frequently answers the first Connect() after a recent
        // disconnect with "le-connection-abort-by-local"; retry a few
        // times with backoff before giving up.
        connectWithRetry(e, found)
        waitForServices(found, timeoutMs = 15_000L)
        peripheral = found
    }

    private suspend fun connectWithRetry(e: SdbusEngine, p: BluetoothPeripheral) {
        var lastError: Throwable? = null
        repeat(4) { attempt ->
            try {
                e.connect(p)
                if (attempt > 0) {
                    println("RETRY-OK: connect succeeded on attempt ${attempt + 1}")
                }
                return
            } catch (t: Throwable) {
                println("RETRY-FAIL: connect attempt ${attempt + 1} threw ${t::class.simpleName}: ${t.message}")
                lastError = t
                delay(1000L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("connect failed")
    }

    @AfterTest
    fun tearDown() = runBlocking {
        val e = engine ?: return@runBlocking
        val p = peripheral
        if (p != null) {
            try { e.disconnect(p) } catch (_: Exception) {}
        }
        e.destroy()
        // BlueZ needs time after disconnect before the next Connect() call
        // will succeed. Without this, the follow-up test sees
        // "le-connection-abort-by-local".
        delay(2000)
    }

    private suspend fun waitForServices(p: BluetoothPeripheral, timeoutMs: Long) {
        val deadline = timeoutMs / 100
        repeat(deadline.toInt()) {
            if (p.services.isNotEmpty()) return
            delay(100)
        }
        fail("Services did not resolve within ${timeoutMs}ms")
    }

    private fun engine() = engine ?: fail("setUp failed")
    private fun harness() = harness ?: fail("setUp failed")
    private fun peripheral() = peripheral ?: fail("setUp failed")

    private fun findChar(uuid: String): BluetoothCharacteristic {
        val target = uuidFrom(uuid)
        return peripheral().characteristics.firstOrNull { it.uuid == target }
            ?: fail("Characteristic $uuid not found")
    }

    // ---- Device identity ----

    @Test
    fun connectedDeviceIsBfTest() {
        assertEquals(BfTestConstants.DEVICE_NAME, peripheral().name)
    }

    // ---- Connection ----

    @Test
    fun connectionStateIsConnected() {
        assertEquals(BluetoothPeripheralState.Connected, engine().connectionState(peripheral()))
    }

    // ---- Service discovery ----

    @Test
    fun discoversTestService() {
        val uuids = peripheral().services.map { it.uuid.toString().lowercase() }
        assertTrue(uuids.any { BfTestConstants.SERVICE_1 in it }, "Service 1 (BF10) missing")
    }

    @Test
    fun discoversSecureService() {
        val uuids = peripheral().services.map { it.uuid.toString().lowercase() }
        assertTrue(uuids.any { BfTestConstants.SERVICE_2 in it }, "Service 2 (BF20) missing")
    }

    @Test
    fun discoversAllCharacteristics() {
        val uuids = peripheral().characteristics.map { it.uuid.toString().lowercase() }
        for (expected in listOf(
            BfTestConstants.CHAR_A_READ,
            BfTestConstants.CHAR_B_WRITE,
            BfTestConstants.CHAR_C_WRITE_NR,
            BfTestConstants.CHAR_D_NOTIFY,
            BfTestConstants.CHAR_E_INDICATE,
            BfTestConstants.CHAR_F_DESC,
            BfTestConstants.CHAR_H_NOTIFY_IND,
        )) {
            assertTrue(uuids.any { expected in it }, "Characteristic $expected missing")
        }
    }

    // ---- Read ----

    @Test
    fun readFixedValue(): Unit = runBlocking {
        val char = findChar(BfTestConstants.CHAR_A_READ)
        engine().readCharacteristic(peripheral(), char)
        assertContentEquals(BfTestConstants.CHAR_A_EXPECTED, char.value)
    }

    // ---- Write ----

    @Test
    fun writeAndReadBack(): Unit = runBlocking {
        val charB = findChar(BfTestConstants.CHAR_B_WRITE)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        engine().writeCharacteristic(peripheral(), charB, data)
        engine().readCharacteristic(peripheral(), charB)
        assertContentEquals(data, charB.value)
    }

    @Test
    fun writeNoResponse(): Unit = runBlocking {
        val charC = findChar(BfTestConstants.CHAR_C_WRITE_NR)
        val data = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        engine().writeCharacteristic(peripheral(), charC, data, writeType = 1)
        delay(500) // write-without-response has no callback
        engine().readCharacteristic(peripheral(), charC)
        assertContentEquals(data, charC.value)
    }

    // ---- Notifications ----

    @Test
    fun charDNotifications(): Unit = runBlocking {
        val charD = findChar(BfTestConstants.CHAR_D_NOTIFY)
        engine().notifyCharacteristic(peripheral(), charD, true)
        val values = harness().collectNotifications(charD, count = 2, timeoutMs = 15_000L)
        assertEquals(2, values.size)
        engine().notifyCharacteristic(peripheral(), charD, false)
    }

    @Test
    fun indications(): Unit = runBlocking {
        val charE = findChar(BfTestConstants.CHAR_E_INDICATE)
        engine().indicateCharacteristic(peripheral(), charE, true)
        val data = byteArrayOf(0x42, 0x46) // "BF"
        engine().writeCharacteristic(peripheral(), findChar(BfTestConstants.CHAR_B_WRITE), data)
        val indication = harness().awaitCharacteristicValue(charE, timeoutMs = 5_000L)
        assertContentEquals(data, indication, "Char E should echo value written to Char B")
    }

    // ---- Descriptors ----

    @Test
    fun descriptorsPresent() {
        assertTrue(
            findChar(BfTestConstants.CHAR_F_DESC).descriptors.isNotEmpty(),
            "Char F should have descriptors"
        )
    }

    @Test
    fun writeDescriptor(): Unit = runBlocking {
        val charF = findChar(BfTestConstants.CHAR_F_DESC)
        val descriptor = charF.descriptors.firstOrNull { desc ->
            desc.uuid.toString().contains("2901", ignoreCase = true)
        } ?: charF.descriptors.first()
        engine().writeDescriptor(peripheral(), descriptor, "Test".encodeToByteArray())
    }

    // ---- MTU ----

    @Test
    fun reportMtu(): Unit = runBlocking {
        // BlueZ doesn't expose MTU negotiation; changeMTU reports what BlueZ
        // already agreed to. Just verify it completes and produces a value.
        engine().changeMTU(peripheral(), 247)
        val mtu = peripheral().mtuSize
        assertTrue(mtu != null && mtu > 0, "Expected an MTU value, got $mtu")
    }

    // ---- Bonding ----

    @Test
    fun bondAndReadEncrypted(): Unit = runBlocking {
        try {
            engine().createBond(peripheral())
        } catch (_: Exception) {
            // May already be bonded from a previous run
        }
        val charG = findChar(BfTestConstants.CHAR_G_ENCRYPTED)
        engine().readCharacteristic(peripheral(), charG)
        assertContentEquals(
            BfTestConstants.CHAR_G_EXPECTED, charG.value,
            "Char G should return SECURE after bonding",
        )
    }

    // L2CAP CoC is not supported on BlueZ via D-Bus; SdbusEngine throws
    // UnsupportedOperationException for openL2capChannel, so there's no
    // positive-path test here.
}
