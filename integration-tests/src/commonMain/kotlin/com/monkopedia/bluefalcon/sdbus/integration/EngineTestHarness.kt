package com.monkopedia.bluefalcon.sdbus.integration

import com.monkopedia.bluefalcon.sdbus.SdbusCharacteristic
import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

/**
 * Thin test helper around [BlueFalconEngine]. The 3.0 engine is already
 * suspend-native, so most legacy harness deferreds collapse to direct
 * calls; we only need helpers for flow-driven discovery and notification
 * collection.
 */
class EngineTestHarness(
    val engine: BlueFalconEngine,
    private val defaultTimeoutMs: Long = 15_000L,
) {
    suspend fun scanForDevice(
        filters: List<ServiceFilter> = emptyList(),
        timeoutMs: Long = defaultTimeoutMs,
        predicate: (BluetoothPeripheral) -> Boolean,
    ): BluetoothPeripheral = withTimeout(timeoutMs) {
        engine.clearPeripherals()
        engine.scan(filters)
        try {
            engine.peripherals
                .filter { set -> set.any(predicate) }
                .first()
                .first(predicate)
        } finally {
            engine.stopScanning()
        }
    }

    suspend fun collectNotifications(
        characteristic: BluetoothCharacteristic,
        count: Int,
        timeoutMs: Long = defaultTimeoutMs,
    ): List<ByteArray> = withTimeout(timeoutMs) {
        val char = characteristic as? SdbusCharacteristic
            ?: error("Only SdbusCharacteristic exposes a value flow")
        char.valueFlow
            .filter { it != null }
            .take(count)
            .toList()
            .map { it!!.copyOf() }
    }

    suspend fun awaitCharacteristicValue(
        characteristic: BluetoothCharacteristic,
        timeoutMs: Long = defaultTimeoutMs,
    ): ByteArray = withTimeout(timeoutMs) {
        val char = characteristic as? SdbusCharacteristic
            ?: error("Only SdbusCharacteristic exposes a value flow")
        char.valueFlow.filter { it != null }.first()!!.copyOf()
    }
}

/** Convenience UUID parser for test constants written as lowercase hex strings. */
fun uuidFrom(string: String): Uuid = Uuid.parse(string)
