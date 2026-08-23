package com.piercingxx.calendar.ui.editor

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.OperationApplicationException
import android.provider.CalendarContract
import android.provider.CalendarContract.Reminders
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.EventDraft
import com.piercingxx.calendar.calendar.LoadedEvent
import com.piercingxx.calendar.calendar.OpaqueColumns
import com.piercingxx.calendar.calendar.RecurrenceEditor
import com.piercingxx.calendar.core.EndCondition
import com.piercingxx.calendar.core.EventFieldEdits
import com.piercingxx.calendar.core.Frequency
import com.piercingxx.calendar.core.Resolution
import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.TimeMath
import com.piercingxx.calendar.core.Weekday
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S1/S2/S3 fix suite for the editor plumbing: explicit-clear diffs surviving
 * the §6.3 merge, the atomic Reminders batch, the rule builder's UNTIL value
 * type, and the all-day recurring DTEND contract.
 */
@RunWith(RobolectricTestRunner::class)
class EditorStateTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val eventId = 42L

    private val startMillis: Long =
        LocalDate.of(2026, 8, 17).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val recurringTimed = EventDraft(
        calendarId = 7L,
        startMillis = startMillis + 14 * 3600_000L,
        endMillis = null,
        eventTimezone = "UTC",
        eventId = eventId,
        title = "standup",
        location = "annex",
        description = "sync notes",
        duration = "PT30M",
        allDay = false,
        rrule = "FREQ=WEEKLY;BYDAY=MO",
        availability = 0,
    )

    // ---- S1: explicit clear signal ---------------------------------------

    @Test
    fun `diffEdits of identical drafts is the all-unchanged value`() {
        assertEquals(EventFieldEdits(), diffEdits(recurringTimed, recurringTimed))
    }

    @Test
    fun `diffEdits distinguishes set, clear, and untouched`() {
        val loaded = recurringTimed.copy(eventEndTimezone = "UTC")
        val updated = loaded.copy(
            title = "renamed",
            description = null,
            eventEndTimezone = null,
        )
        val edits = diffEdits(loaded, updated)

        assertEquals("renamed", edits.title)
        assertFalse(edits.clearTitle)
        assertNull(edits.description)
        assertTrue(edits.clearDescription)
        assertNull(edits.eventEndTimezone)
        assertTrue(edits.clearEventEndTimezone)
        assertNull(edits.location)
        assertFalse(edits.clearLocation)
    }

    @Test
    fun `cleared text fields survive the UpdateParentRow merge`() = runTest {
        val repository = mockk<CalendarRepository>()
        val editor = RecurrenceEditor(repository, mockk<ContentResolver>())
        val saved = slot<EventDraft>()
        coEvery { repository.loadEvent(eventId) } returns
            LoadedEvent(recurringTimed, OpaqueColumns.HeldValues.EMPTY)
        coEvery { repository.saveEvent(capture(saved), any()) } returns eventId

        val updated = recurringTimed.copy(title = null, description = null)
        editor.apply(Resolution.UpdateParentRow(eventId, diffEdits(recurringTimed, updated)))

        val merged = saved.captured
        assertNull(merged.title)
        assertNull(merged.description)
        assertEquals("annex", merged.location)
        assertEquals("PT30M", merged.duration)
    }

    @Test
    fun `cleared fields survive the exception-row merge`() = runTest {
        val repository = mockk<CalendarRepository>()
        val editor = RecurrenceEditor(repository, mockk<ContentResolver>())
        val saved = slot<EventDraft>()
        coEvery { repository.loadEvent(eventId) } returns
            LoadedEvent(recurringTimed, OpaqueColumns.HeldValues.EMPTY)
        coEvery { repository.saveEvent(capture(saved), any()) } returns 99L

        val updated = recurringTimed.copy(location = null)
        editor.apply(
            Resolution.InsertExceptionRow(
                parentEventId = eventId,
                originalInstanceTimeMillis = recurringTimed.startMillis,
                newRowEdits = diffEdits(recurringTimed, updated),
            ),
        )

        assertNull(saved.captured.location)
        assertEquals("standup", saved.captured.title)
        assertEquals("sync notes", saved.captured.description)
    }

    @Test
    fun `timed recurring switching to all-day clears stale duration`() = runTest {
        val repository = mockk<CalendarRepository>()
        val editor = RecurrenceEditor(repository, mockk<ContentResolver>())
        val saved = slot<EventDraft>()
        coEvery { repository.loadEvent(eventId) } returns
            LoadedEvent(recurringTimed, OpaqueColumns.HeldValues.EMPTY)
        coEvery { repository.saveEvent(capture(saved), any()) } returns eventId

        val dayEnd = TimeMath.allDayDateToStorage(LocalDate.of(2026, 8, 18))
        val updated = recurringTimed.copy(allDay = true, endMillis = dayEnd, duration = null)
        val edits = diffEdits(recurringTimed, updated)

        assertTrue(edits.clearDuration)
        assertNull(edits.duration)
        assertEquals(dayEnd, edits.endMillis)
        assertEquals(true, edits.allDay)

        editor.apply(Resolution.UpdateParentRow(eventId, edits))
        val merged = saved.captured
        assertNull(merged.duration)
        assertEquals(dayEnd, merged.endMillis)
        assertTrue(merged.allDay)
    }

    // ---- S2: atomic reminder replacement ---------------------------------

    @Test
    fun `replaceReminders issues exactly one delete-plus-inserts batch`() = runTest {
        val resolver = mockk<ContentResolver>()
        val ops = slot<ArrayList<ContentProviderOperation>>()
        every { resolver.applyBatch(eq(CalendarContract.AUTHORITY), capture(ops)) } returns emptyArray()

        replaceReminders(resolver, eventId, listOf(30, 10, 1440))

        val batch = ops.captured
        assertEquals(4, batch.size)
        assertTrue(batch[0].isDelete)
        assertEquals(Reminders.CONTENT_URI, batch[0].uri)
        for (i in 1..3) {
            assertTrue(batch[i].isInsert)
            assertEquals(Reminders.CONTENT_URI, batch[i].uri)
            assertTrue(batch[i].isWriteOperation)
        }
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
        verify(exactly = 0) { resolver.insert(any(), any()) }
        verify(exactly = 1) { resolver.applyBatch(any(), any<ArrayList<ContentProviderOperation>>()) }
    }

    @Test
    fun `replaceReminders propagates batch failure without partial writes`() = runTest {
        val resolver = mockk<ContentResolver>()
        every { resolver.applyBatch(any(), any<ArrayList<ContentProviderOperation>>()) } throws
            OperationApplicationException("provider refused")

        try {
            replaceReminders(resolver, eventId, listOf(15, 30))
            fail("expected the failed batch to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("reminder"))
            assertNotNull(e.cause)
        }
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
        verify(exactly = 0) { resolver.insert(any(), any()) }
    }

    // ---- S3: rule builder UNTIL value type + all-day recurring span -------

    @Test
    fun `repeat builder emits DATE-form UNTIL for an all-day series`() {
        val rule = buildCustomRule(
            frequency = Frequency.WEEKLY,
            interval = 1,
            weeklyDays = setOf(Weekday.MO),
            monthlyByDate = true,
            monthDay = 17,
            nthOrdinal = 1,
            endKind = EndKind.OnDate,
            untilDate = LocalDate.of(2026, 9, 30),
            count = 10,
            anchorDate = LocalDate.of(2026, 8, 17),
            allDay = true,
        )
        val until = rule.end as EndCondition.Until
        assertTrue(until.dateOnly)
        assertEquals(
            LocalDate.of(2026, 9, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            until.untilMillisUtc,
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260930", rule.serialize())
    }

    @Test
    fun `repeat builder emits DATE-TIME UNTIL for a timed series`() {
        val rule = buildCustomRule(
            frequency = Frequency.DAILY,
            interval = 1,
            weeklyDays = setOf(Weekday.MO),
            monthlyByDate = true,
            monthDay = 17,
            nthOrdinal = 1,
            endKind = EndKind.OnDate,
            untilDate = LocalDate.of(2026, 9, 30),
            count = 10,
            anchorDate = LocalDate.of(2026, 8, 17),
            allDay = false,
        )
        val until = rule.end as EndCondition.Until
        assertFalse(until.dateOnly)
        assertEquals(
            "FREQ=DAILY;UNTIL=20260930T000000Z",
            rule.serialize(),
        )
    }

    @Test
    fun `all-day recurring multi-day span carries exclusive DTEND`() {
        val form = EditorForm(
            title = "retreat",
            allDay = true,
            startDate = LocalDate.of(2026, 9, 1),
            startTime = null,
            endDate = LocalDate.of(2026, 9, 3),
            endTime = null,
            timezone = "UTC",
            rule = RRuleModel.daily(),
            ruleUnreadable = false,
            calendarId = 7L,
            location = "",
            description = "",
            reminders = listOf(10),
            busy = true,
        )
        val draft = buildDraft(form, original = null, duplicate = false, deviceZone = zone)

        assertNotNull(draft.rrule)
        assertNull(draft.duration)
        assertEquals(TimeMath.allDayDateToStorage(LocalDate.of(2026, 9, 4)), draft.endMillis)

        // Single-day default stays the same effective span (start + 1 day).
        val single = form.copy(endDate = form.startDate)
        assertEquals(
            TimeMath.allDayDateToStorage(LocalDate.of(2026, 9, 2)),
            buildDraft(single, original = null, duplicate = false, deviceZone = zone).endMillis,
        )

        // Timed recurring rows still carry DURATION instead of DTEND.
        val timed = form.copy(
            allDay = false,
            startDate = LocalDate.of(2026, 9, 1),
            startTime = LocalTime.of(14, 0),
            endDate = LocalDate.of(2026, 9, 1),
            endTime = LocalTime.of(14, 30),
            timezone = "Europe/Berlin",
        )
        val timedDraft = buildDraft(timed, original = null, duplicate = false, deviceZone = zone)
        assertNull(timedDraft.endMillis)
        assertEquals("PT30M", timedDraft.duration)
    }
}
