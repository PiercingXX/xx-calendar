package com.piercingxx.calendar.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.piercingxx.calendar.calendar.Fixtures.seedCalendar

/**
 * changes() must emit on real ContentObserver notifications — registered the
 * normal way through the resolver — and stay silent for URIs it does not
 * watch. No sleeps: notifications dispatch inline (null-handler observer),
 * and the flow's buffer carries emissions to the collector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChangesFlowTest : FakeProviderFixture() {

    private fun repo() = CalendarRepository(resolver)

    private fun insertEventDirectly(title: String): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, 1L)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, 1000L)
            put(CalendarContract.Events.DTEND, 2000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }
        return ContentUris.parseId(
            ApplicationProvider.getApplicationContext<android.app.Application>()
                .contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)!!,
        )
    }

    @Test
    fun `emits when an event row is inserted and stamps lastProviderChange`() = runTest {
        fake.seedCalendar()
        val repository = repo()
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.changes().collect { emissions.trySend(Unit) }
        }
        runCurrent()
        assertTrue(emissions.tryReceive().isFailure) // nothing yet

        insertEventDirectly("notified")

        withTimeout(5_000) { emissions.receive() }
        assertNotNull(repository.lastProviderChange())
    }

    @Test
    fun `emits on raw resolver notification without any row change`() = runTest {
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo().changes().collect { emissions.trySend(Unit) }
        }
        runCurrent()

        resolver.notifyChange(CalendarContract.Instances.CONTENT_URI, null)

        withTimeout(5_000) { emissions.receive() }
    }

    @Test
    fun `calendars-only changes do not emit`() = runTest {
        fake.seedCalendar()
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo().changes().collect { emissions.trySend(Unit) }
        }
        runCurrent()

        resolver.notifyChange(CalendarContract.Calendars.CONTENT_URI, null)

        assertTrue(emissions.tryReceive().isFailure)
    }

    @Test
    fun `a write made through the repository emits exactly once per save`() = runTest {
        fake.seedCalendar()
        val repository = repo()
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.changes().collect { emissions.trySend(Unit) }
        }
        runCurrent()

        val id = repository.saveEvent(
            EventDraft(
                calendarId = 1L,
                startMillis = 0L,
                endMillis = 1L,
                eventTimezone = "UTC",
                title = "via repo",
            ),
        )
        withTimeout(5_000) { emissions.receive() }

        repository.deleteEvent(id)
        withTimeout(5_000) { emissions.receive() }

        assertTrue(emissions.tryReceive().isFailure)
    }
}
