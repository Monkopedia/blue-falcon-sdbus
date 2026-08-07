package com.monkopedia.bluefalcon.sdbus.integration

import com.monkopedia.bluefalcon.sdbus.SdbusEngine
import dev.bluefalcon.core.BluetoothPeripheral
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Connect-retry policy for the integration harness — **deliberately different
 * from the engine's production default.**
 *
 * The production default in `SdbusEngineConfig` retries the transient BlueZ
 * `le-connection-abort-by-local` race up to [MAX_ATTEMPTS] times with linear
 * 1s/2s/3s backoff. That is right for an application. It is not right for this
 * suite, because BlueZ reports the *same* error when the peripheral is simply
 * absent — after burning its own ~48s connect timeout on every attempt. The
 * production policy therefore spends ~198s per test before failing, roughly 50
 * minutes across the suite, in silence. A suite that takes 50 minutes to tell
 * you the device is switched off is not a usable failure signal, which is the
 * only reason to run it.
 *
 * **The discriminator is per-attempt duration, and it is physical.** The two
 * situations differ by more than an order of magnitude in how long a single
 * attempt takes:
 *
 * | situation | one attempt |
 * |---|---|
 * | transient race after a disconnect | ~0.5–1.2s (measured: 2/16 forced rounds raced, both firing at ~490ms and clearing on the next attempt) |
 * | peripheral absent | ~48s — BlueZ's own connect timeout |
 *
 * So this policy retries **exactly as production does** — same attempt count,
 * same `attempt.seconds` backoff — and adds one extra way to stop: if a single
 * attempt itself burned more than [ATTEMPT_BUDGET], the peripheral is absent
 * rather than racing, and further attempts will only burn ~48s each.
 *
 * Keying on the *attempt* rather than on cumulative elapsed time is what makes
 * this safe. A cumulative budget has to be larger than the whole retry
 * sequence, which puts it uncomfortably close to the sequence's own worst case
 * (~10.8s of backoff alone) and makes it sensitive to machine load — so a slow
 * but genuinely recovering race could be abandoned. Per-attempt, the race path
 * cannot be truncated at all, however deep the retries go or however loaded
 * the machine is, because no individual racing attempt comes close to
 * [ATTEMPT_BUDGET].
 *
 * An earlier version of this cut the attempt count instead, and measurably
 * broke the suite: `writeNoResponse` failed against real hardware in 3.006s,
 * having exhausted a shortened budget on a race the production policy absorbs.
 * That is the failure mode this design exists to avoid, and it is why the
 * backoff must stay at production's `attempt.seconds`.
 *
 * A fully-failing suite goes from ~50 minutes to ~12 — bounded by BlueZ's ~48s
 * connect timeout, which the harness cannot shorten from here. Bounding *that*
 * would mean wrapping `connect()` in a `withTimeout`, which risks leaving
 * BlueZ mid-connect (the documented wedge state). Not attempted.
 *
 * @see connect the only supported entry point
 * @see announce for the output that tells a reader this policy was in effect
 */
internal object HarnessConnectRetry {

    /** Matched to production; the per-attempt budget does the extra work. */
    const val MAX_ATTEMPTS = 3

    /**
     * Ceiling on a *single* connect attempt. An order of magnitude above the
     * slowest observed race attempt (~1.2s) and an order of magnitude below
     * BlueZ's absent-device timeout (~48s), so it is insensitive to exactly
     * where it sits between them.
     */
    private val ATTEMPT_BUDGET = 15.seconds

    private var attemptMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var pendingBackoff: Duration = Duration.ZERO

    /**
     * Connect through the harness policy. **The only supported entry point** —
     * the per-attempt clock starts here, so calling [SdbusEngine.connect]
     * directly would leave the budget silently inapplicable (or, worse,
     * applied against a stale mark from a previous test).
     */
    suspend fun connect(engine: SdbusEngine, peripheral: BluetoothPeripheral) {
        attemptMark = TimeSource.Monotonic.markNow()
        pendingBackoff = Duration.ZERO
        engine.connect(peripheral)
    }

    /**
     * Retries the same error the production default retries, on the same
     * schedule, unless the failing attempt took long enough to prove the
     * peripheral is absent.
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

        // elapsedNow() spans the backoff we asked for plus the attempt that
        // followed it; subtract the backoff to get the attempt alone.
        val attemptTook = attemptMark?.let { it.elapsedNow() - pendingBackoff }
        if (attemptTook != null && attemptTook > ATTEMPT_BUDGET) {
            println(
                "[harness] giving up after $attempt attempt(s): that attempt alone took " +
                    "${attemptTook.inWholeSeconds}s, past the ${ATTEMPT_BUDGET.inWholeSeconds}s " +
                    "per-attempt budget, so this is an unreachable peripheral rather than the " +
                    "transient reconnect race. Production would keep retrying here.",
            )
            return null
        }

        val backoff = attempt.seconds // production parity — the race is time-driven
        attemptMark = TimeSource.Monotonic.markNow()
        pendingBackoff = backoff
        return backoff
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
            "[harness] connect-retry matches production (${MAX_ATTEMPTS} retries, " +
                "1s/2s/3s backoff) but stops early if a single attempt exceeds " +
                "${ATTEMPT_BUDGET.inWholeSeconds}s. The transient reconnect race retries " +
                "exactly as it would in production; an unreachable peripheral fails after " +
                "one attempt instead of four.",
        )
    }
}
