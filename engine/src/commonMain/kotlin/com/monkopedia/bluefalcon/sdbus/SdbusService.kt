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
     * dropped **whatever its UUID** — so a second object at a held path would
     * keep the first UUID seen and discard the later one.
     *
     * That input cannot reach this guard as the engine is written, and the
     * reason is here rather than in BlueZ's behaviour:
     * `SdbusEngine.resolveGattObjects` builds from
     * `getManagedObjects(): Map<ObjectPath, …>`, so each path appears **once**
     * per pass by map semantics; its accumulator is a function-local, so
     * nothing survives a pass; and `SdbusPeripheral.setServices` clears and
     * replaces, so a re-enumeration yields the **new** value rather than
     * being compared against the old.
     *
     * Stated because the choice is invisible at the call site and points the
     * opposite way from what a reader keying on UUID would assume — and
     * because it stops being unreachable the moment any of that per-pass state
     * is made to persist — the accumulator, or the [SdbusService] instances
     * themselves being reused across passes rather than rebuilt.
     */
    internal fun addCharacteristic(characteristic: SdbusCharacteristic) {
        if (_characteristics.none { it.objectPath == characteristic.objectPath }) {
            _characteristics.add(characteristic)
        }
    }
}
