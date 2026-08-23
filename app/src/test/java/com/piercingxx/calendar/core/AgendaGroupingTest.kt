package com.piercingxx.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Day-bucket grouping for the schedule view (design §8.2): every covered date
 * gets the instance, empty days vanish, end instant stays exclusive, all-day
 * pins ahead, dates ascend.
 */
class AgendaGroupingTest {

    private val ny = ZoneId.of("America/New_York")

    // tzdata 2026b removed the "Pacific/Tonga" alias; this is the canonical +13 id.
    private val tonga = ZoneId.of("Pacific/Tongatapu")

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun timed(eventId: Long, startIso: String, endIso: String) =
        InstanceSpan(eventId, millis(startIso), millis(endIso), allDay = false)

    private fun allDay(startIso: String, endIso: String, eventId: Long = idSeq++) =
        InstanceSpan(eventId, millis(startIso), millis(endIso), allDay = true)

    private fun dates(buckets: List<DayBucket>) = buckets.map { it.date }

    // ---- single-day timed event lands under its local date

    @Test
    fun `single-day timed event buckets under its local calendar date`() {
        val span = timed(1, "2026-08-17T14:00:00Z", "2026-08-17T15:00:00Z")

        val buckets = AgendaGrouping.group(listOf(span), ny)

        assertEquals(listOf(LocalDate.of(2026, 8, 17)), dates(buckets))
        assertEquals(listOf(span), buckets.single().timed)
        assertTrue(buckets.single().allDay.isEmpty())
    }

    // ---- §8.2: days holding nothing are dropped entirely

    @Test
    fun `empty days between events are dropped from the output`() {
        val monday = timed(1, "2026-08-17T14:00:00Z", "2026-08-17T15:00:00Z")
        val wednesday = timed(2, "2026-08-19T14:00:00Z", "2026-08-19T15:00:00Z")

        val buckets = AgendaGrouping.group(listOf(monday, wednesday), ny)

        assertEquals(
            listOf(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 19)),
            dates(buckets),
        )
    }

    // ---- §8.2: multi-day spans every covered date; end instant is exclusive

    @Test
    fun `multi-day event covers each date it touches up to but excluding the end instant`() {
        val span = timed(1, "2026-08-18T09:00:00-04:00", "2026-08-20T09:00:00-04:00")

        val buckets = AgendaGrouping.group(listOf(span), ny)

        assertEquals(
            listOf(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 19), LocalDate.of(2026, 8, 20)),
            dates(buckets),
        )
        assertTrue(buckets.all { it.timed == listOf(span) })
    }

    @Test
    fun `event ending exactly at local midnight does not bleed into the next day`() {
        val span = timed(1, "2026-08-18T09:00:00-04:00", "2026-08-19T00:00:00-04:00")

        val buckets = AgendaGrouping.group(listOf(span), ny)

        assertEquals(listOf(LocalDate.of(2026, 8, 18)), dates(buckets))
    }

    @Test
    fun `zero-length event covers only its start date`() {
        val span = timed(1, "2026-08-18T09:00:00-04:00", "2026-08-18T09:00:00-04:00")

        val buckets = AgendaGrouping.group(listOf(span), ny)

        assertEquals(listOf(LocalDate.of(2026, 8, 18)), dates(buckets))
    }

    // ---- ordering within a day: all-day pinned ahead; ties fall to eventId

    @Test
    fun `all-day instances are pinned ahead of timed ones within a day`() {
        val lateTimed = timed(1, "2026-08-18T09:00:00-04:00", "2026-08-18T10:00:00-04:00")
        val earlyTimed = timed(2, "2026-08-18T07:00:00-04:00", "2026-08-18T08:00:00-04:00")
        val allDayEvent = InstanceSpan(3, millis("2026-08-18T00:00:00Z"), millis("2026-08-19T00:00:00Z"), allDay = true)
        val timedFirst = timed(4, "2026-08-18T06:00:00-04:00", "2026-08-18T06:30:00-04:00")

        val bucket = AgendaGrouping.group(
            listOf(lateTimed, allDayEvent, timedFirst, earlyTimed),
            ny,
        ).single()

        assertEquals(listOf(allDayEvent), bucket.allDay)
        assertEquals(listOf(timedFirst, earlyTimed, lateTimed), bucket.timed)
    }

    @Test
    fun `same-start ties order by event id ascending within a list`() {
        val laterId = InstanceSpan(9, millis("2026-08-18T00:00:00Z"), millis("2026-08-19T00:00:00Z"), allDay = true)
        val earlierId = InstanceSpan(2, millis("2026-08-18T00:00:00Z"), millis("2026-08-19T00:00:00Z"), allDay = true)
        val timedLaterId = timed(7, "2026-08-18T13:00:00Z", "2026-08-18T14:00:00Z")
        val timedEarlierId = timed(3, "2026-08-18T13:00:00Z", "2026-08-18T14:00:00Z")

        val bucket = AgendaGrouping.group(listOf(laterId, timedLaterId, earlierId, timedEarlierId), ny).single()

        assertEquals(listOf(2L, 9L), bucket.allDay.map { it.eventId })
        assertEquals(listOf(3L, 7L), bucket.timed.map { it.eventId })
    }

    // ---- buckets ascend by date regardless of input order

    @Test
    fun `buckets come back ascending by date even when input arrives shuffled`() {
        val thursday = timed(3, "2026-08-20T14:00:00Z", "2026-08-20T15:00:00Z")
        val monday = timed(1, "2026-08-17T14:00:00Z", "2026-08-17T15:00:00Z")
        val wednesday = timed(2, "2026-08-19T14:00:00Z", "2026-08-19T15:00:00Z")

        val buckets = AgendaGrouping.group(listOf(thursday, monday, wednesday), ny)

        assertEquals(dates(buckets), dates(buckets).sorted())
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 19),
                LocalDate.of(2026, 8, 20),
            ),
            dates(buckets),
        )
    }

    // ---- §6.4 + §8.2: all-day coverage reads at UTC, never the display zone

    @Test
    fun `spanning all-day event groups identically at UTC plus thirteen because coverage reads at UTC`() {
        val twoDayAllDay = InstanceSpan(1, millis("2026-06-15T00:00:00Z"), millis("2026-06-17T00:00:00Z"), allDay = true)

        val buckets = AgendaGrouping.group(listOf(twoDayAllDay), tonga)

        assertEquals(
            listOf(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 16)),
            dates(buckets),
        )
        assertEquals(
            "grouping must not shift with the display zone",
            AgendaGrouping.group(listOf(twoDayAllDay), ZoneOffset.UTC),
            buckets,
        )
    }

    // ---- month edges are just dates; spanning must not care

    @Test
    fun `span across a month edge covers every date on both sides`() {
        val span = timed(1, "2026-05-31T22:00:00-04:00", "2026-06-02T02:00:00-04:00")

        val buckets = AgendaGrouping.group(listOf(span), ny)

        assertEquals(
            listOf(
                LocalDate.of(2026, 5, 31),
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 2),
            ),
            dates(buckets),
        )
    }

    // ---- S2 safety cap: a pathological multi-year span cannot flood buckets

    @Test
    fun `span covering years is capped at its first year of covered dates`() {
        val pathological = timed(1, "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z")

        val buckets = AgendaGrouping.group(listOf(pathological), ZoneOffset.UTC)

        assertEquals(366, buckets.size)
        assertEquals(LocalDate.of(2026, 1, 1), buckets.first().date)
        assertEquals(LocalDate.of(2027, 1, 1), buckets.last().date)
        assertTrue(buckets.all { it.timed == listOf(pathological) })
    }

    @Test
    fun `window clamp produces only the buckets intersecting the agenda window`() {
        val pathological = timed(1, "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z")
        val june2026 = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 30)

        val buckets = AgendaGrouping.group(listOf(pathological), ZoneOffset.UTC, window = june2026)

        assertEquals(
            (1..30).map { LocalDate.of(2026, 6, it) },
            dates(buckets),
        )
        assertTrue(
            "a window disjoint from the span yields no buckets",
            AgendaGrouping.group(
                listOf(pathological),
                ny,
                window = LocalDate.of(2030, 1, 1)..LocalDate.of(2030, 1, 31),
            ).isEmpty(),
        )
    }

    private companion object {
        var idSeq: Long = 100L
    }
}
