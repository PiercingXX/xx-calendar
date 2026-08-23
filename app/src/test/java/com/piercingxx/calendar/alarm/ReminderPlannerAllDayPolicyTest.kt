package com.piercingxx.calendar.alarm

import com.piercingxx.calendar.settings.AllDayNotification
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.6's all-day notification policy as ReminderPlanner consumes it: the
 * anchor math (hourOfDay:00 local, daysBefore days ahead), the full plan()
 * overload, and the process-wide mirror the app writes at startup.
 */
class ReminderPlannerAllDayPolicyTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val now: Long = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()

    @After
    fun resetMirror() {
        // The mirror is process-wide; other suites must see the legacy default.
        ReminderPlanner.allDayPolicy = null
    }

    private fun allDayInstance(startIso: String): PlannerInstance = PlannerInstance(
        eventId = 7L,
        startMillis = Instant.parse(startIso).toEpochMilli(),
        allDay = true,
        declined = false,
        reminderMinutes = listOf(30),
    )

    // ------------------------------------------------------- anchor math

    @Test
    fun `fires at hourOfDay local one day before the event date`() {
        // Event day 2026-08-22 (stored at UTC midnight); 18:00 Berlin the day
        // before = 16:00 UTC (CEST, +2).
        val trigger = ReminderPlanner.allDayTriggerAtMillis(
            Instant.parse("2026-08-22T00:00:00Z").toEpochMilli(),
            AllDayNotification(hourOfDay = 18, daysBefore = 1),
            zone,
        )
        assertEquals(Instant.parse("2026-08-21T16:00:00Z").toEpochMilli(), trigger)
    }

    @Test
    fun `daysBefore reaches further back and hour follows the zone`() {
        val trigger = ReminderPlanner.allDayTriggerAtMillis(
            Instant.parse("2026-08-22T00:00:00Z").toEpochMilli(),
            AllDayNotification(hourOfDay = 8, daysBefore = 2),
            ZoneId.of("America/New_York"),
        )
        // 2026-08-20 08:00 New York (EDT, -4) = 12:00 UTC.
        assertEquals(Instant.parse("2026-08-20T12:00:00Z").toEpochMilli(), trigger)
    }

    @Test
    fun `out-of-range policy fields clamp instead of crashing`() {
        val start = Instant.parse("2026-08-22T00:00:00Z").toEpochMilli()
        val clampedHour = ReminderPlanner.allDayTriggerAtMillis(start, AllDayNotification(hourOfDay = 30, daysBefore = 1), zone)
        assertEquals(Instant.parse("2026-08-21T21:00:00Z").toEpochMilli(), clampedHour) // 23:00 CEST
        val clampedDays = ReminderPlanner.allDayTriggerAtMillis(start, AllDayNotification(hourOfDay = 9, daysBefore = -3), zone)
        assertEquals(Instant.parse("2026-08-22T07:00:00Z").toEpochMilli(), clampedDays) // same day 09:00 CEST
    }

    // ------------------------------------------- plan() with an explicit policy

    @Test
    fun `plan uses the policy anchor for every reminder row of an all-day instance`() {
        val start = Instant.parse("2026-08-22T00:00:00Z").toEpochMilli()
        val inst = PlannerInstance(7L, start, allDay = true, declined = false, listOf(1440, 30, 0))
        val policy = AllDayNotification(hourOfDay = 9, daysBefore = 1)

        val keys = ReminderPlanner.plan(listOf(inst), now, policy, zone)

        assertEquals(1, keys.size)
        assertEquals(
            ReminderPlanner.allDayTriggerAtMillis(start, policy, zone),
            keys.single().triggerAtMillis,
        )
    }

    @Test
    fun `null policy restores the historical fixed 18h lead`() {
        val start = Instant.parse("2026-08-22T00:00:00Z").toEpochMilli()
        val inst = PlannerInstance(7L, start, allDay = true, declined = false, listOf(30))

        val keys = ReminderPlanner.plan(listOf(inst), now, policy = null, zone)

        assertEquals(
            setOf(AlarmKey(7L, start, start - ReminderPlanner.ALL_DAY_LEAD_MILLIS)),
            keys,
        )
    }

    @Test
    fun `a past anchor is skipped like any past trigger`() {
        // Event day Aug 1; its 18:00 CEST eve-of anchor (Jul 31 16:00 UTC) is
        // already behind `now` (Aug 1 00:00 UTC) — no late buzz.
        val start = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
        val inst = PlannerInstance(7L, start, allDay = true, declined = false, listOf(30))

        assertTrue(
            ReminderPlanner.plan(listOf(inst), now, AllDayNotification(hourOfDay = 18, daysBefore = 1), zone)
                .isEmpty(),
        )
    }

    // ------------------------------------------------- process-wide mirror

    @Test
    fun `the two-arg plan reads the mirrored policy once MainActivity sets it`() {
        val start = Instant.parse("2026-08-22T00:00:00Z").toEpochMilli()
        val inst = PlannerInstance(7L, start, allDay = true, declined = false, listOf(30))
        val policy = AllDayNotification(hourOfDay = 12, daysBefore = 0)

        ReminderPlanner.allDayPolicy = policy
        try {
            val keys = ReminderPlanner.plan(listOf(inst), now)
            assertEquals(
                setOf(
                    AlarmKey(
                        7L,
                        start,
                        ReminderPlanner.allDayTriggerAtMillis(start, policy, ZoneId.systemDefault()),
                    ),
                ),
                keys,
            )
        } finally {
            ReminderPlanner.allDayPolicy = null
        }
    }
}
