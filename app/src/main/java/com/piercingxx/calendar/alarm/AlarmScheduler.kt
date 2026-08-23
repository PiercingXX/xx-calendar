package com.piercingxx.calendar.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Thin wrapper over [AlarmManager] (design §4.3 step 3, todo WS8). Exact via
 * `setExactAndAllowWhileIdle` so reminders survive Doze; when exact alarms
 * are not grantable it degrades to a 15-minute `setWindow` AND logs — the
 * user-visible warning row is WS9's job (§10: "warned in Settings, stated
 * plainly, never nagged").
 *
 * Request codes are [AlarmKeys.stableRequestCode], so re-scheduling an
 * unchanged key replaces its own PendingIntent instead of accumulating, and
 * cancelling finds the right one without bookkeeping.
 */
class AlarmScheduler(private val context: Context) {

    private val appContext = context.applicationContext

    private val alarmManager =
        appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * True when `setExactAndAllowWhileIdle` will actually be honored.
     * Below S exact alarms need no runtime grant.
     */
    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun schedule(key: AlarmKey) {
        if (canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    key.triggerAtMillis,
                    operationIntent(key),
                )
                return
            } catch (e: SecurityException) {
                // Grant revoked between canScheduleExactAlarms() and the call
                // (check-then-use race): degrade THIS key instead of letting
                // the exception abort the whole reconcile pass.
                Log.w(TAG, "exact alarm revoked mid-flight; 15min-window fallback for $key")
            }
        } else {
            Log.w(TAG, "exact alarms denied; 15min-window fallback for $key")
        }
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            key.triggerAtMillis,
            FALLBACK_WINDOW_MILLIS,
            operationIntent(key),
        )
    }

    /** No-op when nothing is registered under the key (FLAG_NO_CREATE). */
    fun cancel(key: AlarmKey) {
        val existing = PendingIntent.getBroadcast(
            appContext,
            AlarmKeys.stableRequestCode(key),
            receiverIntent(key),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing != null) {
            alarmManager.cancel(existing)
            existing.cancel()
        }
    }

    private fun receiverIntent(key: AlarmKey): Intent =
        Intent(appContext, ReminderReceiver::class.java).apply { putExtras(extrasOf(key)) }

    private fun operationIntent(key: AlarmKey): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            AlarmKeys.stableRequestCode(key),
            receiverIntent(key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val TAG = "AlarmScheduler"

        const val EXTRA_EVENT_ID = "com.piercingxx.calendar.alarm.EXTRA_EVENT_ID"
        const val EXTRA_INSTANCE_START = "com.piercingxx.calendar.alarm.EXTRA_INSTANCE_START"
        const val EXTRA_TRIGGER_AT = "com.piercingxx.calendar.alarm.EXTRA_TRIGGER_AT"

        const val FALLBACK_WINDOW_MILLIS: Long = 15L * 60L * 1000L

        fun extrasOf(key: AlarmKey): Bundle = Bundle().apply {
            putLong(EXTRA_EVENT_ID, key.eventId)
            putLong(EXTRA_INSTANCE_START, key.instanceStartMillis)
            putLong(EXTRA_TRIGGER_AT, key.triggerAtMillis)
        }

        fun keyOf(extras: Bundle?): AlarmKey? {
            extras ?: return null
            val eventId = extras.getLong(EXTRA_EVENT_ID, Long.MIN_VALUE)
            val start = extras.getLong(EXTRA_INSTANCE_START, Long.MIN_VALUE)
            val trigger = extras.getLong(EXTRA_TRIGGER_AT, Long.MIN_VALUE)
            if (eventId == Long.MIN_VALUE || start == Long.MIN_VALUE ||
                trigger == Long.MIN_VALUE
            ) {
                return null
            }
            return AlarmKey(eventId, start, trigger)
        }
    }
}
