package com.monkopedia.bluefalcon.sdbus.integration

import com.monkopedia.bluefalcon.sdbus.SdbusPeripheral
import com.monkopedia.bluefalcon.sdbus.bluez.Device1Proxy
import com.monkopedia.sdbus.Connection
import com.monkopedia.sdbus.SdbusException
import com.monkopedia.sdbus.ServiceName
import com.monkopedia.sdbus.createProxy
import com.monkopedia.sdbus.createSystemBusConnection
import dev.bluefalcon.core.BluetoothPeripheral
import kotlinx.coroutines.delay

/**
 * Reads `org.bluez.Device1.Paired` straight off the system bus, out of band of
 * the engine.
 *
 * The engine deliberately exposes no bond-state accessor, and the hardware audit
 * on issue #22 showed that asserting on a successful encrypted read is *not* an
 * assertion about bonding: BlueZ elevates security on the read and the engine's
 * `NoInputNoOutputAgent` approves it, so the read succeeds whether or not
 * `createBond` did anything. The only way to observe the bond itself is to ask
 * BlueZ directly, before any encrypted traffic happens.
 *
 * Uses a synchronous-only proxy (`runEventLoopThread = false`) on its own
 * connection, so it cannot perturb the engine's connection or its event loop.
 *
 * **The connection must be created with [createSystemBusConnection].** The
 * no-connection `createProxy(destination, path)` overload calls
 * `createBusConnection()`, which opens the *session* bus in a user context —
 * `org.bluez` is not there, every read fails, and if those failures are mapped
 * to "not paired" the result is a test that reports a bonding failure when the
 * instrument is simply pointed at the wrong bus. That happened on the first
 * measurement pass of this spike; it cost ten runs.
 */
object BondState {
    private val BLUEZ = ServiceName("org.bluez")

    private val connection: Connection by lazy { createSystemBusConnection() }

    private fun proxyFor(peripheral: BluetoothPeripheral): Device1Proxy {
        val path = (peripheral as? SdbusPeripheral)?.objectPath
            ?: error("BondState needs an SdbusPeripheral, got ${peripheral::class}")
        return Device1Proxy(createProxy(connection, BLUEZ, path, runEventLoopThread = false))
    }

    /**
     * `Device1.Paired`, or null when the device object does not exist on the bus
     * at all — which is what `Adapter1.RemoveDevice` leaves behind until the next
     * discovery re-creates it. Null and false both mean "not bonded", but they
     * are distinguishable, which matters when diagnosing a run.
     *
     * Anything else — a wrong bus, a broken proxy, a permissions problem —
     * **rethrows**. An instrument that reports "not paired" when it actually
     * failed to look is worse than the vacuous test this spike is trying to
     * replace.
     */
    fun pairedOrNull(peripheral: BluetoothPeripheral): Boolean? = try {
        proxyFor(peripheral).pairedProperty.getOrNull()
    } catch (e: SdbusException) {
        val name = e.name
        val gone = "UnknownObject" in name || "UnknownInterface" in name ||
            "ServiceUnknown" in name || "DoesNotExist" in name
        if (gone) null else throw e
    }

    /**
     * Polls `Device1.Paired` until it is true or [timeoutMs] elapses. BlueZ's
     * `Pair()` returns once pairing has completed, but the property update
     * arrives over `PropertiesChanged`, so a bare read immediately after can
     * race. The poll is bounded and short: it must not be so generous that it
     * masks a `createBond` that never bonded.
     */
    suspend fun awaitPaired(peripheral: BluetoothPeripheral, timeoutMs: Long): Boolean {
        var waited = 0L
        while (true) {
            if (pairedOrNull(peripheral) == true) return true
            if (waited >= timeoutMs) return false
            delay(250)
            waited += 250
        }
    }
}
