package com.monkopedia.bluefalcon.sdbus.integration

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Connect-retry policy for the integration harness — **deliberately different
 * from the engine's production default.**
 *
 * The production default in `SdbusEngineConfig` retries the transient BlueZ
 * `le-connection-abort-by-local` race up to [PRODUCTION_MAX_ATTEMPTS] times
 * with linear 1s/2s/3s backoff. That is right for an application. It is not
 * right for this suite, because BlueZ reports the *same* error when the
 * peripheral is simply absent — after burning its own ~48s connect timeout on
 * every attempt. The production policy therefore spends ~198s per test before
 * failing, roughly 50 minutes across the suite, in silence. A suite that takes
 * 50 minutes to tell you the device is switched off is not a usable failure
 * signal, which is the only reason to run it.
 *
 * **The lever is elapsed time, not attempt count.** Cutting attempts was tried
 * first and measurably broke the suite: `writeNoResponse` failed against real
 * hardware in 3.006s, having exhausted a one-retry budget on a race that the
 * production policy absorbs. The two situations need different treatment and
 * they are cheaply distinguishable, because they differ by more than an order
 * of magnitude:
 *
 * | situation | per attempt | measured |
 * |---|---|---|
 * | transient race after a disconnect | ~1.2s | 3.006s across 2 attempts |
 * | peripheral absent | ~48s (BlueZ's own timeout) | ~198s across 4 attempts |
 *
 * So this policy keeps production's attempt count and adds a wall-clock
 * [BUDGET] measured from the start of the connect. A race retries exactly as
 * it would in production; an absent peripheral fails after one attempt,
 * because one attempt already blew the budget.
 *
 * Failing suite goes from ~50 minutes to ~12 — bounded by BlueZ's ~48s connect
 * timeout, which the harness cannot shorten from here. Bounding *that* would
 * mean wrapping `connect()` in a `withTimeout`, which risks leaving BlueZ
 * mid-connect (the documented wedge state). Not attempted.
 *
 * @see announce for the output that tells a reader this policy was in effect
 */
internal object HarnessConnectRetry {

    /** What `SdbusEngineConfig` uses in production. Documentation only. */
    const val PRODUCTION_MAX_ATTEMPTS = 3

    /** Matched to production — the budget below does the real work. */
    const val MAX_ATTEMPTS = PRODUCTION_MAX_ATTEMPTS

    /**
     * Wall-clock budget from the start of the connect. Comfortably above any
     * observed transient recovery (~3s, plus backoff) and far below a single
     * absent-device attempt (~48s), so it separates them without being
     * sensitive to where exactly it sits between the two.
     */
    private val BUDGET = 15.seconds

    private val BACKOFF = 500.milliseconds

    private var connectStartedAt: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Call immediately before `connect()`. Starts the [BUDGET] clock, so
     * elapsed time on the first failure is the duration of the first attempt
     * — which is the signal that separates a race from an absent device.
     */
    fun beginConnect() {
        connectStartedAt = TimeSource.Monotonic.markNow()
    }

    /**
     * Retries the same error the production default retries, within [BUDGET].
     *
     * Matches on the message alone, where the production default also checks
     * `error is SdbusException`. `sdbus-kotlin` is an `implementation`
     * dependency of `:engine` and so is not on this module's test classpath;
     * widening that just to narrow a predicate in a test harness is the worse
     * trade. Any throwable carrying `le-connection-abort-by-local` came from
     * BlueZ regardless of its type.
     */
    suspend fun onConnectDelay(attempt: Int, error: Throwable): Duration? {
        val message = error.message ?: return null
        if ("le-connection-abort-by-local" !in message) return null
        if (attempt > MAX_ATTEMPTS) return null

        val elapsed = connectStartedAt?.elapsedNow()
        if (elapsed != null && elapsed > BUDGET) {
            println(
                "[harness] giving up after $attempt attempt(s): ${elapsed.inWholeSeconds}s " +
                    "elapsed exceeds the ${BUDGET.inWholeSeconds}s budget, so this is an " +
                    "unreachable peripheral rather than the transient reconnect race. " +
                    "Production would keep retrying here.",
            )
            return null
        }
        return BACKOFF
    }

    private var announced = false

    /**
     * Printed once per run, so a reader of the run output can tell a device
     * that was absent from a harness that gave up early. Without it, a fast
     * connect failure is ambiguous in exactly the way this suite exists to
     * resolve.
     *
     * Reaching the console depends on the build script enabling
     * `testLogging.showStandardStreams` for the gated tasks — Gradle
     * otherwise captures test stdout into the XML report, where nobody
     * reading a run will see it.
     */
    fun announce() {
        if (announced) return
        announced = true
        println(
            "[harness] connect-retry bounded by a ${BUDGET.inWholeSeconds}s wall-clock " +
                "budget (production: $PRODUCTION_MAX_ATTEMPTS retries, unbounded in time). " +
                "The transient reconnect race still retries; an unreachable peripheral " +
                "fails after one attempt instead of four.",
        )
    }
}
