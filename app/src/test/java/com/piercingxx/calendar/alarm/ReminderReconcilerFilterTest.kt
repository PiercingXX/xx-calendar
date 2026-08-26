package com.piercingxx.calendar.alarm

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.calendar.settings.AutoAddedFilterMode
import com.piercingxx.calendar.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController

/**
 * WS16: alarms match the VISIBLE calendar. Views hide auto-added rows through
 * [com.piercingxx.calendar.calendar.InstanceFilters]; the reconciler used to
 * plan over the raw provider window, so a Gmail-booking row hidden in every
 * view could still fire an alarm from behind the filter.
 *
 * Runs one real reconcile pass over a scenario provider twice — filter armed,
 * then disarmed — and asserts the planned alarm set tracks the setting.
 *
 * The scenario provider serves exactly the three queries one pass makes
 * (Instances window, Calendars list, Reminders `EVENT_ID IN (...)` join);
 * it is local to this file because the shared FakeCalendarProvider is owned
 * by another workstream and cannot model this join yet.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderReconcilerFilterTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val HOUR = 3_600_000L

    private class ScenarioProvider : ContentProvider() {

        val calendarRows = mutableListOf<Map<String, Any?>>()
        val instanceRows = mutableListOf<Map<String, Any?>>()
        val reminderRows = mutableListOf<Map<String, Any?>>()

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String? = null

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection?.toList() ?: error("scenario provider needs a projection")
            val rows: List<Map<String, Any?>> = when (uri.pathSegments.firstOrNull()) {
                "instances" -> instanceRows
                "calendars" -> calendarRows
                "reminders" -> {
                    require(selection!!.contains(" IN ")) { "unexpected selection $selection" }
                    val ids = selectionArgs.orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
                    reminderRows.filter { it[Reminders.EVENT_ID] in ids }
                }

                else -> error("unexpected query $uri")
            }
            return MatrixCursor(columns.toTypedArray()).apply {
                rows.forEach { row -> addRow(columns.map { row[it] }) }
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri =
            throw UnsupportedOperationException("scenario provider is read-only")

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw UnsupportedOperationException("scenario provider is read-only")

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = throw UnsupportedOperationException("scenario provider is read-only")
    }

    private val provider = ScenarioProvider()

    private lateinit var controller: ContentProviderController<ScenarioProvider>

    @Before
    fun wireScenario() {
        controller = ContentProviderController.of(provider).create(CalendarContract.AUTHORITY)
        ProviderObserver.stopForTests()
        // The all-day policy mirror is process-global; isolate from other suites.
        ReminderPlanner.allDayPolicy = null
    }

    @After
    fun unwireScenario() {
        controller.shutdown()
        ReminderPlanner.allDayPolicy = null
    }

    /**
     * A Google-generated secondary calendar (`@group.v.calendar.google.com`)
     * holding one timed, reminded event two hours out — inside §4.3's 48h
     * horizon, trigger strictly future.
     */
    private fun seedAutoAddedEventWithReminder() {
        val start = System.currentTimeMillis() + 2 * HOUR
        provider.calendarRows += mapOf(
            Calendars._ID to 1L,
            Calendars.ACCOUNT_NAME to "us-events@group.v.calendar.google.com",
            Calendars.ACCOUNT_TYPE to "com.google",
            Calendars.CALENDAR_DISPLAY_NAME to "synced",
            Calendars.CALENDAR_COLOR to 0xFF888888.toInt(),
            Calendars.VISIBLE to 1L,
            Calendars.OWNER_ACCOUNT to "us-events@group.v.calendar.google.com",
            Calendars.CALENDAR_ACCESS_LEVEL to CalendarContract.Calendars.CAL_ACCESS_OWNER.toLong(),
        )
        provider.instanceRows += mapOf(
            Instances.EVENT_ID to 42L,
            Instances.CALENDAR_ID to 1L,
            Instances.TITLE to "Flight UA 214 booked",
            Instances.EVENT_LOCATION to null,
            Instances.DESCRIPTION to null,
            Instances.BEGIN to start,
            Instances.END to start + 30 * 60_000L,
            Instances.ALL_DAY to 0L,
            Instances.EVENT_TIMEZONE to "UTC",
            Instances.EVENT_END_TIMEZONE to null,
            Instances.RRULE to null,
            Instances.DURATION to null,
            Instances.AVAILABILITY to 0,
            Instances.STATUS to Events_Status.confirmed,
            Instances.ORIGINAL_ID to null,
            Instances.ORIGINAL_INSTANCE_TIME to null,
            Instances.SELF_ATTENDEE_STATUS to 0, // not declined
        )
        provider.reminderRows += mapOf(
            Reminders.EVENT_ID to 42L,
            Reminders.MINUTES to 30,
            Reminders.METHOD to Reminders.METHOD_ALERT,
        )
    }

    private object Events_Status {
        const val confirmed: Int = CalendarContract.Events.STATUS_CONFIRMED
    }

    private fun writeFilterSettings(hideAutoAdded: Boolean) {
        runBlocking {
            val store = SettingsStore(app)
            store.setAutoAddedFilterMode(AutoAddedFilterMode.METADATA)
            store.setHideAutoAdded(hideAutoAdded)
            store.setShowDeclined(false)
        }
    }

    @Test
    fun `auto-added instance inside the horizon is absent while hideAutoAdded is on`() {
        seedAutoAddedEventWithReminder()
        writeFilterSettings(hideAutoAdded = true)

        ReminderReconciler.reconcileBlocking(app)

        assertFalse(
            ScheduledAlarmRegistry(app).load().any { it.eventId == 42L },
        )
    }

    @Test
    fun `auto-added instance is planned once hideAutoAdded is off`() {
        seedAutoAddedEventWithReminder()
        writeFilterSettings(hideAutoAdded = false)

        ReminderReconciler.reconcileBlocking(app)

        assertTrue(
            "filter off -> the row is visible, so its alarm must be planned",
            ScheduledAlarmRegistry(app).load().any { it.eventId == 42L },
        )
    }
}
