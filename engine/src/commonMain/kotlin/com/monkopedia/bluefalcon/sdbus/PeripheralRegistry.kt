package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.ObjectPath
import dev.bluefalcon.core.BluetoothPeripheral
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The engine's discovered peripherals: every instance it has created, keyed by
 * BlueZ object path, and the subset published on [peripherals].
 *
 * The scan loop runs one collector per device path on Dispatchers.Default, so
 * these are written in parallel. Both are swapped atomically: a plain
 * read-modify-write on the StateFlow loses devices when two collectors publish
 * at once, and [getOrCreate] must hand every caller the same instance for a
 * path, since callers hold on to it and connection state is keyed on it.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PeripheralRegistry {
    private val known = AtomicReference<Map<ObjectPath, SdbusPeripheral>>(emptyMap())

    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()

    operator fun get(path: ObjectPath): SdbusPeripheral? = known.load()[path]

    /** The peripheral for [path], creating it on first sight. */
    fun getOrCreate(path: ObjectPath): SdbusPeripheral {
        var created: SdbusPeripheral? = null
        while (true) {
            val current = known.load()
            current[path]?.let { return it }
            val peripheral = created ?: SdbusPeripheral(path).also { created = it }
            if (known.compareAndSet(current, current + (path to peripheral))) return peripheral
        }
    }

    /** Adds [peripheral] to [peripherals]. */
    fun publish(peripheral: SdbusPeripheral) {
        _peripherals.update { it + peripheral }
    }

    fun clear() {
        _peripherals.value = emptySet()
        known.store(emptyMap())
    }
}
