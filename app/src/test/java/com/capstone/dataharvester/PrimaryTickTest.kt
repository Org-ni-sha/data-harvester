package com.capstone.dataharvester

import com.capstone.dataharvester.data.CollectionCheckpoint
import com.capstone.dataharvester.util.MeasurementRules.GAP_THRESHOLD_MS
import com.capstone.dataharvester.util.PrimaryTick
import com.capstone.dataharvester.util.PrimaryTick.Counters
import org.junit.Assert.assertEquals
import org.junit.Test

/** Interrupted readings, reboots and crash recovery of the primary (30 s) collector. */
class PrimaryTickTest {

    // Device booted at wall time BOOT; elapsedRealtime counts from there
    private val boot = 1_793_000_000_000L
    private fun at(elapsed: Long) = elapsed to boot + elapsed

    private fun tick(cp: CollectionCheckpoint, rx: Long, tx: Long, elapsed: Long): PrimaryTick.Result {
        val (e, w) = at(elapsed)
        return PrimaryTick.compute(cp, Counters(rx, tx), e, w)
    }

    private fun unsupported(cp: CollectionCheckpoint, elapsed: Long): PrimaryTick.Result {
        val (e, w) = at(elapsed)
        return PrimaryTick.compute(cp, Counters(-1, -1), e, w)
    }

    private val first = tick(CollectionCheckpoint(), 1_000, 100, 100_000)

    @Test
    fun firstReading_isBaselineWithNoBytes() {
        assertEquals("BASELINE", first.status)
        assertEquals(0L, first.bytesTotal)
        assertEquals(-1L, first.elapsedMs)
        assertEquals(1_000L, first.checkpoint.mobileRx)
        assertEquals(100_000L, first.checkpoint.counterElapsed)
    }

    @Test
    fun ordinaryTick_countsTheDeltaOverThirtySeconds() {
        val t = tick(first.checkpoint, 1_500, 150, 130_000)
        assertEquals("OK", t.status)
        assertEquals(500L, t.bytesRx)
        assertEquals(50L, t.bytesTx)
        assertEquals(30_000L, t.elapsedMs)
        assertEquals(boot + 100_000, t.intervalStart)
    }

    @Test
    fun failedReading_keepsByteBaselineAndItsTime() {
        val failed = unsupported(first.checkpoint, 130_000)
        assertEquals("UNSUPPORTED", failed.status)
        assertEquals(0L, failed.bytesTotal)
        assertEquals(1_000L, failed.checkpoint.mobileRx)
        assertEquals(100_000L, failed.checkpoint.counterElapsed) // still the last GOOD reading
        assertEquals(130_000L, failed.checkpoint.lastTickElapsed)
    }

    @Test
    fun recoveryAfterFailedReading_spansFromLastGoodReadingAndIsInterrupted() {
        val failed = unsupported(first.checkpoint, 130_000)
        val recovered = tick(failed.checkpoint, 1_900, 100, 160_000)
        // Bytes since the reading at 100 s, and the duration covers that same span
        assertEquals(900L, recovered.bytesTotal)
        assertEquals(60_000L, recovered.elapsedMs)
        assertEquals(boot + 100_000, recovered.intervalStart)
        assertEquals("INTERRUPTED", recovered.status)
        // After recovery the next tick is ordinary again
        assertEquals("OK", tick(recovered.checkpoint, 2_000, 100, 190_000).status)
    }

    @Test
    fun longSilence_keepsRealBytesButIsGap() {
        val t = tick(first.checkpoint, 9_000, 100, 100_000 + GAP_THRESHOLD_MS + 1)
        assertEquals("GAP", t.status)
        assertEquals(8_000L, t.bytesTotal)
    }

    @Test
    fun reboot_isCounterResetWithNoBytes() {
        // elapsedRealtime restarted: the device rebooted between ticks
        val t = PrimaryTick.compute(first.checkpoint, Counters(50, 5), 20_000, boot + 3_600_000)
        assertEquals("COUNTER_RESET", t.status)
        assertEquals(0L, t.bytesTotal)
        assertEquals(-1L, t.elapsedMs)
        assertEquals(50L, t.checkpoint.mobileRx)
    }

    @Test
    fun rebootWhileReadingsFail_nextGoodReadingIsBaseline() {
        val failedAfterReboot = PrimaryTick.compute(first.checkpoint, Counters(-1, -1), 20_000, boot + 3_600_000)
        assertEquals(-1L, failedAfterReboot.checkpoint.mobileRx)
        val next = PrimaryTick.compute(failedAfterReboot.checkpoint, Counters(70, 7), 50_000, boot + 3_630_000)
        assertEquals("BASELINE", next.status)
        assertEquals(0L, next.bytesTotal)
    }

    @Test
    fun countersGoingBackwardsWithoutReboot_isCounterReset() {
        val t = tick(first.checkpoint, 10, 1, 130_000)
        assertEquals("COUNTER_RESET", t.status)
        assertEquals(0L, t.bytesTotal)
    }

    @Test
    fun crashBeforeCommit_nextTickCountsTheBytesExactlyOnce() {
        // A tick at 130 s computed 500 bytes but the process died before the commit, so the
        // checkpoint is still the one from 100 s. The next tick counts from there: the 500
        // bytes appear once (in this row), never twice.
        tick(first.checkpoint, 1_500, 100, 130_000) // lost
        val next = tick(first.checkpoint, 1_800, 100, 160_000)
        assertEquals(800L, next.bytesTotal)
        assertEquals(60_000L, next.elapsedMs)
    }
}
