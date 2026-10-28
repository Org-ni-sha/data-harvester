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
 *   1. Call initializeFromSavedState() on service start (reboot recovery)
 *   2. Call getUsageDelta() every tick to get interval bytes
 *   3. After the record is saved, call commit(delta), then save
 *      getCurrentCumulativeRx/Tx() for reboot recovery
 */
class TrafficStatsHelper {

    companion object {
        private const val TAG = "TrafficStatsHelper"
    }

    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L

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
        val currentTx: Long = -1L
    )

    /**
     * Initialize with the last known cumulative values from SharedPreferences.
     * This allows detecting reboots: if current TrafficStats values are less than
     * these saved values, the device rebooted.
     *
     * @param savedRx Last saved cumulative mobile RX bytes (-1 if no saved state)
     * @param savedTx Last saved cumulative mobile TX bytes (-1 if no saved state)
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
     * @return UsageDelta containing interval bytes and reboot flag
     */
    fun getUsageDelta(): UsageDelta {
        val currentRx = TrafficStats.getMobileRxBytes()
        val currentTx = TrafficStats.getMobileTxBytes()

        // TrafficStats returns UNSUPPORTED (-1) on some devices/emulators
        if (currentRx == TrafficStats.UNSUPPORTED.toLong() ||
            currentTx == TrafficStats.UNSUPPORTED.toLong()
        ) {
            Log.w(TAG, "TrafficStats not supported on this device")
            return UsageDelta(0L, 0L, 0L, false)
        }

        val deltaRx: Long
        val deltaTx: Long
        var wasReboot = false

        when {
            // First run ever — no previous data
            lastRxBytes == -1L || lastTxBytes == -1L -> {
                Log.i(TAG, "First run: no previous state, recording baseline")
                deltaRx = 0L
                deltaTx = 0L
            }

            // REBOOT DETECTED: current counters are less than last saved
            currentRx < lastRxBytes || currentTx < lastTxBytes -> {
                Log.w(
                    TAG,
                    "Reboot detected! Current RX=$currentRx < Last RX=$lastRxBytes"
                )
                deltaRx = 0L
                deltaTx = 0L
                wasReboot = true
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
            currentTx = currentTx
        )
    }

    /** Make [delta]'s readings the baseline for the next [getUsageDelta]. */
    fun commit(delta: UsageDelta) {
        if (delta.currentRx < 0 || delta.currentTx < 0) return
        lastRxBytes = delta.currentRx
        lastTxBytes = delta.currentTx
    }

    /** Get the current cumulative RX bytes (for saving to SharedPreferences). */
    fun getCurrentCumulativeRx(): Long = lastRxBytes

    /** Get the current cumulative TX bytes (for saving to SharedPreferences). */
    fun getCurrentCumulativeTx(): Long = lastTxBytes
}
