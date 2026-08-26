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
import com.piercingxx.calendar.core.RecurrenceScope
import com.piercingxx.calendar.core.Resolution
import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.RuleParse
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
import org.junit.Assert.assertSame
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
    fun `diffEdits carries a calendar change and nothing else when only it moved`() {
        val edits = diffEdits(recurringTimed, recurringTimed.copy(calendarId = 9L))

        assertEquals(9L, edits.calendarId)
        assertEquals(EventFieldEdits(calendarId = 9L), edits)
    }

    // ---- 14.1: occurrence-anchored prefill baseline ------------------------

    @Test
    fun `atOccurrence shifts a recurring series draft to the tapped occurrence`() {
        val third = recurringTimed.copy(
            startMillis = recurringTimed.startMillis + 14 * day,
            endMillis = null,
        )
        val occurrenceStart = recurringTimed.startMillis + 14 * day

        val anchored = LoadedEvent(recurringTimed, OpaqueColumns.HeldValues.EMPTY)
            .atOccurrence(occurrenceStart)

        assertEquals(occurrenceStart, anchored.draft.startMillis)
        assertEquals("duration-based extent stays duration-based", "PT30M", anchored.draft.duration)
        assertEquals(recurringTimed.eventId, anchored.draft.eventId)
        // Untouched times relative to the anchor produce no diff.
        assertEquals(
            EventFieldEdits(),
            diffEdits(anchored.draft, buildDraft(
                EditorForm.fromLoaded(anchored, zone),
                original = anchored,
                duplicate = false,
                deviceZone = zone,
            )),
        )
    }

    @Test
    fun `atOccurrence shifts an absolute DTEND by the same delta`() {
        val dtendRow = EventDraft(
            calendarId = 7L,
            startMillis = startMillis,
            endMillis = startMillis + 3_600_000L,
            eventTimezone = "UTC",
            eventId = eventId,
            title = "workshop",
            rrule = "FREQ=DAILY",
        )
        val occurrenceStart = startMillis + 3 * day

        val anchored = LoadedEvent(dtendRow, OpaqueColumns.HeldValues.EMPTY)
            .atOccurrence(occurrenceStart)

        assertEquals(occurrenceStart, anchored.draft.startMillis)
        assertEquals(occurrenceStart + 3_600_000L, anchored.draft.endMillis)
    }

    @Test
    fun `atOccurrence leaves non-recurring rows and null starts untouched`() {
        val plain = LoadedEvent(recurringTimed.copy(rrule = null), OpaqueColumns.HeldValues.EMPTY)
        val recurring = LoadedEvent(recurringTimed, OpaqueColumns.HeldValues.EMPTY)

        // A non-recurring row IS its own anchor: never shifted.
        assertSame(plain, plain.atOccurrence(startMillis + day))
        // Null or already-equal starts are identity too.
        assertSame(recurring, recurring.atOccurrence(null))
        assertSame(recurring, recurring.atOccurrence(recurringTimed.startMillis))
    }

    // ---- F1: all-events saves shift the pattern instead of moving the anchor

    private fun parsedRule(draft: EventDraft): RRuleModel =
        (RRuleModel.parse(requireNotNull(draft.rrule)) as RuleParse.Parsed).rule

    @Test
    fun `all-events time edit at a later occurrence becomes an anchor delta`() {
        val occ3 = recurringTimed.startMillis + 14 * day
        val original = LoadedEvent(recurringTimed, OpaqueColumns.HeldValues.EMPTY)
        val baseline = original.atOccurrence(occ3)
        val updated = baseline.draft.copy(startMillis = occ3 + 3_600_000L)

        val resolution = resolveScopedEdit(
            original,
            parsedRule(recurringTimed),
            occ3,
            RecurrenceScope.AllEvents,
            updated,
        )

        val edits = (resolution as Resolution.UpdateParentRow).edits
        // The parent start shifts by the SAME hour as the tapped slot — it
        // does not jump to the tapped day. The absolute would have been
        // occ3 + 1h; the delta lands at anchor + 1h.
        assertEquals(recurringTimed.startMillis + 3_600_000L, edits.startMillis)
        assertNull("duration rows carry no end to edit", edits.endMillis)
        assertFalse(edits.clearEndMillis)
        assertNull("same length means no duration edit", edits.duration)
    }

    @Test
    fun `all-events save with untouched times never touches the anchor`() {
        val occ3 = recurringTimed.startMillis + 14 * day
        val original = LoadedEvent(recurringTimed, OpaqueColumns.HeldValues.EMPTY)
        val baseline = original.atOccurrence(occ3)
        val updated = baseline.draft.copy(title = "renamed series")

        val resolution = resolveScopedEdit(
            original,
            parsedRule(recurringTimed),
            occ3,
            RecurrenceScope.AllEvents,
            updated,
        )

        // Title only: no time fields may ride along, or the parent DTSTART
        // would move as a side effect of where the editor was opened.
        assertEquals(
            EventFieldEdits(title = "renamed series"),
            (resolution as Resolution.UpdateParentRow).edits,
        )
    }

    @Test
    fun `all-events end-time edit keeps the start and carries the new length`() {
        val occ2 = recurringTimed.startMillis + 7 * day
        val original = LoadedEvent(recurringTimed, OpaqueColumns.HeldValues.EMPTY)
        val baseline = original.atOccurrence(occ2)
        val updated = baseline.draft.copy(duration = "PT45M")

        val edits = diffEdits(baseline.draft, updated).reanchoredToSeries(
            parent = recurringTimed,
            baseline = baseline.draft,
            updated = updated,
        )

        assertNull("start untouched -> anchor unmoved", edits.startMillis)
        assertEquals("a length is anchor-free and travels verbatim", "PT45M", edits.duration)
    }

    @Test
    fun `this-instance scope still stamps the occurrence with absolute times`() {
        val occ3 = recurringTimed.startMillis + 14 * day
        val original = LoadedEvent(recurringTimed, OpaqueColumns.HeldValues.EMPTY)
        val baseline = original.atOccurrence(occ3)
        val updated = baseline.draft.copy(startMillis = occ3 + 3_600_000L)

        val resolution = resolveScopedEdit(
            original,
            parsedRule(recurringTimed),
            occ3,
            RecurrenceScope.ThisInstance,
            updated,
        )

        // Exception rows WANT the tapped slot's absolutes — no re-anchoring.
        val exception = resolution as Resolution.InsertExceptionRow
        assertEquals(occ3, exception.originalInstanceTimeMillis)
        assertEquals(occ3 + 3_600_000L, exception.newRowEdits.startMillis)
    }

    private val day: Long = 86_400_000L

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
    fun `all-day recurring multi-day span carries P n D duration`() {
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
        assertEquals("P3D", draft.duration)
        assertNull(draft.endMillis)

        val single = buildDraft(
            form.copy(endDate = form.startDate),
            original = null,
            duplicate = false,
            deviceZone = zone,
        )
        assertEquals("P1D", single.duration)
        assertNull(single.endMillis)

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

    @Test
    fun `fromLoaded reads all-day duration when DTEND is absent`() {
        val start = TimeMath.allDayDateToStorage(LocalDate.of(2026, 9, 1))
        val form = EditorForm.fromLoaded(
            LoadedEvent(
                EventDraft(
                    calendarId = 7L,
                    startMillis = start,
                    endMillis = null,
                    eventTimezone = "UTC",
                    eventId = eventId,
                    title = "retreat",
                    duration = "P3D",
                    allDay = true,
                    rrule = "FREQ=WEEKLY",
                ),
                OpaqueColumns.HeldValues.EMPTY,
            ),
            zone,
        )
        assertEquals(LocalDate.of(2026, 9, 1), form.startDate)
        assertEquals(LocalDate.of(2026, 9, 3), form.endDate)
        assertTrue(form.allDay)
    }

    @Test
    fun `fromLoaded still prefers exclusive DTEND over duration for all-day`() {
        val start = TimeMath.allDayDateToStorage(LocalDate.of(2026, 9, 1))
        val form = EditorForm.fromLoaded(
            LoadedEvent(
                EventDraft(
                    calendarId = 7L,
                    startMillis = start,
                    endMillis = TimeMath.allDayDateToStorage(LocalDate.of(2026, 9, 2)),
                    eventTimezone = "UTC",
                    eventId = eventId,
                    title = "holiday",
                    duration = "P3D",
                    allDay = true,
                ),
                OpaqueColumns.HeldValues.EMPTY,
            ),
            zone,
        )
        assertEquals(LocalDate.of(2026, 9, 1), form.endDate)
    }

    @Test
    fun `fromLoaded models Google WKST yearly birthdays`() {
        val draft = EventDraft(
            calendarId = 1L,
            startMillis = TimeMath.allDayDateToStorage(LocalDate.of(2026, 7, 3)),
            endMillis = TimeMath.allDayDateToStorage(LocalDate.of(2026, 7, 4)),
            eventTimezone = "UTC",
            eventId = 1L,
            title = "Monkey",
            allDay = true,
            rrule = "FREQ=YEARLY;WKST=MO;BYMONTHDAY=3;BYMONTH=7",
        )
        val form = EditorForm.fromLoaded(
            LoadedEvent(draft, OpaqueColumns.HeldValues.EMPTY),
            zone,
        )
        assertFalse(form.ruleUnreadable)
        assertNotNull(form.rule)
        assertEquals(Frequency.YEARLY, form.rule!!.frequency)
        assertEquals(Weekday.MO, form.rule!!.weekStart)
        assertEquals(listOf(7), form.rule!!.byMonth)
        assertEquals(listOf(3), form.rule!!.byMonthDay)
        assertEquals(
            "FREQ=YEARLY;BYMONTHDAY=3;BYMONTH=7;WKST=MO",
            form.rule!!.serialize(),
        )
    }

    @Test
    fun `fromLoaded models a Google weekly-at-the-same-time series`() {
        // The shape that produced "this app does not model" on a real
        // DAVx⁵/Google weekly event: FREQ + WKST, no BYDAY, DTSTART is the day.
        val form = EditorForm.fromLoaded(
            LoadedEvent(
                recurringTimed.copy(rrule = "FREQ=WEEKLY;WKST=MO"),
                OpaqueColumns.HeldValues.EMPTY,
            ),
            zone,
        )
        assertFalse(
            "WKST weekly must be editable, not refused as unreadable",
            form.ruleUnreadable,
        )
        assertEquals(Frequency.WEEKLY, form.rule!!.frequency)
        assertEquals(Weekday.MO, form.rule!!.weekStart)
        assertTrue(form.rule!!.byDay.isEmpty())
        assertEquals("FREQ=WEEKLY;WKST=MO", form.rule!!.serialize())
    }

    @Test
    fun `fromLoaded models every-other-week WKST=SU`() {
        val form = EditorForm.fromLoaded(
            LoadedEvent(recurringTimed.copy(rrule = "FREQ=WEEKLY;INTERVAL=2;WKST=SU;BYDAY=SU"), OpaqueColumns.HeldValues.EMPTY),
            zone,
        )
        assertFalse(form.ruleUnreadable)
        assertEquals(2, form.rule!!.interval)
        assertEquals(Weekday.SU, form.rule!!.weekStart)
        assertEquals(listOf(Weekday.SU), form.rule!!.byDay.map { it.weekday })
    }

    @Test
    fun `custom builder keeps WKST and yearly month on a loaded birthday`() {
        val initial = (com.piercingxx.calendar.core.RRuleModel.parse(
            "FREQ=YEARLY;WKST=MO;BYMONTHDAY=3;BYMONTH=7",
        ) as com.piercingxx.calendar.core.RuleParse.Parsed).rule
        val rebuilt = buildCustomRule(
            frequency = Frequency.YEARLY,
            interval = 1,
            weeklyDays = emptySet(),
            monthlyByDate = true,
            monthDay = 3,
            nthOrdinal = 1,
            endKind = EndKind.Never,
            untilDate = LocalDate.of(2026, 9, 30),
            count = 10,
            anchorDate = LocalDate.of(2026, 7, 3),
            allDay = true,
            weekStart = initial.weekStart,
            byMonth = initial.byMonth,
            byMonthDayYearly = initial.byMonthDay,
        )
        assertEquals(initial, rebuilt)
    }

    @Test
    fun `toggleWeeklyDay cannot drop the last day`() {
        val only = setOf(Weekday.MO)
        assertEquals(only, toggleWeeklyDay(only, Weekday.MO, wantOn = false))
        assertEquals(setOf(Weekday.MO, Weekday.WE), toggleWeeklyDay(only, Weekday.WE, wantOn = true))
        assertEquals(
            setOf(Weekday.MO),
            toggleWeeklyDay(setOf(Weekday.MO, Weekday.WE), Weekday.WE, wantOn = false),
        )
    }
}
