package com.piercingxx.calendar.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The one Robolectric smoke suite this workstream allows itself (planner math
 * lives in pure JVM tests). Covers what pure JVM cannot:
 * - receiver plumbing: alarm delivery -> notification posted / silently
 *   skipped without POST_NOTIFICATIONS or when the key is not in the
 *   registry (spoofed-intent guard);
 * - AlarmScheduler <-> AlarmManager wiring incl. the exact-denied fallback;
 * - a full reconciler pass over an absent provider (converge-to-empty) and
 *   the daily heartbeat arming;
 * - registry persistence through real SharedPreferences.
 *
 * Provider-backed reconciliation (real Instances join) is deliberately NOT
 * here: WS12 runs instrumented against the real provider (design §11).
 */
@RunWith(RobolectricTestRunner::class)
class ReceiverSmokeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val start = 1_800_000_000_000L
    private val key = AlarmKey(eventId = 42L, instanceStartMillis = start, triggerAtMillis = start)

    @Before
    fun cleanSlate() {
        ProviderObserver.stopForTests()
        shadowOf(getNotificationManager()).allNotifications.clear()
    }

    private fun getNotificationManager() =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun getAlarmManager() =
        app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // ---- reminder delivery -> notification

    @Test
    fun `alarm delivery posts one quiet notification`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // A real delivery is always a key the reconciler recorded.
        ScheduledAlarmRegistry(app).persist(setOf(key))

        val intent = Intent(app, ReminderReceiver::class.java)
            .putExtras(AlarmScheduler.extrasOf(key))
        ReminderReceiver().onReceive(app, intent)

        val all = shadowOf(getNotificationManager()).allNotifications
        assertEquals(1, all.size)
        assertEquals(app.getString(app.applicationInfo.labelRes), all[0].extras.getString("android.title"))
        // Quiet defaults D12: time-of-event text, never an event title preview.
        assertTrue(
            "content must be non-empty",
            !all[0].extras.getCharSequence("android.text").isNullOrEmpty(),
        )
    }

    @Test
    fun `delivery without POST_NOTIFICATIONS skips silently`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ScheduledAlarmRegistry(app).persist(setOf(key))

        val intent = Intent(app, ReminderReceiver::class.java)
            .putExtras(AlarmScheduler.extrasOf(key))
        ReminderReceiver().onReceive(app, intent)

        assertTrue(shadowOf(getNotificationManager()).allNotifications.isEmpty())
    }

    @Test
    fun `delivery of a key absent from the registry is ignored silently`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // Spoofed explicit intent: extras for an alarm the reconciler never
        // scheduled. Must not become a notification even though granted.
        val intent = Intent(app, ReminderReceiver::class.java)
            .putExtras(AlarmScheduler.extrasOf(key))
        ReminderReceiver().onReceive(app, intent)

        assertTrue(shadowOf(getNotificationManager()).allNotifications.isEmpty())
    }

    @Test
    fun `delivery without extras is a no-op`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        ReminderReceiver().onReceive(app, Intent(app, ReminderReceiver::class.java))

        assertTrue(shadowOf(getNotificationManager()).allNotifications.isEmpty())
    }

    // ---- broadcast triggers run the reconcile pass

    @Test
    fun `TIME_SET broadcast completes a pass over an absent provider`() {
        ReminderReceiver().onReceive(
            app,
            Intent(Intent.ACTION_TIME_CHANGED).setClass(app, ReminderReceiver::class.java),
        )
        // No exception = pass survived; async completion asserted nowhere on purpose.
    }

    @Test
    fun `BOOT_COMPLETED smoke`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    // ---- reconciler converge-to-empty + heartbeat arming

    @Test
    fun `reconcile over empty provider converges and arms the daily heartbeat`() {
        val before = System.currentTimeMillis()

        ReminderReconciler.reconcileBlocking(app)

        val next = shadowOf(getAlarmManager()).peekNextScheduledAlarm()
        assertNotNull("heartbeat must be armed by every reconcile", next)
        assertTrue(next!!.triggerAtTime > before)
        assertEquals(emptySet<AlarmKey>(), ScheduledAlarmRegistry(app).load())
    }

    // ---- AlarmScheduler wiring

    @Test
    fun `schedule registers and cancel unregisters the exact pendingintent`() {
        val scheduler = AlarmScheduler(app)

        scheduler.schedule(key)
        assertNotNull(
            PendingIntent.getBroadcast(
                app,
                AlarmKeys.stableRequestCode(key),
                Intent(app, ReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        assertEquals(key.triggerAtMillis, shadowOf(getAlarmManager()).peekNextScheduledAlarm()!!.triggerAtTime)

        scheduler.cancel(key)
        assertNull(
            PendingIntent.getBroadcast(
                app,
                AlarmKeys.stableRequestCode(key),
                Intent(app, ReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    @Test
    fun `exact denied falls back to window scheduling instead of crashing`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduler = AlarmScheduler(app)
        assertFalse(scheduler.canScheduleExactAlarms())

        scheduler.schedule(key) // must not throw even though exact is denied

        assertEquals(key.triggerAtMillis, shadowOf(getAlarmManager()).peekNextScheduledAlarm()!!.triggerAtTime)
        scheduler.cancel(key)
    }

    // ---- registry persistence

    @Test
    fun `registry persists keys across instances`() {
        val other = AlarmKey(7L, start + 5, start - ReminderPlanner.ALL_DAY_LEAD_MILLIS)

        ScheduledAlarmRegistry(app).persist(setOf(key, other))

        assertEquals(setOf(key, other).sorted(), ScheduledAlarmRegistry(app).load().toList())
    }
}
