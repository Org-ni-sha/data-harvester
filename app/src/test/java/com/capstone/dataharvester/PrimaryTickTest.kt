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

    // ── 1.6.7: mobile interface down/up and impossible jumps ─────────────────

    @Test
    fun mobileDataDropsThenComesBack_noBigDelta_onlyRealUsageCounted() {
        // 6 GB since boot; mobile data goes off (counters fall to 0), then comes back
        val gb6 = 6L * 1024 * 1024 * 1024
        val base = tick(CollectionCheckpoint(), gb6, 100, 100_000).checkpoint
        val down = tick(base, 0, 0, 130_000)
        assertEquals("COUNTER_RESET", down.status)
        assertEquals(0L, down.bytesTotal)
        assertEquals(gb6, down.checkpoint.mobileRx)                  // baseline kept, not lowered
        assertEquals(100_000L, down.checkpoint.counterElapsed)

        // Interface back: counter returns to the full value plus 2 MB of real usage
        val back = tick(down.checkpoint, gb6 + 2_000_000, 100, 160_000)
        assertEquals(2_000_000L, back.bytesTotal)                     // not 6 GB
        assertEquals("INTERRUPTED", back.status)                     // the span had a dropped reading
        assertEquals(60_000L, back.elapsedMs)
        assertEquals("OK", tick(back.checkpoint, gb6 + 2_100_000, 100, 190_000).status)
    }

    @Test
    fun counterStaysLowPastTheHold_isAcceptedAsATrueReset() {
        val base = tick(CollectionCheckpoint(), 5_000_000, 0, 100_000).checkpoint
        val low = tick(base, 1_000, 0, 100_000 + PrimaryTick.COUNTER_DROP_HOLD_MS + 1)
        assertEquals("COUNTER_RESET", low.status)
        assertEquals(1_000L, low.checkpoint.mobileRx)                 // re-baselined at the low value
        assertEquals("OK", tick(low.checkpoint, 2_000, 0, 100_000 + PrimaryTick.COUNTER_DROP_HOLD_MS + 30_001).status)
    }

    @Test
    fun overCeilingJump_isFlagged_notCounted_andRebaselined() {
        // 6,333 MB in 7 s, as in the official data
        val jump = tick(first.checkpoint, 1_000 + 6_333L * 1024 * 1024, 100, 107_000)
        assertEquals("COUNTER_JUMP", jump.status)
        assertEquals(0L, jump.bytesTotal)
        assertEquals(1_000L + 6_333L * 1024 * 1024, jump.checkpoint.mobileRx)
        // The next ordinary tick counts normally from the new baseline
        val next = tick(jump.checkpoint, 1_000 + 6_333L * 1024 * 1024 + 500, 100, 137_000)
        assertEquals("OK", next.status)
        assertEquals(500L, next.bytesTotal)
    }

    @Test
    fun heavyButRealUsage_underTheCeiling_staysOk() {
        // 900 MB in 30 s = 30 MB/s (about 250 Mbit/s): a fast 5G download, not an artifact
        val t = tick(first.checkpoint, 1_000 + 900L * 1024 * 1024, 100, 130_000)
        assertEquals("OK", t.status)
        assertEquals(900L * 1024 * 1024, t.bytesTotal)
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

    // ── 1.6.8: mobile data down at the baseline (MIUI tears it down on Wi-Fi) ──

    private fun read(cp: CollectionCheckpoint, rx: Long, tx: Long, elapsed: Long, mobileUp: Boolean): PrimaryTick.Result {
        val (e, w) = at(elapsed)
        return PrimaryTick.compute(cp, Counters(rx, tx, mobileUp), e, w)
    }

    @Test
    fun baselineOnWifi_thenMobileReturns_isCounterResetNotUsage() {
        // The POCO F3 case: baseline read on Wi-Fi with mobile down (counter low), then mobile
        // came back and the counter jumped by 1,204,791,640 B in 28,945 ms (41 MB/s, under the
        // COUNTER_JUMP ceiling) while NetworkStatsManager saw 23 MB
        val onWifi = read(CollectionCheckpoint(), 5_000_000, 1_000_000, 100_000, mobileUp = false)
        assertEquals(0, onWifi.checkpoint.mobileUpAtReading)
        val back = read(onWifi.checkpoint, 5_000_000 + 1_100_000_000, 1_000_000 + 104_791_640, 128_945, mobileUp = true)
        assertEquals("COUNTER_RESET", back.status)
        assertEquals(0L, back.bytesTotal)
        assertEquals(1, back.checkpoint.mobileUpAtReading)
        // Re-baselined at the high value: the next tick counts only real usage
        val next = read(back.checkpoint, 1_105_000_000 + 2_000_000, 105_791_640 + 500_000, 158_945, mobileUp = true)
        assertEquals("OK", next.status)
        assertEquals(2_500_000L, next.bytesTotal)
    }

    @Test
    fun longWifiStretch_holdExpires_thenMobileReturns_isCounterReset() {
        val up = read(CollectionCheckpoint(), 900_000_000, 90_000_000, 100_000, mobileUp = true)
        // Mobile torn down on Wi-Fi: the counter falls; held for 10 min, then re-baselined low
        val held = read(up.checkpoint, 1_000, 100, 130_000, mobileUp = false)
        assertEquals("COUNTER_RESET", held.status)
        val expired = read(held.checkpoint, 1_000, 100, 100_000 + PrimaryTick.COUNTER_DROP_HOLD_MS + 1, mobileUp = false)
        assertEquals(1_000L, expired.checkpoint.mobileRx)
        assertEquals(0, expired.checkpoint.mobileUpAtReading)
        // 1.6.7 counted this return as ~990 MB of usage
        val back = read(expired.checkpoint, 900_100_000, 90_010_000, 100_000 + PrimaryTick.COUNTER_DROP_HOLD_MS + 30_000, mobileUp = true)
        assertEquals("COUNTER_RESET", back.status)
        assertEquals(0L, back.bytesTotal)
    }

    @Test
    fun shortWifiStretch_withinTheHold_stillCountsRealUsage() {
        // Unchanged from 1.6.7: the baseline was read with mobile up and is held while it is down
        val up = read(CollectionCheckpoint(), 900_000_000, 90_000_000, 100_000, mobileUp = true)
        val down = read(up.checkpoint, 1_000, 100, 130_000, mobileUp = false)
        assertEquals(1, down.checkpoint.mobileUpAtReading)
        val back = read(down.checkpoint, 900_400_000, 90_100_000, 160_000, mobileUp = true)
        assertEquals("INTERRUPTED", back.status) // real bytes; the held reading inside the span did not count
        assertEquals(500_000L, back.bytesTotal)
    }

    @Test
    fun mobileStaysDown_countsNothingAndNeverResets() {
        val a = read(CollectionCheckpoint(), 1_000, 100, 100_000, mobileUp = false)
        val b = read(a.checkpoint, 1_000, 100, 130_000, mobileUp = false)
        assertEquals("OK", b.status)
        assertEquals(0L, b.bytesTotal)
    }

    @Test
    fun checkpointFromBefore168_firstTickWithMobileUp_isCounterReset() {
        // MIGRATION_13_14 leaves mobile_up_at_reading = -1 (unknown): not trusted as "up"
        val old = CollectionCheckpoint(
            mobileRx = 1_000, mobileTx = 100, lastTickElapsed = 100_000, lastTickWall = boot + 100_000,
            counterElapsed = 100_000, counterWall = boot + 100_000
        )
        val t = read(old, 2_000, 200, 130_000, mobileUp = true)
        assertEquals("COUNTER_RESET", t.status)
        assertEquals(0L, t.bytesTotal)
        assertEquals("OK", read(t.checkpoint, 2_500, 200, 160_000, mobileUp = true).status)
    }
}
