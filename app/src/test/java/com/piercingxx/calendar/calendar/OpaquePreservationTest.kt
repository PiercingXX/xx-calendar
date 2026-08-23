package com.piercingxx.calendar.calendar

import android.provider.CalendarContract.Events
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.piercingxx.calendar.calendar.Fixtures.seedCalendar
import com.piercingxx.calendar.calendar.Fixtures.utc

/**
 * THE D8 TEST (R6): an event arrives with every unmodelled column populated,
 * as a real synced row would. The app loads it, changes ONE modeled field,
 * saves — and every unmodelled column must come back byte-identical.
 * Anything else is vandalising the user's Google account through DAVx⁵.
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

    private val opaqueColumns = listOf(
        Events.ACCESS_LEVEL, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS,
        Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ATTENDEE_DATA, Events.ORGANIZER,
        Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI,
        "sync_data1", "sync_data2", "sync_data3",
    )

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
        for (column in opaqueColumns) {
            assertTrue("capture missed $column", loaded.opaque.values.containsKey(column))
        }
        for (modeled in OpaqueColumns.MODELED_EVENT_COLUMNS) {
            assertFalse("opaque bag must not hold modeled $modeled", loaded.opaque.values.containsKey(modeled))
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
        opaqueColumns.forEach { column ->
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
}
