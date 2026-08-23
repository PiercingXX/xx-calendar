package com.piercingxx.calendar.alarm

import android.content.Context
import android.content.SharedPreferences

/**
 * The "what is currently scheduled" half of the reconcile diff, persisted to
 * a private SharedPreferences file as newline-encoded [AlarmKey]s.
 *
 * Honest limitations (both acceptable by §4.3's convergence argument):
 * - In-memory only within a process; the file is the cross-reboot memory.
 *   AlarmManager itself forgets everything at reboot, so a lost registry
 *   after reboot costs nothing — the reconciler reschedules from scratch.
 * - If the file is unreadable (e.g. LOCKED_BOOT_COMPLETED before user
 *   unlock, when credential-encrypted storage is not available) this reports
 *   an EMPTY set. That makes the next reconcile schedule everything desired,
 *   which is exactly right: alarms never survive reboot anyway.
 */
class ScheduledAlarmRegistry(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Set<AlarmKey> = try {
        val blob = prefs.getString(KEY_KEYS, null)
        if (blob == null) {
            emptySet()
        } else {
            AlarmKeys.decodeAll(blob)
        }
    } catch (_: Exception) {
        // Locked device / corrupted blob: treat as "nothing known scheduled".
        emptySet()
    }

    fun persist(keys: Set<AlarmKey>) {
        prefs.edit().putString(KEY_KEYS, AlarmKeys.encodeAll(keys)).apply()
    }

    private companion object {
        const val PREFS_NAME = "alarm_registry"
        const val KEY_KEYS = "scheduled_keys"
    }
}
