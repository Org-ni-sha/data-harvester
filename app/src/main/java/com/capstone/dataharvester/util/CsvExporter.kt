package com.capstone.dataharvester.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.data.UsageRecord
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports usage records from Room database to a CSV file in the Downloads folder.
 *
 * CSV format:
 * - RFC 4180 compliant (string values are quoted, quotes are escaped)
 * - Header row included
 * - Sorted by timestamp ascending (chronological order)
 * - Column names use snake_case matching the dataset schema
 *
 * Output file: Downloads/data_harvest_YYYYMMDD_HHmmss.csv
 */
class CsvExporter(private val context: Context) {

    companion object {
        private const val TAG = "CsvExporter"

        // CSV Header — matches the dataset schema from the implementation plan
        private const val CSV_HEADER =
            "id,timestamp,datetime,hour,minute,day_of_week,is_weekend," +
            "time_period,bytes_rx,bytes_tx,bytes_total,mb_used," +
            "cumulative_mb_today,network_type,screen_on,battery_level"
    }

    /**
     * Export all records to a CSV file in the Downloads directory.
     *
     * @return The exported File object
     * @throws Exception if export fails (disk full, permission denied, etc.)
     */
    suspend fun exportToCsv(): File {
        val dao = AppDatabase.getInstance(context).usageDao()
        val records = dao.getAllAscending() // Chronological order

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "data_harvest_$timestamp.csv"

        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        // Ensure Downloads directory exists
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val file = File(downloadsDir, fileName)

        PrintWriter(file).use { writer ->
            // Write header
            writer.println(CSV_HEADER)

            // Write data rows
            for (record in records) {
                writer.println(formatRow(record))
            }
        }

        Log.i(TAG, "Exported ${records.size} records to ${file.absolutePath}")
        return file
    }

    /**
     * Get the number of records that would be exported.
     */
    suspend fun getExportableCount(): Int {
        return AppDatabase.getInstance(context).usageDao().getCount()
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
            r.batteryLevel.toString()
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
