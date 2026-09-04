package com.piercingxx.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.YearMonth
import java.util.Locale

/**
 * Chrome contract: horizontal swipe must not open a calendars drawer, and
 * Calendars + Settings live on the three-dot overflow as their own windows.
 */
class CalendarChromeTest {

    private fun source(path: String): String =
        sequenceOf(
            File(path),
            File("src/main/java/com/piercingxx/calendar/$path"),
            File("app/src/main/java/com/piercingxx/calendar/$path"),
        ).first { it.exists() }.readText()

    private fun manifest(): String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    @Test
    fun `main chrome has no swipe-open navigation drawer`() {
        val main = source("MainActivity.kt")
        assertFalse(
            "ModalNavigationDrawer intercepts swipe-right; it must not wrap the views",
            main.contains("ModalNavigationDrawer"),
        )
        assertFalse(
            "the calendars list must not live in a swipe drawer",
            main.contains("CalendarDrawer"),
        )
    }

    @Test
    fun `overflow menu opens Calendars and Settings windows`() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("\"Calendars\""))
        assertTrue(main.contains("\"Settings\""))
        assertTrue(main.contains("CalendarsActivity"))
        assertTrue(main.contains("SettingsActivity"))
    }

    @Test
    fun `calendars activity is a non-exported settings-style window`() {
        val xml = manifest()
        assertTrue(xml.contains("android:name=\".CalendarsActivity\""))
        val activity = xml.substring(xml.indexOf("android:name=\".CalendarsActivity\""))
        assertTrue(activity.contains("android:exported=\"false\""))
    }

    @Test
    fun `chrome title tracks the visible month not wall-clock today`() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("monthYearLabel(pickerMonth)"))
        assertFalse(main.contains("currentMonthYear()"))
        assertTrue(source("ui/month/MonthScreen.kt").contains("onVisibleMonthChange"))
    }

    @Test
    fun `monthYearLabel is uppercase month and year`() {
        assertEquals("AUGUST 2026", monthYearLabel(YearMonth.of(2026, 8), Locale.US))
        assertEquals("JANUARY 2027", monthYearLabel(YearMonth.of(2027, 1), Locale.US))
    }

    @Test
    fun `day week and schedule swipe through the shared navigate helper`() {
        val day = source("ui/day/DayScreen.kt")
        val week = source("ui/week/WeekScreen.kt")
        val schedule = source("ui/schedule/ScheduleScreen.kt")
        assertTrue(day.contains("horizontalSwipeNavigate"))
        assertTrue(day.contains("state.shift(-1)"))
        assertTrue(week.contains("horizontalSwipeNavigate"))
        assertTrue(week.contains("state.shiftWeeks(-1)"))
        assertTrue(schedule.contains("horizontalSwipeNavigate"))
    }
}
