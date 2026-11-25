package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.SdbusException
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.NotificationSubscriptionResult

/**
 * Translation from BlueZ D-Bus error names to blue-falcon-core 3.7.0's typed
 * result hierarchies.
 *
 * Kept separate from [SdbusEngine] and free of D-Bus I/O so the mapping itself
 * is unit-testable without an adapter or a peripheral. **Only the mapping is
 * covered by tests — which error BlueZ actually raises in a given situation is
 * not verifiable without hardware.**
 *
 * Error names are taken from BlueZ's `src/error.c` / `src/gatt-client.c`.
 */
internal object BluezErrors {
    const val NOT_SUPPORTED = "org.bluez.Error.NotSupported"
    const val NOT_PERMITTED = "org.bluez.Error.NotPermitted"
    const val NOT_AUTHORIZED = "org.bluez.Error.NotAuthorized"
    const val NOT_CONNECTED = "org.bluez.Error.NotConnected"
    const val IN_PROGRESS = "org.bluez.Error.InProgress"
    const val INVALID_VALUE_LENGTH = "org.bluez.Error.InvalidValueLength"

    /** BlueZ removes the object when the link drops, so the call lands nowhere. */
    const val UNKNOWN_OBJECT = "org.freedesktop.DBus.Error.UnknownObject"
    const val UNKNOWN_METHOD = "org.freedesktop.DBus.Error.UnknownMethod"
    const val SERVICE_UNKNOWN = "org.freedesktop.DBus.Error.ServiceUnknown"
}

/** The D-Bus error name, if this failure came from D-Bus at all. */
internal val Throwable.dbusErrorName: String?
    get() = (this as? SdbusException)?.name

/**
 * Maps a failed `GattCharacteristic1.WriteValue` to a [CharacteristicWriteResult].
 *
 * [maximumLength] is the engine's best knowledge of the write ceiling for this
 * peripheral; it is only used to give [CharacteristicWriteResult.PayloadTooLarge]
 * a number. When BlueZ reports a length error and the engine has no MTU cached,
 * the honest answer is [CharacteristicWriteResult.Failed] rather than a made-up
 * maximum.
 *
 * Note there is deliberately no path to [CharacteristicWriteResult.Backpressured]:
 * BlueZ's D-Bus `WriteValue` has no flow-control reply, so the engine can never
 * observe backpressure. See `SdbusEngine.characteristicWriteReady`.
 */
internal fun mapWriteFailure(cause: Throwable, maximumLength: Int?): CharacteristicWriteResult =
    when (cause.dbusErrorName) {
        BluezErrors.NOT_SUPPORTED,
        BluezErrors.NOT_PERMITTED,
        BluezErrors.NOT_AUTHORIZED,
        BluezErrors.UNKNOWN_METHOD,
        -> CharacteristicWriteResult.Unsupported

        BluezErrors.NOT_CONNECTED,
        BluezErrors.UNKNOWN_OBJECT,
        BluezErrors.SERVICE_UNKNOWN,
        -> CharacteristicWriteResult.Disconnected

        BluezErrors.INVALID_VALUE_LENGTH ->
            if (maximumLength != null) {
                CharacteristicWriteResult.PayloadTooLarge(maximumLength)
            } else {
                CharacteristicWriteResult.Failed(cause)
            }

        else -> CharacteristicWriteResult.Failed(cause)
    }

/**
 * Maps a failed `StartNotify`/`StopNotify` to a [NotificationSubscriptionResult].
 *
 * BlueZ raises `org.bluez.Error.NotSupported` when a characteristic declares
 * neither `notify` nor `indicate`, which is the contract's
 * [NotificationSubscriptionResult.Unsupported].
 */
internal fun mapNotificationFailure(cause: Throwable): NotificationSubscriptionResult =
    when (cause.dbusErrorName) {
        BluezErrors.NOT_SUPPORTED,
        BluezErrors.NOT_PERMITTED,
        BluezErrors.UNKNOWN_METHOD,
        -> NotificationSubscriptionResult.Unsupported

        BluezErrors.NOT_CONNECTED,
        BluezErrors.UNKNOWN_OBJECT,
        BluezErrors.SERVICE_UNKNOWN,
        -> NotificationSubscriptionResult.Disconnected

        else -> NotificationSubscriptionResult.Failed(cause)
    }

/** BlueZ `GattCharacteristic1.Flags` value for a write-with-response characteristic. */
internal const val FLAG_WRITE = "write"

/** BlueZ `GattCharacteristic1.Flags` value for a write-without-response characteristic. */
internal const val FLAG_WRITE_WITHOUT_RESPONSE = "write-without-response"

/**
 * The ATT protocol overhead on both `ATT_WRITE_REQ` and `ATT_WRITE_CMD`:
 * one opcode byte plus a two-byte attribute handle.
 */
internal const val ATT_WRITE_HEADER_BYTES = 3

/** The GATT ceiling on a single attribute value, per Core spec Vol 3 Part F. */
internal const val GATT_MAX_ATTRIBUTE_LENGTH = 512

/** The `GattCharacteristic1.Flags` entry a characteristic must declare for this write type. */
internal val CharacteristicWriteType.bluezFlag: String
    get() = when (this) {
        CharacteristicWriteType.WithResponse -> FLAG_WRITE
        CharacteristicWriteType.WithoutResponse -> FLAG_WRITE_WITHOUT_RESPONSE
    }

/** The `type` option BlueZ's `WriteValue` expects for this write type. */
internal val CharacteristicWriteType.bluezWriteOption: String
    get() = when (this) {
        CharacteristicWriteType.WithResponse -> "request"
        CharacteristicWriteType.WithoutResponse -> "command"
    }
