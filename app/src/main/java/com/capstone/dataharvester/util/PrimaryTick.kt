package com.capstone.dataharvester.util

import com.capstone.dataharvester.data.CollectionCheckpoint
import kotlin.math.abs

/**
 * The arithmetic of one primary collection tick, with no Android types so the JVM unit
 * tests can drive it: reboot detection, the byte delta, the interval it spans, its
 * measurement_status, and the checkpoint to commit together with the row.
 */
object PrimaryTick {

    /** Wall-clock vs. elapsedRealtime drift above this between two ticks = reboot */
    const val BOOT_TIME_TOLERANCE_MS = 60_000L

    /**
     * Highest believable mobile throughput: 100 MiB/s (about 840 Mbit/s), above the peak
     * 5G speeds measured on Philippine networks and far above LTE. A tick above this is not
     * usage but a counter artifact (the official data had rows of 6-26 GB in 2-15 s): it is
     * recorded as COUNTER_JUMP with 0 bytes and the counter is re-baselined.
     */
    const val MAX_PLAUSIBLE_BYTES_PER_SECOND = 100L * 1024 * 1024

    /**
     * TrafficStats' mobile counters only sum the mobile interfaces that are up, so they fall
     * when mobile data drops (network NONE) and return to the full cumulative value when it
     * comes back. A fall without a reboot is therefore held for up to this long: the old
     * baseline and its time are kept (0 bytes, COUNTER_RESET) and the first reading back at
     * or above it counts only the real usage since. A fall that lasts longer is accepted as
     * a true reset and re-baselined at the lower value.
     */
    const val COUNTER_DROP_HOLD_MS = 10 * 60_000L

    /**
     * Cumulative mobile counters as read from TrafficStats; -1 = UNSUPPORTED.
     * [mobileUp]: a cellular network with internet was up at the reading. TrafficStats only
     * sums mobile interfaces that are up, so a reading taken while mobile is down is low.
     */
    data class Counters(val rx: Long, val tx: Long, val mobileUp: Boolean = true) {
        val supported get() = rx >= 0 && tx >= 0
    }

    data class Result(
        val bytesRx: Long,
        val bytesTx: Long,
        val status: String,
        /** Wall-clock start of the span the bytes belong to (= nowWall when unknown) */
        val intervalStart: Long,
        /** Monotonic length of that span; -1 when unknown */
        val elapsedMs: Long,
        val checkpoint: CollectionCheckpoint
    ) {
        val bytesTotal get() = bytesRx + bytesTx
    }

    fun compute(
        cp: CollectionCheckpoint,
        counters: Counters,
        nowElapsed: Long,
        nowWall: Long
    ): Result {
        // ── Reboot: boot time (wall clock minus uptime) moves only on a reboot ──
        val hasPreviousTick = cp.lastTickElapsed >= 0 && cp.lastTickWall > 0
        val rebooted = hasPreviousTick && (
            nowElapsed < cp.lastTickElapsed ||
                abs((nowWall - nowElapsed) - (cp.lastTickWall - cp.lastTickElapsed)) > BOOT_TIME_TOLERANCE_MS
            )

        if (!counters.supported) {
            // Failed reading: no bytes, but the tick is recorded. The byte baseline AND its
            // time stay put, so the next good reading spans the whole gap; after a reboot
            // the old baseline is meaningless and the next good reading is a BASELINE
            val spanKnown = hasPreviousTick && !rebooted
            return Result(
                bytesRx = 0L, bytesTx = 0L,
                status = "UNSUPPORTED",
                intervalStart = if (spanKnown) cp.lastTickWall else nowWall,
                elapsedMs = if (spanKnown) nowElapsed - cp.lastTickElapsed else -1L,
                checkpoint = if (rebooted) {
                    CollectionCheckpoint(lastTickElapsed = nowElapsed, lastTickWall = nowWall)
                } else {
                    cp.copy(lastTickElapsed = nowElapsed, lastTickWall = nowWall)
                }
            )
        }

        val hasBaseline = cp.mobileRx >= 0 && cp.mobileTx >= 0
        // The bytes span from the last SUCCESSFUL counter reading, not the last tick
        val hasCounterTime = cp.counterElapsed >= 0 && cp.counterWall > 0 && !rebooted
        val elapsedMs = if (hasCounterTime) nowElapsed - cp.counterElapsed else -1L

        // Counter fell without a reboot: most likely the mobile interface went down. Keep the
        // baseline and its time, like a failed reading, until it comes back or the hold ends
        val dropped = hasBaseline && !rebooted && (counters.rx < cp.mobileRx || counters.tx < cp.mobileTx)
        if (dropped && hasCounterTime && elapsedMs < COUNTER_DROP_HOLD_MS) {
            return Result(
                bytesRx = 0L, bytesTx = 0L,
                status = "COUNTER_RESET",
                intervalStart = cp.lastTickWall,
                elapsedMs = nowElapsed - cp.lastTickElapsed,
                checkpoint = cp.copy(lastTickElapsed = nowElapsed, lastTickWall = nowWall)
            )
        }

        // Baseline read while mobile was down (or before 1.6.8 recorded it), and mobile is up
        // now: the counters have jumped back to the interfaces' lifetime totals. Some phones
        // (MIUI) tear mobile data down on Wi-Fi, so this is not usage; a baseline taken on
        // Wi-Fi, or held past COUNTER_DROP_HOLD_MS, would otherwise count GBs as OK.
        val mobileReturned = hasBaseline && !rebooted && counters.mobileUp && cp.mobileUpAtReading != 1

        val deltaRx = counters.rx - cp.mobileRx
        val deltaTx = counters.tx - cp.mobileTx
        // Unknown span (no counter time): judge it as one ordinary tick, the strict choice
        val spanMs = (if (elapsedMs > 0) elapsedMs else MeasurementRules.COLLECTION_INTERVAL_MS).coerceAtLeast(1_000L)
        val implausible = (deltaRx + deltaTx) > MAX_PLAUSIBLE_BYTES_PER_SECOND * spanMs / 1_000L

        val counterStatus = when {
            !hasBaseline -> "BASELINE"
            rebooted || dropped || mobileReturned -> "COUNTER_RESET"
            implausible -> "COUNTER_JUMP"
            else -> "OK"
        }
        val counted = counterStatus == "OK"

        // A tick after the last good reading means a reading inside the span failed
        val readingsFailedInSpan = hasCounterTime && cp.lastTickElapsed != cp.counterElapsed

        return Result(
            bytesRx = if (counted) deltaRx else 0L,
            bytesTx = if (counted) deltaTx else 0L,
            status = MeasurementRules.primaryStatus(counterStatus, elapsedMs, readingsFailedInSpan),
            intervalStart = if (hasCounterTime) cp.counterWall else nowWall,
            elapsedMs = elapsedMs,
            checkpoint = CollectionCheckpoint(
                mobileRx = counters.rx,
                mobileTx = counters.tx,
                lastTickElapsed = nowElapsed,
                lastTickWall = nowWall,
                counterElapsed = nowElapsed,
                counterWall = nowWall,
                mobileUpAtReading = if (counters.mobileUp) 1 else 0
            )
        )
    }
}
