package com.piercingxx.calendar.ui.month

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthPagerTest {

    @Test
    fun `monthPagerPage is START_PAGE for the base month's today`() {
        val base = YearMonth.of(2026, 8).minusMonths(1200)
        val page = monthPagerPage(LocalDate.of(2026, 8, 15), base)
        assertEquals(1200, page)
    }

    @Test
    fun `monthPagerPage steps one page per month`() {
        val base = YearMonth.of(2026, 1)
        assertEquals(0, monthPagerPage(LocalDate.of(2026, 1, 1), base, pageCount = 24))
        assertEquals(3, monthPagerPage(LocalDate.of(2026, 4, 30), base, pageCount = 24))
        assertEquals(23, monthPagerPage(LocalDate.of(2028, 1, 1), base, pageCount = 24))
    }

    @Test
    fun `monthPagerPage clamps to the pager`() {
        val base = YearMonth.of(2026, 1)
        assertEquals(0, monthPagerPage(LocalDate.of(2020, 1, 1), base, pageCount = 12))
        assertEquals(11, monthPagerPage(LocalDate.of(2030, 1, 1), base, pageCount = 12))
    }
}
