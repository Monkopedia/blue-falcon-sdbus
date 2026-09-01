package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.ObjectPath
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.Uuid

class SdbusService internal constructor(
    val objectPath: ObjectPath,
    override val uuid: Uuid,
) : BluetoothService {
    override val name: String? get() = uuid.toString()

    private val _characteristics = mutableListOf<SdbusCharacteristic>()
    internal val characteristicsInternal: List<SdbusCharacteristic> get() = _characteristics

    override val characteristics: List<BluetoothCharacteristic> get() = _characteristics.toList()

    /**
     * Adds [characteristic], ignoring a repeat of one already held. Identity is
     * the D-Bus object path, not the UUID: GATT permits sibling characteristics
     * that share a UUID, and BlueZ exposes each at its own path.
     *
     * Because the path is authoritative, a repeat at a path already held is
     * dropped **whatever its UUID** — so if a path were ever re-enumerated with
     * a different UUID, the first one seen wins and the later value is
     * discarded. BlueZ gives one UUID per path, so that input does not arise
     * in practice; it is stated because the choice is made here silently and
     * a reader keying on UUID would expect the opposite.
     */
    internal fun addCharacteristic(characteristic: SdbusCharacteristic) {
        if (_characteristics.none { it.objectPath == characteristic.objectPath }) {
            _characteristics.add(characteristic)
        }
    }
}
