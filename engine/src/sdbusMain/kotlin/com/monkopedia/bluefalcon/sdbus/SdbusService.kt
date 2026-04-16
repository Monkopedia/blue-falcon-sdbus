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

    internal fun addCharacteristic(characteristic: SdbusCharacteristic) {
        if (_characteristics.none { it.uuid == characteristic.uuid }) {
            _characteristics.add(characteristic)
        }
    }
}
