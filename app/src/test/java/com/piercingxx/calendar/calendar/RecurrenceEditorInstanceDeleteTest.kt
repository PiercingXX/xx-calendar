package com.piercingxx.calendar.calendar

import android.provider.CalendarContract.Events
import com.piercingxx.calendar.calendar.Fixtures.seedCalendar
import com.piercingxx.calendar.calendar.Fixtures.seedEvent
import com.piercingxx.calendar.calendar.Fixtures.utc
import com.piercingxx.calendar.core.InstanceRef
import com.piercingxx.calendar.core.RecurringEventContext
import com.piercingxx.calendar.core.RecurrenceScope
import com.piercingxx.calendar.core.Resolution
import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.ScopeResolver
import com.piercingxx.calendar.core.EventFieldEdits
import com.piercingxx.calendar.core.RuleParse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Delete-this-instance end-to-end against the fake provider (14.2): the
 * canceled-exception write must be observable in EXPANSION — the tapped
 * occurrence disappears while its siblings survive — which the old
 * events/{millis} DELETE could never prove because the fake keyed deletes on
 * _id. Also covers the review repro: deleting an instance, then splitting the
 * series, must keep the deletion deleted (the canceled exception follows the
 * continuation via tail migration).
 */
@RunWith(RobolectricTestRunner::class)
class RecurrenceEditorInstanceDeleteTest : FakeProviderFixture() {

    private val day = 86_400_000L
    private val week = 7 * day

    // Weekly Mondays at 14:00 UTC, PT30M: Aug 3 / 10 / 17 / 24 / 31, 2026.
    private val occ1 = utc(2026, 8, 3, 14)
    private val occ2 = occ1 + week
    private val occ3 = occ1 + 2 * week
    private val occ4 = occ1 + 3 * week
    private val occ5 = occ1 + 4 * week

    private fun repository() = CalendarRepository(resolver)

    private fun seedWeeklySeries(): Long {
        fake.seedCalendar()
        return fake.seedEvent(
            calendarId = 1L,
            Events.TITLE to "standup",
            Events.DTSTART to occ1,
            Events.DTEND to null,
            Events.DURATION to "PT30M",
            Events.RRULE to "FREQ=WEEKLY;BYDAY=MO",
            Events.EVENT_TIMEZONE to "UTC",
        )
    }

    @Test
    fun `delete this instance removes exactly that occurrence from expansion`() = runTest {
        val parentId = seedWeeklySeries()
        val repo = repository()
        val editor = RecurrenceEditor(repo, resolver)

        val outcome = editor.apply(Resolution.DeleteInstanceUri(parentId, occ3))

        assertEquals(RecurrenceEditor.Outcome.Written(parentId), outcome)
        // The exclusion is a canceled exception ROW on the parent, inserted
        // through the CONTENT_EXCEPTION_URI shape — not an events/{millis}
        // DELETE that no provider could honor.
        val lastInsert = fake.insertUris.last()
        assertEquals("exception", lastInsert.pathSegments.first())
        assertEquals(parentId.toString(), lastInsert.pathSegments[1])
        val exceptionRow = fake.events.values.single {
            (it[Events.ORIGINAL_ID] as? Number)?.toLong() == parentId
        }
        assertEquals(occ3, (exceptionRow[Events.ORIGINAL_INSTANCE_TIME] as Number).toLong())
        assertEquals(
            Events.STATUS_CANCELED.toLong(),
            (exceptionRow[Events.STATUS] as Number).toLong(),
        )
        assertEquals(0L, (exceptionRow[Events.ORIGINAL_ALL_DAY] as Number).toLong())
        // The parent itself is untouched: no RRULE rewrite, no hand-written
        // EXDATE (§6.3: pick one mechanism and never mix).
        val parent = fake.events.getValue(parentId)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", parent[Events.RRULE])
        assertEquals(null, parent[Events.EXDATE])

        // …and expansion agrees: #3 gone, siblings remain.
        val starts = repo.instances(occ1 - day, occ5 + day).map { it.startMillis }
        assertEquals(listOf(occ1, occ2, occ4, occ5), starts)
    }

    @Test
    fun `canceled tail instance stays deleted after a this-and-following split`() = runTest {
        val parentId = seedWeeklySeries()
        val repo = repository()
        val editor = RecurrenceEditor(repo, resolver)

        // Delete #5, then edit occurrence #3 choosing This-and-following.
        editor.apply(Resolution.DeleteInstanceUri(parentId, occ5))
        val resolution = ScopeResolver.resolveEdit(
            context = RecurringEventContext(
                parentEventId = parentId,
                rule = (RRuleModel.parse("FREQ=WEEKLY;BYDAY=MO") as RuleParse.Parsed).rule,
                startMillis = occ1,
                allDay = false,
            ),
            scope = RecurrenceScope.ThisAndFollowing,
            instance = InstanceRef(parentId, occ3),
            edits = EventFieldEdits(title = "standup v2"),
        )
        val outcome = editor.apply(resolution)
        assertTrue(outcome is RecurrenceEditor.Outcome.Written)

        // Parent expands #1–#2 only; the continuation owns #3 onward but #5
        // stays suppressed because its canceled exception was re-pointed onto
        // the new series. The old bug resurrected #5 here.
        val instances = repo.instances(occ1 - day, occ5 + day)
        assertEquals(listOf(occ1, occ2, occ3, occ4), instances.map { it.startMillis })

        // The renamed occurrences are exactly the continuation's.
        val continuationInstances = instances.filter { it.title == "standup v2" }
        assertEquals(listOf(occ3, occ4), continuationInstances.map { it.startMillis })
    }

    @Test
    fun `delete this instance of an all-day series writes ORIGINAL_ALL_DAY`() = runTest {
        fake.seedCalendar()
        val parentId = fake.seedEvent(
            calendarId = 1L,
            Events.TITLE to "holiday",
            Events.DTSTART to occ1,
            Events.DTEND to null,
            Events.DURATION to "P1D",
            Events.ALL_DAY to 1L,
            Events.RRULE to "FREQ=WEEKLY;BYDAY=MO",
            Events.EVENT_TIMEZONE to "UTC",
        )
        val editor = RecurrenceEditor(repository(), resolver)

        editor.apply(Resolution.DeleteInstanceUri(parentId, occ3))

        val exceptionRow = fake.events.values.single {
            (it[Events.ORIGINAL_ID] as? Number)?.toLong() == parentId
        }
        assertEquals(1L, (exceptionRow[Events.ORIGINAL_ALL_DAY] as Number).toLong())
        assertEquals(occ3, (exceptionRow[Events.ORIGINAL_INSTANCE_TIME] as Number).toLong())
        val starts = repository().instances(occ1 - day, occ5 + day).map { it.startMillis }
        assertEquals(listOf(occ1, occ2, occ4, occ5), starts)
    }

    @Test
    fun `canceling every visible occurrence empties the expansion`() = runTest {
        val parentId = seedWeeklySeries()
        val repo = repository()
        val editor = RecurrenceEditor(repo, resolver)

        editor.apply(Resolution.DeleteInstanceUri(parentId, occ2))
        editor.apply(Resolution.DeleteInstanceUri(parentId, occ4))

        val instances = repo.instances(occ1 - day, occ5 + day)
        assertEquals(listOf(occ1, occ3, occ5), instances.map { it.startMillis })
    }
}
