package com.piercingxx.calendar.calendar

import android.provider.CalendarContract.Events
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.piercingxx.calendar.calendar.Fixtures.seedCalendar
import com.piercingxx.calendar.calendar.Fixtures.seedEvent
import com.piercingxx.calendar.calendar.Fixtures.utc

@RunWith(RobolectricTestRunner::class)
class InstanceQueryTest : FakeProviderFixture() {

    private companion object {
        const val HOUR = 3_600_000L
    }

    private val windowStart = utc(2026, 8, 10, 0, 0)
    private val windowEnd = utc(2026, 8, 11, 0, 0)

    private fun seedStandardSet() {
        val calendarId = fake.seedCalendar()
        fake.seedEvent(
            calendarId,
            Events.TITLE to "before",
            Events.DTSTART to utc(2026, 8, 9, 9, 0),
            Events.DTEND to utc(2026, 8, 9, 10, 0),
        )
        // Ends exactly at windowStart → excluded by [start, end).
        fake.seedEvent(
            calendarId,
            Events.TITLE to "ends-at-start",
            Events.DTSTART to utc(2026, 8, 9, 23, 0),
            Events.DTEND to windowStart,
        )
        // Overlaps the leading edge.
        fake.seedEvent(
            calendarId,
            Events.TITLE to "overlap",
            Events.DTSTART to utc(2026, 8, 9, 23, 0),
            Events.DTEND to utc(2026, 8, 10, 1, 0),
        )
        // All-day, stored per contract as UTC midnight bounds.
        fake.seedEvent(
            calendarId,
            Events.TITLE to "release freeze",
            Events.DTSTART to windowStart,
            Events.DTEND to windowEnd,
            Events.ALL_DAY to 1L,
            Events.EVENT_TIMEZONE to "UTC",
        )
        fake.seedEvent(
            calendarId,
            Events.TITLE to "short",
            Events.DTSTART to utc(2026, 8, 10, 7, 0),
            Events.DTEND to utc(2026, 8, 10, 7, 30),
        )
        // Every second day from Aug 8 → one occurrence lands in-window (Aug 10 08:00).
        fake.seedEvent(
            calendarId,
            Events.TITLE to "gym",
            Events.DTSTART to utc(2026, 8, 8, 8, 0),
            Events.DTEND to utc(2026, 8, 8, 9, 0),
            Events.RRULE to "FREQ=DAILY;INTERVAL=2",
        )
        fake.seedEvent(
            calendarId,
            Events.TITLE to "standup",
            Events.DTSTART to utc(2026, 8, 10, 9, 0),
            Events.DTEND to utc(2026, 8, 10, 9, 15),
        )
        // Starts exactly at windowEnd → excluded by [start, end).
        fake.seedEvent(
            calendarId,
            Events.TITLE to "starts-at-end",
            Events.DTSTART to windowEnd,
            Events.DTEND to utc(2026, 8, 11, 1, 0),
        )
        fake.seedEvent(
            calendarId,
            Events.TITLE to "after",
            Events.DTSTART to utc(2026, 8, 12, 9, 0),
            Events.DTEND to utc(2026, 8, 12, 10, 0),
        )
    }

    @Test
    fun `range is half-open and results sort by instance start ascending`() = runTest {
        seedStandardSet()

        val instances = CalendarRepository(resolver).instances(windowStart, windowEnd)

        assertEquals(
            listOf("overlap", "release freeze", "short", "gym", "standup"),
            instances.map { it.title },
        )
        // Occurrences intersect [start, end): an event overlapping the leading
        // edge legitimately starts before the window, exactly as the real
        // provider reports it.
        assertTrue(
            instances.all { it.endMillis > windowStart && it.startMillis < windowEnd },
        )
    }

    @Test
    fun `all-day flag is surfaced`() = runTest {
        seedStandardSet()

        val instances = CalendarRepository(resolver).instances(windowStart, windowEnd)

        val allDay = instances.filter { it.allDay }
        assertEquals(listOf("release freeze"), allDay.map { it.title })
        assertFalse(instances.first { it.title == "standup" }.allDay)
    }

    @Test
    fun `recurring event surfaces rrule and occurrence timing`() = runTest {
        seedStandardSet()

        val gym = CalendarRepository(resolver).instances(windowStart, windowEnd)
            .first { it.title == "gym" }

        assertEquals("FREQ=DAILY;INTERVAL=2", gym.rrule)
        assertEquals(utc(2026, 8, 10, 8, 0), gym.startMillis)
        assertEquals(utc(2026, 8, 10, 9, 0), gym.endMillis)
        org.junit.Assert.assertNull(gym.duration)
    }

    @Test
    fun `third occurrence of a weekly series reports its own start and end`() = runTest {
        val calendarId = fake.seedCalendar()
        // Series anchored Wednesday Aug 5; occurrences Aug 5/12/19 at 09:00.
        fake.seedEvent(
            calendarId,
            Events.TITLE to "weekly sync",
            Events.DTSTART to utc(2026, 8, 5, 9, 0),
            Events.DTEND to utc(2026, 8, 5, 10, 0),
            Events.RRULE to "FREQ=WEEKLY",
        )

        val instances = CalendarRepository(resolver)
            .instances(utc(2026, 8, 5, 0, 0), utc(2026, 8, 26, 0, 0))
            .filter { it.title == "weekly sync" }

        assertEquals(
            listOf(
                utc(2026, 8, 5, 9, 0),
                utc(2026, 8, 12, 9, 0),
                utc(2026, 8, 19, 9, 0),
            ),
            instances.map { it.startMillis },
        )
        // Regression: the third occurrence must carry ITS extent — not the
        // series anchor (Aug 5) that DTSTART would report on every row.
        val third = instances[2]
        assertEquals(utc(2026, 8, 19, 9, 0), third.startMillis)
        assertEquals(utc(2026, 8, 19, 10, 0), third.endMillis)
    }

    @Test
    fun `hidden calendar instances are absent from results`() = runTest {
        val shown = fake.seedCalendar(displayName = "shown")
        val hidden = fake.seedCalendar(displayName = "hidden", visible = false)
        fake.seedEvent(
            shown,
            Events.TITLE to "kept",
            Events.DTSTART to utc(2026, 8, 10, 9, 0),
            Events.DTEND to utc(2026, 8, 10, 10, 0),
        )
        fake.seedEvent(
            hidden,
            Events.TITLE to "dropped",
            Events.DTSTART to utc(2026, 8, 10, 11, 0),
            Events.DTEND to utc(2026, 8, 10, 12, 0),
        )

        val titles = CalendarRepository(resolver)
            .instances(windowStart, windowEnd)
            .map { it.title }

        assertTrue(titles.contains("kept"))
        assertFalse(titles.contains("dropped"))
    }

    @Test
    fun `margin widens the window symmetrically and only when asked`() {
        val calendarId = fake.seedCalendar()
        // Ends 8 hours before windowStart; only a ≥8h margin can reach it.
        fake.seedEvent(
            calendarId,
            Events.TITLE to "late-night",
            Events.DTSTART to utc(2026, 8, 9, 12, 0),
            Events.DTEND to utc(2026, 8, 9, 16, 0),
        )

        runTest {
            val repo = CalendarRepository(resolver)
            assertFalse(repo.instances(windowStart, windowEnd).any { it.title == "late-night" })
            assertFalse(
                repo.instances(windowStart, windowEnd, marginMillis = 4 * HOUR)
                    .any { it.title == "late-night" },
            )
            assertTrue(
                repo.instances(windowStart, windowEnd, marginMillis = 12 * HOUR)
                    .any { it.title == "late-night" },
            )
        }
    }

    @Test
    fun `empty window yields empty list`() = runTest {
        fake.seedCalendar()

        val instances = CalendarRepository(resolver).instances(
            utc(2030, 1, 1, 0, 0),
            utc(2030, 1, 2, 0, 0),
        )

        assertTrue(instances.isEmpty())
    }

    @Test
    fun `instance carries view-relevant fields`() = runTest {
        val calendarId = fake.seedCalendar(id = 7, displayName = "work")
        fake.seedEvent(
            calendarId,
            Events.TITLE to "dentist",
            Events.DTSTART to utc(2026, 8, 10, 11, 30),
            Events.DTEND to utc(2026, 8, 10, 12, 15),
            Events.EVENT_LOCATION to "Hauptstraße 5",
            Events.DESCRIPTION to "bring X-rays",
            Events.AVAILABILITY to Events.AVAILABILITY_TENTATIVE.toLong(),
            Events.STATUS to Events.STATUS_CONFIRMED,
        )

        val instance = CalendarRepository(resolver)
            .instances(windowStart, windowEnd)
            .single { it.title == "dentist" }

        assertEquals(calendarId, instance.calendarId)
        assertEquals("Hauptstraße 5", instance.location)
        assertEquals("bring X-rays", instance.description)
        assertEquals(Events.AVAILABILITY_TENTATIVE, instance.availability)
        assertEquals(Events.STATUS_CONFIRMED, instance.status)
        assertEquals(utc(2026, 8, 10, 11, 30), instance.startMillis)
        assertEquals(utc(2026, 8, 10, 12, 15), instance.endMillis)
    }
}
