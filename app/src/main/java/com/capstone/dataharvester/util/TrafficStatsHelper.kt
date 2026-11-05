package com.capstone.dataharvester.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.util.Log

/**
 * Reads Android's cumulative mobile byte counters (TrafficStats).
 *
 * Only reads: the delta, reboot handling and the checkpoint are computed by
 * [PrimaryTick] and committed by [PrimaryCollector] in one Room transaction.
 */
class TrafficStatsHelper(private val context: Context) {

    companion object {
        private const val TAG = "TrafficStatsHelper"
    }

    /**
     * Current cumulative mobile RX/TX; -1 when TrafficStats is UNSUPPORTED on this device.
     * Also records whether mobile data was up at the reading: the counters only sum mobile
     * interfaces that are up, so a reading taken while mobile is down is low.
     */
    fun readMobileCounters(): PrimaryTick.Counters {
        val mobileUp = isMobileUp()
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            Log.w(TAG, "TrafficStats not supported on this device")
            return PrimaryTick.Counters(-1L, -1L, mobileUp)
        }
        return PrimaryTick.Counters(rx, tx, mobileUp)
    }

    /**
     * True when any cellular network with internet is up, default or not (with "mobile data
     * always active" it stays up behind Wi-Fi). If the state cannot be read, true: the 1.6.7
     * behaviour, never a false reset.
     */
    fun isMobileUp(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION") // allNetworks: the only API-23 way to see non-default networks
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read mobile network state", e)
        true
    }
}
