package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.ObjectPath
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService

class SdbusPeripheral internal constructor(
    val objectPath: ObjectPath,
) : BluetoothPeripheral {

    // MAC address derived from /org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF
    val address: String = run {
        val devPart = objectPath.value.substringAfterLast("/dev_", "")
        devPart.replace("_", ":").ifEmpty { objectPath.value }
    }

    internal var _name: String? = null
    override val name: String? get() = _name ?: address

    override val uuid: String get() = address

    override var rssi: Float? = null
    override var mtuSize: Int? = null

    internal var _manufacturerData: Map<Int, ByteArray> = emptyMap()

    /**
     * Manufacturer-specific advertisement data, keyed by company ID.
     *
     * Sourced from BlueZ's `org.bluez.Device1.ManufacturerData` (`a{qv}`), which
     * BlueZ populates from the advertisement and then *retains* on the cached
     * device object. Unlike Android/iOS — where this is a per-scan-result value —
     * a value here may therefore be left over from an earlier advertisement.
     */
    override val manufacturerData: Map<Int, ByteArray> get() = _manufacturerData

    private val _services = mutableListOf<SdbusService>()
    override val services: List<BluetoothService> get() = _services.toList()

    override val characteristics: List<BluetoothCharacteristic>
        get() = _services.flatMap { it.characteristicsInternal }

    internal fun setServices(services: List<SdbusService>) {
        _services.clear()
        _services.addAll(services)
    }

    override fun equals(other: Any?): Boolean =
        other is SdbusPeripheral && objectPath == other.objectPath

    override fun hashCode(): Int = objectPath.hashCode()
    override fun toString(): String = address
}
