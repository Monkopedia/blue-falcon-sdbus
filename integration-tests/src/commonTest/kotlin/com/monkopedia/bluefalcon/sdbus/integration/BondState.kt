package com.monkopedia.bluefalcon.sdbus.integration

import com.monkopedia.bluefalcon.sdbus.SdbusPeripheral
import com.monkopedia.bluefalcon.sdbus.bluez.Device1Proxy
import com.monkopedia.sdbus.ServiceName
import com.monkopedia.sdbus.createProxy
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
 * Uses a light-weight synchronous-only proxy on its own connection (no event
 * loop thread) so it cannot perturb the engine's connection or its event loop.
 */
object BondState {
    private val BLUEZ = ServiceName("org.bluez")

    private fun proxyFor(peripheral: BluetoothPeripheral): Device1Proxy {
        val path = (peripheral as? SdbusPeripheral)?.objectPath
            ?: error("BondState needs an SdbusPeripheral, got ${peripheral::class}")
        return Device1Proxy(createProxy(BLUEZ, path, runEventLoopThread = false))
    }

    /**
     * `Device1.Paired`, or null when the device object does not exist on the bus
     * at all (which is what `Adapter1.RemoveDevice` leaves behind until the next
     * discovery re-creates it). Null and false are both "not bonded", but they
     * are distinguishable, which matters when diagnosing a failed run.
     */
    fun pairedOrNull(peripheral: BluetoothPeripheral): Boolean? = try {
        proxyFor(peripheral).pairedProperty.getOrNull()
    } catch (_: Exception) {
        null
    }

    /**
     * Polls `Device1.Paired` until it is true or [timeoutMs] elapses. BlueZ's
     * `Pair()` returns once pairing has completed, but the property update
     * arrives over `PropertiesChanged`, so a bare read immediately after can
     * race. The poll is bounded and short: it must not be so generous that it
     * masks a `createBond` that never bonded.
     */
    suspend fun awaitPaired(peripheral: BluetoothPeripheral, timeoutMs: Long): Boolean {
        val proxy = proxyFor(peripheral)
        var waited = 0L
        while (true) {
            val paired = try {
                proxy.pairedProperty.getOrNull()
            } catch (_: Exception) {
                null
            }
            if (paired == true) return true
            if (waited >= timeoutMs) return false
            delay(250)
            waited += 250
        }
    }
}
