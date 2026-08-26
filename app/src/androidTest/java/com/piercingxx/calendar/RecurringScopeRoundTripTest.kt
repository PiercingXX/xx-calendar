package com.piercingxx.calendar

import android.Manifest
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.piercingxx.calendar.calendar.EventDraft
import com.piercingxx.calendar.calendar.RecurrenceEditor
import com.piercingxx.calendar.core.EndCondition
import com.piercingxx.calendar.core.EventFieldEdits
import com.piercingxx.calendar.core.InstanceRef
import com.piercingxx.calendar.core.RecurringEventContext
import com.piercingxx.calendar.core.RecurrenceScope
import com.piercingxx.calendar.core.Resolution
import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.RuleParse
import com.piercingxx.calendar.core.ScopeResolver
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WS12 instrumented suite, test 2 — design §6.3 executed against the REAL
 * provider. Every [ScopeResolver] edit scope and delete scope goes through
 * [RecurrenceEditor] on a `FREQ=DAILY;COUNT=5` series created through the
 * repository, and the resulting rows are asserted raw:
 *
 * | Scope              | Asserted outcome                                              |
 * |--------------------|---------------------------------------------------------------|
 * | Edit this instance | exception row with ORIGINAL_ID/ORIGINAL_INSTANCE_TIME;        |
 * |                    | provider suppresses the generated occurrence                  |
 * | Edit +following    | parent UNTIL set; new series row present; both expand         |
 * | Edit all           | parent row updated in place, rule untouched                   |
 * | Delete this inst.  | canceled exception row via CONTENT_EXCEPTION_URI              |
 * |                    | (ORIGINAL_INSTANCE_TIME + STATUS_CANCELED); the tapped        |
 * |                    | occurrence vanishes from Instances, siblings remain           |
 * | Delete +following  | parent UNTIL set, no EXDATE introduced                        |
 * | Delete all         | parent gone                                                   |
 *
 * The one honest wrinkle: §6.3's own resolver refuses to SPLIT a COUNT-bounded
 * series (a truncated parent plus "remaining" tail cannot be expressed without
 * expanding it). So the two +following writes run on the same daily series
 * without COUNT; the refusal itself is asserted too, so the COUNT series still
 * exercises every scope decision it legally has.
 */
@RunWith(AndroidJUnit4::class)
class RecurringScopeRoundTripTest : ProviderFixture() {

    @get:Rule
    val calendarAccess: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private val dayMillis = 86_400_000L

    private val seriesStart: Long = LocalDate.now(ZoneOffset.UTC).plusDays(1)
        .atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun dailySeries(rrule: String): Long = runBlocking {
        repository.saveEvent(
            EventDraft(
                calendarId = writableCalendarId(),
                startMillis = seriesStart,
                endMillis = null,
                eventTimezone = "UTC",
                title = "daily standup",
                duration = "PT30M",
                rrule = rrule,
            ),
        )
    }

    private fun editor(): RecurrenceEditor = RecurrenceEditor(repository, resolver)

    /** Context built from the row AS THE PROVIDER STORED IT (rule re-parsed). */
    private fun contextOf(parentEventId: Long): RecurringEventContext {
        val parent = eventSnapshot(parentEventId)!!
        val rule = (RRuleModel.parse(parent.rrule!!) as RuleParse.Parsed).rule
        return RecurringEventContext(parentEventId, rule, seriesStart, allDay = false)
    }

    private fun occurrences(fromDaysBefore: Long, forDays: Long) = runBlocking {
        repository.instances(
            seriesStart - fromDaysBefore * dayMillis,
            seriesStart + forDays * dayMillis,
        )
    }

    // ------------------------------------------------- edit: this instance

    @Test
    fun edit_thisInstance_writes_exception_row_linked_to_parent() = runBlocking {
        val parent = dailySeries("FREQ=DAILY;COUNT=5")
        val second = seriesStart + dayMillis

        val resolution = ScopeResolver.resolveEdit(
            contextOf(parent),
            RecurrenceScope.ThisInstance,
            InstanceRef(parent, second),
            EventFieldEdits(title = "moved standup"),
        )
        assertTrue("expected InsertExceptionRow, got $resolution", resolution is Resolution.InsertExceptionRow)

        val outcome = editor().apply(resolution)
        val exceptionId = (outcome as RecurrenceEditor.Outcome.Written).touchedEventId!!
        assertFalse(exceptionId == parent)

        // The inserted row carries the original linkage and no rule of its own.
        val exception = eventSnapshot(exceptionId)!!
        assertEquals(parent, exception.originalId)
        assertEquals(second, exception.originalInstanceTime)
        assertEquals("moved standup", exception.title)
        assertEquals(second, exception.dtstart)
        assertEquals(null, exception.rrule)

        // Parent row untouched.
        val parentRow = eventSnapshot(parent)!!
        assertEquals("daily standup", parentRow.title)
        assertEquals("FREQ=DAILY;COUNT=5", parentRow.rrule)

        // And the provider suppresses the generated occurrence in favour of
        // the exception — the whole point of §6.3's "this instance only".
        val atSecond = occurrences(1, 7).first { it.startMillis == second }
        assertEquals("moved standup", atSecond.title)
        assertEquals(exceptionId, atSecond.eventId)
    }

    // --------------------------------------------- edit: this and following

    @Test
    fun edit_thisAndFollowing_truncates_parent_and_starts_new_series() = runBlocking {
        // Never-ended twin: a COUNT series may not be split (see class KDoc).
        val parent = dailySeries("FREQ=DAILY")
        val third = seriesStart + 2 * dayMillis

        val resolution = ScopeResolver.resolveEdit(
            contextOf(parent),
            RecurrenceScope.ThisAndFollowing,
            InstanceRef(parent, third),
            EventFieldEdits(title = "renamed tail"),
        )
        assertTrue("expected SplitParent, got $resolution", resolution is Resolution.SplitParent)

        val outcome = editor().apply(resolution)
        val newRowId = (outcome as RecurrenceEditor.Outcome.Written).touchedEventId!!

        // Parent UNTIL lands just before the edited instance (§6.3 boundary).
        val parentRow = eventSnapshot(parent)!!
        val truncated = (RRuleModel.parse(parentRow.rrule!!) as RuleParse.Parsed).rule
        assertEquals(EndCondition.Until(third - 1), truncated.end)
        assertEquals(seriesStart, parentRow.dtstart)

        // New recurring row starts at the instance and carries the edits.
        val newRow = eventSnapshot(newRowId)!!
        assertEquals(third, newRow.dtstart)
        assertEquals("renamed tail", newRow.title)
        assertEquals("FREQ=DAILY", newRow.rrule)
        assertEquals(null, newRow.originalId)

        // Both halves exist AND both expand, with a clean cut at the boundary.
        val expanded = occurrences(1, 30)
        assertTrue(expanded.any { it.eventId == parent && it.startMillis == seriesStart })
        assertTrue(expanded.none { it.eventId == parent && it.startMillis >= third })
        assertTrue(expanded.any { it.eventId == newRowId && it.startMillis == third })
    }

    // ------------------------------------------------------ edit: all events

    @Test
    fun edit_allEvents_updates_parent_row_in_place() = runBlocking {
        val parent = dailySeries("FREQ=DAILY;COUNT=5")

        val resolution = ScopeResolver.resolveEdit(
            contextOf(parent),
            RecurrenceScope.AllEvents,
            InstanceRef(parent, seriesStart + dayMillis),
            EventFieldEdits(title = "renamed series", location = "big room"),
        )
        assertTrue("expected UpdateParentRow, got $resolution", resolution is Resolution.UpdateParentRow)

        val outcome = editor().apply(resolution)
        assertEquals(parent, (outcome as RecurrenceEditor.Outcome.Written).touchedEventId)

        val row = eventSnapshot(parent)!!
        assertEquals("renamed series", row.title)
        assertEquals("big room", row.location)
        assertEquals("FREQ=DAILY;COUNT=5", row.rrule)
        // In place means in place: no exception rows appeared.
        assertTrue(eventsWhere("${Events.ORIGINAL_ID}=?", arrayOf(parent.toString())).isEmpty())
    }

    // ------------------------------------------- edit refusal: COUNT + split

    @Test
    fun edit_thisAndFollowing_on_count_bounded_series_is_refused_without_writes() = runBlocking {
        val parent = dailySeries("FREQ=DAILY;COUNT=5")

        val resolution = ScopeResolver.resolveEdit(
            contextOf(parent),
            RecurrenceScope.ThisAndFollowing,
            InstanceRef(parent, seriesStart + dayMillis),
            EventFieldEdits(title = "x"),
        )
        assertTrue("expected Refusal, got $resolution", resolution is Resolution.Refusal)

        val outcome = editor().apply(resolution)
        assertTrue(outcome is RecurrenceEditor.Outcome.Refused)
        assertEquals("refusal must write nothing", "FREQ=DAILY;COUNT=5", eventSnapshot(parent)!!.rrule)
    }

    // ------------------------------------------------ delete: this instance

    @Test
    fun delete_thisInstance_suppresses_the_occurrence_via_canceled_exception() = runBlocking {
        val parent = dailySeries("FREQ=DAILY;COUNT=5")
        val second = seriesStart + dayMillis
        val third = seriesStart + 2 * dayMillis

        val resolution = ScopeResolver.resolveDelete(
            contextOf(parent),
            RecurrenceScope.ThisInstance,
            InstanceRef(parent, second),
        )
        assertTrue("expected DeleteInstanceUri, got $resolution", resolution is Resolution.DeleteInstanceUri)

        val outcome = editor().apply(resolution)
        assertEquals(parent, (outcome as RecurrenceEditor.Outcome.Written).touchedEventId)

        // NEW contract (14.2): the exclusion is a canceled exception row
        // inserted through CONTENT_EXCEPTION_URI — not an EXDATE edit we
        // hand-wrote, and not an events/{millis} DELETE the provider cannot
        // honor. The parent's rule and its EXDATE column stay untouched.
        val canceled = canceledExceptionsFor(second)
        assertEquals(
            "expected exactly one canceled exception for the tapped slot",
            1,
            canceled.size,
        )
        assertEquals(parent, canceled.single().originalId)
        assertEquals(second, canceled.single().originalInstanceTime)

        val row = eventSnapshot(parent)!!
        assertEquals("FREQ=DAILY;COUNT=5", row.rrule)
        assertNull("no EXDATE may be hand-written for this scope", row.exdate)

        // The deleted occurrence vanished from expansion; its neighbours remain.
        val expanded = occurrences(1, 7)
        assertTrue(expanded.none { it.eventId == parent && it.startMillis == second })
        assertTrue(expanded.any { it.eventId == parent && it.startMillis == third })
    }

    // ------------------------------------------ delete: this and following

    @Test
    fun delete_thisAndFollowing_sets_until_on_the_parent() = runBlocking {
        val parent = dailySeries("FREQ=DAILY")
        val third = seriesStart + 2 * dayMillis

        val resolution = ScopeResolver.resolveDelete(
            contextOf(parent),
            RecurrenceScope.ThisAndFollowing,
            InstanceRef(parent, third),
        )
        assertTrue("expected SetUntil, got $resolution", resolution is Resolution.SetUntil)

        editor().apply(resolution)

        val row = eventSnapshot(parent)!!
        val truncated = (RRuleModel.parse(row.rrule!!) as RuleParse.Parsed).rule
        assertEquals(EndCondition.Until(third - 1), truncated.end)
        assertNull("SetUntil must not introduce EXDATE", row.exdate)
        assertTrue(occurrences(1, 30).none { it.eventId == parent && it.startMillis >= third })
    }

    // ------------------------------------------------------ delete: all events

    @Test
    fun delete_allEvents_removes_the_series() = runBlocking {
        val parent = dailySeries("FREQ=DAILY;COUNT=5")

        val resolution = ScopeResolver.resolveDelete(
            contextOf(parent),
            RecurrenceScope.AllEvents,
            InstanceRef(parent, seriesStart),
        )
        assertTrue("expected DeleteParentRow, got $resolution", resolution is Resolution.DeleteParentRow)

        editor().apply(resolution)

        assertNull(eventSnapshot(parent))
    }

    // ------------------------- interaction: delete-this then split keeps exclusions

    /**
     * The §6.3 composition hazard: a this-instance delete lives in a canceled
     * exception ROW (not the parent's EXDATE string), so a later "this and
     * following" split must re-point that row onto the continuation —
     * otherwise the previously deleted occurrence resurrects in the tail.
     */
    @Test
    fun delete_thisInstance_then_split_thisAndFollowing_moves_exclusion_onto_the_continuation() =
        runBlocking {
            // Never-ended twin: COUNT-bounded series may not be split (KDoc).
            val parent = dailySeries("FREQ=DAILY")
            val third = seriesStart + 2 * dayMillis
            val fifth = seriesStart + 4 * dayMillis

            // 1) Delete occurrence #5 through the provider — a canceled
            //    exception via CONTENT_EXCEPTION_URI (the AOSP path).
            val deleteResolution = ScopeResolver.resolveDelete(
                contextOf(parent),
                RecurrenceScope.ThisInstance,
                InstanceRef(parent, fifth),
            )
            assertTrue(
                "expected DeleteInstanceUri, got $deleteResolution",
                deleteResolution is Resolution.DeleteInstanceUri,
            )
            editor().apply(deleteResolution)
            val cancellation = canceledExceptionsFor(fifth)
            assertEquals(
                "no canceled exception recorded for the deleted instance",
                1,
                cancellation.size,
            )
            assertEquals(parent, cancellation.single().originalId)

            // 2) Edit occurrence #3 with "this and following".
            val splitResolution = ScopeResolver.resolveEdit(
                contextOf(parent),
                RecurrenceScope.ThisAndFollowing,
                InstanceRef(parent, third),
                EventFieldEdits(title = "renamed tail"),
            )
            assertTrue(
                "expected SplitParent, got $splitResolution",
                splitResolution is Resolution.SplitParent,
            )
            val outcome = editor().apply(splitResolution)
            val tailId = (outcome as RecurrenceEditor.Outcome.Written).touchedEventId!!

            // 3) The exclusion moved as an exception ROW onto the CONTINUATION
            //    (tail migration), so #5 stays gone; no EXDATE was ever written.
            val tail = eventSnapshot(tailId)!!
            assertEquals(third, tail.dtstart)
            assertNull("continuation must carry no hand-written EXDATE", tail.exdate)
            assertNull("truncated parent must carry no hand-written EXDATE", eventSnapshot(parent)!!.exdate)
            val movedCancellation = canceledExceptionsFor(fifth)
            assertEquals(1, movedCancellation.size)
            assertEquals(
                "canceled exception must follow the continuation series",
                tailId,
                movedCancellation.single().originalId,
            )

            // 4) Instance view: #5 absent everywhere; the cut at #3 stays clean.
            val expanded = occurrences(1, 30)
            assertTrue(expanded.none { it.startMillis == fifth })
            assertTrue(expanded.none { it.eventId == parent && it.startMillis >= third })
            assertTrue(expanded.any { it.eventId == parent && it.startMillis == seriesStart })
            assertTrue(expanded.any { it.eventId == tailId && it.startMillis == third })
        }

    // ------------------------------------------------------------------ helpers

    /**
     * Exception rows recording a canceled (deleted) occurrence of the series:
     * ORIGINAL_INSTANCE_TIME names the slot, STATUS marks the cancellation.
     * This is the NEW delete-this-instance contract (14.2) — the exclusion is
     * a row the provider suppresses expansion for, not an EXDATE string.
     */
    private fun canceledExceptionsFor(instanceMillis: Long): List<EventRowSnapshot> =
        eventsWhere(
            "${Events.ORIGINAL_INSTANCE_TIME}=? AND ${Events.STATUS}=?",
            arrayOf(instanceMillis.toString(), Events.STATUS_CANCELED.toString()),
        )
}
