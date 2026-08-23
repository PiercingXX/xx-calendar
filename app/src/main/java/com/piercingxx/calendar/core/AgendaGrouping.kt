package com.piercingxx.calendar.core

import java.time.LocalDate
import java.time.ZoneId

/** One expanded occurrence as the schedule needs to see it. */
data class InstanceSpan(
    val eventId: Long,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
)

/** One day of the agenda: all-day events pinned ahead of timed events. */
data class DayBucket(
    val date: LocalDate,
    val allDay: List<InstanceSpan>,
    val timed: List<InstanceSpan>,
) {
    val isEmpty: Boolean get() = allDay.isEmpty() && timed.isEmpty()
}

/**
 * Groups instances into day buckets (design §8.2). Days holding nothing are
 * dropped entirely — the schedule view skips them, never renders empty rows.
 *
 * Pass [window] (the visible agenda range) to intersect each span's coverage
 * with it; callers whose window is not trivially available may omit it.
 * Independently of [window], every span's expansion is hard-capped at
 * [MAX_COVERED_DAYS]: a span covering more than a year contributes only its
 * first year — real calendars do not do this, and the cap keeps a corrupt or
 * pathological provider row from materializing unbounded buckets.
 */
object AgendaGrouping {

    fun group(
        instances: List<InstanceSpan>,
        zone: ZoneId,
        window: ClosedRange<LocalDate>? = null,
    ): List<DayBucket> {
        val byDate = LinkedHashMap<LocalDate, MutableList<InstanceSpan>>()

        for (span in instances) {
            for (date in coveredDates(span, zone, window)) {
                byDate.getOrPut(date) { mutableListOf() }.add(span)
            }
        }

        return byDate.entries
            .sortedBy { it.key }
            .map { (date, spans) ->
                val (allDay, timed) = spans.partition { it.allDay }
                DayBucket(
                    date = date,
                    allDay = allDay.sortedWith(compareBy({ it.startMillis }, { it.eventId })),
                    timed = timed.sortedWith(compareBy({ it.startMillis }, { it.eventId })),
                )
            }
    }

    /**
     * Dates from first to last covered, inclusive; end instant is exclusive.
     * When [window] is given, coverage is intersected with it first; the
     * [MAX_COVERED_DAYS] cap then applies either way — a span covering more
     * than a year contributes only its first year. Real calendars do not do
     * this; the cap exists so a corrupt row (dtend years past dtstart) can
     * neither hang expansion nor flood every bucket.
     */
    private fun coveredDates(
        span: InstanceSpan,
        zone: ZoneId,
        window: ClosedRange<LocalDate>?,
    ): List<LocalDate> {
        val zoneForDates = if (span.allDay) ZoneId.of("UTC") else zone
        val lastInstant = if (span.endMillis > span.startMillis) span.endMillis - 1 else span.startMillis
        val first = TimeMath.localDateOf(span.startMillis, zoneForDates)
        val last = TimeMath.localDateOf(lastInstant, zoneForDates)

        var cursor = first
        var stopAt = last
        if (window != null) {
            if (window.endInclusive.isBefore(first) || window.start.isAfter(last)) return emptyList()
            cursor = maxOf(cursor, window.start)
            stopAt = minOf(stopAt, window.endInclusive)
        }

        val dates = mutableListOf<LocalDate>()
        while (!cursor.isAfter(stopAt) && dates.size < MAX_COVERED_DAYS) {
            dates += cursor
            cursor = cursor.plusDays(1)
        }
        return dates
    }

    private const val MAX_COVERED_DAYS = 366
}
