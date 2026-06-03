package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.ObjectPath
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class SdbusCharacteristic internal constructor(
    val objectPath: ObjectPath,
    override val uuid: Uuid,
    val serviceObjectPath: ObjectPath,
) : BluetoothCharacteristic {
    override val name: String? get() = uuid.toString()

    private val _valueFlow = MutableStateFlow<ByteArray?>(null)

    /**
     * Observes the characteristic value as a [StateFlow]. Updated by reads
     * and, while [isNotifying] is true, by notifications/indications.
     *
     * Exposed on the concrete [SdbusCharacteristic] because the core
     * [BluetoothCharacteristic] interface has no reactive surface;
     * consumers who want push-based updates can cast to this class.
     */
    val valueFlow: StateFlow<ByteArray?> = _valueFlow.asStateFlow()

    internal var _value: ByteArray?
        get() = _valueFlow.value
        set(v) { _valueFlow.value = v }

    override val value: ByteArray? get() = _valueFlow.value

    private val _notifications = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The core [BluetoothCharacteristic] notification stream. Emits each
     * notification/indication value while [isNotifying]; unlike [valueFlow] it
     * carries only push updates, not values fetched by explicit reads.
     */
    override val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    /** Records a pushed value: updates [valueFlow] and emits on [notifications]. */
    internal fun emitNotification(value: ByteArray) {
        _valueFlow.value = value
        _notifications.tryEmit(value)
    }

    private val _descriptors = mutableListOf<SdbusDescriptor>()
    override val descriptors: List<BluetoothCharacteristicDescriptor> get() = _descriptors.toList()

    internal var _isNotifying: Boolean = false
    internal var _notifyJob: Job? = null
    override val isNotifying: Boolean get() = _isNotifying

    private var _service: SdbusService? = null
    override val service: BluetoothService? get() = _service

    internal fun setService(service: SdbusService) { _service = service }

    internal fun addDescriptor(descriptor: SdbusDescriptor) {
        if (_descriptors.none { it.uuid == descriptor.uuid }) {
            _descriptors.add(descriptor)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SdbusCharacteristic && objectPath == other.objectPath

    override fun hashCode(): Int = objectPath.hashCode()
}

class SdbusDescriptor internal constructor(
    val objectPath: ObjectPath,
    override val uuid: Uuid,
    val characteristicObjectPath: ObjectPath,
) : BluetoothCharacteristicDescriptor {
    internal var _value: ByteArray? = null
    override val value: ByteArray? get() = _value

    private var _characteristic: SdbusCharacteristic? = null
    override val characteristic: BluetoothCharacteristic? get() = _characteristic

    internal fun setCharacteristic(characteristic: SdbusCharacteristic) {
        _characteristic = characteristic
    }

    override fun equals(other: Any?): Boolean =
        other is SdbusDescriptor && objectPath == other.objectPath

    override fun hashCode(): Int = objectPath.hashCode()
}
