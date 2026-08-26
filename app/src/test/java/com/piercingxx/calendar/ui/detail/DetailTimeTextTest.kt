package com.piercingxx.calendar.ui.detail

import com.piercingxx.calendar.calendar.EventDraft
import com.piercingxx.calendar.core.TimeMath
import com.piercingxx.calendar.ui.editor.atOccurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 15.2: all-day rows store both bounds at UTC midnight (§6.4); the sheet must
 * read BOTH ends at UTC. West of UTC, a device-zone read of DTSTART turns
 * June 10 into June 9, while the old UTC read of exclusive DTEND kept the
 * real date — one span disagreeing with itself.
 *
 * F2: when the sheet was opened on an expanded occurrence, the DISPLAYED
 * times derive from that occurrence's BEGIN ([atOccurrence]), not the raw
 * parent row — tapping occurrence #3 of a weekly series must show #3's time,
 * not the series anchor's.
 *
 * Expected strings are built from the same UTC-read dates via the same
 * pattern, so the assertions hold under any test-JVM locale. Robolectric only
 * because this file's class initializers touch android.net.Uri.
 */
@RunWith(RobolectricTestRunner::class)
class DetailTimeTextTest {

    /** West of UTC: local-midnight drift territory (UTC-7/-8). */
    private val zone = ZoneId.of("America/Los_Angeles")

    private val fmt = DateTimeFormatter.ofPattern("EEE d MMM")

    private fun allDayDraft(start: Long, end: Long?): EventDraft = EventDraft(
        calendarId = 1L,
        startMillis = start,
        endMillis = end,
        eventTimezone = "UTC",
        title = "offsite",
        allDay = true,
    )

    @Test
    fun `single all-day date does not drift west of UTC`() {
        val june10 = LocalDate.of(2026, 6, 10)
        val start = TimeMath.allDayDateToStorage(june10)
        // Single day: exclusive DTEND is storage midnight after the day (§6.4).
        val end = TimeMath.allDayDateToStorage(june10.plusDays(1))

        val text = detailTimeText(allDayDraft(start, end), zone)

        assertEquals("${june10.format(fmt)} · all-day", text)
    }

    @Test
    fun `all-day duration without DTEND still renders the full span`() {
        val firstDay = LocalDate.of(2026, 6, 10)
        val lastDay = LocalDate.of(2026, 6, 12)
        val start = TimeMath.allDayDateToStorage(firstDay)
        val draft = EventDraft(
            calendarId = 1L,
            startMillis = start,
            endMillis = null,
            eventTimezone = "UTC",
            title = "retreat",
            duration = "P3D",
            allDay = true,
            rrule = "FREQ=WEEKLY",
        )

        val text = detailTimeText(draft, zone)

        assertEquals("${firstDay.format(fmt)} - ${lastDay.format(fmt)} · all-day", text)
    }

    @Test
    fun `multi-day span reads both ends at UTC and stays self-consistent`() {
        val firstDay = LocalDate.of(2026, 6, 10)
        val lastDay = LocalDate.of(2026, 6, 12)
        // Exclusive DTEND: storage midnight AFTER the last covered day (§6.4).
        val start = TimeMath.allDayDateToStorage(firstDay)
        val end = TimeMath.allDayDateToStorage(lastDay.plusDays(1))

        val text = detailTimeText(allDayDraft(start, end), zone)

        assertEquals("${firstDay.format(fmt)} - ${lastDay.format(fmt)} · all-day", text)
    }

    // ------------------------------------------------------- F2: occurrence view

    /** Weekly Mondays 14:00–14:30 UTC from Aug 17, 2026 (duration-based row). */
    private fun weeklyTimedDraft(): EventDraft {
        val anchor =
            Instant.parse("2026-08-17T14:00:00Z").toEpochMilli()
        return EventDraft(
            calendarId = 1L,
            startMillis = anchor,
            endMillis = null,
            eventTimezone = "UTC",
            title = "standup",
            duration = "PT30M",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
        )
    }

    @Test
    fun `timed occurrence shows its own slot, not the series anchor`() {
        val draft = weeklyTimedDraft()
        val third = draft.startMillis + 14 * 86_400_000L

        val shown = detailTimeText(draft.atOccurrence(third), zone)
        val anchorShown = detailTimeText(draft, zone)

        // A duration-based row has no absolute end: the sheet renders it with
        // the full range pattern.
        val start = Instant.ofEpochMilli(third).atZone(zone)
        val expected = start.format(DateTimeFormatter.ofPattern("EEE d MMM  HH:mm"))
        assertEquals(expected, shown)
        assertEquals("sanity: the anchor really is a different slot", third - 14 * 86_400_000L, draft.startMillis)
        assertEquals(anchorShown, detailTimeText(draft.atOccurrence(draft.startMillis), zone))
    }

    @Test
    fun `all-day occurrence keeps UTC date formatting at its own day`() {
        val firstDay = LocalDate.of(2026, 8, 17)
        val start = TimeMath.allDayDateToStorage(firstDay)
        val draft = allDayDraft(start, TimeMath.allDayDateToStorage(firstDay.plusDays(1)))
            .copy(rrule = "FREQ=DAILY")
        val third = start + 2 * 86_400_000L

        val shown = detailTimeText(draft.atOccurrence(third), zone)

        assertEquals(
            "${firstDay.plusDays(2).format(fmt)} · all-day",
            shown,
        )
    }

    @Test
    fun `anchor-equal or guarded cases fall back to the raw draft times`() {
        val draft = weeklyTimedDraft()
        // Anchor-equal shift is identity.
        assertEquals(detailTimeText(draft, zone), detailTimeText(draft.atOccurrence(draft.startMillis), zone))
        // A non-recurring row IS its own anchor: a stray stamp must not move it.
        val plain = draft.copy(rrule = null)
        assertEquals(
            detailTimeText(plain, zone),
            detailTimeText(plain.atOccurrence(plain.startMillis + 86_400_000L), zone),
        )
    }
}
