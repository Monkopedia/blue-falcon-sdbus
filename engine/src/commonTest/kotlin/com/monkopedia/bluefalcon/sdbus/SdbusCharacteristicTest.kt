package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.ObjectPath
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SdbusCharacteristic]'s reactive surface — the push-only
 * [SdbusCharacteristic.notifications] flow (Blue Falcon 3.4) versus the
 * read-reflecting [SdbusCharacteristic.valueFlow]. Pure flow/state logic, no
 * D-Bus, so these run on every target without hardware.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdbusCharacteristicTest {

    private fun newCharacteristic() = SdbusCharacteristic(
        objectPath = ObjectPath("/org/bluez/hci0/dev_AA/service0001/char0002"),
        uuid = Uuid.parse("0000bfa4-1000-2000-8000-00805f9b34fb"),
        serviceObjectPath = ObjectPath("/org/bluez/hci0/dev_AA/service0001"),
    )

    @Test
    fun pushUpdatesValueFlowAndEmitsOnNotifications() = runTest {
        val char = newCharacteristic()
        val received = mutableListOf<ByteArray>()
        // notifications has no replay, so subscribe before emitting.
        val job = backgroundScope.launch { char.notifications.collect { received += it } }
        runCurrent()

        val payload = byteArrayOf(0xBF.toByte(), 0x01, 0x02)
        char.emitNotification(payload)
        runCurrent()

        assertContentEquals(payload, char.value, "value should reflect the pushed notification")
        assertContentEquals(payload, char.valueFlow.value)
        assertEquals(1, received.size, "notifications should emit the pushed value once")
        assertContentEquals(payload, received.single())
        job.cancel()
    }

    @Test
    fun readUpdatesValueFlowButDoesNotEmitOnNotifications() = runTest {
        val char = newCharacteristic()
        val received = mutableListOf<ByteArray>()
        val job = backgroundScope.launch { char.notifications.collect { received += it } }
        runCurrent()

        // A read sets the value directly (as the engine's readCharacteristic
        // does), bypassing the push-only notifications stream.
        char._value = byteArrayOf(0x10, 0x20)
        runCurrent()

        assertContentEquals(byteArrayOf(0x10, 0x20), char.valueFlow.value)
        assertTrue(received.isEmpty(), "reads must not surface on the push-only notifications flow")
        job.cancel()
    }

    @Test
    fun valueIsNullBeforeAnyUpdate() {
        val char = newCharacteristic()
        assertEquals(null, char.value)
        assertEquals(null, char.valueFlow.value)
    }
}
