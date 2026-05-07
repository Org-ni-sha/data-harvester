package com.capstone.dataharvester.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Helper class for reading device state information:
 * - Battery level (percentage)
 * - Screen on/off state
 * - Network type (WiFi, LTE, 5G, 3G, etc.)
 *
 * All methods are safe to call without special permissions
 * (network type detection degrades gracefully if READ_PHONE_STATE is unavailable).
 */
class DeviceInfoHelper(private val context: Context) {

    companion object {
        private const val TAG = "DeviceInfoHelper"
    }

    /**
     * Get current battery level as a percentage (0-100).
     * Returns -1 if unable to read battery info.
     */
    fun getBatteryLevel(): Int {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let {
                context.registerReceiver(null, it)
            }
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

            if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else {
                -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read battery level", e)
            -1
        }
    }

    /**
     * Check if the device screen is currently on (interactive).
     * Uses PowerManager.isInteractive() which is available on API 20+.
     */
    fun isScreenOn(): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isInteractive
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read screen state", e)
            false
        }
    }

    /**
     * Get the current network type as a human-readable string.
     *
     * Returns one of: "WIFI", "5G", "LTE", "3G", "2G", "CELLULAR", "NONE", "UNKNOWN"
     *
     * Note: Detailed cellular type (5G/LTE/3G) requires READ_PHONE_STATE on some
     * Android versions. If unavailable, falls back to "CELLULAR".
     */
    fun getNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "NONE"
            val capabilities = cm.getNetworkCapabilities(network) ?: return "UNKNOWN"

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getCellularType()
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "UNKNOWN"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read network type", e)
            "UNKNOWN"
        }
    }

    /**
     * Attempt to determine the specific cellular network type (5G, LTE, 3G, 2G).
     * Falls back to "CELLULAR" if READ_PHONE_STATE is not granted.
     */
    private fun getCellularType(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            @Suppress("MissingPermission")
            when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
                else -> "CELLULAR"
            }
        } catch (e: SecurityException) {
            Log.d(TAG, "Cannot determine cellular type (no READ_PHONE_STATE), using 'CELLULAR'")
            "CELLULAR"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to determine cellular type", e)
            "CELLULAR"
        }
    }

    /**
     * Get the current signal strength in dBm.
     *
     * Uses TelephonyManager.getSignalStrength() on API 28+.
     * Falls back to CellInfo-based reading on older APIs.
     * Returns -999 if unable to read signal strength.
     */
    fun getSignalStrength(): Int {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+: Use getSignalStrength() directly
                val signalStrength = tm.signalStrength
                if (signalStrength != null) {
                    // getLevel() returns 0-4, but we want dBm for richer data
                    // Try to get dBm from CellSignalStrength objects
                    val cellSignalStrengths = signalStrength.cellSignalStrengths
                    if (cellSignalStrengths.isNotEmpty()) {
                        cellSignalStrengths[0].dbm
                    } else {
                        -999
                    }
                } else {
                    -999
                }
            } else {
                // API 23-27: Try to get from CellInfo
                @Suppress("MissingPermission")
                val cellInfoList = tm.allCellInfo
                if (cellInfoList != null && cellInfoList.isNotEmpty()) {
                    val cellInfo = cellInfoList[0]
                    when (cellInfo) {
                        is android.telephony.CellInfoLte ->
                            cellInfo.cellSignalStrength.dbm
                        is android.telephony.CellInfoWcdma ->
                            cellInfo.cellSignalStrength.dbm
                        is android.telephony.CellInfoGsm ->
                            cellInfo.cellSignalStrength.dbm
                        else -> -999
                    }
                } else {
                    -999
                }
            }
        } catch (e: SecurityException) {
            Log.d(TAG, "Cannot read signal strength (no permission)")
            -999
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read signal strength", e)
            -999
        }
    }

    /**
     * Check if the device is currently charging (USB, AC, or wireless).
     */
    fun isCharging(): Boolean {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let {
                context.registerReceiver(null, it)
            }
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read charging state", e)
            false
        }
    }

    /**
     * Get the device model string (manufacturer + model).
     * E.g., "Samsung SM-A546E", "Google Pixel 8"
     */
    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }
}
