package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.ObjectPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit tests for [SdbusPeripheral]'s pure derivation of address / name / uuid
 * from the BlueZ D-Bus object path, plus identity (equals / hashCode keyed on
 * the path). No D-Bus, so these run on every target without hardware.
 */
class SdbusPeripheralTest {

    private fun peripheral(path: String) = SdbusPeripheral(ObjectPath(path))

    @Test
    fun derivesMacAddressFromObjectPath() {
        val p = peripheral("/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF")
        assertEquals("AA:BB:CC:DD:EE:FF", p.address, "underscores become colons")
        assertEquals("AA:BB:CC:DD:EE:FF", p.uuid, "uuid mirrors the derived address")
    }

    @Test
    fun nameFallsBackToAddressUntilSet() {
        val p = peripheral("/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF")
        assertEquals("AA:BB:CC:DD:EE:FF", p.name, "name defaults to the address")
        p._name = "BF-Test"
        assertEquals("BF-Test", p.name, "an advertised name takes precedence")
    }

    @Test
    fun fallsBackToFullPathWhenNoDeviceSegment() {
        // A path with no /dev_ segment leaves the derived address empty; the
        // .ifEmpty fallback keeps the full path rather than an empty string.
        val path = "/org/bluez/hci0"
        assertEquals(path, peripheral(path).address)
    }

    @Test
    fun identityIsKeyedOnObjectPath() {
        val a = peripheral("/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF")
        val b = peripheral("/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF")
        val c = peripheral("/org/bluez/hci0/dev_11_22_33_44_55_66")
        assertEquals(a, b, "same object path => equal")
        assertEquals(a.hashCode(), b.hashCode(), "equal peripherals share a hashCode")
        assertNotEquals(a, c, "different object path => not equal")
    }
}
