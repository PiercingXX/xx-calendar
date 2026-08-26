package com.piercingxx.calendar.calendar

import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.piercingxx.calendar.calendar.Fixtures.seedCalendar
import com.piercingxx.calendar.calendar.Fixtures.seedEvent
import com.piercingxx.calendar.calendar.Fixtures.seedReminder
import com.piercingxx.calendar.calendar.Fixtures.utc

@RunWith(RobolectricTestRunner::class)
class CalendarRepositoryTest : FakeProviderFixture() {

    private fun repo() = CalendarRepository(resolver)

    // -------------------------------------------------------- round-trip

    @Test
    fun `insert then load returns every modeled field`() = runTest {
        val calendarId = fake.seedCalendar()
        val draft = EventDraft(
            calendarId = calendarId,
            startMillis = utc(2026, 8, 24, 9, 0),
            endMillis = utc(2026, 8, 24, 9, 15),
            eventTimezone = "Europe/Berlin",
            title = "standup",
            location = "Room 1",
            description = "daily",
        )

        val id = repo().saveEvent(draft)
        val loaded = repo().loadEvent(id)

        assertTrue(loaded != null)
        loaded!!
        assertEquals(draft.title, loaded.draft.title)
        assertEquals(draft.location, loaded.draft.location)
        assertEquals(draft.description, loaded.draft.description)
        assertEquals(calendarId, loaded.draft.calendarId)
        assertEquals(draft.startMillis, loaded.draft.startMillis)
        assertEquals(draft.endMillis, loaded.draft.endMillis)
        assertEquals("Europe/Berlin", loaded.draft.eventTimezone)
        assertEquals(id, loaded.eventId)
    }

    @Test
    fun `updating one modeled field leaves the others untouched`() = runTest {
        fake.seedCalendar()
        val repo = repo()
        val original = EventDraft(
            calendarId = 1L,
            startMillis = utc(2026, 8, 24, 14, 0),
            endMillis = utc(2026, 8, 24, 15, 0),
            eventTimezone = "UTC",
            title = "design review",
            location = "old room",
            description = "weekly",
        )
        val id = repo.saveEvent(original)
        val loaded = repo.loadEvent(id)!!

        val edited = loaded.draft.copy(location = "new room")
        repo.saveEvent(edited, loaded.opaque)
        val reloaded = repo.loadEvent(id)!!.draft

        assertEquals("new room", reloaded.location)
        assertEquals("design review", reloaded.title)
        assertEquals(original.startMillis, reloaded.startMillis)
        assertEquals(original.endMillis, reloaded.endMillis)
        assertEquals("weekly", reloaded.description)
        assertEquals(1L, reloaded.calendarId)
    }

    @Test
    fun `delete removes the row`() = runTest {
        fake.seedCalendar()
        val repo = repo()
        val id = repo.saveEvent(
            EventDraft(
                calendarId = 1L,
                startMillis = 1000L,
                endMillis = 2000L,
                eventTimezone = "UTC",
                title = "temp",
            ),
        )
        assertTrue(fake.events.containsKey(id))

        repo.deleteEvent(id)

        assertFalse(fake.events.containsKey(id))
        assertNull(repo.loadEvent(id))
    }

    @Test
    fun `recurring drafts write duration instead of dtend`() = runTest {
        fake.seedCalendar()
        val repo = repo()
        val id = repo.saveEvent(
            EventDraft(
                calendarId = 1L,
                startMillis = utc(2026, 8, 24, 8, 0),
                endMillis = null,
                eventTimezone = "UTC",
                title = "gym",
                duration = "PT45M",
                rrule = "FREQ=DAILY;INTERVAL=2",
            ),
        )
        val row = fake.events.getValue(id)
        assertEquals("PT45M", row[Events.DURATION])
        // Written explicitly NULL so an update merged onto a DTEND-shaped row
        // cannot leave both extents behind (WS13.2).
        assertNull(row[Events.DTEND])
        assertEquals("FREQ=DAILY;INTERVAL=2", row[Events.RRULE])
    }

    // --------------------------------------- DTEND / DURATION exclusivity

    @Test
    fun `adding a repeat to a timed single event nulls the leftover dtend`() = runTest {
        fake.seedCalendar()
        val repo = repo()
        val start = utc(2026, 8, 24, 9, 0)
        // A timed SINGLE event as the provider stores it: DTEND, no DURATION.
        val id = repo.saveEvent(
            EventDraft(
                calendarId = 1L,
                startMillis = start,
                endMillis = start + 1_800_000L,
                eventTimezone = "UTC",
                title = "lunch",
            ),
        )
        assertEquals(start + 1_800_000L, fake.events.getValue(id)[Events.DTEND])

        // Adding a repeat produces exactly what buildDraft emits: a duration,
        // no absolute end. CalendarProvider2 merges this onto the row above
        // before validating — without the explicit DTEND null the stale value
        // survives and the provider throws
        // "Cannot have both DTEND and DURATION in an event".
        repo.saveEvent(
            EventDraft(
                calendarId = 1L,
                eventId = id,
                startMillis = start,
                endMillis = null,
                eventTimezone = "UTC",
                title = "lunch",
                duration = "PT30M",
                rrule = "FREQ=DAILY",
            ),
        )
        val row = fake.events.getValue(id)
        assertEquals("PT30M", row[Events.DURATION])
        assertNull(row[Events.DTEND])
    }

    @Test
    fun `removing a repeat restores dtend and nulls duration`() = runTest {
        fake.seedCalendar()
        val repo = repo()
        val start = utc(2026, 8, 24, 9, 0)
        // Recurring row in the provider's duration shape.
        val id = fake.seedEvent(
            1L,
            Events.TITLE to "lunch",
            Events.DTSTART to start,
            Events.DURATION to "PT30M",
            Events.RRULE to "FREQ=DAILY",
        )
        fake.events.getValue(id).remove(Events.DTEND)

        // Removing the repeat: absolute end again, rule and duration gone.
        repo.saveEvent(
            EventDraft(
                calendarId = 1L,
                eventId = id,
                startMillis = start,
                endMillis = start + 1_800_000L,
                eventTimezone = "UTC",
                title = "lunch",
                duration = null,
                rrule = null,
            ),
        )
        val row = fake.events.getValue(id)
        assertEquals(start + 1_800_000L, row[Events.DTEND])
        assertNull(row[Events.DURATION])
        assertNull(row[Events.RRULE])
    }

    @Test
    fun `all-day recurring row keeps the duration shape when saved`() = runTest {
        fake.seedCalendar()
        val repo = repo()
        val dayStart = utc(2026, 8, 24, 0, 0)
        // All-day recurring Google/DAVx⁵ row: DURATION=P1D, DTEND absent.
        val id = fake.seedEvent(
            1L,
            Events.TITLE to "retreat",
            Events.DTSTART to dayStart,
            Events.DURATION to "P1D",
            Events.ALL_DAY to 1L,
            Events.EVENT_TIMEZONE to "UTC",
            Events.RRULE to "FREQ=WEEKLY",
        )
        fake.events.getValue(id).remove(Events.DTEND)

        // What buildDraft emits for a saved all-day recurring event: an
        // EXCLUSIVE DTEND and no duration. writeModeledInto must translate it
        // back to the provider recurrence shape instead of writing DTEND onto
        // the DURATION-shaped row.
        repo.saveEvent(
            EventDraft(
                calendarId = 1L,
                eventId = id,
                startMillis = dayStart,
                endMillis = dayStart + DAY_MILLIS,
                eventTimezone = "UTC",
                title = "retreat renamed",
                allDay = true,
                rrule = "FREQ=WEEKLY",
            ),
        )
        val row = fake.events.getValue(id)
        assertEquals("P1D", row[Events.DURATION])
        assertNull(row[Events.DTEND])
        assertEquals("FREQ=WEEKLY", row[Events.RRULE])

        // Multi-day span travels as whole days too.
        repo.saveEvent(
            EventDraft(
                calendarId = 1L,
                eventId = id,
                startMillis = dayStart,
                endMillis = dayStart + 3 * DAY_MILLIS,
                eventTimezone = "UTC",
                title = "retreat extended",
                allDay = true,
                rrule = "FREQ=WEEKLY",
            ),
        )
        assertEquals("P3D", fake.events.getValue(id)[Events.DURATION])
        assertNull(fake.events.getValue(id)[Events.DTEND])
    }

    @Test
    fun `single all-day event keeps exclusive dtend and nulls duration`() = runTest {
        fake.seedCalendar()
        val repo = repo()
        val dayStart = utc(2026, 8, 24, 0, 0)

        val id = repo.saveEvent(
            EventDraft(
                calendarId = 1L,
                startMillis = dayStart,
                endMillis = dayStart + DAY_MILLIS,
                eventTimezone = "UTC",
                title = "one-off holiday",
                allDay = true,
            ),
        )

        val row = fake.events.getValue(id)
        assertEquals(dayStart + DAY_MILLIS, row[Events.DTEND])
        assertNull(row[Events.DURATION])
        assertNull(row[Events.RRULE])
    }

    // ---------------------------------------------------------- calendars

    @Test
    fun `calendars maps access level and visibility`() {
        fake.seedCalendar(id = 1, displayName = "owner", accessLevel = Calendars.CAL_ACCESS_OWNER)
        fake.seedCalendar(
            id = 2,
            accountName = "work@corp.example",
            displayName = "readonly",
            visible = false,
            accessLevel = Calendars.CAL_ACCESS_READ,
        )
        val repo = CalendarRepository(resolver)

        runTest {
            val calendars = repo.calendars()

            assertEquals(2, calendars.size)
            val owner = calendars.first { it.id == 1L }
            assertTrue(owner.isWritable)
            assertTrue(owner.isVisible)
            assertEquals("owner", owner.displayName)
            val reader = calendars.first { it.id == 2L }
            assertFalse(reader.isWritable)
            assertFalse(reader.isVisible)
        }
    }

    @Test
    fun `setVisible writes VISIBLE only and never touches SYNC_EVENTS`() = runTest {
        val id = fake.seedCalendar(visible = true)
        val beforeSyncEvents = fake.calendars.getValue(id)[Calendars.SYNC_EVENTS]

        repo().setVisible(id, false)

        val row = fake.calendars.getValue(id)
        assertEquals(0L, row[Calendars.VISIBLE])
        assertEquals(beforeSyncEvents, row[Calendars.SYNC_EVENTS])
        assertFalse(repo().calendars().single { it.id == id }.isVisible)
    }

    // ---------------------------------------------------------- reminders

    @Test
    fun `remindersFor reads rows as stored including non-alert methods`() {
        fake.seedCalendar()
        val eventId = fake.seedEvent(1L)
        fake.seedReminder(eventId, minutes = 30, method = Reminders.METHOD_ALERT)
        fake.seedReminder(eventId, minutes = 10, method = Reminders.METHOD_ALERT)
        fake.seedReminder(eventId, minutes = 60, method = Reminders.METHOD_EMAIL)
        val other = fake.seedEvent(1L)
        fake.seedReminder(other, minutes = 5, method = Reminders.METHOD_ALERT)

        runTest {
            val reminders = CalendarRepository(resolver).remindersFor(eventId)

            assertEquals(
                listOf(
                    EventReminder(10, Reminders.METHOD_ALERT),
                    EventReminder(30, Reminders.METHOD_ALERT),
                    EventReminder(60, Reminders.METHOD_EMAIL),
                ),
                reminders,
            )
        }
    }

    // ------------------------------------------- lastProviderChange stamp

    @Test
    fun `lastProviderChange is unknown until this process changes something`() = runTest {
        val repo = repo()
        assertNull(repo.lastProviderChange())

        fake.seedCalendar() // raw fake mutation bypassing the repository: still unobserved
        assertNull(repo.lastProviderChange())

        repo.saveEvent(
            EventDraft(calendarId = 1L, startMillis = 0L, endMillis = 1L, eventTimezone = "UTC"),
        )
        assertTrue(repo.lastProviderChange() != null)
    }

    // ------------------------------------------------- duplicate carries opaque

    @Test
    fun `duplicate flow can insert an event carrying opaque values`() = runTest {
        fake.seedCalendar()
        val repo = repo()
        val sourceId = fake.seedEvent(
            1L,
            Events.TITLE to "Flight UA234",
            Events.ACCESS_LEVEL to 3L,
            Events.ORGANIZER to "airline@example.com",
            "sync_data1" to "server-token-99",
        )
        val loaded = repo.loadEvent(sourceId)!!

        val duplicateId = repo.saveEvent(
            loaded.draft.copy(eventId = null, title = "Flight UA234 (copy)"),
            loaded.opaque,
        )
        assertNotEquals(sourceId, duplicateId)

        // App-writable opaque columns clone onto the copy; sync-adapter-owned
        // ones must not — the fake would reject the insert otherwise (WS13.1).
        val row = fake.events.getValue(duplicateId)
        assertEquals("Flight UA234 (copy)", row[Events.TITLE])
        assertEquals(3L, row[Events.ACCESS_LEVEL])
        assertEquals("airline@example.com", row[Events.ORGANIZER])
        assertFalse(row.containsKey("sync_data1"))
    }
}

private const val DAY_MILLIS = 86_400_000L
