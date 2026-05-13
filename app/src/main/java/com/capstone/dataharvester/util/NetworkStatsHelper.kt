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

        /**
         * Maximum number of apps to track per collection interval.
         * Only the top N apps by total bytes consumed are kept.
         * This prevents the dataset from exploding with hundreds of
         * low-usage system processes.
         */
        const val TOP_N_APPS = 20
    }

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
     * Only apps with non-zero usage during the interval are included.
     *
     * @param startTime Start of interval in epoch millis
     * @param endTime End of interval in epoch millis
     * @return List of per-app usage snapshots, empty if permission not granted
     */
    fun queryAppUsage(startTime: Long, endTime: Long): List<AppUsageSnapshot> {
        if (!hasUsageAccessPermission()) {
            Log.w(TAG, "Usage access permission not granted — skipping per-app collection")
            return emptyList()
        }

        val statsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE)
            as? NetworkStatsManager
        if (statsManager == null) {
            Log.e(TAG, "NetworkStatsManager not available")
            return emptyList()
        }

        val allSnapshots = mutableListOf<AppUsageSnapshot>()

        // Query MOBILE network
        allSnapshots.addAll(
            queryForNetwork(statsManager, ConnectivityManager.TYPE_MOBILE, startTime, endTime, "MOBILE")
        )

        // Query WIFI network
        allSnapshots.addAll(
            queryForNetwork(statsManager, ConnectivityManager.TYPE_WIFI, startTime, endTime, "WIFI")
        )

        // ── Filter to top N apps by total bytes (aggregated across network types) ──
        val topUids = allSnapshots
            .groupBy { it.uid }
            .mapValues { (_, snapshots) -> snapshots.sumOf { it.bytesTotal } }
            .entries
            .sortedByDescending { it.value }
            .take(TOP_N_APPS)
            .map { it.key }
            .toSet()

        val results = allSnapshots.filter { it.uid in topUids }

        Log.i(TAG, "Collected ${results.size} per-app entries (top $TOP_N_APPS of " +
                "${allSnapshots.groupBy { it.uid }.size} apps) — " +
                "${results.count { it.networkType == "MOBILE" }} mobile, " +
                "${results.count { it.networkType == "WIFI" }} wifi")

        return results
    }

    /**
     * Query usage for a specific network type (mobile or wifi).
     */
    @Suppress("DEPRECATION")
    private fun queryForNetwork(
        statsManager: NetworkStatsManager,
        networkType: Int,
        startTime: Long,
        endTime: Long,
        networkLabel: String
    ): List<AppUsageSnapshot> {
        val snapshots = mutableListOf<AppUsageSnapshot>()

        try {
            val networkStats: NetworkStats = statsManager.querySummary(
                networkType,
                null,  // subscriberId — null queries all
                startTime,
                endTime
            )

            val bucket = NetworkStats.Bucket()

            while (networkStats.hasNextBucket()) {
                networkStats.getNextBucket(bucket)

                val uid = bucket.uid
                val rxBytes = bucket.rxBytes
                val txBytes = bucket.txBytes

                // Skip entries with zero usage
                if (rxBytes == 0L && txBytes == 0L) continue

                // Skip removed apps (UID_REMOVED) and tethering (UID_TETHERING)
                if (uid == NetworkStats.Bucket.UID_REMOVED ||
                    uid == NetworkStats.Bucket.UID_TETHERING
                ) continue

                val appInfo = resolveUid(uid)

                snapshots.add(
                    AppUsageSnapshot(
                        uid = uid,
                        packageName = appInfo.packageName,
                        appName = appInfo.appName,
                        bytesRx = rxBytes,
                        bytesTx = txBytes,
                        bytesTotal = rxBytes + txBytes,
                        networkType = networkLabel,
                        isSystemApp = appInfo.isSystemApp
                    )
                )
            }

            networkStats.close()

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying $networkLabel stats — permission revoked?", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying $networkLabel network stats", e)
        }

        return snapshots
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
     * Falls back to "uid_XXXXX" if the package cannot be resolved.
     */
    private fun resolveUid(uid: Int): ResolvedAppInfo {
        val pm = context.packageManager

        try {
            val packages = pm.getPackagesForUid(uid)
            if (packages != null && packages.isNotEmpty()) {
                val packageName = packages[0]
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, 0)
                }
                val appName = pm.getApplicationLabel(appInfo).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                return ResolvedAppInfo(packageName, appName, isSystem)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "Package not found for UID $uid")
        } catch (e: Exception) {
            Log.d(TAG, "Could not resolve UID $uid: ${e.message}")
        }

        // Fallback for unresolvable UIDs (removed apps, kernel, etc.)
        return ResolvedAppInfo(
            packageName = "uid_$uid",
            appName = "Unknown (UID $uid)",
            isSystemApp = true // Assume system for unresolvable UIDs
        )
    }
}
