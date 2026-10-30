package com.capstone.dataharvester

import com.capstone.dataharvester.sync.CloudSyncManager
import com.capstone.dataharvester.util.MeasurementRules
import com.capstone.dataharvester.util.MeasurementRules.APP_GAP_THRESHOLD_MS
import com.capstone.dataharvester.util.MeasurementRules.GAP_THRESHOLD_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MeasurementRulesTest {

    private val radio = { "LTE" }

    // ── network_type of primary rows ──

    @Test
    fun anyMobileBytesOnWifi_getMobileLabel() {
        // 2 KB of mobile traffic while Wi-Fi stayed active used to keep the WIFI label
        assertEquals("LTE", MeasurementRules.primaryNetworkLabel("WIFI", false, 2_048L, radio))
        assertEquals("LTE", MeasurementRules.primaryNetworkLabel("WIFI", false, 1L, radio))
    }

    @Test
    fun wifiWithoutMobileBytes_staysWifi() {
        assertEquals("WIFI", MeasurementRules.primaryNetworkLabel("WIFI", false, 0L, radio))
    }

    @Test
    fun mobileDefaultNetworkDuringInterval_getsMobileLabelEvenWithoutBytes() {
        assertEquals("LTE", MeasurementRules.primaryNetworkLabel("WIFI", true, 0L, radio))
    }

    @Test
    fun bytesWithNoActiveNetwork_getMobileLabel() {
        assertEquals("LTE", MeasurementRules.primaryNetworkLabel("NONE", false, 500L, radio))
        assertEquals("NONE", MeasurementRules.primaryNetworkLabel("NONE", false, 0L, radio))
    }

    @Test
    fun cellularLabelIsKept() {
        assertEquals("5G", MeasurementRules.primaryNetworkLabel("5G", false, 10L) { "LTE" })
    }

    // ── status of primary rows ──

    @Test
    fun ordinaryInterval_isOk() {
        assertEquals("OK", MeasurementRules.primaryStatus("OK", 30_000L, false))
    }

    @Test
    fun recoveredAfterFailedReading_isInterrupted() {
        // bytes and duration both span from the last successful reading (60 s, 2 ticks)
        assertEquals("INTERRUPTED", MeasurementRules.primaryStatus("OK", 60_000L, true))
    }

    @Test
    fun longOrUnknownSpan_isGap() {
        assertEquals("GAP", MeasurementRules.primaryStatus("OK", GAP_THRESHOLD_MS + 1, false))
        assertEquals("GAP", MeasurementRules.primaryStatus("OK", -1L, false))
        assertEquals("GAP", MeasurementRules.primaryStatus("OK", GAP_THRESHOLD_MS + 1, true))
    }

    @Test
    fun counterProblems_keepTheirOwnStatus() {
        for (s in listOf("BASELINE", "COUNTER_RESET", "UNSUPPORTED")) {
            assertEquals(s, MeasurementRules.primaryStatus(s, 30_000L, true))
        }
    }

    // ── status of per-app rows ──

    @Test
    fun tenMinuteAppInterval_isOk() {
        assertEquals("OK", MeasurementRules.appStatus(0L, 600_000L, false))
    }

    @Test
    fun appIntervalAfterLongInterruption_isGap() {
        assertEquals("GAP", MeasurementRules.appStatus(0L, 3 * 3_600_000L, false))
        assertEquals("OK", MeasurementRules.appStatus(0L, APP_GAP_THRESHOLD_MS, false))
    }

    @Test
    fun appClockRunningBackwards_isClockError() {
        assertEquals("CLOCK_ERROR", MeasurementRules.appStatus(600_000L, 0L, false))
    }

    @Test
    fun appCounterReset_winsOverSpanChecks() {
        assertEquals("COUNTER_RESET", MeasurementRules.appStatus(600_000L, 0L, true))
    }

    // ── upload history batch id ──

    @Test
    fun batchUid_isStableAndOrderIndependent() {
        val a = CloudSyncManager.batchUid(listOf("u1", "u2", "u3"))
        assertEquals(a, CloudSyncManager.batchUid(listOf("u3", "u1", "u2")))
        assertEquals(32, a.length)
        assertNotEquals(a, CloudSyncManager.batchUid(listOf("u1", "u2")))
    }

    @Test
    fun batchUid_matchesServer() {
        // Same vector as api/_lib/records.test.js: the server computes this id too
        assertEquals("f050416e571aa7132e4b29d9885d6806", CloudSyncManager.batchUid(listOf("u3", "u1", "u2")))
    }
}
