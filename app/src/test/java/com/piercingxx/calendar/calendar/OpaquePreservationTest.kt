package com.piercingxx.calendar.calendar

import android.provider.CalendarContract.Events
import com.piercingxx.calendar.calendar.Fixtures.seedCalendar
import com.piercingxx.calendar.calendar.Fixtures.utc
import com.piercingxx.calendar.core.EndCondition
import com.piercingxx.calendar.core.EventFieldEdits
import com.piercingxx.calendar.core.Frequency
import com.piercingxx.calendar.core.Resolution
import com.piercingxx.calendar.core.RRuleModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * THE D8 TEST (R6): an event arrives with every unmodelled column populated,
 * as a real synced row would. The app loads it, changes ONE modeled field,
 * saves — and every unmodelled column must come back byte-identical.
 * Anything else is vandalising the user's Google account through DAVx⁵.
 *
 * WS13.1 sharpening: `SYNC_DATA*` (where DAVx⁵ keeps href/etag) is
 * sync-adapter-owned — it must survive edits by ABSENCE on update, never be
 * held in the opaque bag, and never be cloned onto inserted exception or
 * continuation rows. FakeCalendarProvider rejects such writes exactly like
 * CalendarProvider2 ("Only sync adapters may write to …"), so these tests fail
 * loudly if the strip regresses.
 */
@RunWith(RobolectricTestRunner::class)
class OpaquePreservationTest : FakeProviderFixture() {

    private val seededRow: Map<String, Any?> = linkedMapOf(
        Events._ID to 1L,
        Events.CALENDAR_ID to 1L,
        Events.TITLE to "Flight UA234 SFO → FRA",
        Events.DTSTART to utc(2026, 9, 2, 18, 20),
        Events.DTEND to utc(2026, 9, 3, 13, 55),
        Events.ALL_DAY to 0L,
        Events.EVENT_TIMEZONE to "America/Los_Angeles",
        Events.EVENT_END_TIMEZONE to "Europe/Berlin",
        // --- attendee / sharing machinery the app does not model ---
        Events.ACCESS_LEVEL to Events.ACCESS_CONFIDENTIAL.toLong(),
        Events.GUESTS_CAN_MODIFY to 0L,
        Events.GUESTS_CAN_INVITE_OTHERS to 1L,
        Events.GUESTS_CAN_SEE_GUESTS to 1L,
        Events.HAS_ATTENDEE_DATA to 1L,
        Events.ORGANIZER to "bookings@google.com",
        // --- custom-app / conferencing passthrough ---
        Events.CUSTOM_APP_PACKAGE to "com.example.conferencing",
        Events.CUSTOM_APP_URI to "content://com.example.conferencing/room/42",
        // --- sync-adapter-owned data that MUST survive client edits ---
        "sync_data1" to "gmail-message-id:AAAAAA",
        "sync_data2" to "etag:v9",
        "sync_data3" to null,
    )

    /** Opaque columns this app MAY hold and write back (design §6.2). */
    private val heldOpaqueColumns = listOf(
        Events.ACCESS_LEVEL, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS,
        Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ATTENDEE_DATA, Events.ORGANIZER,
        Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI,
    )

    /** Sync-adapter-owned columns that must travel by absence only (WS13.1). */
    private val syncOwnedColumns = listOf("sync_data1", "sync_data2", "sync_data3")

    @Before
    fun seed() {
        fake.seedCalendar()
        fake.claimEventId(1L)
        fake.events[1L] = LinkedHashMap(seededRow)
    }

    private fun repo() = CalendarRepository(resolver)

    // -------------------------------------------------------------- load

    @Test
    fun `load surfaces modeled fields and captures every opaque column`() = runTest {
        val loaded = repo().loadEvent(1L)!!

        assertEquals("Flight UA234 SFO → FRA", loaded.draft.title)
        assertEquals(seededRow[Events.DTSTART], loaded.draft.startMillis)
        assertEquals(seededRow[Events.DTEND], loaded.draft.endMillis)
        assertEquals("America/Los_Angeles", loaded.draft.eventTimezone)
        assertEquals("Europe/Berlin", loaded.draft.eventEndTimezone)
        for (column in heldOpaqueColumns) {
            assertTrue("capture missed $column", loaded.opaque.values.containsKey(column))
        }
        for (modeled in OpaqueColumns.MODELED_EVENT_COLUMNS) {
            assertFalse("opaque bag must not hold modeled $modeled", loaded.opaque.values.containsKey(modeled))
        }
    }

    @Test
    fun `load never captures sync-owned columns into the opaque bag`() = runTest {
        val loaded = repo().loadEvent(1L)!!

        for (column in syncOwnedColumns) {
            assertFalse(
                "opaque bag must not hold sync-adapter-owned $column",
                loaded.opaque.values.containsKey(column),
            )
        }
    }

    // ------------------------------------------------------- THE D8 TEST

    @Test
    fun `saving a title edit leaves every opaque column byte-identical`() = runTest {
        val repo = repo()
        val loaded = repo.loadEvent(1L)
        assertNotNull(loaded)

        repo.saveEvent(loaded!!.draft.copy(title = "Flight UA234 (renamed locally)"), loaded.opaque)

        val after = fake.events.getValue(1L)
        assertEquals("Flight UA234 (renamed locally)", after[Events.TITLE])
        (heldOpaqueColumns + syncOwnedColumns).forEach { column ->
            assertEquals("opaque column $column changed on save", seededRow[column], after[column])
        }
        // Modeled-but-unedited fields are equally untouched.
        assertEquals(seededRow[Events.DTSTART], after[Events.DTSTART])
        assertEquals(seededRow[Events.DTEND], after[Events.DTEND])
        assertEquals(seededRow[Events.EVENT_TIMEZONE], after[Events.EVENT_TIMEZONE])
        assertEquals(seededRow[Events.EVENT_END_TIMEZONE], after[Events.EVENT_END_TIMEZONE])
        assertEquals(seededRow[Events.ALL_DAY], after[Events.ALL_DAY])
        assertEquals(seededRow[Events.CALENDAR_ID], after[Events.CALENDAR_ID])
    }

    // --------------------------------------------------- by-construction

    @Test
    fun `update never introduces provider-managed columns`() = runTest {
        val repo = repo()
        val loaded = repo.loadEvent(1L)!!

        repo.saveEvent(loaded.draft.copy(location = "Gate G9"), loaded.opaque)

        val row = fake.events.getValue(1L)
        val introduced = setOf(Events.DIRTY, Events.MUTATORS, Events._SYNC_ID, Events.LAST_SYNCED)
            .filter { row.containsKey(it) && !seededRow.containsKey(it) }
        assertTrue("save wrote provider-managed keys: $introduced", introduced.isEmpty())
    }

    @Test
    fun `editor can clear a modeled field but not an opaque one`() = runTest {
        val repo = repo()
        val loaded = repo.loadEvent(1L)!!

        // description == null in the draft clears DESCRIPTION…
        repo.saveEvent(loaded.draft.copy(description = null), loaded.opaque)

        val after = fake.events.getValue(1L)
        assertTrue(after.containsKey(Events.DESCRIPTION))
        assertEquals(null, after[Events.DESCRIPTION])
        // …while sync_data3 (already null) stays null and sync_data1 stays put.
        assertEquals("gmail-message-id:AAAAAA", after["sync_data1"])
        assertTrue(after.containsKey("sync_data3"))
        assertEquals(null, after["sync_data3"])
    }

    // -------------------------------------------------- no opaque, no write

    @Test
    fun `saving without held values still cannot clear unmodeled columns`() = runTest {
        val repo = repo()
        val loaded = repo.loadEvent(1L)!!

        // Deliberately pass EMPTY instead of loaded.opaque — simulates a caller
        // that forgot the passthrough. The mechanism cannot resurrect what it
        // did not hold, but it also must not have cleared anything: absence of
        // a key in ContentValues means "unchanged" for an UPDATE.
        repo.saveEvent(loaded.draft.copy(title = "x"), OpaqueColumns.HeldValues.EMPTY)

        val after = fake.events.getValue(1L)
        assertEquals("gmail-message-id:AAAAAA", after["sync_data1"])
        assertEquals("bookings@google.com", after[Events.ORGANIZER])
        assertEquals("x", after[Events.TITLE])
    }

    @Test
    fun `title-only edit sends no SYNC_DATA columns to the provider`() = runTest {
        val repo = repo()
        val loaded = repo.loadEvent(1L)!!

        // If any SYNC_DATA* key were present in the update ContentValues — as
        // held values or otherwise — FakeCalendarProvider rejects the write
        // exactly like CalendarProvider2's verifyNoSyncColumns.
        repo.saveEvent(loaded.draft.copy(title = "renamed"), loaded.opaque)

        val after = fake.events.getValue(1L)
        assertEquals("renamed", after[Events.TITLE])
        assertEquals("gmail-message-id:AAAAAA", after["sync_data1"])
        assertEquals("etag:v9", after["sync_data2"])
    }

    // ------------------------------------- recurring writes (WS13.1 paths)

    /** A synced recurring row in the provider's duration shape, DAVx⁵-style. */
    private fun seedSyncedRecurringSeries(): Long {
        fake.seedCalendar()
        fake.claimEventId(1L)
        fake.events[1L] = linkedMapOf(
            Events._ID to 1L,
            Events.CALENDAR_ID to 1L,
            Events.TITLE to "standup",
            Events.DTSTART to utc(2026, 8, 17, 14, 0),
            Events.DURATION to "PT30M",
            Events.ALL_DAY to 0L,
            Events.EVENT_TIMEZONE to "UTC",
            Events.RRULE to "FREQ=WEEKLY;BYDAY=MO",
            Events.ACCESS_LEVEL to 2L,
            Events.ORGANIZER to "work@example.com",
            "sync_data1" to "dav:href/collection.ics/1",
            "sync_data2" to "etag:7",
        )
        return 1L
    }

    @Test
    fun `this-instance exception insert never clones SYNC_DATA columns`() = runTest {
        seedSyncedRecurringSeries()
        val editor = RecurrenceEditor(repo(), resolver)

        val outcome = editor.apply(
            Resolution.InsertExceptionRow(
                parentEventId = 1L,
                originalInstanceTimeMillis = utc(2026, 8, 24, 14, 0),
                newRowEdits = EventFieldEdits(title = "standup moved once"),
            ),
        )

        val newId = (outcome as RecurrenceEditor.Outcome.Written).touchedEventId!!
        val inserted = fake.events.getValue(newId)
        assertEquals(1L, inserted[Events.ORIGINAL_ID])
        assertEquals("standup moved once", inserted[Events.TITLE])
        // The exception is a fresh row: it must not inherit the parent's
        // sync-adapter-owned href/etag under any spelling of the write.
        assertFalse(inserted.containsKey("sync_data1"))
        assertFalse(inserted.containsKey("sync_data2"))
        // …and the parent's own sync data is untouched.
        val parent = fake.events.getValue(1L)
        assertEquals("dav:href/collection.ics/1", parent["sync_data1"])
        assertEquals("etag:7", parent["sync_data2"])
    }

    @Test
    fun `this-and-following continuation insert never clones SYNC_DATA columns`() = runTest {
        seedSyncedRecurringSeries()
        val secondOccurrence = utc(2026, 8, 24, 14, 0)
        val editor = RecurrenceEditor(repo(), resolver)

        val outcome = editor.apply(
            Resolution.SplitParent(
                parentEventId = 1L,
                newUntil = EndCondition.Until(secondOccurrence - 1),
                newRowStartMillis = secondOccurrence,
                newRowEdits = EventFieldEdits(),
                remainingRule = RRuleModel(frequency = Frequency.WEEKLY),
            ),
        )

        val continuationId = (outcome as RecurrenceEditor.Outcome.Written).touchedEventId!!
        // Two writes happened: the truncated parent update and the inserted
        // continuation. Neither write may carry SYNC_DATA* — the fake rejects
        // both exactly where CalendarProvider2 would — so the inserted row
        // starts without them.
        val continuation = fake.events.getValue(continuationId)
        assertFalse("continuation must not carry sync_data1", continuation.containsKey("sync_data1"))
        assertFalse("continuation must not carry sync_data2", continuation.containsKey("sync_data2"))
        // The truncated parent KEEPS its stored sync data (absence preserved).
        assertEquals("dav:href/collection.ics/1", fake.events.getValue(1L)["sync_data1"])
        assertEquals("etag:7", fake.events.getValue(1L)["sync_data2"])
    }
}
