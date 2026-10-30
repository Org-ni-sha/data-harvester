package com.capstone.dataharvester.util

import android.net.TrafficStats
import android.util.Log

/**
 * Reads Android's cumulative mobile byte counters (TrafficStats).
 *
 * Only reads: the delta, reboot handling and the checkpoint are computed by
 * [PrimaryTick] and committed by [PrimaryCollector] in one Room transaction.
 */
class TrafficStatsHelper {

    companion object {
        private const val TAG = "TrafficStatsHelper"
    }

    /** Current cumulative mobile RX/TX; -1 when TrafficStats is UNSUPPORTED on this device. */
    fun readMobileCounters(): PrimaryTick.Counters {
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            Log.w(TAG, "TrafficStats not supported on this device")
            return PrimaryTick.Counters(-1L, -1L)
        }
        return PrimaryTick.Counters(rx, tx)
    }
}
