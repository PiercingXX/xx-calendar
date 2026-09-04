package com.piercingxx.calendar.ui.schedule

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mini-month picker talks to [ScheduleWindowState.jumpTo]. A pick inside
 * the already-loaded window must still name [ScheduleWindowState.focusDate]
 * so the list scrolls — resizing the query range is a no-op for that case.
 */
class ScheduleWindowStateTest {

    @Test
    fun `jumpTo inside the current window still sets focusDate`() {
        val state = ScheduleWindowState()
        assertNull(state.focusDate)
        val today = LocalDate.now()
        state.jumpTo(today)
        assertEquals(today, state.focusDate)
        val before = state.startDay to state.endDay
        state.jumpTo(today.plusDays(3))
        assertEquals(today.plusDays(3), state.focusDate)
        assertEquals("window already covered this month", before, state.startDay to state.endDay)
    }

    @Test
    fun `jumpTo a far date recenters the window on that month`() {
        val state = ScheduleWindowState()
        val target = LocalDate.of(2027, 3, 15)
        state.jumpTo(target)
        assertEquals(target, state.focusDate)
        assertEquals(YearMonth.of(2027, 2).atDay(1), state.startDate)
        assertEquals(YearMonth.of(2027, 4).atEndOfMonth(), state.endDate)
    }

    @Test
    fun `focusOn inside the window does not resize it`() {
        val state = ScheduleWindowState()
        val today = LocalDate.now()
        state.jumpTo(today)
        val before = state.startDay to state.endDay
        state.focusOn(today.plusDays(1))
        assertEquals(today.plusDays(1), state.focusDate)
        assertEquals(before, state.startDay to state.endDay)
    }
}
