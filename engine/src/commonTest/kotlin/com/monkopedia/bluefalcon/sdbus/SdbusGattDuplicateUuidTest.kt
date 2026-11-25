package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.ObjectPath
import dev.bluefalcon.core.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for issue #52: GATT permits sibling characteristics (and
 * sibling descriptors) that share a UUID — BlueZ distinguishes them by object
 * path. Identity for these objects is the object path, which is also what
 * [SdbusCharacteristic.equals] / [SdbusDescriptor.equals] use, so the
 * de-duplication guards must key on the path and never on the UUID.
 *
 * Pure state logic, so these run on jvm and native without D-Bus or hardware.
 * They cannot be covered by the integration suite: the BF-Test reference
 * peripheral's UUIDs are all distinct.
 */
class SdbusGattDuplicateUuidTest {

    private val servicePath = ObjectPath("/org/bluez/hci0/dev_AA/service0001")
    private val serviceUuid = Uuid.parse("0000180f-0000-1000-8000-00805f9b34fb")
    private val sharedCharUuid = Uuid.parse("00002a19-0000-1000-8000-00805f9b34fb")
    private val sharedDescUuid = Uuid.parse("00002904-0000-1000-8000-00805f9b34fb")

    private fun newService() = SdbusService(servicePath, serviceUuid)

    private fun newCharacteristic(path: String, uuid: Uuid = sharedCharUuid) =
        SdbusCharacteristic(ObjectPath(path), uuid, servicePath)

    @Test
    fun siblingCharacteristicsSharingAUuidAreBothRetained() {
        val service = newService()
        val first = newCharacteristic("${servicePath.value}/char0002")
        val second = newCharacteristic("${servicePath.value}/char0005")

        service.addCharacteristic(first)
        service.addCharacteristic(second)

        assertEquals(
            2,
            service.characteristics.size,
            "both duplicate-UUID siblings should be retained; got " +
                service.characteristicsInternal.map { it.objectPath.value },
        )
        assertEquals(
            listOf("${servicePath.value}/char0002", "${servicePath.value}/char0005"),
            service.characteristicsInternal.map { it.objectPath.value },
        )
    }

    @Test
    fun siblingDescriptorsSharingAUuidAreBothRetained() {
        val charPath = ObjectPath("${servicePath.value}/char0002")
        val characteristic = newCharacteristic(charPath.value)
        val first = SdbusDescriptor(
            ObjectPath("${charPath.value}/desc0003"),
            sharedDescUuid,
            charPath,
        )
        val second = SdbusDescriptor(
            ObjectPath("${charPath.value}/desc0004"),
            sharedDescUuid,
            charPath,
        )

        characteristic.addDescriptor(first)
        characteristic.addDescriptor(second)

        assertEquals(
            2,
            characteristic.descriptors.size,
            "both duplicate-UUID descriptors should be retained; got " +
                characteristic.descriptors.filterIsInstance<SdbusDescriptor>()
                    .map { it.objectPath.value },
        )
        assertEquals(
            listOf("${charPath.value}/desc0003", "${charPath.value}/desc0004"),
            characteristic.descriptors.filterIsInstance<SdbusDescriptor>()
                .map { it.objectPath.value },
        )
    }

    @Test
    fun theSameCharacteristicPathIsStillDeDuplicated() {
        val service = newService()
        val path = "${servicePath.value}/char0002"

        service.addCharacteristic(newCharacteristic(path))
        // A second, distinct instance at the same path is the same GATT object.
        service.addCharacteristic(
            newCharacteristic(path, Uuid.parse("00002a1a-0000-1000-8000-00805f9b34fb")),
        )

        assertEquals(1, service.characteristics.size, "one path must yield one characteristic")
        assertEquals(sharedCharUuid, service.characteristicsInternal.single().uuid)
    }

    @Test
    fun theSameDescriptorPathIsStillDeDuplicated() {
        val charPath = ObjectPath("${servicePath.value}/char0002")
        val characteristic = newCharacteristic(charPath.value)
        val descPath = ObjectPath("${charPath.value}/desc0003")

        characteristic.addDescriptor(SdbusDescriptor(descPath, sharedDescUuid, charPath))
        characteristic.addDescriptor(
            SdbusDescriptor(
                descPath,
                Uuid.parse("00002901-0000-1000-8000-00805f9b34fb"),
                charPath,
            ),
        )

        assertEquals(1, characteristic.descriptors.size, "one path must yield one descriptor")
        assertEquals(sharedDescUuid, characteristic.descriptors.single().uuid)
    }
}
