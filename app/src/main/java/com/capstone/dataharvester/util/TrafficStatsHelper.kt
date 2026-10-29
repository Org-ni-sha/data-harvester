package com.capstone.dataharvester.util

import android.net.TrafficStats
import android.util.Log

/**
 * Wrapper around Android's TrafficStats API for reading mobile data usage.
 *
 * Handles:
 * - Reading cumulative mobile RX/TX byte counters
 * - Computing deltas (bytes since last read)
 * - Detecting device reboots (counters reset to 0)
 * - Handling UNSUPPORTED (-1) on devices that don't implement TrafficStats
 *
 * Usage:
 *   1. Call initializeFromSavedState() on service start, with the checkpoint from Room
 *   2. Call getUsageDelta() every tick to get interval bytes
 *   3. Save the record and delta.currentRx/Tx (the checkpoint) in one Room transaction,
 *      then call commit(delta)
 */
class TrafficStatsHelper {

    companion object {
        private const val TAG = "TrafficStatsHelper"
    }

    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L

    /** Why a delta has the value it has; stored as usage_records.measurement_status. */
    enum class Status {
        /** Real bytes since the previous tick */
        OK,
        /** No previous reading: this tick only records the baseline (delta = 0) */
        BASELINE,
        /** Counters went backwards or the device rebooted: delta = 0, new baseline */
        COUNTER_RESET,
        /** TrafficStats returned UNSUPPORTED on this device: delta = 0 is not "no usage" */
        UNSUPPORTED
    }

    /**
     * Result of a single usage delta computation.
     */
    data class UsageDelta(
        /** Bytes received since last snapshot */
        val bytesRx: Long,
        /** Bytes transmitted since last snapshot */
        val bytesTx: Long,
        /** Total bytes (rx + tx) */
        val bytesTotal: Long,
        /** True if a reboot was detected (delta forced to 0) */
        val wasReboot: Boolean,
        /** Counter readings this delta ends at; pass to [commit] once the row is saved */
        val currentRx: Long = -1L,
        val currentTx: Long = -1L,
        val status: Status = Status.OK
    )

    /**
     * Initialize with the last committed cumulative values (the Room checkpoint).
     * This allows detecting reboots: if current TrafficStats values are less than
     * these saved values, the device rebooted.
     *
     * @param savedRx Last committed cumulative mobile RX bytes (-1 if no saved state)
     * @param savedTx Last committed cumulative mobile TX bytes (-1 if no saved state)
     */
    fun initializeFromSavedState(savedRx: Long, savedTx: Long) {
        lastRxBytes = savedRx
        lastTxBytes = savedTx
        Log.d(TAG, "Initialized with saved state: RX=$savedRx, TX=$savedTx")
    }

    /**
     * Read current TrafficStats mobile byte counters and compute the delta
     * since the last committed baseline. Handles reboot detection automatically.
     *
     * Does NOT move the baseline: call [commit] after the record is saved, so a
     * failed database insert leaves those bytes to be counted by the next tick.
     *
     * @param forceReset true when the caller knows the device rebooted since the last tick
     *   (elapsedRealtime went backwards); treated like a counter reset even if the new
     *   counters happen to be above the old ones
     * @return UsageDelta containing interval bytes and status
     */
    fun getUsageDelta(forceReset: Boolean = false): UsageDelta {
        val currentRx = TrafficStats.getMobileRxBytes()
        val currentTx = TrafficStats.getMobileTxBytes()

        // TrafficStats returns UNSUPPORTED (-1) on some devices/emulators
        if (currentRx == TrafficStats.UNSUPPORTED.toLong() ||
            currentTx == TrafficStats.UNSUPPORTED.toLong()
        ) {
            Log.w(TAG, "TrafficStats not supported on this device")
            return UsageDelta(0L, 0L, 0L, false, status = Status.UNSUPPORTED)
        }

        val deltaRx: Long
        val deltaTx: Long
        var wasReboot = false
        var status = Status.OK

        when {
            // First run ever — no previous data
            lastRxBytes == -1L || lastTxBytes == -1L -> {
                Log.i(TAG, "First run: no previous state, recording baseline")
                deltaRx = 0L
                deltaTx = 0L
                status = Status.BASELINE
            }

            // REBOOT DETECTED: rebooted since the last tick, or counters went backwards
            forceReset || currentRx < lastRxBytes || currentTx < lastTxBytes -> {
                Log.w(
                    TAG,
                    "Reboot detected (forced=$forceReset)! Current RX=$currentRx, Last RX=$lastRxBytes"
                )
                deltaRx = 0L
                deltaTx = 0L
                wasReboot = true
                status = Status.COUNTER_RESET
            }

            // Normal case: compute delta
            else -> {
                deltaRx = currentRx - lastRxBytes
                deltaTx = currentTx - lastTxBytes
            }
        }

        Log.d(TAG, "Delta: RX=$deltaRx, TX=$deltaTx, Total=${deltaRx + deltaTx}")

        return UsageDelta(
            bytesRx = deltaRx,
            bytesTx = deltaTx,
            bytesTotal = deltaRx + deltaTx,
            wasReboot = wasReboot,
            currentRx = currentRx,
            currentTx = currentTx,
            status = status
        )
    }

    /** Make [delta]'s readings the baseline for the next [getUsageDelta]. */
    fun commit(delta: UsageDelta) {
        if (delta.currentRx < 0 || delta.currentTx < 0) return
        lastRxBytes = delta.currentRx
        lastTxBytes = delta.currentTx
    }

    /** Current committed cumulative RX baseline (-1 if none). */
    fun getCurrentCumulativeRx(): Long = lastRxBytes

    /** Current committed cumulative TX baseline (-1 if none). */
    fun getCurrentCumulativeTx(): Long = lastTxBytes
}
