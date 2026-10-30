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

    /** Cumulative mobile counters as read from TrafficStats; -1 = UNSUPPORTED */
    data class Counters(val rx: Long, val tx: Long) {
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
        val counterStatus = when {
            !hasBaseline -> "BASELINE"
            rebooted || counters.rx < cp.mobileRx || counters.tx < cp.mobileTx -> "COUNTER_RESET"
            else -> "OK"
        }
        val counted = counterStatus == "OK"

        // The bytes span from the last SUCCESSFUL counter reading, not the last tick
        val hasCounterTime = cp.counterElapsed >= 0 && cp.counterWall > 0 && !rebooted
        val elapsedMs = if (hasCounterTime) nowElapsed - cp.counterElapsed else -1L
        // A tick after the last good reading means a reading inside the span failed
        val readingsFailedInSpan = hasCounterTime && cp.lastTickElapsed != cp.counterElapsed

        return Result(
            bytesRx = if (counted) counters.rx - cp.mobileRx else 0L,
            bytesTx = if (counted) counters.tx - cp.mobileTx else 0L,
            status = MeasurementRules.primaryStatus(counterStatus, elapsedMs, readingsFailedInSpan),
            intervalStart = if (hasCounterTime) cp.counterWall else nowWall,
            elapsedMs = elapsedMs,
            checkpoint = CollectionCheckpoint(
                mobileRx = counters.rx,
                mobileTx = counters.tx,
                lastTickElapsed = nowElapsed,
                lastTickWall = nowWall,
                counterElapsed = nowElapsed,
                counterWall = nowWall
            )
        )
    }
}
