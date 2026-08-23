package com.piercingxx.calendar.alarm

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desired-set math for §4.3 step 2 — the whole reliability story rests on
 * these semantics being exactly what the KDoc of [ReminderPlanner] says:
 * declined never notifies, past is skipped without grace, all-day gets the
 * canonical 18h lead, and the diff touches only what differs.
 */
class ReminderPlannerTest {

    private val now: Long = Instant.parse("2026-08-20T12:00:00Z").toEpochMilli()

    private val MINUTE = 60_000L
    private val HOUR = 3_600_000L

    private fun instance(
        eventId: Long = 1L,
        startIso: String = "2026-08-21T14:00:00Z",
        allDay: Boolean = false,
        declined: Boolean = false,
        minutes: List<Int> = listOf(30),
    ): PlannerInstance = PlannerInstance(
        eventId = eventId,
        startMillis = Instant.parse(startIso).toEpochMilli(),
        allDay = allDay,
        declined = declined,
        reminderMinutes = minutes,
    )

    // ---- standard minus-minutes triggers

    @Test
    fun `standard reminder triggers minutes before start`() {
        val inst = instance(minutes = listOf(30))
        val expected = Instant.parse("2026-08-21T13:30:00Z").toEpochMilli()

        val keys = ReminderPlanner.plan(listOf(inst), now)

        assertEquals(
            setOf(AlarmKey(inst.eventId, inst.startMillis, expected)),
            keys,
        )
    }

    @Test
    fun `zero-minute reminder fires exactly at start`() {
        val inst = instance(minutes = listOf(0))

        val keys = ReminderPlanner.plan(listOf(inst), now)

        assertEquals(
            setOf(AlarmKey(inst.eventId, inst.startMillis, inst.startMillis)),
            keys,
        )
    }

    // ---- multiple reminders per event -> multiple alarms

    @Test
    fun `multiple reminder rows yield multiple alarms`() {
        val inst = instance(minutes = listOf(30, 10, 60))

        val keys = ReminderPlanner.plan(listOf(inst), now)

        assertEquals(
            listOf(60L, 30L, 10L).map {
                AlarmKey(inst.eventId, inst.startMillis, inst.startMillis - it * MINUTE)
            },
            keys.sorted().toList(),
        )
    }

    @Test
    fun `duplicate reminder rows collapse to one alarm`() {
        val inst = instance(minutes = listOf(30, 30))

        val keys = ReminderPlanner.plan(listOf(inst), now)

        assertEquals(1, keys.size)
    }

    // ---- declined filtered

    @Test
    fun `declined events never notify`() {
        val declined = instance(declined = true, minutes = listOf(30))
        val accepted = instance(eventId = 2L, minutes = listOf(30))

        val keys = ReminderPlanner.plan(listOf(declined, accepted), now)

        assertTrue(keys.all { it.eventId == accepted.eventId })
        assertEquals(1, keys.size)
    }

    // ---- past-skip, no grace

    @Test
    fun `trigger already passed is skipped entirely - no grace`() {
        // Started 10 minutes ago, 30-minute reminder -> trigger was 20 min ago.
        val start = now + 10 * MINUTE
        val inst = PlannerInstance(1L, start, allDay = false, declined = false, listOf(30))

        assertEquals(emptySet<AlarmKey>(), ReminderPlanner.plan(listOf(inst), now))
    }

    @Test
    fun `just-passed trigger is skipped`() {
        val start = now + (30 * MINUTE - 1)
        val inst = PlannerInstance(1L, start, false, false, listOf(30)) // trigger == now - 1

        assertEquals(emptySet<AlarmKey>(), ReminderPlanner.plan(listOf(inst), now))
    }

    @Test
    fun `exactly-now trigger is skipped - only strictly future survive`() {
        val start = now + 30 * MINUTE
        val inst = PlannerInstance(1L, start, false, false, listOf(30)) // trigger == now

        assertEquals(emptySet<AlarmKey>(), ReminderPlanner.plan(listOf(inst), now))
    }

    @Test
    fun `just-future trigger survives`() {
        val start = now + 1 + 30 * MINUTE
        val inst = PlannerInstance(1L, start, false, false, listOf(30)) // trigger == now + 1

        assertEquals(
            setOf(AlarmKey(1L, start, now + 1)),
            ReminderPlanner.plan(listOf(inst), now),
        )
    }

    @Test
    fun `mixed past and future rows keep only the future ones`() {
        val start = now + HOUR
        val inst = PlannerInstance(1L, start, false, false, listOf(90, 30)) // 90min is past

        assertEquals(
            setOf(AlarmKey(1L, start, start - 30 * MINUTE)),
            ReminderPlanner.plan(listOf(inst), now),
        )
    }

    // ---- all-day: canonical 18h-before-start lead

    @Test
    fun `all-day event triggers 18 hours before its start`() {
        val start = Instant.parse("2026-08-22T00:00:00Z").toEpochMilli()
        val inst = PlannerInstance(7L, start, allDay = true, declined = false, listOf(30))

        val keys = ReminderPlanner.plan(listOf(inst), now)

        assertEquals(
            setOf(AlarmKey(7L, start, start - ReminderPlanner.ALL_DAY_LEAD_MILLIS)),
            keys,
        )
        assertEquals(start - 18 * HOUR, keys.single().triggerAtMillis)
    }

    @Test
    fun `all-day stored minutes are ignored - rows collapse to one canonical alarm`() {
        val start = Instant.parse("2026-08-22T00:00:00Z").toEpochMilli()
        val inst =
            PlannerInstance(7L, start, allDay = true, declined = false, listOf(1440, 30, 0))

        val keys = ReminderPlanner.plan(listOf(inst), now)

        assertEquals(1, keys.size)
        assertEquals(start - ReminderPlanner.ALL_DAY_LEAD_MILLIS, keys.single().triggerAtMillis)
    }

    @Test
    fun `all-day whose canonical trigger already passed is skipped too`() {
        val start = now + 6 * HOUR // 18h lead lands 12h in the past
        val inst = PlannerInstance(7L, start, true, false, listOf(30))

        assertEquals(emptySet<AlarmKey>(), ReminderPlanner.plan(listOf(inst), now))
    }

    // ---- empty reminders

    @Test
    fun `no reminder rows means no alarm - including all-day`() {
        assertEquals(
            emptySet<AlarmKey>(),
            ReminderPlanner.plan(
                listOf(
                    instance(minutes = emptyList()),
                    PlannerInstance(9L, now + HOUR, true, false, emptyList()),
                ),
                now,
            ),
        )
    }

    // ---- computePlan diff correctness

    @Test
    fun `plan sets the missing cancels the stale and is stable when equal`() {
        val a = AlarmKey(1L, 100L, 50L)
        val b = AlarmKey(2L, 200L, 150L)
        val c = AlarmKey(3L, 300L, 250L)

        // desired adds b, drops a, keeps c.
        val plan = ReminderPlanner.computePlan(desired = setOf(b, c), scheduled = setOf(a, c))

        assertEquals(setOf(b), plan.toSet)
        assertEquals(setOf(a), plan.toCancel)

        val settled = ReminderPlanner.computePlan(desired = setOf(b, c), scheduled = setOf(b, c))
        assertTrue(settled.toSet.isEmpty())
        assertTrue(settled.toCancel.isEmpty())
    }

    // ---- determinism

    @Test
    fun `identical inputs give identically ordered output`() {
        val instances = listOf(
            instance(eventId = 5L, minutes = listOf(15)),
            instance(eventId = 2L, minutes = listOf(45, 10)),
            instance(eventId = 9L, minutes = listOf(120), allDay = true),
        )

        val first = ReminderPlanner.plan(instances, now).toList()
        val second = ReminderPlanner.plan(instances.asReversed(), now).toList()

        assertEquals(first, second)
        assertEquals(first.sorted(), first) // ascending key order, input-order independent
    }
}
