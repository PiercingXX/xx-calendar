package com.piercingxx.calendar.calendar

import android.provider.CalendarContract.Attendees
import com.piercingxx.calendar.settings.AutoAddedFilterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.6 consumption filters applied above the query layer: the declined
 * predicate, the auto-added reach per [AutoAddedFilterMode], and the combined
 * pass every view and widget runs. Pure JVM — provider constants are
 * compile-time ints.
 */
class InstanceFiltersTest {

    private fun calendar(
        id: Long = 1L,
        accountName: String? = "you@gmail.com",
        displayName: String = "Personal",
    ) = CalendarSummary(
        id = id,
        accountName = accountName,
        accountType = "com.google",
        displayName = displayName,
        color = 0,
        isVisible = true,
        isWritable = true,
    )

    private fun instance(
        eventId: Long = 10L,
        calendarId: Long = 1L,
        description: String? = null,
        selfAttendeeStatus: Int = Attendees.ATTENDEE_STATUS_INVITED,
    ) = CalendarInstance(
        eventId = eventId,
        calendarId = calendarId,
        title = "Dinner",
        location = null,
        description = description,
        startMillis = 1_800_000_000_000L,
        endMillis = 1_800_003_900_000L,
        allDay = false,
        eventTimezone = "UTC",
        eventEndTimezone = null,
        rrule = null,
        duration = null,
        availability = 0,
        status = 1,
        originalId = null,
        originalInstanceTime = null,
        selfAttendeeStatus = selfAttendeeStatus,
    )

    // ---------------------------------------------------- declined predicate

    @Test
    fun `declined is exactly SELF_ATTENDEE_STATUS_DECLINED`() {
        assertTrue(InstanceFilters.isDeclined(instance(selfAttendeeStatus = Attendees.ATTENDEE_STATUS_DECLINED)))
    }

    @Test
    fun `invited accepted tentative and none are not declined`() {
        for (status in intArrayOf(
            Attendees.ATTENDEE_STATUS_INVITED,
            Attendees.ATTENDEE_STATUS_ACCEPTED,
            Attendees.ATTENDEE_STATUS_TENTATIVE,
            Attendees.ATTENDEE_STATUS_NONE,
            0,
        )) {
            assertFalse("status $status must not read as declined", InstanceFilters.isDeclined(instance(selfAttendeeStatus = status)))
        }
    }

    // ------------------------------------------------------- combined pass

    @Test
    fun `apply hides declined by default and keeps them when showDeclined`() {
        val declined = instance(selfAttendeeStatus = Attendees.ATTENDEE_STATUS_DECLINED)
        val going = instance(eventId = 11L, selfAttendeeStatus = Attendees.ATTENDEE_STATUS_ACCEPTED)

        assertEquals(listOf(going), InstanceFilters.apply(listOf(declined, going), showDeclined = false, hideAutoAdded = false, autoAddedFilterMode = AutoAddedFilterMode.OFF))
        assertEquals(listOf(declined, going), InstanceFilters.apply(listOf(declined, going), showDeclined = true, hideAutoAdded = false, autoAddedFilterMode = AutoAddedFilterMode.OFF))
    }

    // ------------------------------------------- auto-added: CALENDAR mode

    @Test
    fun `CALENDAR mode hides google group-v source calendars`() {
        val calendars = mapOf(1L to calendar(accountName = "en.uk.holiday@group.v.calendar.google.com"))
        val filtered = InstanceFilters.apply(
            listOf(instance()),
            showDeclined = true,
            hideAutoAdded = true,
            autoAddedFilterMode = AutoAddedFilterMode.CALENDAR,
            calendarsById = calendars,
        )
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `CALENDAR mode hides holiday display names but ignores booking urls in descriptions`() {
        val holidays = mapOf(2L to calendar(displayName = "Holidays in Germany"))

        assertTrue(
            InstanceFilters.isHiddenAsAutoAdded(instance(calendarId = 2L), holidays[2L], AutoAddedFilterMode.CALENDAR),
        )
        assertFalse(
            InstanceFilters.isHiddenAsAutoAdded(
                instance(description = "book at https://www.googlemail.com/calendar-render?action=TEMPLATE"),
                calendar(),
                AutoAddedFilterMode.CALENDAR,
            ),
        )
    }

    // ------------------------------------------- auto-added: METADATA mode

    @Test
    fun `METADATA mode hides booking urls found in the description`() {
        val booking = instance(description = "confirmation https://www.google.com/calendar-render?eid=x end")
        assertTrue(
            InstanceFilters.isHiddenAsAutoAdded(booking, calendar(), AutoAddedFilterMode.METADATA),
        )
    }

    @Test
    fun `METADATA mode keeps unrelated urls and plain descriptions`() {
        assertFalse(
            InstanceFilters.isHiddenAsAutoAdded(
                instance(description = "join https://meet.example.org/room/123"),
                calendar(),
                AutoAddedFilterMode.METADATA,
            ),
        )
        assertFalse(
            InstanceFilters.isHiddenAsAutoAdded(instance(description = "just notes"), calendar(), AutoAddedFilterMode.METADATA),
        )
        assertFalse(
            InstanceFilters.isHiddenAsAutoAdded(instance(), calendar(), AutoAddedFilterMode.METADATA),
        )
    }

    @Test
    fun `METADATA mode still fires on stage-1 calendar identity`() {
        val birthdays = mapOf(3L to calendar(id = 3L, displayName = "Birthdays"))
        assertTrue(
            InstanceFilters.isHiddenAsAutoAdded(instance(calendarId = 3L), birthdays[3L], AutoAddedFilterMode.METADATA),
        )
    }

    @Test
    fun `OFF mode never hides even obvious junk`() {
        val calendars = mapOf(1L to calendar(accountName = "x@group.v.calendar.google.com"))
        val filtered = InstanceFilters.apply(
            listOf(instance(description = "see https://www.googlemail.com/calendar-render")),
            showDeclined = true,
            hideAutoAdded = true,
            autoAddedFilterMode = AutoAddedFilterMode.OFF,
            calendarsById = calendars,
        )
        assertEquals(1, filtered.size)
    }

    @Test
    fun `hideAutoAdded off keeps everything whatever the mode says`() {
        val calendars = mapOf(1L to calendar(accountName = "x@group.v.calendar.google.com"))
        val rows = listOf(instance())
        assertEquals(
            rows,
            InstanceFilters.apply(rows, showDeclined = true, hideAutoAdded = false, autoAddedFilterMode = AutoAddedFilterMode.METADATA, calendarsById = calendars),
        )
    }

    // -------------------------------------------------------- url scraping

    @Test
    fun `firstUrl extracts the first http url up to a terminator`() {
        assertEquals(
            "https://www.googlemail.com/calendar-render?a=1",
            InstanceFilters.firstUrl("text https://www.googlemail.com/calendar-render?a=1 more text"),
        )
        assertEquals(
            "http://example.com/x",
            InstanceFilters.firstUrl("(http://example.com/x)"),
        )
    }

    @Test
    fun `firstUrl fails closed on non-urls and null`() {
        assertNull(InstanceFilters.firstUrl(null))
        assertNull(InstanceFilters.firstUrl("no link here"))
        assertNull(InstanceFilters.firstUrl("ftp://example.com/file"))
        assertNull(InstanceFilters.firstUrl("http://")) // scheme only, no host text
    }
}
