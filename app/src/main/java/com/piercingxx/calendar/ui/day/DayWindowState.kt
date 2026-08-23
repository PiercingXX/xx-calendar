package com.piercingxx.calendar.ui.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * The visible day of the day view (design §8.3), owned by the screen —
 * mirroring [com.piercingxx.calendar.ui.schedule.ScheduleWindowState]'s
 * state-holder pattern so a later chrome wave can hoist it unchanged.
 */
class DayWindowState {

    var epochDay: Long by mutableLongStateOf(LocalDate.now().toEpochDay())
        private set

    /** Bumped on provider changes to force a re-query of the visible day. */
    var revision: Int by mutableIntStateOf(0)
        private set

    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)

    fun onToday(): Boolean = date == LocalDate.now()

    fun shift(days: Int) {
        epochDay += days
    }

    fun jumpTo(date: LocalDate) {
        epochDay = date.toEpochDay()
    }

    fun forceRefresh() {
        revision++
    }
}
