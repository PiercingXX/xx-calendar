package com.piercingxx.calendar.ui.schedule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.YearMonth

/**
 * The visible window of the schedule, in whole days. Owned by the chrome so
 * the Today button and the mini-month picker act on the same window the list
 * renders; [ScheduleScreen] queries it.
 *
 * The window is deliberately wider than what is on screen: it starts one
 * month before its anchor month and ends one month after, so scrolling near
 * either edge finds data already loaded while an extension re-query runs.
 */
class ScheduleWindowState {

    var startDay: Long by mutableStateOf(initialStart().toEpochDay())
        private set

    /** Inclusive last day of the window. */
    var endDay: Long by mutableStateOf(initialEnd().toEpochDay())
        private set

    /**
     * Bumped by whoever wants a forced re-query (provider change observers in
     * the screen). Window edits themselves need no bump — they are keys of the
     * query effect already.
     */
    var revision: Int by mutableIntStateOf(0)
        private set

    val startDate: LocalDate get() = LocalDate.ofEpochDay(startDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endDay)

    /**
     * Day the list should scroll to. Null until a jump (picker, Today, deep
     * link) or a horizontal swipe names one. A pick inside the already-loaded
     * window still sets this — that is what made the mini-month picker look
     * like it did nothing: [jumpTo] only resized the query range, and a date
     * in the current month left the range (and the scroll) unchanged.
     */
    var focusDate: LocalDate? by mutableStateOf(null)
        private set

    /** Today counts as "on screen" only when today's month lies inside the window. */
    fun onCurrentMonth(): Boolean {
        val now = YearMonth.now()
        return !now.isBefore(YearMonth.from(startDate)) && !now.isAfter(YearMonth.from(endDate))
    }

    fun extendBackward() {
        startDay = startDate.minusMonths(EXTEND_MONTHS).toEpochDay()
    }

    fun extendForward() {
        endDay = endDate.plusMonths(EXTEND_MONTHS).toEpochDay()
    }

    /** Jump so [date]'s month sits at the centre with a month of margin each way. */
    fun jumpTo(date: LocalDate) {
        val month = YearMonth.from(date)
        startDay = month.minusMonths(1).atDay(1).toEpochDay()
        endDay = month.plusMonths(1).atEndOfMonth().toEpochDay()
        focusDate = date
    }

    /**
     * Name [date] as the focused day without necessarily resizing the window.
     * Swipe navigation uses this so a one-day flick does not rebuild the
     * three-month query; a date outside the window still [jumpTo]s.
     */
    fun focusOn(date: LocalDate) {
        if (date.isBefore(startDate) || date.isAfter(endDate)) {
            jumpTo(date)
        } else {
            focusDate = date
        }
    }

    /** Month to open the picker on: the focused day, else today. */
    fun pickerMonth(): YearMonth = YearMonth.from(focusDate ?: LocalDate.now())

    fun forceRefresh() {
        revision++
    }

    private companion object {
        const val EXTEND_MONTHS = 2L

        fun initialStart(): LocalDate = YearMonth.now().minusMonths(1).atDay(1)

        fun initialEnd(): LocalDate = YearMonth.now().plusMonths(1).atEndOfMonth()
    }
}
