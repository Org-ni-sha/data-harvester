package com.capstone.dataharvester.util

/**
 * Labelling and quality rules for collected rows, kept free of Android types so the
 * JVM unit tests can check them directly.
 *
 * measurement_status values:
 *  - OK            ordinary interval of about the expected length
 *  - BASELINE      first reading; no delta yet
 *  - COUNTER_RESET counters went backwards (reboot, stats wiped); delta forced to 0
 *  - UNSUPPORTED   the counter could not be read; 0 bytes is not "no usage"
 *  - GAP           real bytes, but over a much longer (or unknown) span than normal
 *  - INTERRUPTED   real bytes; one or more readings inside the span failed
 *  - CLOCK_ERROR   the wall clock ran backwards, so the span cannot be trusted
 */
object MeasurementRules {

    /** Stored as schema_version on every row; bump whenever a rule below changes meaning */
    const val SCHEMA_VERSION = 2

    const val COLLECTION_INTERVAL_MS = 30_000L
    const val APP_COLLECTION_INTERVAL_MS = 600_000L

    /** A primary interval longer than this (Doze, service killed, stopped) is a GAP */
    const val GAP_THRESHOLD_MS = 2 * COLLECTION_INTERVAL_MS + 15_000L

    /** A per-app interval longer than this is a GAP, not an ordinary 10-minute sample */
    const val APP_GAP_THRESHOLD_MS = 2 * APP_COLLECTION_INTERVAL_MS + 60_000L

    private val CELLULAR_LABELS = setOf("5G", "LTE", "3G", "2G", "CELLULAR")

    fun isCellular(label: String) = label in CELLULAR_LABELS

    /**
     * network_type of a primary row. The primary counter measures MOBILE bytes only, so
     * any interval with mobile bytes (however few) or with a mobile default network gets
     * the cellular label. A non-cellular label (WIFI, NONE, ...) is kept only when the
     * interval moved no mobile bytes at all, so dropping WIFI rows never drops mobile usage.
     * The connection that was active stays in active_network_at_sample.
     */
    fun primaryNetworkLabel(
        intervalNetwork: String,
        cellularSeen: Boolean,
        bytesTotal: Long,
        cellularRadio: () -> String
    ): String =
        if (!isCellular(intervalNetwork) && (bytesTotal > 0 || cellularSeen)) cellularRadio()
        else intervalNetwork

    /**
     * Status of a primary row.
     * @param counterStatus the TrafficStatsHelper status name (OK unless the read itself failed)
     * @param elapsedMs monotonic span since the last SUCCESSFUL counter reading; -1 if unknown
     * @param readingsFailedInSpan true when a tick inside the span could not read the counter
     */
    fun primaryStatus(counterStatus: String, elapsedMs: Long, readingsFailedInSpan: Boolean): String = when {
        counterStatus != "OK" -> counterStatus
        elapsedMs < 0 || elapsedMs > GAP_THRESHOLD_MS -> "GAP"
        readingsFailedInSpan -> "INTERRUPTED"
        else -> "OK"
    }

    /** Status of a per-app row covering the wall-clock span [since, until]. */
    fun appStatus(since: Long, until: Long, counterReset: Boolean): String = when {
        counterReset -> "COUNTER_RESET"
        until < since -> "CLOCK_ERROR"
        until - since > APP_GAP_THRESHOLD_MS -> "GAP"
        else -> "OK"
    }
}
