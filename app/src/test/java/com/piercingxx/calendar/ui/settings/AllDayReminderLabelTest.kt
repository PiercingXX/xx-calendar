package com.piercingxx.calendar.ui.settings

import com.piercingxx.calendar.settings.AllDayNotification
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 15.5: the all-day anchor label must not call `daysBefore == 0` — which
 * fires on the event's own calendar date — "day before". The shipped presets
 * include two same-day stops; both were labelled wrong before the branch.
 */
class AllDayReminderLabelTest {

    @Test
    fun `zero days before is same day`() {
        assertEquals(
            "%02d:00, %s".format(Locale.ROOT, 18, "same day"),
            allDayLabel(AllDayNotification(hourOfDay = 18, daysBefore = 0)),
        )
        assertEquals(
            "%02d:00, %s".format(Locale.ROOT, 8, "same day"),
            allDayLabel(AllDayNotification(hourOfDay = 8, daysBefore = 0)),
        )
    }

    @Test
    fun `one day before keeps its label`() {
        assertEquals(
            "%02d:00, %s".format(Locale.ROOT, 9, "day before"),
            allDayLabel(AllDayNotification(hourOfDay = 9, daysBefore = 1)),
        )
    }

    @Test
    fun `more than one day names the count`() {
        assertEquals(
            "%02d:00, %s".format(Locale.ROOT, 12, "2 days before"),
            allDayLabel(AllDayNotification(hourOfDay = 12, daysBefore = 2)),
        )
        assertEquals(
            "%02d:00, %s".format(Locale.ROOT, 18, "7 days before"),
            allDayLabel(AllDayNotification(hourOfDay = 18, daysBefore = 7)),
        )
    }
}
