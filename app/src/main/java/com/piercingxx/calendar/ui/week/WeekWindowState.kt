package com.piercingxx.calendar.ui.week

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * The visible week of the week view (design §8.3), owned by the screen.
 * Weeks are Monday-anchored — the §8.6 default start day — and always span
 * exactly seven columns; the grid compresses column width instead of
 * scrolling horizontally.
 */
class WeekWindowState {

    var weekStartDay: Long by mutableLongStateOf(mondayOf(LocalDate.now()).toEpochDay())
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
        weekStartDay = mondayOf(date).toEpochDay()
    }

    fun forceRefresh() {
        revision++
    }

    companion object {
        /** ISO Monday of the week holding [date]. */
        fun mondayOf(date: LocalDate): LocalDate =
            date.minusDays(((date.dayOfWeek.value + 6) % 7).toLong())
    }
}
