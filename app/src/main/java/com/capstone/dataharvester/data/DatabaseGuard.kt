package com.capstone.dataharvester.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the database before anything uses it, so an update with a missing or broken
 * migration neither crashes the app nor erases the participant's records.
 *
 * Room runs the migrations, and validates the result against the expected schema, inside
 * one SQLite transaction. When either fails, the transaction rolls back and the file on
 * disk is exactly what the previous version left. This guard catches that failure, closes
 * the database, and reports it: the dashboard explains it, collection and uploads pause,
 * and the update check keeps running, so the next (fixed) update migrates the untouched file.
 */
object DatabaseGuard {

    private const val TAG = "DatabaseGuard"

    @Volatile
    private var opened = false

    /** Why the database could not be opened, or null when it is usable. */
    @Volatile
    var problem: String? = null
        private set

    /** True when the database is open and usable. Call off the main thread. */
    suspend fun ensureOpen(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (opened) return@withContext true
        synchronized(this@DatabaseGuard) {
            if (opened) return@synchronized true
            val error = tryOpen(
                open = { AppDatabase.getInstance(context) },
                close = { AppDatabase.closeInstance() }
            )
            problem = error
            opened = error == null
            opened
        }
    }

    /**
     * Forces the open (and so any pending migration) now. Returns the failure message, or
     * null on success. On failure the instance is closed: nothing keeps a half-open database.
     */
    fun tryOpen(open: () -> AppDatabase, close: () -> Unit): String? = try {
        open().openHelper.writableDatabase
        null
    } catch (e: Exception) {
        Log.e(TAG, "Database could not be opened — records left untouched", e)
        runCatching(close)
        e.message ?: e.javaClass.simpleName
    }
}
