package com.piercingxx.calendar.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-schedules reminders after boot (design §4.3, §10). Both boot actions
 * funnel into the same reconciler pass.
 *
 * Direct-boot note (the manifest comment deferred this decision to WS8): on
 * LOCKED_BOOT_COMPLETED the app runs before user unlock, so credential-
 * encrypted storage — including the alarm registry's SharedPreferences — is
 * not yet readable, and CalendarProvider2 is itself unavailable pre-unlock.
 * The reconciler degrades correctly rather than crashing: the registry reads
 * as empty (full reschedule; alarms never survive reboot anyway) and the
 * provider query yields no instances. BOOT_COMPLETED always follows after
 * unlock and does the real pass. The receiver is deliberately NOT marked
 * directBootAware: there is nothing useful it can do while locked, and
 * attempting it would only burn the 10s broadcast budget against locked
 * storage.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            -> ReminderReconciler.reconcileAfterBroadcast(context, goAsync()?.let { it::finish })
        }
    }
}
