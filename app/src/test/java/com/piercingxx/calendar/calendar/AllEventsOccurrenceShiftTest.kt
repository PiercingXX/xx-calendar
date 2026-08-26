package com.piercingxx.calendar.calendar

import android.provider.CalendarContract.Events
import com.piercingxx.calendar.calendar.Fixtures.seedCalendar
import com.piercingxx.calendar.calendar.Fixtures.seedEvent
import com.piercingxx.calendar.calendar.Fixtures.utc
import com.piercingxx.calendar.core.EventFieldEdits
import com.piercingxx.calendar.core.RecurrenceScope
import com.piercingxx.calendar.core.Resolution
import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.RuleParse
import com.piercingxx.calendar.ui.editor.EditorForm
import com.piercingxx.calendar.ui.editor.atOccurrence
import com.piercingxx.calendar.ui.editor.buildDraft
import com.piercingxx.calendar.ui.editor.resolveScopedEdit
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F1 end-to-end on the fake provider: an ALL-EVENTS save opened at a NON-FIRST
 * occurrence must shift the whole pattern by the edited delta — never drag the
 * parent's DTSTART to the tapped slot, which truncated every head occurrence.
 * Drives the production pipeline (atOccurrence baseline -> diffEdits ->
 * resolveScopedEdit -> ScopeResolver -> RecurrenceEditor), minus composables.
 */
@RunWith(RobolectricTestRunner::class)
class AllEventsOccurrenceShiftTest : FakeProviderFixture() {

    private val day = 86_400_000L
    private val hour = 3_600_000L

    /** Daily 09:00–09:30 UTC from Aug 1 2026, five occurrences. */
    private val anchor = utc(2026, 8, 1, 9)
    private val occ3 = anchor + 2 * day

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun seedDaily(): Long {
        fake.seedCalendar()
        return fake.seedEvent(
            calendarId = 1L,
            Events.TITLE to "daily standup",
            Events.DTSTART to anchor,
            Events.DTEND to null,
            Events.DURATION to "PT30M",
            Events.RRULE to "FREQ=DAILY;COUNT=5",
            Events.EVENT_TIMEZONE to "UTC",
        )
    }

    private fun repository() = CalendarRepository(resolver)

    private fun ruleOf(raw: String) = (RRuleModel.parse(raw) as RuleParse.Parsed).rule

    /** The form-updated draft with the occurrence's start moved +[delta] (same length). */
    private fun shiftedDraft(parentId: Long, delta: Long): EventDraft {
        val loaded = runBlocking { repository().loadEvent(parentId) }!!
        val baseline = loaded.atOccurrence(occ3)
        val newStart = Instant.ofEpochMilli(occ3 + delta).atZone(zone).toLocalDateTime()
        val form = EditorForm.fromLoaded(baseline, zone).copy(
            startDate = newStart.toLocalDate(),
            startTime = newStart.toLocalTime(),
            endDate = newStart.toLocalDate(),
            endTime = newStart.toLocalTime().plusMinutes(30),
        )
        return buildDraft(form, original = baseline, duplicate = false, deviceZone = zone)
    }

    @Test
    fun `all-events +1h at occurrence three shifts every occurrence and truncates nothing`() = runTest {
        val parentId = seedDaily()
        val resolution = resolveScopedEdit(
            original = runBlocking { repository().loadEvent(parentId) }!!,
            rule = ruleOf("FREQ=DAILY;COUNT=5"),
            instanceStartMillis = occ3,
            scope = RecurrenceScope.AllEvents,
            updated = shiftedDraft(parentId, hour),
        )

        val outcome = RecurrenceEditor(repository(), resolver).apply(resolution)
        assertTrue(outcome is RecurrenceEditor.Outcome.Written)

        // The parent ANCHOR moved +1h — it did not jump to the tapped day.
        val parentRow = fake.events.getValue(parentId)
        assertEquals(anchor + hour, (parentRow[Events.DTSTART] as Number).toLong())
        assertEquals("FREQ=DAILY;COUNT=5", parentRow[Events.RRULE])

        // Expansion is intact: all five occurrences survive, all shifted —
        // the pre-fix bug wrote DTSTART = occ3 + 1h and dropped Aug 1/Aug 2.
        // Window ends at Aug 6 09:00: the fake's expander ignores COUNT
        // (documented limit), so anything later catches a phantom 6th slot.
        val starts = repository().instances(anchor - day, anchor + 5 * day).map { it.startMillis }
        assertEquals((0..4).map { anchor + hour + it * day }, starts)
    }

    @Test
    fun `untouched times with a title change write the title only and leave the anchor`() = runTest {
        val parentId = seedDaily()
        val loaded = runBlocking { repository().loadEvent(parentId) }!!
        val baseline = loaded.atOccurrence(occ3)

        val resolution = resolveScopedEdit(
            original = loaded,
            rule = ruleOf("FREQ=DAILY;COUNT=5"),
            instanceStartMillis = occ3,
            scope = RecurrenceScope.AllEvents,
            updated = buildDraft(
                EditorForm.fromLoaded(baseline, zone).copy(title = "renamed series"),
                original = baseline,
                duplicate = false,
                deviceZone = zone,
            ),
        )
        assertEquals(
            "no time edit may leak into an untouched-times save",
            EventFieldEdits(title = "renamed series"),
            (resolution as Resolution.UpdateParentRow).edits,
        )

        val outcome = RecurrenceEditor(repository(), resolver).apply(resolution)
        assertTrue(outcome is RecurrenceEditor.Outcome.Written)

        val parentRow = fake.events.getValue(parentId)
        assertEquals("renamed series", parentRow[Events.TITLE])
        assertEquals(anchor, (parentRow[Events.DTSTART] as Number).toLong())
        val starts = repository().instances(anchor - day, anchor + 5 * day).map { it.startMillis }
        assertEquals((0..4).map { anchor + it * day }, starts)
    }

    @Test
    fun `all-events title-only on a duration all-day series does not lengthen it`() = runTest {
        fake.seedCalendar()
        val start = utc(2026, 8, 1, 0)
        val parentId = fake.seedEvent(
            calendarId = 1L,
            Events.TITLE to "retreat",
            Events.DTSTART to start,
            Events.DTEND to null,
            Events.DURATION to "P1D",
            Events.ALL_DAY to 1L,
            Events.EVENT_TIMEZONE to "UTC",
            Events.RRULE to "FREQ=DAILY;COUNT=5",
        )
        fake.events.getValue(parentId).remove(Events.DTEND)
        val loaded = runBlocking { repository().loadEvent(parentId) }!!
        val baseline = loaded.atOccurrence(start + 2 * day)

        val resolution = resolveScopedEdit(
            original = loaded,
            rule = ruleOf("FREQ=DAILY;COUNT=5"),
            instanceStartMillis = start + 2 * day,
            scope = RecurrenceScope.AllEvents,
            updated = buildDraft(
                EditorForm.fromLoaded(baseline, zone).copy(title = "renamed retreat"),
                original = baseline,
                duplicate = false,
                deviceZone = zone,
            ),
        )
        assertEquals(
            EventFieldEdits(title = "renamed retreat"),
            (resolution as Resolution.UpdateParentRow).edits,
        )

        RecurrenceEditor(repository(), resolver).apply(resolution)
        val parentRow = fake.events.getValue(parentId)
        assertEquals("renamed retreat", parentRow[Events.TITLE])
        assertEquals("P1D", parentRow[Events.DURATION])
        assertEquals(start, (parentRow[Events.DTSTART] as Number).toLong())
        assertEquals(null, parentRow[Events.DTEND])
    }
}
