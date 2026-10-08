package com.vibethroughcode.ftree.kutumb.treesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlcTest {
    @Test fun readingsOrderByTimeThenCounterThenDevice() {
        assertTrue(Hlc(1, 9, "z") < Hlc(2, 0, "a"))
        assertTrue(Hlc(2, 0, "z") < Hlc(2, 1, "a"))
        assertTrue(Hlc(2, 1, "a") < Hlc(2, 1, "b"))
        assertEquals(0, Hlc(2, 1, "a").compareTo(Hlc(2, 1, "a")))
    }

    @Test fun aReadingSurvivesFormatting_evenWithDotsInTheDeviceId() {
        for (h in listOf(Hlc(1_700_000_000_000, 0, "phone"), Hlc(5, 12, "a.b.c"))) {
            assertEquals(h, Hlc.parse(h.format()))
        }
    }

    @Test fun malformedReadingsAreRefusedNotGuessedAt() {
        for (bad in listOf("", "1.2", "x.1.d", "1.x.d", "-1.0.d", "1.-1.d", "1.0.", "1.0." + "d".repeat(65), "99999999999999999999.0.d")) {
            assertNull("accepted: $bad", Hlc.parse(bad))
        }
    }

    @Test fun tickNeverRepeatsOrGoesBackwards_evenInsideOneMillisecondOrWhenTheClockSlips() {
        var now = 1_000L
        val clock = HybridClock("a", { now })
        val a = clock.tick()
        val b = clock.tick()
        assertTrue(b > a)
        now = 500L // the phone's clock was set back
        val c = clock.tick()
        assertTrue(c > b)
        now = 2_000L
        val d = clock.tick()
        assertTrue(d > c)
        assertEquals(0, d.counter)
    }

    @Test fun anEditMadeAfterSeeingAnotherSortsAfterIt() {
        var nowA = 1_000L
        var nowB = 900L // B's clock runs behind A's
        val a = HybridClock("a", { nowA })
        val b = HybridClock("b", { nowB })
        val first = a.tick()
        assertTrue(b.observe(first))
        val reply = b.tick()
        assertTrue("B's reply must sort after what it saw", reply > first)
    }

    @Test fun aReadingFromTheFarFutureIsRefusedAndChangesNothing() {
        val clock = HybridClock("a", { 1_000L }, maxFutureMs = 10_000)
        val before = clock.last()
        assertFalse(clock.observe(Hlc(1_000L + 10_001, 0, "b")))
        assertEquals(before, clock.last())
        assertTrue(clock.observe(Hlc(1_000L + 10_000, 0, "b")))
    }

    @Test fun aRestartedClockResumesFromItsSavedReading() {
        val saved = Hlc(5_000, 7, "a")
        val clock = HybridClock("a", { 1_000L }, start = saved)
        assertTrue(clock.tick() > saved)
    }

    @Test fun deviceIdsMustBeUsable() {
        assertNotNull(runCatching { HybridClock("", { 0L }) }.exceptionOrNull())
        assertNotNull(runCatching { HybridClock("d".repeat(65), { 0L }) }.exceptionOrNull())
    }
}
