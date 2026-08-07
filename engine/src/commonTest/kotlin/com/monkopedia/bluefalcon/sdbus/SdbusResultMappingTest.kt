package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.SdbusException
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.NotificationSubscriptionResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the pure BlueZ-error → typed-result mapping introduced for
 * blue-falcon-core 3.7.0.
 *
 * These tests prove the *translation* only. They cannot prove that BlueZ raises
 * a given error in a given situation — that needs an adapter and a peripheral.
 */
class SdbusResultMappingTest {

    private fun dbus(name: String) = SdbusException(name, "boom")

    @Test
    fun writeNotSupportedMapsToUnsupported() {
        assertEquals(
            CharacteristicWriteResult.Unsupported,
            mapWriteFailure(dbus(BluezErrors.NOT_SUPPORTED), maximumLength = 20),
        )
    }

    @Test
    fun writeNotPermittedMapsToUnsupported() {
        assertEquals(
            CharacteristicWriteResult.Unsupported,
            mapWriteFailure(dbus(BluezErrors.NOT_PERMITTED), maximumLength = null),
        )
    }

    @Test
    fun writeNotConnectedMapsToDisconnected() {
        assertEquals(
            CharacteristicWriteResult.Disconnected,
            mapWriteFailure(dbus(BluezErrors.NOT_CONNECTED), maximumLength = 20),
        )
    }

    @Test
    fun writeUnknownObjectMapsToDisconnected() {
        assertEquals(
            CharacteristicWriteResult.Disconnected,
            mapWriteFailure(dbus(BluezErrors.UNKNOWN_OBJECT), maximumLength = null),
        )
    }

    @Test
    fun writeInvalidLengthMapsToPayloadTooLargeOnlyWhenTheMaximumIsKnown() {
        assertEquals(
            CharacteristicWriteResult.PayloadTooLarge(20),
            mapWriteFailure(dbus(BluezErrors.INVALID_VALUE_LENGTH), maximumLength = 20),
        )
        // No cached MTU: inventing a maximum would be worse than reporting Failed.
        val unknown = mapWriteFailure(dbus(BluezErrors.INVALID_VALUE_LENGTH), maximumLength = null)
        assertEquals(true, unknown is CharacteristicWriteResult.Failed)
    }

    @Test
    fun writeInProgressMapsToFailedCarryingTheCause() {
        val cause = dbus(BluezErrors.IN_PROGRESS)
        assertEquals(CharacteristicWriteResult.Failed(cause), mapWriteFailure(cause, null))
    }

    @Test
    fun nonDbusFailureMapsToFailed() {
        val cause = IllegalStateException("not a bus error")
        assertEquals(CharacteristicWriteResult.Failed(cause), mapWriteFailure(cause, 20))
        assertEquals(
            NotificationSubscriptionResult.Failed(cause),
            mapNotificationFailure(cause),
        )
    }

    @Test
    fun notifyNotSupportedMapsToUnsupported() {
        assertEquals(
            NotificationSubscriptionResult.Unsupported,
            mapNotificationFailure(dbus(BluezErrors.NOT_SUPPORTED)),
        )
    }

    @Test
    fun notifyNotConnectedMapsToDisconnected() {
        assertEquals(
            NotificationSubscriptionResult.Disconnected,
            mapNotificationFailure(dbus(BluezErrors.NOT_CONNECTED)),
        )
    }

    @Test
    fun writeTypesMapToTheirBluezFlagAndOption() {
        assertEquals(FLAG_WRITE, CharacteristicWriteType.WithResponse.bluezFlag)
        assertEquals(
            FLAG_WRITE_WITHOUT_RESPONSE,
            CharacteristicWriteType.WithoutResponse.bluezFlag,
        )
        assertEquals("request", CharacteristicWriteType.WithResponse.bluezWriteOption)
        assertEquals("command", CharacteristicWriteType.WithoutResponse.bluezWriteOption)
    }
}
