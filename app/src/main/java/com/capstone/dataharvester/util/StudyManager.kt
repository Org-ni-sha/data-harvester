package com.capstone.dataharvester.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Enrollment in the official study, set by the "Start Official Collection" step.
 *
 * Only rows collected after enrollment carry [OFFICIAL_STUDY_ID], and only those rows
 * are uploaded to Dataharvester_v2. Older rows (study_id '') stay on the phone and in
 * the CSV export, but never reach the official database.
 *
 * Kept in its own prefs file so Reset (which clears collection state) leaves it alone.
 * Changing [OFFICIAL_STUDY_ID] asks every phone to enroll again.
 */
class StudyManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "study_prefs"
        private const val KEY_STUDY_ID = "study_id"
        private const val KEY_ENROLLED_AT = "enrolled_at"

        /** The official collection; matches the Dataharvester_v2 cloud database. */
        const val OFFICIAL_STUDY_ID = "dataharvester_v2"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnrolled(): Boolean = prefs.getString(KEY_STUDY_ID, null) == OFFICIAL_STUDY_ID

    /** The study new rows belong to, or "" when not enrolled. */
    fun studyId(): String = if (isEnrolled()) OFFICIAL_STUDY_ID else ""

    /** Wall-clock epoch millis of enrollment, or -1 when not enrolled. */
    fun enrolledAt(): Long = if (isEnrolled()) prefs.getLong(KEY_ENROLLED_AT, -1L) else -1L

    /**
     * Record enrollment. Call only after collection is stopped and the counter
     * baselines are cleared, so the first enrolled rows start from a fresh baseline.
     */
    fun enroll(now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_STUDY_ID, OFFICIAL_STUDY_ID)
            .putLong(KEY_ENROLLED_AT, now)
            .commit() // synchronous: the collector started right after must see it
    }
}
