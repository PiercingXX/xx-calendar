package com.piercingxx.calendar

import android.Manifest
import android.content.ContentValues
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Reminders
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.piercingxx.calendar.alarm.AlarmKey
import com.piercingxx.calendar.alarm.ReminderReconciler
import com.piercingxx.calendar.alarm.ScheduledAlarmRegistry
import com.piercingxx.calendar.calendar.EventDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WS12 instrumented suite, test 4 — design §11 "Reminder reconciliation after
 * a simulated boot" and R7's convergence story.
 *
 * A boot is simulated the honest way: AlarmManager is empty after reboot, so
 * the reconciler's persisted ledger ([ScheduledAlarmRegistry]) is reset to
 * empty and [ReminderReconciler.reconcileBlocking] is invoked directly. One
 * pass must register exactly the alarms the next 48h deserve; a second pass
 * must be a no-op (§4.3: reconcile converges, event-chasing does not).
 *
 * The registered exact alarms themselves are not introspectable through the
 * public SDK (`getNextAlarmClock` only sees `setAlarmClock` entries), so the
 * registry file IS the observable — it is what the reconciler diffs against,
 * and todo WS12 names it as such.
 */
@RunWith(AndroidJUnit4::class)
class ReminderReconciliationAfterBootTest : ProviderFixture() {

    @get:Rule
    val calendarAccess: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    @Test
    fun boot_reconcile_registers_upcoming_reminder_and_skips_declined() {
        val calendarId = writableCalendarId()
        val registry = ScheduledAlarmRegistry(context)
        val now = System.currentTimeMillis()
        val minuteMillis = 60_000L
        val start = now + 120 * minuteMillis

        val upcoming = runBlocking {
            repository.saveEvent(
                EventDraft(
                    calendarId = calendarId,
                    startMillis = start,
                    endMillis = start + 30 * minuteMillis,
                    eventTimezone = "UTC",
                    title = "reminded review",
                ),
            )
        }.also { insertReminder(it) }

        val declined = runBlocking {
            repository.saveEvent(
                EventDraft(
                    calendarId = calendarId,
                    startMillis = start,
                    endMillis = start + 30 * minuteMillis,
                    eventTimezone = "UTC",
                    title = "declined duplicate",
                ),
            )
        }.also { id ->
            insertReminder(id)
            markDeclined(id)
        }

        // The planner's quiet rule keys off Instances.SELF_ATTENDEE_STATUS;
        // only assert the declined branch on images that actually propagated
        // the attendee row (WS12 brief: Assume where an image misbehaves).
        val window = runBlocking {
            repository.instances(now - minuteMillis, now + ReminderReconciler.HORIZON_MILLIS - minuteMillis)
        }
        val declinedInstance = window.firstOrNull { it.eventId == declined }
        assumeTrue(
            "image did not propagate SELF_ATTENDEE_STATUS for $declinedInstance",
            declinedInstance != null &&
                declinedInstance.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED,
        )
        assertTrue("upcoming instance missing from window", window.any { it.eventId == upcoming })

        // --- simulated boot ---
        registry.persist(emptySet())
        ReminderReconciler.reconcileBlocking(context)

        val expected = AlarmKey(
            eventId = upcoming,
            instanceStartMillis = start,
            triggerAtMillis = start - 10 * minuteMillis,
        )
        val registered = registry.load()
        assertTrue("expected $expected among $registered", expected in registered)
        assertTrue(
            "declined event produced an alarm: ${registered.filter { it.eventId == declined }}",
            registered.none { it.eventId == declined },
        )

        // Convergence: the second pass must not add, remove or churn anything.
        ReminderReconciler.reconcileBlocking(context)
        assertEquals("reconcile did not converge to a fixed point", registered, registry.load())
    }

    private fun insertReminder(eventId: Long) {
        resolver.insert(
            Reminders.CONTENT_URI,
            ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, 10)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            },
        ) ?: error("provider refused reminder insert for event $eventId")
    }

    private fun markDeclined(eventId: Long) {
        // Owner-matching email: LocalCalendarBootstrap sets OWNER_ACCOUNT="Local",
        // which is what makes the provider compute SELF_ATTENDEE_STATUS from this row.
        resolver.insert(
            Attendees.CONTENT_URI,
            ContentValues().apply {
                put(Attendees.EVENT_ID, eventId)
                put(Attendees.ATTENDEE_EMAIL, "Local")
                put(Attendees.ATTENDEE_NAME, "Local")
                put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
                put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_NONE)
                put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_DECLINED)
            },
        ) ?: error("provider refused attendee insert for event $eventId")
    }
}
