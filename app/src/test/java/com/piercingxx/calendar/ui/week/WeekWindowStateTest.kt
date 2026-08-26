package com.piercingxx.calendar.ui.week

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 15.6: the week grid follows §8.6's start day of week instead of hard ISO
 * Monday. A SUNDAY window must put Sunday in column 0; the Monday default
 * keeps every existing call site on today's behavior until the navigation
 * call site passes the live setting.
 */
class WeekWindowStateTest {

    /** Wednesday, 19 August 2026. */
    private val wednesday = LocalDate.of(2026, 8, 19)

    @Test
    fun `default window is Monday-anchored`() {
        val state = WeekWindowState()
        state.jumpTo(wednesday)

        assertEquals(DayOfWeek.MONDAY, state.startDate.dayOfWeek)
        assertEquals(LocalDate.of(2026, 8, 17), state.startDate)
        assertEquals(LocalDate.of(2026, 8, 23), state.endDate)
    }

    @Test
    fun `sunday first-day window puts sunday in column zero`() {
        val state = WeekWindowState(DayOfWeek.SUNDAY)
        state.jumpTo(wednesday)

        val dates = state.dates
        assertEquals(7, dates.size)
        assertEquals(LocalDate.of(2026, 8, 16), dates[0])
        assertEquals(DayOfWeek.SUNDAY, dates[0].dayOfWeek)
        assertEquals(LocalDate.of(2026, 8, 22), dates[6])
        assertTrue(dates.contains(wednesday))
    }

    @Test
    fun `every anchor lands its own weekday in column zero within six days`() {
        for (first in DayOfWeek.entries) {
            val start = WeekWindowState.weekStartOf(wednesday, first)

            assertEquals("anchor $first must head its own week", first, start.dayOfWeek)
            assertFalse(start.isAfter(wednesday))
            assertTrue("window is seven days wide", wednesday.toEpochDay() - start.toEpochDay() < 7)
            assertEquals(first, WeekWindowState(first).let {
                it.jumpTo(wednesday)
                it.startDate.dayOfWeek
            })
        }
    }

    @Test
    fun `shiftWeeks slides whole windows regardless of anchor`() {
        val state = WeekWindowState(DayOfWeek.SUNDAY)
        state.jumpTo(wednesday)

        state.shiftWeeks(1)
        assertEquals(LocalDate.of(2026, 8, 23), state.startDate)

        state.shiftWeeks(-2)
        assertEquals(LocalDate.of(2026, 8, 9), state.startDate)
    }

    @Test
    fun `onToday is true only when this week holds today`() {
        val state = WeekWindowState(DayOfWeek.SUNDAY)
        state.jumpTo(LocalDate.of(1999, 12, 31))

        assertFalse(state.onToday())
        state.jumpTo(LocalDate.now())
        assertTrue(state.onToday())
    }
}
