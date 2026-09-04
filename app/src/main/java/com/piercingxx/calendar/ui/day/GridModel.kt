package com.piercingxx.calendar.ui.day

import com.piercingxx.calendar.calendar.CalendarInstance
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.core.AgendaGrouping
import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.InstanceSpan
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.core.TimeMath
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Minute geometry of the grid (design §8.3). Pure JVM, unit-testable. */
internal const val DAY_MINUTES = 24 * 60
internal const val SNAP_MINUTES = 15
internal const val DEFAULT_SLOT_MINUTES = 30

/** Below this block height the muted time line is dropped (§8.3: ">~30min"). */
internal const val TIME_TEXT_MIN_MINUTES = 30

/**
 * One day column handed to [TimeGrid]: the date plus its expanded occurrences
 * split the way §8.3 renders them — all-day pinned above, timed laid out in
 * the scrolling grid.
 */
data class GridColumn(
    val date: LocalDate,
    val allDay: List<CalendarInstance> = emptyList(),
    val timed: List<CalendarInstance> = emptyList(),
)

internal data class LayoutSpan(val index: Int, val startMinute: Float, val endMinute: Float)

internal data class LayoutSlot(val index: Int, val lane: Int, val laneCount: Int)

/**
 * Simple column-packing (design §8.3): overlapping occurrences share the day
 * column side by side. Spans are clustered by transitive overlap; inside a
 * cluster each span takes the first free lane; every block in a cluster gets
 * 1/laneCount of the column width.
 */
object GridEventLayout {

    internal fun pack(spans: List<LayoutSpan>): List<LayoutSlot> {
        if (spans.isEmpty()) return emptyList()

        val slots = arrayOfNulls<LayoutSlot>(spans.size)
        val order = spans.indices.sortedWith(
            compareBy({ spans[it].startMinute }, { spans[it].endMinute }, { it }),
        )

        val cluster = ArrayList<Int>()
        val clusterLanes = ArrayList<Int>()
        val laneEnds = ArrayList<Float>()
        var clusterLaneCount = 0
        var clusterEnd = Float.NEGATIVE_INFINITY

        fun flush() {
            val count = clusterLaneCount.coerceAtLeast(1)
            for (i in cluster.indices) {
                slots[cluster[i]] = LayoutSlot(cluster[i], clusterLanes[i], count)
            }
            cluster.clear()
            clusterLanes.clear()
            laneEnds.clear()
            clusterLaneCount = 0
            clusterEnd = Float.NEGATIVE_INFINITY
        }

        for (i in order) {
            val span = spans[i]
            if (cluster.isNotEmpty() && span.startMinute >= clusterEnd) flush()

            var lane = laneEnds.indexOfFirst { it <= span.startMinute }
            if (lane < 0) {
                laneEnds.add(span.endMinute)
                lane = laneEnds.lastIndex
            } else {
                laneEnds[lane] = span.endMinute
            }

            cluster.add(i)
            clusterLanes.add(lane)
            if (laneEnds.size > clusterLaneCount) clusterLaneCount = laneEnds.size
            if (span.endMinute > clusterEnd) clusterEnd = span.endMinute
        }
        flush()

        return List(spans.size) { requireNotNull(slots[it]) }
    }
}

/** Snap a raw minute position to the 15-minute grid (§8.3). */
internal fun snapMinute(rawMinute: Float): Int =
    (rawMinute / SNAP_MINUTES).roundToInt() * SNAP_MINUTES

/**
 * Tap on empty grid → a default-length slot starting at the snapped minute.
 * Clamped so the whole slot stays inside the day (a tap at 23:50 is 23:30–24:00).
 */
internal fun slotFromTapMinute(rawMinute: Float): Pair<Int, Int> {
    val start = snapMinute(rawMinute).coerceIn(0, DAY_MINUTES - DEFAULT_SLOT_MINUTES)
    return start to start + DEFAULT_SLOT_MINUTES
}

/**
 * Date-only create (month cell / empty peek): 09:00 local, default length.
 * 09:00 always fits a 30-minute slot; the clamp is for a longer default.
 */
internal fun timedSlotOnDate(
    date: LocalDate,
    zone: ZoneId,
    durationMinutes: Int = DEFAULT_SLOT_MINUTES,
): Pair<Long, Long> {
    val dayStart = TimeMath.localDayStart(date, zone)
    val startMinute = (9 * 60).coerceAtMost(DAY_MINUTES - durationMinutes)
    val start = dayStart + startMinute * 60_000L
    return start to start + durationMinutes * 60_000L
}

/**
 * Clamp a freshly created slot: inside the day, at least one snap long, and
 * anchored to its start. Input is expected ordered (create-drag guarantees it).
 */
internal fun coerceSlot(start: Int, end: Int): Pair<Int, Int> {
    val s = start.coerceIn(0, DAY_MINUTES - SNAP_MINUTES)
    val e = end.coerceIn(s + SNAP_MINUTES, DAY_MINUTES)
    return s to e
}

/** Top-edge resize: the moving start clamps against the fixed end. */
internal fun coerceResizeTop(newStart: Int, fixedEnd: Int): Pair<Int, Int> =
    newStart.coerceIn(0, fixedEnd - SNAP_MINUTES) to fixedEnd

/** Bottom-edge resize: the moving end clamps against the fixed start. */
internal fun coerceResizeBottom(fixedStart: Int, newEnd: Int): Pair<Int, Int> =
    fixedStart to newEnd.coerceIn(fixedStart + SNAP_MINUTES, DAY_MINUTES)

/** Where a freshly opened grid should land: ~90 minutes before now, else 07:00. */
internal fun initialScrollMinutes(visible: List<LocalDate>, nowMillis: Long, zone: ZoneId): Int {
    val today = TimeMath.localDateOf(nowMillis, zone)
    return if (visible.contains(today)) {
        val nowMinute = ((nowMillis - TimeMath.localDayStart(today, zone)) / 60_000f).toInt()
        (nowMinute - 90).coerceAtLeast(0)
    } else {
        7 * 60
    }
}

/** The calendar's sigil tier, resolved through the drawer's account-name key. */
internal fun tierOf(
    calendarId: Long,
    sigils: Map<CalendarKey, SigilTier>,
    calendarsById: Map<Long, CalendarSummary>,
): SigilTier? = sigils[CalendarKey(calendarId, calendarsById[calendarId]?.accountName ?: "")]

/**
 * Distribute one window of expanded occurrences into per-day columns: every
 * date in [dates] gets a column (empty days render as empty grid), multi-day
 * spans land in each covered day and are clipped there at render time.
 */
internal fun buildGridColumns(
    dates: List<LocalDate>,
    zone: ZoneId,
    instances: List<CalendarInstance>,
): List<GridColumn> {
    val grouped = AgendaGrouping.group(
        instances.map { InstanceSpan(it.eventId, it.startMillis, it.endMillis, it.allDay) },
        zone,
    )
    val byEventId = instances.associateBy { it.eventId }
    val allDay = dates.associateWith { mutableListOf<CalendarInstance>() }
    val timed = dates.associateWith { mutableListOf<CalendarInstance>() }

    for (bucket in grouped) {
        val bucketAllDay = allDay[bucket.date] ?: continue
        val bucketTimed = timed.getValue(bucket.date)
        bucket.allDay.forEach { span -> byEventId[span.eventId]?.let(bucketAllDay::add) }
        bucket.timed.forEach { span -> byEventId[span.eventId]?.let(bucketTimed::add) }
    }

    return dates.map { date -> GridColumn(date, allDay.getValue(date), timed.getValue(date)) }
}
