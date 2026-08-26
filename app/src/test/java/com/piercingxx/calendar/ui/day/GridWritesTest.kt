package com.piercingxx.calendar.ui.day

import android.provider.CalendarContract.Events
import com.piercingxx.calendar.calendar.FakeProviderFixture
import com.piercingxx.calendar.calendar.Fixtures.seedCalendar
import com.piercingxx.calendar.calendar.Fixtures.seedEvent
import com.piercingxx.calendar.calendar.Fixtures.utc
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The grid's write refusals against the fake provider. 14.4: dragging one
 * block of a recurring event must NOT rewrite the series — move refuses
 * recurring rows exactly like resize always has, and the screens route the
 * user into the editor where the §6.3 scope prompt lives.
 */
@RunWith(RobolectricTestRunner::class)
class GridWritesTest : FakeProviderFixture() {

    private val start = utc(2026, 8, 17, 14)

    private fun repo() = com.piercingxx.calendar.calendar.CalendarRepository(resolver)

    private fun seedTimed(rrule: String? = null, duration: String? = null): Long {
        fake.seedCalendar()
        return fake.seedEvent(
            calendarId = 1L,
            Events.TITLE to "block",
            Events.DTSTART to start,
            Events.DTEND to if (duration == null) start + 3_600_000L else null,
            Events.DURATION to duration,
            Events.RRULE to rrule,
        )
    }

    private fun loadedStart(id: Long): Long =
        fake.events.getValue(id)[Events.DTSTART]!!.let { (it as Number).toLong() }

    @Test
    fun `moving a plain timed event rewrites its bounds`() = runTest {
        val id = seedTimed()

        val moved = repo().moveTimedEvent(id, start + 3_600_000L, start + 2 * 3_600_000L)

        assertTrue(moved)
        assertEquals(start + 3_600_000L, loadedStart(id))
    }

    @Test
    fun `moving a recurring event is refused and leaves the row untouched`() = runTest {
        val id = seedTimed(rrule = "FREQ=WEEKLY;BYDAY=MO")

        val moved = repo().moveTimedEvent(id, start + day, start + day + 3_600_000L)

        assertFalse("a drag must never shift the series", moved)
        assertEquals(start, loadedStart(id))
    }

    @Test
    fun `moving a duration-based recurring event is refused too`() = runTest {
        val id = seedTimed(rrule = "FREQ=DAILY", duration = "PT30M")

        assertFalse(repo().moveTimedEvent(id, start + day, start + day + 1_800_000L))
        assertEquals(start, loadedStart(id))
    }

    @Test
    fun `resizing a recurring event stays refused`() = runTest {
        val id = seedTimed(rrule = "FREQ=WEEKLY;BYDAY=MO")

        assertFalse(repo().resizeTimedEvent(id, start, start + 7_200_000L))
    }

    @Test
    fun `moving a vanished row reports false`() = runTest {
        fake.seedCalendar()
        assertFalse(repo().moveTimedEvent(9999L, start, start + 3_600_000L))
    }

    private val day: Long = 86_400_000L
}
