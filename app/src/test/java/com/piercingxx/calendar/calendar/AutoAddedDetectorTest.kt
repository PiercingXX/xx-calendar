package com.piercingxx.calendar.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The detector's contract is deliberately conservative: it fires only on its
 * stated patterns and FAILS CLOSED on everything else, because a false
 * positive hides a real event while a false negative only shows junk.
 */
@RunWith(RobolectricTestRunner::class)
class AutoAddedDetectorTest {

    private fun calendar(
        accountName: String? = "you@gmail.com",
        accountType: String? = "com.google",
        displayName: String = "Personal",
    ) = CalendarSummary(
        id = 1L,
        accountName = accountName,
        accountType = accountType,
        displayName = displayName,
        color = 0,
        isVisible = true,
        isWritable = true,
    )

    private fun instance() = CalendarInstance(
        eventId = 10L,
        calendarId = 1L,
        title = "Dinner with mum",
        location = null,
        description = null,
        startMillis = Fixtures.utc(2026, 8, 24, 18, 30),
        endMillis = Fixtures.utc(2026, 8, 24, 19, 45),
        allDay = false,
        eventTimezone = "UTC",
        eventEndTimezone = null,
        rrule = null,
        duration = null,
        availability = CalendarInstanceCopy.availabilityBusy,
        status = CalendarInstanceCopy.statusConfirmed,
        originalId = null,
        originalInstanceTime = null,
        selfAttendeeStatus = 0,
    )

    // ------------------------------------------------------- stage 1 fires

    @Test
    fun `fires on google group-v calendar account name`() {
        assertTrue(
            AutoAddedDetector.isLikelyAutoAdded(
                instance(),
                calendar(accountName = "en.uk.holiday@group.v.calendar.google.com"),
            ),
        )
    }

    @Test
    fun `fires on holidays display name regardless of case`() {
        assertTrue(
            AutoAddedDetector.isLikelyAutoAdded(
                instance(),
                calendar(displayName = "Holidays in Germany"),
            ),
        )
    }

    @Test
    fun `fires on birthdays display name`() {
        assertTrue(
            AutoAddedDetector.isLikelyAutoAdded(
                instance(),
                calendar(displayName = "Birthdays"),
            ),
        )
    }

    // ------------------------------------------------------- stage 2 fires

    @Test
    fun `fires on calendar-render booking url`() {
        assertTrue(
            AutoAddedDetector.isLikelyAutoAdded(
                instance(),
                calendar(),
                AutoAddedDetector.Metadata(url = "https://www.googlemail.com/calendar-render?action=TEMPLATE&eid=xyz"),
            ),
        )
    }

    @Test
    fun `fires on non-null custom app package`() {
        assertTrue(
            AutoAddedDetector.isLikelyAutoAdded(
                instance(),
                calendar(),
                AutoAddedDetector.Metadata(customAppPackage = "com.google.android.apps.meetings"),
            ),
        )
    }

    @Test
    fun `blank custom app package does not fire`() {
        assertFalse(
            AutoAddedDetector.isLikelyAutoAdded(
                instance(),
                calendar(),
                AutoAddedDetector.Metadata(customAppPackage = "  "),
            ),
        )
    }

    // --------------------------------------------------------- fail closed

    @Test
    fun `ordinary event on an ordinary personal calendar fails closed`() {
        assertFalse(
            AutoAddedDetector.isLikelyAutoAdded(instance(), calendar(), AutoAddedDetector.Metadata()),
        )
    }

    @Test
    fun `unknown account type with no metadata fails closed`() {
        assertFalse(
            AutoAddedDetector.isLikelyAutoAdded(
                instance(),
                calendar(accountName = "home@local", accountType = "LOCAL", displayName = "Family"),
            ),
        )
    }

    @Test
    fun `missing calendar fails closed`() {
        assertFalse(AutoAddedDetector.isLikelyAutoAdded(instance(), null))
    }

    @Test
    fun `unrelated url fails closed`() {
        assertFalse(
            AutoAddedDetector.isLikelyAutoAdded(
                instance(),
                calendar(),
                AutoAddedDetector.Metadata(url = "https://meet.example.org/room/123"),
            ),
        )
    }

    @Test
    fun `title alone never fires — titles are not evidence`() {
        val suspiciousTitle = instance().copy(title = "Flight UA234 SFO->FRA")
        assertFalse(AutoAddedDetector.isLikelyAutoAdded(suspiciousTitle, calendar()))
    }
}

/** Indirection so fixtures can reference provider constants without imports noise. */
private object CalendarInstanceCopy {
    const val availabilityBusy = 0
    const val statusConfirmed = 1
}
