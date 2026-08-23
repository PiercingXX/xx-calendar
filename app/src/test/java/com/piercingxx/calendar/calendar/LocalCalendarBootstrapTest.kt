package com.piercingxx.calendar.calendar

import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.piercingxx.calendar.calendar.Fixtures.seedCalendar

@RunWith(RobolectricTestRunner::class)
class LocalCalendarBootstrapTest : FakeProviderFixture() {

    private fun bootstrap() = LocalCalendarBootstrap

    @Test
    fun `empty provider creates exactly one local writable calendar`() = runTest {
        val id = bootstrap().ensureWritableCalendar(ApplicationProvider.getApplicationContext())

        assertTrue(id != null)
        val calendars = CalendarRepository(resolver).calendars()
        assertEquals(1, calendars.size)
        val local = calendars.single()
        assertEquals(LocalCalendarBootstrap.LOCAL_ACCOUNT_NAME, local.accountName)
        assertEquals(CalendarContract.ACCOUNT_TYPE_LOCAL, local.accountType)
        assertEquals("Calendar", local.displayName)
        assertTrue(local.isWritable)
        assertTrue(local.isVisible)
    }

    @Test
    fun `second call is idempotent`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val first = bootstrap().ensureWritableCalendar(context)
        val second = bootstrap().ensureWritableCalendar(context)

        assertEquals(first, second)
        assertEquals(1, fake.calendars.size)
    }

    @Test
    fun `existing writable calendar is a no-op regardless of its account`() = runTest {
        fake.seedCalendar(accountName = "you@corp.example", accountType = "at.techbee.DAVx5")

        val id = bootstrap().ensureWritableCalendar(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        )

        assertEquals(1L, id) // the pre-existing calendar's id, untouched
        assertEquals(1, fake.calendars.size)
    }

    @Test
    fun `non-writable calendars do not satisfy the check`() = runTest {
        fake.seedCalendar(accessLevel = CalendarContract.Calendars.CAL_ACCESS_READ)

        val id = bootstrap().ensureWritableCalendar(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        )
        val calendars = CalendarRepository(resolver).calendars()

        assertEquals(2, calendars.size)
        val created = calendars.first { it.id == id }
        assertTrue(created.isWritable)
        assertEquals(CalendarContract.ACCOUNT_TYPE_LOCAL, created.accountType)
        assertFalse(calendars.first { it.id == 1L }.isWritable)
    }
}
