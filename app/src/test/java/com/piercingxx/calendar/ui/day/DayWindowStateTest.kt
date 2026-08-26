package com.piercingxx.calendar.ui.day

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayWindowStateTest {

    @Test
    fun `shift slides one day at a time`() {
        val state = DayWindowState()
        val start = LocalDate.of(2026, 8, 19)
        state.jumpTo(start)

        state.shift(1)
        assertEquals(LocalDate.of(2026, 8, 20), state.date)

        state.shift(-2)
        assertEquals(LocalDate.of(2026, 8, 18), state.date)
    }

    @Test
    fun `onToday is true only for the real today`() {
        val state = DayWindowState()
        state.jumpTo(LocalDate.of(1999, 12, 31))
        assertFalse(state.onToday())
        state.jumpTo(LocalDate.now())
        assertTrue(state.onToday())
    }
}
