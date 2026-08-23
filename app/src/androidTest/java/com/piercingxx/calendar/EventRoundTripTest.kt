package com.piercingxx.calendar

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.piercingxx.calendar.calendar.EventDraft
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WS12 instrumented suite, test 1: the plain create/edit/delete round-trip
 * through [com.piercingxx.calendar.calendar.CalendarRepository] against the
 * REAL provider (design §11 "Create / edit / delete round-trips").
 */
@RunWith(AndroidJUnit4::class)
class EventRoundTripTest : ProviderFixture() {

    @get:Rule
    val calendarAccess: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private fun newDraft(): EventDraft {
        val start = LocalDate.now(ZoneOffset.UTC).plusDays(2)
            .atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return EventDraft(
            calendarId = writableCalendarId(),
            startMillis = start,
            endMillis = start + DEFAULT_DURATION_MILLIS,
            eventTimezone = "UTC",
            title = "round trip",
            location = "Room 6",
            description = "first pass",
        )
    }

    @Test
    fun inserted_event_loads_back_with_all_modeled_fields() = runBlocking {
        val draft = newDraft()

        val id = repository.saveEvent(draft)

        assertTrue("insert returned no row id", id > 0)
        val loaded = repository.loadEvent(id)
        assertNotNull(loaded)
        loaded!!
        assertEquals(draft.title, loaded.draft.title)
        assertEquals(draft.location, loaded.draft.location)
        assertEquals(draft.description, loaded.draft.description)
        assertEquals(draft.startMillis, loaded.draft.startMillis)
        assertEquals(draft.endMillis, loaded.draft.endMillis)
        assertEquals(draft.eventTimezone, loaded.draft.eventTimezone)
        assertEquals(draft.calendarId, loaded.draft.calendarId)
        assertEquals(false, loaded.draft.allDay)
        assertEquals(null, loaded.draft.rrule)
    }

    @Test
    fun title_update_leaves_every_other_modeled_field_untouched() = runBlocking {
        val id = repository.saveEvent(newDraft())
        val before = repository.loadEvent(id)!!

        repository.saveEvent(before.draft.copy(title = "renamed mid-flight"), before.opaque)

        val after = repository.loadEvent(id)!!
        assertEquals("renamed mid-flight", after.draft.title)
        // Whole-draft equality: every modeled field except TITLE is identical.
        assertEquals(before.draft.copy(title = after.draft.title), after.draft)
        // D8: the held opaque bag survives the round trip verbatim.
        assertEquals(before.opaque, after.opaque)
    }

    @Test
    fun deleted_event_stays_deleted() = runBlocking {
        val id = repository.saveEvent(newDraft())
        assertNotNull(repository.loadEvent(id))

        repository.deleteEvent(id)

        assertNull(repository.loadEvent(id))
    }

    private companion object {
        const val DEFAULT_DURATION_MILLIS = 30L * 60_000L
    }
}
