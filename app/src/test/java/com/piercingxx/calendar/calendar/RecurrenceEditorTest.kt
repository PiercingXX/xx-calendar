package com.piercingxx.calendar.calendar

import android.content.ContentResolver
import android.content.ContentValues
import android.database.MatrixCursor
import android.provider.CalendarContract.Events
import com.piercingxx.calendar.core.EndCondition
import com.piercingxx.calendar.core.EventFieldEdits
import com.piercingxx.calendar.core.Frequency
import com.piercingxx.calendar.core.Resolution
import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.Weekday
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The §6.3 executor suite: every Resolution variant maps to exactly the
 * repository/provider operation the mapping table names, with opaque columns
 * preserved verbatim and refusals writing nothing. Mockk verifies the
 * mapping; Robolectric supplies the android.net.Uri machinery the
 * instance-URI delete builds on.
 */
@RunWith(RobolectricTestRunner::class)
class RecurrenceEditorTest {

    private val parentId = 42L
    private val instanceStart: Long =
        ZonedDateTime.of(2026, 8, 17, 14, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private lateinit var repository: CalendarRepository
    private lateinit var resolver: ContentResolver
    private lateinit var editor: RecurrenceEditor

    private val savedDrafts = mutableListOf<EventDraft>()
    private val savedOpaque = mutableListOf<OpaqueColumns.HeldValues>()

    private val opaque = OpaqueColumns.HeldValues.of(
        mapOf(
            "customAppUri" to OpaqueColumns.RawValue.Text("https://meet.example/abc"),
            "accessLevel" to OpaqueColumns.RawValue.Integer(2L),
        ),
    )

    private val recurringDraft = EventDraft(
        calendarId = 7L,
        startMillis = instanceStart,
        endMillis = null,
        eventTimezone = "UTC",
        eventId = parentId,
        title = "standup",
        location = "annex",
        description = "sync",
        duration = "PT30M",
        allDay = false,
        rrule = "FREQ=WEEKLY;BYDAY=MO",
        availability = 0,
    )

    @Before
    fun setUp() {
        repository = mockk()
        resolver = mockk()
        editor = RecurrenceEditor(repository, resolver)
        savedDrafts.clear()
        savedOpaque.clear()
        coEvery { repository.saveEvent(capture(savedDrafts), capture(savedOpaque)) } returns 1L
        coEvery { repository.deleteEvent(any()) } returns Unit
        coEvery { resolver.delete(any(), any(), any()) } returns 1
        // Default: no exception rows anywhere; split tests override as needed.
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
        every { resolver.update(any(), any(), any(), any()) } returns 1
    }

    private fun loaded(draft: EventDraft = recurringDraft) = LoadedEvent(draft, opaque)

    // ---- UpdateParentRow -------------------------------------------------

    @Test
    fun `updateParentRow merges edits onto loaded draft with opaque preserved`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns loaded()

        val outcome = editor.apply(
            Resolution.UpdateParentRow(
                parentId,
                EventFieldEdits(title = "renamed", startMillis = instanceStart + 60_000),
            ),
        )

        assertEquals(parentId, (outcome as RecurrenceEditor.Outcome.Written).touchedEventId)
        assertEquals(1, savedDrafts.size)
        coVerify(exactly = 0) { repository.deleteEvent(any()) }
        val merged = savedDrafts.single()
        assertEquals("renamed", merged.title)
        assertEquals(instanceStart + 60_000, merged.startMillis)
        assertEquals("annex", merged.location)
        assertEquals(parentId, merged.eventId)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", merged.rrule)
        assertSameOpaque(opaque, savedOpaque.single())
    }

    @Test
    fun `updateParentRow with replacementRule swaps only the rule`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns loaded()
        val newRule = RRuleModel(frequency = Frequency.DAILY, interval = 2)

        editor.apply(
            Resolution.UpdateParentRow(parentId, EventFieldEdits(title = "renamed")),
            replacementRule = newRule,
        )

        val merged = savedDrafts.single()
        assertEquals(newRule.serialize(), merged.rrule)
        assertEquals("renamed", merged.title)
        assertEquals(instanceStart, merged.startMillis)
    }

    // ---- InsertExceptionRow ----------------------------------------------

    @Test
    fun `insertExceptionRow inserts row carrying original linkage and edits`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns loaded()

        val outcome = editor.apply(
            Resolution.InsertExceptionRow(
                parentEventId = parentId,
                originalInstanceTimeMillis = instanceStart,
                newRowEdits = EventFieldEdits(title = "moved once"),
            ),
        )

        assertTrue(outcome is RecurrenceEditor.Outcome.Written)
        val row = savedDrafts.single()
        assertEquals(null, row.eventId)
        assertEquals(parentId, row.originalId)
        assertEquals(instanceStart, row.originalInstanceTime)
        assertEquals(false, row.originalAllDay)
        assertEquals("moved once", row.title)
        assertEquals(instanceStart, row.startMillis)
        assertEquals(null, row.endMillis)
        assertEquals("PT30M", row.duration)
        assertEquals(null, row.rrule)
        assertEquals(null, row.rdate)
        assertEquals(null, row.exdate)
        assertEquals(7L, row.calendarId)
        assertEquals("annex", row.location)
        assertSameOpaque(opaque, savedOpaque.single())
    }

    @Test
    fun `insertExceptionRow derives absolute end from a DTEND parent`() = runTest {
        val dtendParent = recurringDraft.copy(duration = null, endMillis = instanceStart + 3_600_000)
        coEvery { repository.loadEvent(parentId) } returns LoadedEvent(dtendParent, opaque)

        editor.apply(
            Resolution.InsertExceptionRow(
                parentId,
                instanceStart,
                EventFieldEdits(startMillis = instanceStart + 86_400_000),
            ),
        )

        val row = savedDrafts.single()
        assertEquals(instanceStart + 86_400_000, row.startMillis)
        assertEquals(instanceStart + 86_400_000 + 3_600_000, row.endMillis)
        assertEquals(null, row.duration)
    }

    // ---- SplitParent -----------------------------------------------------

    @Test
    fun `splitParent truncates parent then inserts new series with remainingRule`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns loaded()

        val outcome = editor.apply(
            Resolution.SplitParent(
                parentEventId = parentId,
                newUntil = EndCondition.Until(instanceStart - 1),
                newRowStartMillis = instanceStart,
                newRowEdits = EventFieldEdits(location = "studio"),
                remainingRule = RRuleModel(frequency = Frequency.WEEKLY),
            ),
        )

        assertTrue(outcome is RecurrenceEditor.Outcome.Written)
        assertEquals("two writes: truncated parent, then new row", 2, savedDrafts.size)
        val parentRow = savedDrafts[0]
        assertEquals(parentId, parentRow.eventId)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260817T135959Z", parentRow.rrule)
        assertSameOpaque(opaque, savedOpaque[0])
        val newRow = savedDrafts[1]
        assertEquals(null, newRow.eventId)
        assertEquals(instanceStart, newRow.startMillis)
        assertEquals("FREQ=WEEKLY", newRow.rrule)
        assertEquals("standup", newRow.title)
        assertEquals("studio", newRow.location)
        assertEquals(null, newRow.originalId)
        assertEquals(null, newRow.originalInstanceTime)
        assertEquals(null, newRow.rdate)
        assertEquals(null, newRow.exdate)
    }

    @Test
    fun `splitParent uses replacementRule for the new row when given`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns loaded()
        val replacement = RRuleModel(
            frequency = Frequency.WEEKLY,
            byDay = listOf(com.piercingxx.calendar.core.ByDay(null, Weekday.WE)),
        )

        editor.apply(
            Resolution.SplitParent(
                parentEventId = parentId,
                newUntil = EndCondition.Until(instanceStart - 1),
                newRowStartMillis = instanceStart,
                newRowEdits = EventFieldEdits(),
                remainingRule = RRuleModel(frequency = Frequency.WEEKLY),
            ),
            replacementRule = replacement,
        )

        assertEquals(replacement.serialize(), savedDrafts[1].rrule)
        // The parent is still truncated from its own old rule.
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260817T135959Z", savedDrafts[0].rrule)
    }

    @Test
    fun `splitParent refuses without writing when the parent rule is unmodelled`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns
            LoadedEvent(recurringDraft.copy(rrule = "FREQ=WEEKLY;BYSECOND=13"), opaque)

        val outcome = editor.apply(
            Resolution.SplitParent(
                parentEventId = parentId,
                newUntil = EndCondition.Until(instanceStart - 1),
                newRowStartMillis = instanceStart,
                newRowEdits = EventFieldEdits(),
                remainingRule = RRuleModel(frequency = Frequency.WEEKLY),
            ),
        )

        assertTrue(outcome is RecurrenceEditor.Outcome.Refused)
        coVerify(exactly = 0) { repository.saveEvent(any(), any()) }
    }

    @Test
    fun `splitParent repoints tail exception rows onto the inserted series`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns loaded()
        val newRowId = 777L
        coEvery {
            repository.saveEvent(match<EventDraft> { it.eventId != null }, any())
        } returns parentId
        coEvery {
            repository.saveEvent(match<EventDraft> { it.eventId == null }, any())
        } returns newRowId
        val queriedRows = MatrixCursor(arrayOf(Events._ID)).apply {
            addRow(arrayOf(99L))
            addRow(arrayOf(100L))
        }
        val projections = mutableListOf<Array<String>>()
        val selections = mutableListOf<String>()
        val selectionArgs = mutableListOf<Array<String>>()
        every {
            resolver.query(
                any(),
                capture(projections),
                capture(selections),
                capture(selectionArgs),
                any(),
            )
        } returns queriedRows
        val updateValues = mutableListOf<ContentValues>()
        val updateWheres = mutableListOf<String>()
        val updateArgs = mutableListOf<Array<String>>()
        every {
            resolver.update(any(), capture(updateValues), capture(updateWheres), capture(updateArgs))
        } returns 1

        val outcome = editor.apply(
            Resolution.SplitParent(
                parentEventId = parentId,
                newUntil = EndCondition.Until(instanceStart - 1),
                newRowStartMillis = instanceStart,
                newRowEdits = EventFieldEdits(),
                remainingRule = RRuleModel(frequency = Frequency.WEEKLY),
            ),
        )

        assertEquals(newRowId, (outcome as RecurrenceEditor.Outcome.Written).touchedEventId)
        // Migration query: tail rows of this parent at/after the split point.
        assertEquals(listOf(Events._ID), projections.single().toList())
        assertEquals(
            "${Events.ORIGINAL_ID}=? AND ${Events.ORIGINAL_INSTANCE_TIME}>=?",
            selections.single(),
        )
        assertEquals(
            listOf(parentId.toString(), instanceStart.toString()),
            selectionArgs.single().toList(),
        )
        // Each found row is re-pointed to the NEW series id, by _id.
        assertEquals(listOf("${Events._ID}=?", "${Events._ID}=?"), updateWheres)
        assertEquals(listOf(listOf("99"), listOf("100")), updateArgs.map { it.toList() })
        assertEquals(setOf(newRowId), updateValues.mapNotNull { it.getAsLong(Events.ORIGINAL_ID) }.toSet())
    }

    @Test
    fun `splitParent wraps insert failure in SplitPartialException naming the parent`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns loaded()
        coEvery {
            repository.saveEvent(match<EventDraft> { it.eventId != null }, any())
        } returns parentId
        coEvery {
            repository.saveEvent(match<EventDraft> { it.eventId == null }, any())
        } throws IllegalStateException("provider refused event insert")

        try {
            editor.apply(
                Resolution.SplitParent(
                    parentEventId = parentId,
                    newUntil = EndCondition.Until(instanceStart - 1),
                    newRowStartMillis = instanceStart,
                    newRowEdits = EventFieldEdits(),
                    remainingRule = RRuleModel(frequency = Frequency.WEEKLY),
                ),
            )
            fail("expected SplitPartialException")
        } catch (e: SplitPartialException) {
            assertEquals(parentId, e.parentEventId)
            assertEquals("series truncated; continuation not created", e.message)
            assertTrue(e.cause is IllegalStateException)
        }
        // The truncation itself went through before the failure — that is the point.
        coVerify(exactly = 1) {
            repository.saveEvent(match<EventDraft> { it.eventId == parentId }, any())
        }
    }

    @Test
    fun `splitParent attempts no exception migration when the insert fails`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns loaded()
        coEvery {
            repository.saveEvent(match<EventDraft> { it.eventId != null }, any())
        } returns parentId
        coEvery {
            repository.saveEvent(match<EventDraft> { it.eventId == null }, any())
        } throws IllegalStateException("provider refused event insert")

        try {
            editor.apply(
                Resolution.SplitParent(
                    parentEventId = parentId,
                    newUntil = EndCondition.Until(instanceStart - 1),
                    newRowStartMillis = instanceStart,
                    newRowEdits = EventFieldEdits(),
                    remainingRule = RRuleModel(frequency = Frequency.WEEKLY),
                ),
            )
            fail("expected SplitPartialException")
        } catch (_: SplitPartialException) {
        }

        verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    }

    // ---- Delete variants ---------------------------------------------------

    @Test
    fun `deleteParentRow deletes the parent`() = runTest {
        val outcome = editor.apply(Resolution.DeleteParentRow(parentId))

        assertEquals(RecurrenceEditor.Outcome.Written(touchedEventId = null), outcome)
        coVerify(exactly = 1) { repository.deleteEvent(parentId) }
        coVerify(exactly = 0) { repository.saveEvent(any(), any()) }
    }

    @Test
    fun `setUntil rewrites only the parent rrule`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns loaded()

        val until =
            ZonedDateTime.of(2026, 8, 17, 13, 59, 59, 999_000_000, ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        val outcome = editor.apply(Resolution.SetUntil(parentId, EndCondition.Until(until)))

        assertEquals(RecurrenceEditor.Outcome.Written(parentId), outcome)
        val updated = savedDrafts.single()
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260817T135959Z", updated.rrule)
        assertEquals(recurringDraft.copy(rrule = updated.rrule), updated)
        assertSameOpaque(opaque, savedOpaque.single())
    }

    @Test
    fun `setUntil on an all-day series emits DATE-form UNTIL per RFC 5545`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns
            LoadedEvent(recurringDraft.copy(allDay = true, duration = "P1D"), opaque)

        // "Just before" the 2026-08-17 UTC-midnight instance: 08-16 23:59:59.999.
        val until =
            ZonedDateTime.of(2026, 8, 16, 23, 59, 59, 999_000_000, ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        val outcome =
            editor.apply(Resolution.SetUntil(parentId, EndCondition.Until(until, dateOnly = true)))

        assertEquals(RecurrenceEditor.Outcome.Written(parentId), outcome)
        val updated = savedDrafts.single()
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260816", updated.rrule)
    }

    @Test
    fun `deleteInstanceUri deletes the instance-start URI`() = runTest {
        val outcome = editor.apply(
            Resolution.DeleteInstanceUri(parentId, instanceStart),
        )

        assertEquals(RecurrenceEditor.Outcome.Written(parentId), outcome)
        coVerify(exactly = 0) { repository.saveEvent(any(), any()) }
        coVerify(exactly = 0) { repository.deleteEvent(any()) }
        coVerify(exactly = 1) {
            resolver.delete(
                match { uri ->
                    uri.toString() == "content://com.android.calendar/events/$instanceStart"
                },
                null,
                null,
            )
        }
    }

    // ---- Refusal / missing rows -------------------------------------------

    @Test
    fun `refusal writes nothing and surfaces the reason`() = runTest {
        val outcome = editor.apply(
            Resolution.Refusal("cannot split a COUNT-bounded series without expanding it"),
        )

        assertEquals(
            RecurrenceEditor.Outcome.Refused("cannot split a COUNT-bounded series without expanding it"),
            outcome,
        )
        coVerify(exactly = 0) { repository.saveEvent(any(), any()) }
        coVerify(exactly = 0) { repository.deleteEvent(any()) }
        coVerify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }

    @Test
    fun `vanished parent row reports Missing and writes nothing`() = runTest {
        coEvery { repository.loadEvent(parentId) } returns null

        val outcome = editor.apply(
            Resolution.UpdateParentRow(parentId, EventFieldEdits(title = "x")),
        )

        assertEquals(RecurrenceEditor.Outcome.Missing(parentId), outcome)
        coVerify(exactly = 0) { repository.saveEvent(any(), any()) }
    }

    // ---- helpers ------------------------------------------------------------

    private fun assertSameOpaque(
        expected: OpaqueColumns.HeldValues,
        actual: OpaqueColumns.HeldValues,
    ) {
        assertEquals(expected.values, actual.values)
    }
}
