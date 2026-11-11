package com.capstone.dataharvester.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Vendor battery savers that sit on top of Android's own battery optimization.
 *
 * TECNO / Infinix / itel phones (Transsion, HiOS / XOS) run their own freezer ("Hiber").
 * With the default per-app setting "Intelligent Management" it freezes Data Harvester about
 * 5 seconds after the app leaves the screen, even though collection runs as a foreground
 * service and the app is exempt from Android battery optimization. A frozen process runs no
 * code at all (no timer, no wake-up alarm), so rows stop until the screen is used again.
 * Seen on a TECNO KL6 (HiOS 14.5): "Hiber/sceneManager: freeze uid ... dataharvester".
 *
 * An app cannot change that setting itself; the participant must choose "No Restrictions"
 * under Power Saving Management for apps. This class opens that screen and remembers when
 * the service saw a stall, so the dashboard can ask again.
 */
class BackgroundRestrictions(private val context: Context) {

    companion object {
        private const val TAG = "BackgroundRestrictions"
        private const val PREFS = "background_restrictions"
        private const val KEY_CONFIRMED_AT = "vendor_setup_confirmed_at"
        private const val KEY_STALL_AT = "stall_detected_at"
        private const val KEY_STALL_MINUTES = "stall_minutes"
        private const val KEY_STALL_NOTIFIED_AT = "stall_notified_at"

        /**
         * A primary interval this long without a reboot means the process could not run:
         * the wake-up alarm comes at least every ~9 minutes even in deep Doze, so a normal
         * phone stays well under it.
         */
        const val STALL_MS = 15 * 60_000L

        /** At most one "collection was paused" notification per this period */
        private const val STALL_NOTIFY_EVERY_MS = 12 * 60 * 60_000L

        private val TRANSSION_BRANDS = setOf("tecno", "infinix", "itel")

        /** Per-app power saving screens, most specific first (HiOS 13-14, XOS) */
        private val VENDOR_SCREENS = listOf(
            ComponentName(
                "com.transsion.batterylab",
                "com.android.settings.batterysave.BatteryLab\$TranAppSavingActivity"
            ),
            ComponentName(
                "com.transsion.phonemaster",
                "com.cyin.himgr.powermanager.views.activity.PowerManagerActivity"
            ),
            ComponentName(
                "com.transsion.phonemaster",
                "com.cyin.himgr.autostart.AutoStartActivity"
            )
        )
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** TECNO, Infinix or itel: the phones with the HiOS / XOS freezer. */
    fun isTranssion(): Boolean =
        Build.MANUFACTURER.lowercase() in TRANSSION_BRANDS || Build.BRAND.lowercase() in TRANSSION_BRANDS

    /** The participant said they set "No Restrictions" (cleared again by a later stall). */
    fun isSetupConfirmed(): Boolean = prefs.getLong(KEY_CONFIRMED_AT, 0L) > 0L

    fun confirmSetup() {
        prefs.edit()
            .putLong(KEY_CONFIRMED_AT, System.currentTimeMillis())
            .remove(KEY_STALL_AT)
            .remove(KEY_STALL_MINUTES)
            .apply()
    }

    /** Minutes of the last stall not yet acknowledged by the participant, or 0. */
    fun pendingStallMinutes(): Long =
        if (prefs.getLong(KEY_STALL_AT, 0L) > 0L) prefs.getLong(KEY_STALL_MINUTES, 0L) else 0L

    /**
     * Called by the collector when an interval of [elapsedMs] ended without a reboot.
     * Returns true when the participant should be notified now (rate-limited).
     */
    fun recordStall(elapsedMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val notify = now - prefs.getLong(KEY_STALL_NOTIFIED_AT, 0L) >= STALL_NOTIFY_EVERY_MS
        prefs.edit()
            .putLong(KEY_STALL_AT, now)
            .putLong(KEY_STALL_MINUTES, elapsedMs / 60_000L)
            // The setting is evidently not (or no longer) "No Restrictions": ask again
            .remove(KEY_CONFIRMED_AT)
            .apply { if (notify) putLong(KEY_STALL_NOTIFIED_AT, now) }
            .apply()
        return notify
    }

    /**
     * Opens the vendor's per-app power saving screen, or Android's app details page when
     * this phone has none of the known screens. Returns false when nothing could be opened.
     */
    fun openSettings(): Boolean {
        for (component in VENDOR_SCREENS) {
            try {
                context.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                // Not on this HiOS version; try the next one
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to open $component", e)
            }
        }
        return try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open any battery settings", e)
            false
        }
    }
}
