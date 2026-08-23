package com.piercingxx.calendar.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import com.piercingxx.calendar.MainActivity
import com.piercingxx.calendar.R

/**
 * Two jobs, one class (design §4.3, §12 manifest):
 *
 * 1. Alarm target — a [AlarmScheduler] PendingIntent lands here; post the
 *    reminder notification.
 * 2. Re-reconcile trigger — TIME_SET / TIMEZONE_CHANGED / MY_PACKAGE_REPLACED
 *    (§10's clock/timezone/update rows) and the internal daily heartbeat all
 *    just run another reconcile pass. No per-event logic anywhere.
 *
 * Notification content is deliberately minimal (D12 quiet defaults): app
 * name as the title, the event's start time as the text — no title preview,
 * IMPORTANCE_DEFAULT so no heads-up. Declined events can never reach this
 * path because the planner already filtered them.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val app = context.applicationContext
        when (intent.action) {
            // "android.intent.action.TIME_SET" — the platform constant for the
            // user-changed-clock broadcast is ACTION_TIME_CHANGED.
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_DAILY_HEARTBEAT,
            -> ReminderReconciler.reconcileAfterBroadcast(app, goAsync()?.let { it::finish })
        }
        // No action match => alarm delivery (our own PendingIntents carry no
        // action). Belt #2 behind the manifest's exported="false": a real
        // delivery always names a key the reconciler scheduled and recorded in
        // [ScheduledAlarmRegistry]; any other explicit intent (spoofed extras,
        // stale replay) is ignored silently.
        if (intent.action == null) {
            val key = AlarmScheduler.keyOf(intent.extras)
            if (key != null && isScheduledAlarm(app, key)) {
                postReminderNotification(app, key)
            }
        }
    }

    /** The {eventId, instanceStart} half must match a registered alarm key. */
    private fun isScheduledAlarm(app: Context, key: AlarmKey): Boolean =
        ScheduledAlarmRegistry(app).load().any {
            it.eventId == key.eventId && it.instanceStartMillis == key.instanceStartMillis
        }

    private fun postReminderNotification(app: Context, key: AlarmKey) {
        // POST_NOTIFICATIONS guard: skip silently rather than crash or nag
        // (§10 tone). Documented here because there is no UI to say it:
        // without the grant this app cannot show reminders at all, and WS9's
        // warn row is where that becomes visible.
        if (!notificationsGranted(app)) return

        ensureChannel(app)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val whenText = formatEventTime(app, key.instanceStartMillis)
        val tapIntent = PendingIntent.getActivity(
            app,
            AlarmKeys.stableRequestCode(key),
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(app, CHANNEL_ID)
            // Framework asset on purpose: res/ is outside this workstream's
            // write scope. Swapping in a product glyph later is a one-liner.
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(app.getString(R.string.app_name))
            .setContentText(whenText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(whenText))
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()
        manager.notify(AlarmKeys.stableRequestCode(key), notification)
    }

    /** App name + start time only — no event title, no preview (D12). */
    private fun formatEventTime(context: Context, instanceStartMillis: Long): String =
        DateUtils.formatDateTime(
            context,
            instanceStartMillis,
            DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_ABBREV_ALL,
        )

    private fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Idempotent by contract; cheap enough to call on every delivery. */
    private fun ensureChannel(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            // Name is a literal because res/ is outside this workstream's
            // scope; IMPORTANCE_DEFAULT keeps heads-up off (D12 quiet).
            NotificationChannel(
                CHANNEL_ID,
                "Reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    companion object {
        const val CHANNEL_ID = "reminders"

        /** Internal heartbeat action delivered via explicit intent; not in the manifest. */
        const val ACTION_DAILY_HEARTBEAT = "com.piercingxx.calendar.alarm.DAILY_HEARTBEAT"
    }
}
