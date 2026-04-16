package com.monkopedia.bluefalcon.sdbus

import dev.bluefalcon.core.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for [SdbusEngine]. Populate via the [SdbusEngine] DSL
 * factory:
 *
 * ```kotlin
 * val engine = SdbusEngine {
 *     logger = PrintLnLogger
 *     adapterName = "hci1"
 *     onConnectDelay = { attempt, error ->
 *         // custom retry policy
 *     }
 * }
 * ```
 *
 * All fields have sensible defaults; a bare `SdbusEngine {}` is valid.
 */
class SdbusEngineConfig {
    /**
     * Optional logger; errors and debug info from the engine are routed
     * here. Defaults to `null` (no logging).
     */
    var logger: Logger? = null

    /**
     * BlueZ adapter to drive, e.g. "hci0". Defaults to "hci0".
     */
    var adapterName: String = "hci0"

    /**
     * When true, services are resolved automatically once BlueZ reports
     * `ServicesResolved=true` after a connection. When false, callers
     * must invoke [SdbusEngine.discoverServices] themselves.
     * Defaults to true.
     */
    var autoDiscoverAllServicesAndCharacteristics: Boolean = true

    /**
     * Retry policy for `connect()` failures. Called after each failed
     * attempt; return the [Duration] to wait before retrying, or `null`
     * to give up (the engine then rethrows the original error).
     *
     * `attempt` is 1 after the first failure, 2 after the second, etc.
     *
     * The default handles the one BlueZ failure mode we can confidently
     * identify as transient:
     * `org.bluez.Error.Failed: le-connection-abort-by-local` — BlueZ
     * rejects the first Connect() after a recent disconnect while the
     * kernel/controller is still releasing the previous link. Retries up
     * to 3 times with linear backoff (1s, 2s, 3s). Any other failure
     * propagates immediately.
     *
     * Override to implement deadline-bounded retry, jittered backoff,
     * circuit breaking, etc.:
     *
     * ```kotlin
     * onConnectDelay = { attempt, error ->
     *     if (attempt > 5) null
     *     else 200.milliseconds * (1 shl attempt)  // exponential
     * }
     * ```
     */
    var onConnectDelay: suspend (attempt: Int, error: Throwable) -> Duration? =
        ::defaultConnectRetry
}

private fun defaultConnectRetry(attempt: Int, error: Throwable): Duration? {
    val message = error.message ?: return null
    val isTransient = error is com.monkopedia.sdbus.Error &&
        "le-connection-abort-by-local" in message
    return if (isTransient && attempt <= 3) attempt.seconds else null
}
