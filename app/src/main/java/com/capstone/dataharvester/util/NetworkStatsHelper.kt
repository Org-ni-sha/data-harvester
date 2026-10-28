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
 * Queries cumulative per-UID network usage for both mobile and Wi-Fi networks,
 * resolves UIDs to package names and app labels, and identifies system vs user apps.
 *
 * Requires:
 *  - android.permission.PACKAGE_USAGE_STATS (granted via Settings → Usage Access)
 *
 * Usage:
 *   1. Check hasUsageAccessPermission() before querying
 *   2. Call queryAppUsage(startTime, endTime) to get per-app snapshots
 */
class NetworkStatsHelper(private val context: Context) {

    companion object {
        private const val TAG = "NetworkStatsHelper"
    }

    /**
     * Result of one per-app query. [failed] is true when either network's query
     * threw, so the caller can retry the window instead of treating it as "no usage".
     */
    data class AppUsageQueryResult(
        val snapshots: List<AppUsageSnapshot>,
        val failed: Boolean
    )

    /**
     * Snapshot of a single app's network usage for a given interval and network type.
     */
    data class AppUsageSnapshot(
        val uid: Int,
        val packageName: String,
        val appName: String,
        val bytesRx: Long,
        val bytesTx: Long,
        val bytesTotal: Long,
        val networkType: String,  // "MOBILE" or "WIFI"
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
     * Query per-app network usage for a given time range.
     * Returns snapshots for both MOBILE and WIFI networks.
     *
     * Every UID with non-zero usage is kept (no top-N cut, so small mobile
     * consumers are not dropped). Android can return several buckets per UID
     * (e.g. foreground/background); they are summed into one row per UID and
     * network type for the window.
     *
     * @param startTime Start of interval in epoch millis
     * @param endTime End of interval in epoch millis
     */
    fun queryAppUsage(startTime: Long, endTime: Long): AppUsageQueryResult {
        if (!hasUsageAccessPermission()) {
            Log.w(TAG, "Usage access permission not granted — skipping per-app collection")
            return AppUsageQueryResult(emptyList(), failed = true)
        }

        val statsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE)
            as? NetworkStatsManager
        if (statsManager == null) {
            Log.e(TAG, "NetworkStatsManager not available")
            return AppUsageQueryResult(emptyList(), failed = true)
        }

        val mobile = queryForNetwork(statsManager, ConnectivityManager.TYPE_MOBILE, startTime, endTime, "MOBILE")
        val wifi = queryForNetwork(statsManager, ConnectivityManager.TYPE_WIFI, startTime, endTime, "WIFI")
        val results = mobile.orEmpty() + wifi.orEmpty()

        Log.i(TAG, "Collected ${results.size} per-app entries — " +
                "${mobile?.size ?: "failed"} mobile, ${wifi?.size ?: "failed"} wifi")

        return AppUsageQueryResult(results, failed = mobile == null || wifi == null)
    }

    /**
     * Query usage for a specific network type (mobile or wifi), one row per UID.
     * Returns null if the query failed.
     */
    @Suppress("DEPRECATION")
    private fun queryForNetwork(
        statsManager: NetworkStatsManager,
        networkType: Int,
        startTime: Long,
        endTime: Long,
        networkLabel: String
    ): List<AppUsageSnapshot>? {
        // uid -> (rx, tx), summed over all buckets for that UID
        val totals = linkedMapOf<Int, LongArray>()

        try {
            val networkStats: NetworkStats = statsManager.querySummary(
                networkType,
                null,  // subscriberId — null queries all
                startTime,
                endTime
            )

            val bucket = NetworkStats.Bucket()

            networkStats.use {
                while (it.hasNextBucket()) {
                    it.getNextBucket(bucket)

                    val uid = bucket.uid

                    // Skip entries with zero usage
                    if (bucket.rxBytes == 0L && bucket.txBytes == 0L) continue

                    // Skip removed apps (UID_REMOVED) and tethering (UID_TETHERING)
                    if (uid == NetworkStats.Bucket.UID_REMOVED ||
                        uid == NetworkStats.Bucket.UID_TETHERING
                    ) continue

                    val sum = totals.getOrPut(uid) { LongArray(2) }
                    sum[0] += bucket.rxBytes
                    sum[1] += bucket.txBytes
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying $networkLabel stats — permission revoked?", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying $networkLabel network stats", e)
            return null
        }

        return totals.map { (uid, sum) ->
            val appInfo = resolveUid(uid)
            AppUsageSnapshot(
                uid = uid,
                packageName = appInfo.packageName,
                appName = appInfo.appName,
                bytesRx = sum[0],
                bytesTx = sum[1],
                bytesTotal = sum[0] + sum[1],
                networkType = networkLabel,
                isSystemApp = appInfo.isSystemApp
            )
        }
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
     * Resolution strategy (5 layers):
     *  1. Known Android system UIDs (0=root, 1000=android, 1001=phone…)
     *  2. Isolated process UID range (90000–99999) — always unresolvable by design
     *  3. PackageManager.getPackagesForUid() — the primary path for user apps
     *  4. Prefer the package with a launcher Activity (user-visible app)
     *  5. Last-resort: record package name even if ApplicationInfo lookup fails
     */
    private fun resolveUid(uid: Int): ResolvedAppInfo {

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
