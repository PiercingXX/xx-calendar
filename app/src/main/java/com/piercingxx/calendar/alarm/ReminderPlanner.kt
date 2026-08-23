package com.piercingxx.calendar.alarm

/**
 * One concrete reminder alarm. Pure data, no android imports (design §4.3:
 * the planner is a pure JVM object so the whole desired-set computation is
 * unit-testable without Robolectric).
 *
 * [instanceStartMillis] is part of the identity because one event id can own
 * several occurrences inside the 48h window; [triggerAtMillis] distinguishes
 * the multiple reminders of one occurrence.
 */
data class AlarmKey(
    val eventId: Long,
    val instanceStartMillis: Long,
    val triggerAtMillis: Long,
) : Comparable<AlarmKey> {
    override fun compareTo(other: AlarmKey): Int = compareValuesBy(
        this, other,
        { it.eventId },
        { it.instanceStartMillis },
        { it.triggerAtMillis },
    )
}

/**
 * The planner's view of one expanded occurrence. Android-free on purpose:
 * `declined` is resolved by the caller from
 * `Instances.SELF_ATTENDEE_STATUS == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED`
 * so this file never touches the SDK.
 */
data class PlannerInstance(
    val eventId: Long,
    val startMillis: Long,
    val allDay: Boolean,
    val declined: Boolean,
    val reminderMinutes: List<Int>,
)

/** The diff to apply to [android.app.AlarmManager]. */
data class Plan(
    val toSet: Set<AlarmKey>,
    val toCancel: Set<AlarmKey>,
)

/**
 * Computes which alarms SHOULD exist and diffs them against what IS
 * scheduled (design §4.3 step 2-3). Recompute-the-whole-window, never chase
 * individual events.
 *
 * Exact semantics (quiet-biased by design):
 * - Declined instances never produce alarms.
 * - Instances with no reminders produce no alarms. This includes all-day
 *   events: there is no implicit "default" notification for anything the
 *   user has not put a reminder on (pending WS9 settings may add defaults).
 * - Timed instances: one alarm per distinct reminder row,
 *   `triggerAt = start - minutes * 60_000`.
 * - All-day instances: every reminder row maps to the SAME canonical lead of
 *   [ALL_DAY_LEAD_MILLIS] before start (`start - 18h`), regardless of the
 *   stored minutes value. Rationale: provider minutes are tuned for timed
 *   events ("30 min before 14:00"), which is meaningless for a local-midnight
 *   start; the brief pins the implementation at an 18h lead pending WS9
 *   settings. Duplicate rows collapse through Set equality.
 * - Past is skipped entirely, NO grace window: any trigger with
 *   `triggerAt <= nowMillis` is dropped. An event that started two minutes
 *   ago does not fire a late "you are now late" buzz (design §10 quiet bias);
 *   a missed trigger is simply gone and the next reconcile moves on.
 * - Output is deterministically sorted ascending by key, so identical inputs
 *   always yield an identically ordered set.
 */
object ReminderPlanner {

    /** Canonical pre-notification lead for all-day instances: 18h. See class doc. */
    const val ALL_DAY_LEAD_MILLIS: Long = 18L * 60L * 60L * 1000L

    private const val MINUTE_MILLIS: Long = 60_000L

    /**
     * The desired set over [instances] as of [nowMillis]. Only strictly
     * future triggers survive.
     */
    fun plan(instances: List<PlannerInstance>, nowMillis: Long): Set<AlarmKey> {
        val out = sortedSetOf<AlarmKey>()
        for (instance in instances) {
            if (instance.declined) continue
            if (instance.reminderMinutes.isEmpty()) continue
            if (instance.allDay) {
                val triggerAt = instance.startMillis - ALL_DAY_LEAD_MILLIS
                if (triggerAt > nowMillis) {
                    out.add(AlarmKey(instance.eventId, instance.startMillis, triggerAt))
                }
            } else {
                for (minutes in instance.reminderMinutes) {
                    val triggerAt = instance.startMillis - minutes.toLong() * MINUTE_MILLIS
                    if (triggerAt > nowMillis) {
                        out.add(AlarmKey(instance.eventId, instance.startMillis, triggerAt))
                    }
                }
            }
        }
        return out.toSet()
    }

    /**
     * Diff of desired against currently scheduled. Both directions only ever
     * touch keys that differ, so a stable world produces an empty plan
     * (no-op stability is what makes reconciliation idempotent).
     */
    fun computePlan(desired: Set<AlarmKey>, scheduled: Set<AlarmKey>): Plan = Plan(
        toSet = (desired - scheduled).toSortedSet(),
        toCancel = (scheduled - desired).toSortedSet(),
    )
}
