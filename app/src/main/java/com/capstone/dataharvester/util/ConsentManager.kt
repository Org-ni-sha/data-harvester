package com.capstone.dataharvester.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the participant's acceptance of the Terms and Consent Form (RA 10173).
 *
 * Nothing runs without it: onboarding, MainActivity, DataCollectionService and
 * SyncWorker all check [isAccepted]. The accepted version and time are saved, so
 * changing the form text (bump [CONSENT_VERSION]) asks every participant again.
 */
class ConsentManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "consent_prefs"
        private const val KEY_VERSION = "consent_version"
        private const val KEY_ACCEPTED_AT = "consent_accepted_at"

        /** Version of consent_body in strings.xml; bump when the text changes. */
        const val CONSENT_VERSION = 1
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAccepted(): Boolean = prefs.getInt(KEY_VERSION, 0) >= CONSENT_VERSION

    fun accept() {
        prefs.edit()
            .putInt(KEY_VERSION, CONSENT_VERSION)
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun withdraw() {
        prefs.edit().clear().apply()
    }
}
