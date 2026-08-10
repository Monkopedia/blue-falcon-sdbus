package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.sdbus.SdbusException
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
     * When true (the default), the engine registers a NoInputNoOutput
     * ("Just Works") pairing agent with BlueZ at startup **and calls
     * `RequestDefaultAgent`**, which makes this process `bluetoothd`'s
     * *default* pairing agent — a host-wide role — and answers "yes" to
     * every request it is asked about.
     *
     * The default agent is the one BlueZ routes to whenever the pairing is
     * not being driven by a client that registered its own agent. A desktop
     * or `bluetoothctl` session that initiates a pairing itself still uses
     * its own agent — but **pairing requests initiated by a remote peer, and
     * service-authorization requests, always go to the host default**, which
     * is now ours, and are silently accepted. That is the case a desktop
     * pairing prompt exists to serve.
     *
     * Taking the role also sets the adapter's IO capability to
     * `NoInputNoOutput` for all pairing on that adapter, not just ours.
     *
     * [SdbusEngine.destroy] hands the role back: BlueZ keeps the default
     * agents in a stack and restores the previous holder on
     * `UnregisterAgent`, which also restores the IO capability.
     *
     * This is on by default because `createBond` needs an agent to answer
     * BlueZ's pairing prompts, and because it is the behaviour every
     * release so far has had.
     *
     * Set to false to skip registration entirely — no agent object is
     * published and `RequestDefaultAgent` is never called, so the host's
     * existing pairing agent keeps the role. With it off, pairing that
     * BlueZ needs an agent for (including `createBond` on a host with no
     * other agent registered) will fail rather than be auto-accepted.
     */
    var registerDefaultPairingAgent: Boolean = true

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
    val isTransient = error is SdbusException &&
        "le-connection-abort-by-local" in message
    return if (isTransient && attempt <= 3) attempt.seconds else null
}
