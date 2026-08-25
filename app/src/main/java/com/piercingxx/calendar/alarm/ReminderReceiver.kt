package com.piercingxx.calendar.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import com.piercingxx.calendar.MainActivity
import com.piercingxx.calendar.R
import com.piercingxx.calendar.settings.Settings
import com.piercingxx.calendar.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Two jobs, one class (design §4.3, §12 manifest):
 *
 * 1. Alarm target — a [AlarmScheduler] PendingIntent lands here; post the
 *    reminder notification.
 * 2. Re-reconcile trigger — TIME_SET / TIMEZONE_CHANGED / MY_PACKAGE_REPLACED
 *    (§10's clock/timezone/update rows) and the internal daily heartbeat all
 *    just run another reconcile pass. No per-event logic anywhere.
 *
 * Notification content honours §8.6's quiet-posture switches:
 *  - `headsUp` off (default) → DEFAULT-importance channel, no heads-up; on →
 *    a HIGH-importance channel so the platform may banner it.
 *  - `lockScreenTitle` off (default) → app name as the title, PRIVATE
 *    lock-screen visibility with a redacted public version; on → the event's
 *    title is fetched and shown and visibility is PUBLIC.
 * Declined events can never reach this path because the planner already
 * filtered them.
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

        // One blocking DataStore read per delivery (short local file); the
        // §8.6 switches must be current at post time, not at process start.
        val settings = runBlocking(Dispatchers.IO) {
            runCatching { SettingsStore(app.applicationContext).current() }
                .getOrDefault(Settings())
        }
        val showTitle = settings.lockScreenTitle
        val headsUp = settings.headsUp

        ensureChannel(app, headsUp)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val whenText = formatEventTime(app, key.instanceStartMillis)
        val appName = app.getString(R.string.app_name)
        val tapIntent = PendingIntent.getActivity(
            app,
            AlarmKeys.stableRequestCode(key),
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (showTitle) queryEventTitle(app, key.eventId) ?: appName else appName
        val notification: Notification = NotificationCompat.Builder(app, channelIdFor(headsUp))
            // Framework asset on purpose: res/ is outside this workstream's
            // write scope. Swapping in a product glyph later is a one-liner.
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(whenText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(whenText))
            .setVisibility(lockscreenVisibilityFor(showTitle))
            // Pre-O heads-up hint; the channel importance does the real work.
            .setPriority(
                if (headsUp) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .also { builder ->
                if (!showTitle) {
                    // Redacted lock-screen rendering: the public version shows
                    // only what D12 allows — app name + start time.
                    builder.setPublicVersion(
                        NotificationCompat.Builder(app, channelIdFor(headsUp))
                            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                            .setContentTitle(appName)
                            .setContentText(whenText)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                            .build(),
                    )
                }
            }
            .build()
        manager.notify(AlarmKeys.stableRequestCode(key), notification)
    }

    /** The event's own title for §8.6 `lockScreenTitle`; null when unreadable. */
    private fun queryEventTitle(app: Context, eventId: Long): String? = runCatching {
        app.contentResolver.query(
            android.provider.CalendarContract.Events.CONTENT_URI,
            arrayOf(android.provider.CalendarContract.Events.TITLE),
            "${android.provider.CalendarContract.Events._ID}=?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    }.getOrNull()

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

    /**
     * Idempotent by contract; cheap enough to call on every delivery. The
     * heads-up switch selects the channel — importance is a channel property,
     * so quiet and bannered reminders live in separate channels rather than
     * mutating one under the user. Notification channels are API 26+; minSdk
     * is 26, so no version guard is needed here.
     *
     * The bundled `res/raw/xx_calendar` chime is the channel's default sound.
     * A channel's sound is frozen once the channel exists on a device, so the
     * v2 ids are the migration: create the v2 channel with the sound baked in
     * and delete the pre-sound v1 ids in the same pass.
     */
    private fun ensureChannel(app: Context, headsUp: Boolean) {
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = Uri.parse("android.resource://${app.packageName}/raw/xx_calendar")
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // Name is a literal because res/ is outside this workstream's scope.
        manager.createNotificationChannel(
            NotificationChannel(
                channelIdFor(headsUp),
                "Reminders",
                if (headsUp) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { setSound(sound, attributes) },
        )
        // Retire the soundless v1 channels; deleting an id that never existed
        // on this device is a no-op.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID_HEADS_UP)
    }

    companion object {
        const val CHANNEL_ID = "reminders_v2"

        /** HIGH-importance channel for §8.6 `headsUp`; created on demand. */
        const val CHANNEL_ID_HEADS_UP = "reminders_heads_up_v2"

        /** Pre-v2 ids (no bundled sound); deleted whenever a channel is ensured. */
        private const val LEGACY_CHANNEL_ID = "reminders"
        private const val LEGACY_CHANNEL_ID_HEADS_UP = "reminders_heads_up"

        /** Internal heartbeat action delivered via explicit intent; not in the manifest. */
        const val ACTION_DAILY_HEARTBEAT = "com.piercingxx.calendar.alarm.DAILY_HEARTBEAT"

        /** Channel chosen per delivery from §8.6 `headsUp`. */
        internal fun channelIdFor(headsUp: Boolean): String =
            if (headsUp) CHANNEL_ID_HEADS_UP else CHANNEL_ID

        /** PUBLIC shows the real title on the lock screen; PRIVATE redacts to [public version]. */
        internal fun lockscreenVisibilityFor(showTitle: Boolean): Int =
            if (showTitle) {
                NotificationCompat.VISIBILITY_PUBLIC
            } else {
                NotificationCompat.VISIBILITY_PRIVATE
            }
    }
}
