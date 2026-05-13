package com.capstone.dataharvester.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.data.AppUsageRecord
import com.capstone.dataharvester.data.UsageRecord
import java.io.File
import java.io.PrintWriter

/**
 * Exports usage records from Room database to CSV files in the Downloads folder.
 *
 * Supports two export types:
 *  1. **Main usage records** — device-wide TrafficStats data with sensor columns
 *  2. **Per-app usage records** — NetworkStatsManager per-UID data
 *
 * CSV files use FIXED filenames and OVERWRITE on each export:
 *  - Main:    Downloads/data_harvest.csv
 *  - Per-app: Downloads/app_usage.csv
 */
class CsvExporter(private val context: Context) {

    companion object {
        private const val TAG = "CsvExporter"

        // Fixed filenames — overwrites on each export
        const val MAIN_CSV_FILENAME = "data_harvest.csv"
        const val APP_CSV_FILENAME = "app_usage.csv"

        // CSV Header — matches the updated dataset schema with new columns
        private const val CSV_HEADER =
            "id,timestamp,datetime,hour,minute,day_of_week,is_weekend," +
            "time_period,bytes_rx,bytes_tx,bytes_total,mb_used," +
            "cumulative_mb_today,network_type,screen_on,battery_level," +
            "device_id,signal_strength,is_charging,device_model"

        // Per-app CSV Header
        private const val APP_CSV_HEADER =
            "id,timestamp,datetime,device_id,package_name,app_name," +
            "uid,bytes_rx,bytes_tx,bytes_total,network_type,is_system_app"
    }

    /**
     * Export all main usage records to a CSV file in the Downloads directory.
     * Overwrites any existing file with the same name.
     *
     * @return The exported File object
     * @throws Exception if export fails (disk full, permission denied, etc.)
     */
    suspend fun exportToCsv(): File {
        val dao = AppDatabase.getInstance(context).usageDao()
        val records = dao.getAllAscending() // Chronological order

        val file = getDownloadsFile(MAIN_CSV_FILENAME)

        PrintWriter(file).use { writer ->
            writer.println(CSV_HEADER)
            for (record in records) {
                writer.println(formatRow(record))
            }
        }

        Log.i(TAG, "Exported ${records.size} records to ${file.absolutePath}")
        return file
    }

    /**
     * Export all per-app usage records to a CSV file in the Downloads directory.
     * Overwrites any existing file with the same name.
     *
     * @return The exported File object
     * @throws Exception if export fails
     */
    suspend fun exportAppUsageToCsv(): File {
        val dao = AppDatabase.getInstance(context).appUsageDao()
        val records = dao.getAllAscending()

        val file = getDownloadsFile(APP_CSV_FILENAME)

        PrintWriter(file).use { writer ->
            writer.println(APP_CSV_HEADER)
            for (record in records) {
                writer.println(formatAppRow(record))
            }
        }

        Log.i(TAG, "Exported ${records.size} per-app records to ${file.absolutePath}")
        return file
    }

    /**
     * Export BOTH main usage and per-app usage CSVs in one call.
     *
     * @return Pair of (mainCount, appCount) records exported
     */
    suspend fun exportAll(): Pair<Int, Int> {
        val mainCount = getExportableCount()
        val appCount = getAppExportableCount()

        if (mainCount > 0) exportToCsv()
        if (appCount > 0) exportAppUsageToCsv()

        return Pair(mainCount, appCount)
    }

    /**
     * Get the number of main usage records that would be exported.
     */
    suspend fun getExportableCount(): Int {
        return AppDatabase.getInstance(context).usageDao().getCount()
    }

    /**
     * Get the number of per-app records that would be exported.
     */
    suspend fun getAppExportableCount(): Int {
        return AppDatabase.getInstance(context).appUsageDao().getCount()
    }

    /**
     * Get or create a file in the Downloads directory.
     * If the file already exists, it will be overwritten by PrintWriter.
     */
    private fun getDownloadsFile(filename: String): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        return File(downloadsDir, filename)
    }

    /**
     * Format a single UsageRecord as a CSV row.
     * String fields are quoted to handle any special characters.
     */
    private fun formatRow(r: UsageRecord): String {
        return listOf(
            r.id.toString(),
            r.timestamp.toString(),
            quote(r.datetimeStr),
            r.hour.toString(),
            r.minute.toString(),
            r.dayOfWeek.toString(),
            r.isWeekend.toString(),
            quote(r.timePeriod),
            r.bytesRx.toString(),
            r.bytesTx.toString(),
            r.bytesTotal.toString(),
            "%.6f".format(r.mbUsed),
            "%.6f".format(r.cumulativeMbToday),
            quote(r.networkType),
            r.screenOn.toString(),
            r.batteryLevel.toString(),
            quote(r.deviceId),
            r.signalStrength.toString(),
            r.isCharging.toString(),
            quote(r.deviceModel)
        ).joinToString(",")
    }

    /**
     * Format a single AppUsageRecord as a CSV row.
     */
    private fun formatAppRow(r: AppUsageRecord): String {
        return listOf(
            r.id.toString(),
            r.timestamp.toString(),
            quote(r.datetimeStr),
            quote(r.deviceId),
            quote(r.packageName),
            quote(r.appName),
            r.uid.toString(),
            r.bytesRx.toString(),
            r.bytesTx.toString(),
            r.bytesTotal.toString(),
            quote(r.networkType),
            r.isSystemApp.toString()
        ).joinToString(",")
    }

    /**
     * Quote a string value for CSV output (RFC 4180).
     * Wraps in double quotes and escapes any embedded double quotes.
     */
    private fun quote(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
