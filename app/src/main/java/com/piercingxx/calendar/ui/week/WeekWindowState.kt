package com.piercingxx.calendar.ui.week

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The visible week of the week view (design §8.3), owned by the screen.
 * Weeks anchor to [firstDayOfWeek] — §8.6's start day of week, exactly like
 * [com.piercingxx.calendar.ui.month.MonthScreen] — and always span exactly
 * seven columns; the grid compresses column width instead of scrolling
 * horizontally. Defaults to Monday (the §8.6 default), so call sites that do
 * not pass the setting yet keep today's behavior (15.6).
 */
class WeekWindowState(firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY) {

    /** First column of every rendered week. */
    val firstDayOfWeek: DayOfWeek = firstDayOfWeek

    var weekStartDay: Long by mutableLongStateOf(
        weekStartOf(LocalDate.now(), firstDayOfWeek).toEpochDay(),
    )
        private set

    /** Bumped on provider changes to force a re-query of the visible week. */
    var revision: Int by mutableIntStateOf(0)
        private set

    val startDate: LocalDate get() = LocalDate.ofEpochDay(weekStartDay)
    val endDate: LocalDate get() = startDate.plusDays(6)
    val dates: List<LocalDate> get() = (0..6).map { startDate.plusDays(it.toLong()) }

    fun onToday(): Boolean = dates.contains(LocalDate.now())

    fun shiftWeeks(weeks: Int) {
        weekStartDay += weeks * 7L
    }

    fun jumpTo(date: LocalDate) {
        weekStartDay = weekStartOf(date, firstDayOfWeek).toEpochDay()
    }

    fun forceRefresh() {
        revision++
    }

    companion object {
        /** First day of the week holding [date], when weeks start on [startOfWeek]. */
        fun weekStartOf(date: LocalDate, startOfWeek: DayOfWeek): LocalDate =
            date.minusDays(((date.dayOfWeek.value + 7 - startOfWeek.value) % 7).toLong())
    }
}
