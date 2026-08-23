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
        assertFalse(row.containsKey(Events.DTEND))
        assertEquals("FREQ=DAILY;INTERVAL=2", row[Events.RRULE])
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

        val row = fake.events.getValue(duplicateId)
        assertEquals("Flight UA234 (copy)", row[Events.TITLE])
        assertEquals(3L, row[Events.ACCESS_LEVEL])
        assertEquals("airline@example.com", row[Events.ORGANIZER])
        assertEquals("server-token-99", row["sync_data1"])
    }
}
