package com.monkopedia.bluefalcon.sdbus.integration

import com.monkopedia.bluefalcon.sdbus.SdbusPeripheral
import com.monkopedia.sdbus.Connection
import com.monkopedia.sdbus.InterfaceName
import com.monkopedia.sdbus.PropertyName
import com.monkopedia.sdbus.SdbusException
import com.monkopedia.sdbus.ServiceName
import com.monkopedia.sdbus.createProxy
import com.monkopedia.sdbus.createSystemBusConnection
import com.monkopedia.sdbus.getProperty
import dev.bluefalcon.core.BluetoothPeripheral
import kotlinx.coroutines.delay

/**
 * Reads `org.bluez.Device1.Paired` straight off the system bus, out of band
 * of the engine.
 *
 * This exists because a successful encrypted read is **not** evidence of
 * bonding. BlueZ elevates security on its own when Char G answers
 * insufficient-authentication, and the engine's `NoInputNoOutputAgent`
 * approves that pairing silently — so the read succeeds whether or not
 * `createBond` did anything (hardware audit on issue #22). The only way to
 * observe the bond `createBond` made is to ask BlueZ, before any encrypted
 * traffic happens. `SdbusEngine` exposes no bond-state accessor, hence the
 * direct D-Bus read.
 *
 * It goes through the generic `org.freedesktop.DBus.Properties.Get` rather
 * than the engine's generated `Device1Proxy`, so the test depends on BlueZ's
 * documented interface and not on the codegen output of `engine/src/dbus`.
 *
 * Uses its own system-bus connection with no event loop and synchronous-only
 * proxies, so it cannot perturb the engine's connection or event loop.
 * **It must be the system bus**: the connection-less `createProxy` overload
 * opens the session bus in a user context, where `org.bluez` does not exist,
 * and every read would fail. [release] the instance when done.
 */
internal class BondState {
    private val connection: Connection = createSystemBusConnection()

    /**
     * `Device1.Paired`, or null when the device object is not on the bus at
     * all (what `Adapter1.RemoveDevice` leaves until discovery re-creates it).
     *
     * Any other failure — wrong bus, BlueZ not running, permissions —
     * **rethrows**. An instrument that reports "not paired" when it failed to
     * look would turn its own breakage into a false bonding failure.
     */
    fun pairedOrNull(peripheral: BluetoothPeripheral): Boolean? {
        val path = (peripheral as? SdbusPeripheral)?.objectPath
            ?: error("BondState needs an SdbusPeripheral, got ${peripheral::class}")
        val proxy = createProxy(connection, BLUEZ, path, runEventLoopThread = false)
        return try {
            proxy.getProperty<Boolean>(DEVICE1, PAIRED)
        } catch (e: SdbusException) {
            if (OBJECT_GONE.any { it in e.name }) null else throw e
        } finally {
            proxy.release()
        }
    }

    /**
     * Polls `Device1.Paired` until it is true or [timeoutMs] elapses. `Pair()`
     * returns once pairing completes, but the property can lag it slightly,
     * so a single read immediately after can race. Kept short: it must not be
     * long enough to hide a `createBond` that never bonded.
     */
    suspend fun awaitPaired(peripheral: BluetoothPeripheral, timeoutMs: Long): Boolean {
        var waited = 0L
        while (true) {
            if (pairedOrNull(peripheral) == true) return true
            if (waited >= timeoutMs) return false
            delay(POLL_MS)
            waited += POLL_MS
        }
    }

    fun release() {
        connection.release()
    }

    private companion object {
        val BLUEZ = ServiceName("org.bluez")
        val DEVICE1 = InterfaceName("org.bluez.Device1")
        val PAIRED = PropertyName("Paired")
        val OBJECT_GONE = listOf("UnknownObject", "UnknownInterface", "DoesNotExist")
        const val POLL_MS = 250L
    }
}
