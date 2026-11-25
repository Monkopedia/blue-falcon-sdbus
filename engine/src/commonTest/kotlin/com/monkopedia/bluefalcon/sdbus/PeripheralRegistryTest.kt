package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.ObjectPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [PeripheralRegistry] is written by the scan loop's per-device collectors in
 * parallel. These drive it from Dispatchers.Default the same way: no D-Bus.
 */
class PeripheralRegistryTest {

    private fun path(i: Int) = ObjectPath("/org/bluez/hci0/dev_AA_BB_CC_DD_EE_${i.toString(16).padStart(2, '0')}")

    @Test
    fun concurrentPublishesAreNotLost() = runTest {
        repeat(ROUNDS) { round ->
            val registry = PeripheralRegistry()
            withContext(Dispatchers.Default) {
                coroutineScope {
                    (0 until DEVICES).map { i ->
                        async { registry.publish(registry.getOrCreate(path(i))) }
                    }.awaitAll()
                }
            }
            assertEquals(DEVICES, registry.peripherals.value.size, "round $round lost a publish")
        }
    }

    @Test
    fun concurrentGetOrCreateYieldsOneInstancePerPath() = runTest {
        repeat(ROUNDS) { round ->
            val registry = PeripheralRegistry()
            val seen = withContext(Dispatchers.Default) {
                coroutineScope {
                    (0 until DEVICES * CALLERS_PER_DEVICE).map { n ->
                        async { registry.getOrCreate(path(n % DEVICES)) }
                    }.awaitAll()
                }
            }
            for (i in 0 until DEVICES) {
                val instance = registry[path(i)]
                seen.filter { it.objectPath == path(i) }.forEach {
                    assertSame(instance, it, "round $round: two instances for ${path(i)}")
                }
            }
        }
    }

    @Test
    fun clearForgetsKnownAndPublished() {
        val registry = PeripheralRegistry()
        val p = registry.getOrCreate(path(1))
        registry.publish(p)
        registry.clear()
        assertTrue(registry.peripherals.value.isEmpty())
        assertNull(registry[path(1)])
        // A device seen again after a clear gets a fresh instance.
        assertTrue(registry.getOrCreate(path(1)) !== p)
    }

    private companion object {
        const val ROUNDS = 200
        const val DEVICES = 16
        const val CALLERS_PER_DEVICE = 4
    }
}
