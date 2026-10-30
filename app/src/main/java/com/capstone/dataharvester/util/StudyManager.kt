package com.capstone.dataharvester.util

import android.content.Context
import android.content.SharedPreferences

/**
 * The official study (Dataharvester_v2). Every row collected by app 1.6.1 or later belongs
 * to it, so there is no separate enrollment step: new rows are tagged with
 * [OFFICIAL_STUDY_ID], and rows saved before 1.6.5 are tagged by MIGRATION_12_13.
 *
 * Only rows tagged with the study are uploaded; rows already uploaded stay marked as
 * uploaded, so nothing is sent twice.
 *
 * Kept in its own prefs file so Reset (which clears collection state) leaves it alone.
 */
class StudyManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "study_prefs"
        private const val KEY_ENROLLED_AT = "enrolled_at"

        /** The official collection; matches the Dataharvester_v2 cloud database. */
        const val OFFICIAL_STUDY_ID = "dataharvester_v2"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The study every row belongs to. */
    fun studyId(): String = OFFICIAL_STUDY_ID

    /**
     * Wall-clock epoch millis when this install first ran a version that tags rows with
     * the study (sent with the cloud device_identity row). Recorded on first use.
     */
    fun enrolledAt(): Long {
        val saved = prefs.getLong(KEY_ENROLLED_AT, -1L)
        if (saved > 0) return saved
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_ENROLLED_AT, now).commit()
        return now
    }
}
