package com.capstone.dataharvester

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives DataCollectionService's wake-up alarm. The alarm keeps the CPU awake while this
 * runs; the service then takes its own wake lock for the tick. When the service is not
 * running, nothing happens (CollectionWatchdogWorker restarts a killed collector).
 */
class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DataCollectionService.onHeartbeat()
    }
}
