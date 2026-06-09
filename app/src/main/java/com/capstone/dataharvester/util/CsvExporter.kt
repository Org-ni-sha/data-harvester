package com.capstone.dataharvester.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.data.AppUsageRecord
import com.capstone.dataharvester.data.UsageRecord
import java.io.File
import java.io.OutputStream
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
 *
 * Uses MediaStore.Downloads API (API 29+) for scoped storage compatibility.
 * Falls back to direct File access for API < 29.
 */
class CsvExporter(private val context: Context) {

    companion object {
        private const val TAG = "CsvExporter"

        // Fixed filenames — overwrites on each export
        const val MAIN_CSV_FILENAME = "data_harvest.csv"
        const val APP_CSV_FILENAME  = "app_usage.csv"

        // CSV Header — matches the updated dataset schema with new columns
        private const val CSV_HEADER =
            "id,timestamp,datetime,hour,minute,day_of_week,is_weekend," +
            "time_period,bytes_rx,bytes_tx,bytes_total,mb_used," +
            "cumulative_mb_today,network_type,screen_on,battery_level," +
            "device_id,signal_strength,is_charging,device_model"

        // Per-app CSV Header
        private const val APP_CSV_HEADER =
            "id,timestamp,datetime,device_id,package_name,app_name," +
            "uid,bytes_rx,bytes_tx,bytes_total,network_type,query_start,is_system_app"
    }

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Export all main usage records to Downloads/data_harvest.csv.
     * Overwrites any existing file with the same name.
     *
     * @return Number of records exported
     */
    suspend fun exportToCsv(): Int {
        val records = AppDatabase.getInstance(context).usageDao().getAllAscending()
        writeToDownloads(MAIN_CSV_FILENAME) { stream ->
            val writer = stream.bufferedWriter()
            writer.write(CSV_HEADER)
            writer.newLine()
            for (record in records) {
                writer.write(formatRow(record))
                writer.newLine()
            }
            writer.flush()
        }
        Log.i(TAG, "Exported ${records.size} main records to Downloads/$MAIN_CSV_FILENAME")
        return records.size
    }

    /**
     * Export all per-app usage records to Downloads/app_usage.csv.
     * Overwrites any existing file with the same name.
     *
     * @return Number of records exported
     */
    suspend fun exportAppUsageToCsv(): Int {
        val records = AppDatabase.getInstance(context).appUsageDao().getAllAscending()
        writeToDownloads(APP_CSV_FILENAME) { stream ->
            val writer = stream.bufferedWriter()
            writer.write(APP_CSV_HEADER)
            writer.newLine()
            for (record in records) {
                writer.write(formatAppRow(record))
                writer.newLine()
            }
            writer.flush()
        }
        Log.i(TAG, "Exported ${records.size} per-app records to Downloads/$APP_CSV_FILENAME")
        return records.size
    }

    /**
     * Export BOTH main usage and per-app usage CSVs in one call.
     *
     * @return Pair of (mainCount, appCount) records exported
     */
    suspend fun exportAll(): Pair<Int, Int> {
        val mainCount = getExportableCount()
        val appCount  = getAppExportableCount()

        if (mainCount > 0) exportToCsv()
        if (appCount  > 0) exportAppUsageToCsv()

        return Pair(mainCount, appCount)
    }

    /**
     * Get the number of main usage records that would be exported.
     */
    suspend fun getExportableCount(): Int =
        AppDatabase.getInstance(context).usageDao().getCount()

    /**
     * Get the number of per-app records that would be exported.
     */
    suspend fun getAppExportableCount(): Int =
        AppDatabase.getInstance(context).appUsageDao().getCount()

    // ─── Storage Writer ────────────────────────────────────────────────────

    /**
     * Write data to a file in the public Downloads folder.
     *
     * Strategy (API 29+):
     *  1. Query for an existing MediaStore entry with the same filename
     *  2a. If found  → open with "wt" (write+truncate) to overwrite in-place
     *  2b. If absent → insert a new MediaStore entry
     *  3. Mark IS_PENDING=0 when done so the file is visible in Downloads
     *
     * This avoids the broken delete+insert pattern which silently fails on
     * Android 11+ when the file was created by a previous install session.
     *
     * On Android < 10: falls back to direct File access (legacy path).
     *
     * @param filename  Target filename (e.g. "data_harvest.csv")
     * @param block     Lambda that receives an [OutputStream] to write into
     */
    private fun writeToDownloads(filename: String, block: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // ── API 29+ : MediaStore scoped storage ──────────────────────────
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            // Step 1: Check if file already exists in MediaStore
            val existingUri = findExistingMediaStoreEntry(filename)

            val targetUri: Uri
            val writeMode: String

            if (existingUri != null) {
                // Step 2a: File exists — mark pending and overwrite in-place
                targetUri = existingUri
                writeMode = "wt"  // write + truncate — clears old content
                val pendingValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                resolver.update(targetUri, pendingValues, null, null)
            } else {
                // Step 2b: File doesn't exist — insert a new entry
                writeMode = "w"
                val insertValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                targetUri = resolver.insert(collection, insertValues)
                    ?: throw Exception("MediaStore insert returned null for $filename")
            }

            try {
                resolver.openOutputStream(targetUri, writeMode)?.use { stream ->
                    block(stream)
                } ?: throw Exception("Could not open OutputStream for $filename")

                // Step 3: Mark file as complete — visible in Downloads
                val doneValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(targetUri, doneValues, null, null)

            } catch (e: Exception) {
                // Restore to non-pending so old content remains readable
                val revertValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(targetUri, revertValues, null, null)
                throw e
            }

        } else {
            // ── API < 29 : Direct file access (legacy) ───────────────────────
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val file = File(downloadsDir, filename)
            file.outputStream().use { stream ->
                block(stream)
            }
        }
    }

    /**
     * Query MediaStore Downloads for an existing entry with the given filename.
     * Returns the content URI if found, null otherwise.
     */
    private fun findExistingMediaStoreEntry(filename: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection  = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(filename)

        return context.contentResolver.query(
            collection, projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                collection.buildUpon().appendPath(id.toString()).build()
            } else null
        }
    }

    // ─── CSV Cleanup ──────────────────────────────────────────────────────

    /**
     * Delete both exported CSV files from Downloads.
     * Called by MainActivity.performReset() so stale exports don't confuse users.
     *
     * Uses MediaStore on API 29+, direct File access on older versions.
     */
    fun deleteExportedFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(MAIN_CSV_FILENAME, APP_CSV_FILENAME).forEach { filename ->
                findExistingMediaStoreEntry(filename)?.let { uri ->
                    try {
                        context.contentResolver.delete(uri, null, null)
                        Log.i(TAG, "Deleted MediaStore entry: $filename")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not delete $filename from MediaStore: ${e.message}")
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            listOf(MAIN_CSV_FILENAME, APP_CSV_FILENAME).forEach { filename ->
                val file = File(dir, filename)
                if (file.exists()) {
                    file.delete()
                    Log.i(TAG, "Deleted legacy file: ${file.absolutePath}")
                }
            }
        }
    }

    // ─── Row Formatters ────────────────────────────────────────────────────

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
            quote(r.queryStart),
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
