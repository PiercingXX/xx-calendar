package com.piercingxx.calendar.alarm

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure clock math for the daily reconcile heartbeat (design §4.3 step 1:
 * "on a daily heartbeat"). The heartbeat exists so that the desired set is
 * recomputed at least once a day even if no broadcast and no provider change
 * ever fires — e.g. an event created entirely outside this app, on another
 * device, whose reminder enters the window silently.
 */
object HeartbeatClock {

    /** 03:00 local: deep in the night, cheap on battery (inexact alarm). */
    const val HOUR_OF_DAY: Int = 3

    /**
     * The next 03:00 local time strictly after [nowMillis] in [zone].
     * Strictly-after matters: scheduling an inexact repeating alarm "for
     * 03:00 today" when it is already past 03:00 would fire immediately and
     * double-reconcile.
     */
    fun nextAfter(nowMillis: Long, zone: ZoneId): Long {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        var candidate = now.withHour(HOUR_OF_DAY).withMinute(0).withSecond(0).withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }
}
