package com.piercingxx.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All-day conversion contract (design §6.4): storage is always UTC midnight,
 * display dates never drift with the device zone, DST midnights resolve to
 * valid instants, timezone visibility compares observed offsets (aliases fold).
 */
class TimeMathTest {

    // ---- §6.4: date -> storage -> date identity; storage always UTC midnight

    @Test
    fun `all-day round trip holds for a user at UTC minus eleven and storage stays UTC midnight`() {
        val pacificMinusEleven = ZoneId.of("Etc/GMT+11")

        // 2026-06-16T10:00:00Z is 23:00 on June 15 for this user — the date they see.
        val seenDate = TimeMath.localDateOf(millis("2026-06-16T10:00:00Z"), pacificMinusEleven)
        assertEquals(LocalDate.of(2026, 6, 15), seenDate)

        val storage = TimeMath.allDayDateToStorage(seenDate)

        assertEquals(utcMidnightMillis(LocalDate.of(2026, 6, 15)), storage)
        assertEquals(LocalDate.of(2026, 6, 15), TimeMath.storageToAllDayDate(storage))
    }

    @Test
    fun `all-day round trip holds at UTC and storage stays UTC midnight`() {
        val date = LocalDate.of(2026, 1, 31)

        val storage = TimeMath.allDayDateToStorage(date)

        assertEquals(utcMidnightMillis(date), storage)
        assertEquals(date, TimeMath.storageToAllDayDate(storage))
    }

    @Test
    fun `all-day round trip holds for a user at UTC plus thirteen and storage stays UTC midnight`() {
        // tzdata 2026b removed the "Pacific/Tonga" alias; Tongatapu is the canonical id.
        val tonga = ZoneId.of("Pacific/Tongatapu")

        // 2026-06-14T12:00:00Z is already 01:00 on June 15 for this user.
        val seenDate = TimeMath.localDateOf(millis("2026-06-14T12:00:00Z"), tonga)
        assertEquals(LocalDate.of(2026, 6, 15), seenDate)

        val storage = TimeMath.allDayDateToStorage(seenDate)

        assertEquals(utcMidnightMillis(LocalDate.of(2026, 6, 15)), storage)
        assertEquals(LocalDate.of(2026, 6, 15), TimeMath.storageToAllDayDate(storage))
    }

    // ---- §6.4: DST transitions — local midnight resolves to a valid instant,
    //      and reading it back returns the original calendar date.

    @Test
    fun `spring-forward midnight in New York maps to its pre-gap instant and back`() {
        val ny = ZoneId.of("America/New_York")
        val d = LocalDate.of(2026, 3, 8)

        val start = TimeMath.localDayStart(d, ny)

        assertEquals(
            "2026-03-08 local midnight is EST (-05:00), i.e. 05:00Z",
            Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(),
            start,
        )
        assertEquals(d, TimeMath.localDateOf(start, ny))
    }

    @Test
    fun `fall-back midnight in New York maps to its EDT instant and back`() {
        val ny = ZoneId.of("America/New_York")
        val d = LocalDate.of(2026, 11, 1)

        val start = TimeMath.localDayStart(d, ny)

        assertEquals(
            "2026-11-01 local midnight is EDT (-04:00), i.e. 04:00Z",
            Instant.parse("2026-11-01T04:00:00Z").toEpochMilli(),
            start,
        )
        assertEquals(d, TimeMath.localDateOf(start, ny))
    }

    // ---- §6.4: detail-sheet timezone visibility, compared on normalized ids

    @Test
    fun `identical zone ids hide the timezone`() {
        assertFalse(TimeMath.shouldRenderTimezone("America/New_York", "America/New_York"))
    }

    /**
     * Aliases fold: zones are compared by observed offsets across a year of
     * DST behavior, so "US/Eastern" hides against "America/New_York".
     */
    @Test
    fun `alias id versus canonical id hides the timezone`() {
        assertFalse(TimeMath.shouldRenderTimezone("US/Eastern", "America/New_York"))
    }

    @Test
    fun `genuinely different zones render the timezone`() {
        assertTrue(TimeMath.shouldRenderTimezone("America/New_York", "Pacific/Tonga"))
    }

    @Test
    fun `unparseable id against a valid one renders the timezone`() {
        assertTrue(TimeMath.shouldRenderTimezone("Not/A-Zone", "America/New_York"))
        assertTrue(TimeMath.shouldRenderTimezone("America/New_York", "Not/A-Zone"))
    }

    @Test
    fun `two unparseable ids compare equal and hide the timezone`() {
        assertFalse(TimeMath.shouldRenderTimezone("Not/A-Zone", "Also/Bogus"))
    }

    private companion object {
        fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

        /** UTC midnight by definition — epoch day times milliseconds per day. */
        fun utcMidnightMillis(date: LocalDate): Long = date.toEpochDay() * 86_400_000L
    }
}
