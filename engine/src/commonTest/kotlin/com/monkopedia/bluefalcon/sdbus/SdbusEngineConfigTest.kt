package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.SdbusException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [SdbusEngineConfig]'s default connect-retry policy, exercised
 * through the public [SdbusEngineConfig.onConnectDelay] default. Pure
 * `(attempt, error) -> Duration?` logic, no D-Bus, so these run on every target
 * without hardware.
 *
 * The default deliberately retries only the transient BlueZ
 * `org.bluez.Error.Failed: le-connection-abort-by-local` race — up to 3
 * retries with linear (1s / 2s / 3s) backoff, so 4 `Connect()` calls in total
 * — and gives up on anything else.
 */
class SdbusEngineConfigTest {

    private val retry = SdbusEngineConfig().onConnectDelay

    private fun abortByLocal() =
        SdbusException("org.bluez.Error.Failed", "le-connection-abort-by-local")

    @Test
    fun retriesTransientAbortWithLinearBackoff() = runTest {
        val err = abortByLocal()
        assertEquals(1.seconds, retry(1, err), "first retry waits 1s")
        assertEquals(2.seconds, retry(2, err), "second retry waits 2s")
        assertEquals(3.seconds, retry(3, err), "third retry waits 3s")
    }

    @Test
    fun givesUpAfterThreeRetries() = runTest {
        assertNull(retry(4, abortByLocal()), "there is no fourth retry for the transient race")
    }

    @Test
    fun doesNotRetryOtherSdbusErrors() = runTest {
        val other = SdbusException("org.bluez.Error.Failed", "br-connection-canceled")
        assertNull(retry(1, other), "only le-connection-abort-by-local is treated as transient")
    }

    @Test
    fun doesNotRetryNonSdbusException() = runTest {
        // Same message text, but not an SdbusException — must not be retried:
        // the transient race is only recognised on a real BlueZ D-Bus error.
        assertNull(
            retry(1, RuntimeException("le-connection-abort-by-local")),
            "the transient race is only recognised on SdbusException",
        )
    }

    @Test
    fun doesNotRetryErrorWithNoMessage() = runTest {
        assertNull(retry(1, Throwable()), "a message-less error is not retried")
    }
}
