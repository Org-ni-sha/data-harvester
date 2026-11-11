package com.capstone.dataharvester

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The collection notification was swiped away or cleared. Android 14+ lets users dismiss a
 * foreground service's notification; on HiOS (seen on a TECNO CL7) the app then counts as
 * not visible and is frozen, so collection stopped until the participant pressed Stop and
 * Start. The notification is put back at once (see DataCollectionService.onNotificationDismissed).
 */
class NotificationDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DataCollectionService.onNotificationDismissed(context)
    }
}
