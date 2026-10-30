package com.capstone.dataharvester.util

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log

/**
 * Wrapper around Android's NetworkStatsManager API for per-app data usage collection.
 *
 * Android keeps per-UID history in buckets about 2 hours long, so it cannot answer
 * "how much did app X use in these 10 minutes" exactly: depending on the version it
 * interpolates (smearing usage and under-counting the bucket in progress) or returns
 * whole buckets only. This helper therefore never queries short windows. It returns
 * CUMULATIVE usage per UID since an anchor on a bucket boundary (UTC midnight), with
 * an end time in the future so the in-progress bucket counts in full. The collector
 * subtracts consecutive readings, exactly like it does for TrafficStats.
 *
 * Resolves UIDs to package names and app labels, and identifies system vs user apps.
 *
 * Requires:
 *  - android.permission.PACKAGE_USAGE_STATS (granted via Settings → Usage Access)
 *
 * Usage:
 *   1. Check hasUsageAccessPermission() before querying
 *   2. Call queryCumulative(transport, anchor, now) for each transport
 *   3. Subtract the previous reading; resolveApp(uid) for names
 */
class NetworkStatsHelper(private val context: Context) {

    companion object {
        private const val TAG = "NetworkStatsHelper"
        const val DAY_MS = 86_400_000L

        /** package_name of the per-app row for data shared through the phone's hotspot (uid -5) */
        const val HOTSPOT_PACKAGE = "hotspot"

        /** UTC midnight at or before [time]: a multiple of every NetworkStats bucket length. */
        // Kotlin's mod, not Math.floorMod(long, long): that is API 24 and minSdk is 23
        fun utcMidnight(time: Long): Long = time - time.mod(DAY_MS)
    }

    /** Network a per-app reading covers; queried separately so one can fail alone. */
    @Suppress("DEPRECATION") // querySummary still takes the legacy ConnectivityManager types
    enum class Transport(val legacyType: Int) {
        MOBILE(ConnectivityManager.TYPE_MOBILE),
        WIFI(ConnectivityManager.TYPE_WIFI)
    }

    /** App identity resolved from a UID. */
    data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean
    )

    /**
     * Check if the app has been granted Usage Access permission.
     * This must be granted manually by the user in Settings.
     */
    fun hasUsageAccessPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
                as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check usage access permission", e)
            false
        }
    }

    /**
     * Cumulative usage per UID on [transport] from [anchor] up to [until].
     *
     * [anchor] must be a UTC midnight (see [utcMidnight]) so no partial bucket is cut
     * at the start. Pass `until = now + DAY_MS` to include everything recorded so far
     * in the bucket in progress, or `until = anchor + DAY_MS` to close a past day.
     *
     * Android can return several buckets per UID (e.g. foreground/background); they
     * are summed. Every UID with non-zero usage is kept.
     *
     * @return uid → [rx, tx], or null if the query failed (the caller keeps its
     *   previous reading and retries next tick)
     */
    @Suppress("DEPRECATION")
    fun queryCumulative(transport: Transport, anchor: Long, until: Long): Map<Int, LongArray>? {
        if (!hasUsageAccessPermission()) {
            Log.w(TAG, "Usage access permission not granted — skipping per-app collection")
            return null
        }
        val statsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE)
            as? NetworkStatsManager
        if (statsManager == null) {
            Log.e(TAG, "NetworkStatsManager not available")
            return null
        }

        // uid -> (rx, tx), summed over all buckets for that UID
        val totals = linkedMapOf<Int, LongArray>()

        try {
            val networkStats: NetworkStats = statsManager.querySummary(
                transport.legacyType,
                null,  // subscriberId — null queries all
                anchor,
                until
            )

            val bucket = NetworkStats.Bucket()

            networkStats.use {
                while (it.hasNextBucket()) {
                    it.getNextBucket(bucket)

                    val uid = bucket.uid

                    // Skip entries with zero usage
                    if (bucket.rxBytes == 0L && bucket.txBytes == 0L) continue

                    // Skip removed apps (UID_REMOVED). Tethering (UID_TETHERING) is kept: it is
                    // data this phone shared through its hotspot, recorded as its own row
                    if (uid == NetworkStats.Bucket.UID_REMOVED) continue

                    val sum = totals.getOrPut(uid) { LongArray(2) }
                    sum[0] += bucket.rxBytes
                    sum[1] += bucket.txBytes
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying ${transport.name} stats — permission revoked?", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying ${transport.name} network stats", e)
            return null
        }

        Log.d(TAG, "${transport.name}: cumulative usage for ${totals.size} UIDs since $anchor")
        return totals
    }

    /** Resolve a UID to its package, label and system flag. */
    fun resolveApp(uid: Int): AppInfo = resolveUid(uid).let {
        AppInfo(it.packageName, it.appName, it.isSystemApp)
    }

    /**
     * Resolved app info from a UID lookup.
     */
    private data class ResolvedAppInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean
    )

    /**
     * Resolve a Linux UID to package name, app label, and system app flag.
     *
     * Resolution strategy (5 layers, after the hotspot pseudo-UID):
     *  1. Known Android system UIDs (0=root, 1000=android, 1001=phone…)
     *  2. Isolated process UID range (90000–99999) — always unresolvable by design
     *  3. PackageManager.getPackagesForUid() — the primary path for user apps
     *  4. Prefer the package with a launcher Activity (user-visible app)
     *  5. Last-resort: record package name even if ApplicationInfo lookup fails
     */
    private fun resolveUid(uid: Int): ResolvedAppInfo {

        // ── Hotspot: traffic of other devices using this phone's hotspot ────
        // Already counted in the phone's mobile totals (usage_records); this row says
        // how much of it was shared. Filter on package_name = 'hotspot'.
        if (uid == NetworkStats.Bucket.UID_TETHERING) {
            return ResolvedAppInfo(
                packageName = HOTSPOT_PACKAGE,
                appName     = "Hotspot (tethering)",
                isSystemApp = false
            )
        }

        // ── Layer 1: Known Android system UIDs ──────────────────────────────
        val knownSystemUids = mapOf(
            0    to Pair("root",                        "Linux Kernel / Root"),
            1000 to Pair("android",                     "Android System"),
            1001 to Pair("com.android.phone",           "Phone / Radio"),
            1002 to Pair("com.android.bluetooth",       "Bluetooth"),
            1003 to Pair("com.android.shell",           "ADB Shell"),
            1010 to Pair("wifi",                        "Wi-Fi Stack"),
            1013 to Pair("mediaserver",                 "Media Server"),
            1017 to Pair("keystore",                    "Keystore"),
            1020 to Pair("gps",                         "GPS"),
            1021 to Pair("update_map",                  "Update Maps"),
            2000 to Pair("shell",                       "Shell"),
            9999 to Pair("com.android.networkstack",    "Network Stack")
        )
        knownSystemUids[uid]?.let { (pkg, name) ->
            return ResolvedAppInfo(packageName = pkg, appName = name, isSystemApp = true)
        }

        // ── Layer 2: Isolated process UID range ─────────────────────────────
        // Android assigns isolated sandbox processes UIDs in the range 90000–99999.
        // getPackagesForUid() always returns null for these — by design.
        // They represent WebView renderers, Chrome tab processes, etc.
        if (uid in 90000..99999) {
            return ResolvedAppInfo(
                packageName = "android.isolated_process",
                appName     = "Isolated Process",
                isSystemApp = true
            )
        }

        // ── Layer 3 + 4: PackageManager lookup with smart package selection ─
        val pm = context.packageManager
        try {
            val packages = pm.getPackagesForUid(uid)
            if (!packages.isNullOrEmpty()) {

                // Layer 4: Prefer the package that has a launcher Activity
                // (i.e., the user-facing app), otherwise fall back to packages[0]
                val preferredPackage = packages.firstOrNull { pkg ->
                    val launchIntent = android.content.Intent(
                        android.content.Intent.ACTION_MAIN
                    ).apply {
                        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                        setPackage(pkg)
                    }
                    pm.queryIntentActivities(launchIntent, 0).isNotEmpty()
                } ?: packages[0]

                // ── Layer 5: Graceful fallback if ApplicationInfo fails ──────
                val appInfo = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(
                            preferredPackage,
                            PackageManager.ApplicationInfoFlags.of(0)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(preferredPackage, 0)
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    // Package exists (getPackagesForUid returned it) but ApplicationInfo
                    // is unavailable — record the package name we do have
                    Log.d(TAG, "ApplicationInfo not found for $preferredPackage (UID $uid), using package name as label")
                    return ResolvedAppInfo(
                        packageName = preferredPackage,
                        appName     = preferredPackage.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                        isSystemApp = uid < 10000
                    )
                }

                val appName  = pm.getApplicationLabel(appInfo).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                return ResolvedAppInfo(preferredPackage, appName, isSystem)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not resolve UID $uid: ${e.message}")
        }

        // ── Final fallback: unresolvable UID ────────────────────────────────
        // Still record useful data: UIDs < 10000 are always Android system processes
        return ResolvedAppInfo(
            packageName = "uid_$uid",
            appName     = "Unknown (UID $uid)",
            isSystemApp = uid < 10000
        )
    }
}
