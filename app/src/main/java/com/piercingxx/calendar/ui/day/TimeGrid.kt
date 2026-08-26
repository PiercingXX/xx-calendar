package com.piercingxx.calendar.ui.day

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piercingxx.calendar.calendar.CalendarInstance
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.core.TimeMath
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.CalendarColors
import com.piercingxx.calendar.ui.theme.EventTitle
import com.piercingxx.calendar.ui.theme.Label
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import com.piercingxx.calendar.ui.theme.MonthHeader
import com.piercingxx.calendar.ui.theme.SpaceMono
import com.piercingxx.calendar.ui.theme.Time
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** §8.3 geometry: 56dp per hour, a 52dp label gutter, everything else derived. */
internal val HOUR_HEIGHT = 56.dp
internal val GUTTER_WIDTH = 52.dp

private val BLOCK_BAR_WIDTH = 2.dp
private val BLOCK_MIN_HEIGHT = 14.dp
private val EDGE_TOUCH_HEIGHT = 12.dp
private val NOW_DOT_SIZE = 7.dp

/**
 * Live gesture preview (§8.3): create-drag on empty grid, or move/resize of
 * one event. Minutes are day-relative; [Create] is bound to its column so
 * week view never ghosts the slot into a neighbouring day.
 */
internal sealed interface GesturePreview {
    val date: LocalDate

    data class Create(
        override val date: LocalDate,
        val startMinute: Int,
        val endMinute: Int,
    ) : GesturePreview

    data class Transform(
        override val date: LocalDate,
        val eventId: Long,
        val startMinute: Int,
        val endMinute: Int,
    ) : GesturePreview
}

private data class PlacedBlock(
    val instance: CalendarInstance,
    val date: LocalDate,
    val startMinute: Float,
    val endMinute: Float,
    val lane: Int,
    val laneCount: Int,
    val ghost: Boolean,
)

/** Clip occurrences to their visible day, substitute previews, then pack lanes. */
private fun placeColumn(
    timed: List<CalendarInstance>,
    date: LocalDate,
    dayStartMillis: Long,
    dayEndMillis: Long,
    preview: GesturePreview?,
): List<PlacedBlock> {
    val spans = ArrayList<LayoutSpan>(timed.size)
    val byIndex = ArrayList<CalendarInstance>(timed.size)

    for ((i, instance) in timed.withIndex()) {
        val transform = preview as? GesturePreview.Transform
        val startF: Float
        val endF: Float
        if (transform != null && transform.eventId == instance.eventId) {
            startF = transform.startMinute.toFloat()
            endF = transform.endMinute.toFloat()
        } else {
            startF = (maxOf(instance.startMillis, dayStartMillis) - dayStartMillis) / 60_000f
            endF = (minOf(instance.endMillis, dayEndMillis) - dayStartMillis) / 60_000f
        }
        if (endF <= startF) continue
        spans.add(
            LayoutSpan(
                i,
                startF.coerceIn(0f, DAY_MINUTES.toFloat()),
                endF.coerceIn(0f, DAY_MINUTES.toFloat()),
            ),
        )
        byIndex.add(instance)
    }

    return GridEventLayout.pack(spans).map { slot ->
        val span = spans[slot.index]
        PlacedBlock(
            instance = byIndex[slot.index],
            date = date,
            startMinute = span.startMinute,
            endMinute = span.endMinute,
            lane = slot.lane,
            laneCount = slot.laneCount,
            ghost = (preview as? GesturePreview.Transform)?.eventId == byIndex[slot.index].eventId,
        )
    }
}

/**
 * The Day/Week time grid (design §8.3): 24 hour rules at `line`, Space Mono
 * labels in the gutter, vertical scroll, and — when today is on screen — the
 * signal-white now rule with its filled dot, the only full-white element.
 * Gestures: long-press + drag on empty grid creates; long-press + drag moves
 * an event; dragging a top/bottom edge resizes. Everything snaps to 15
 * minutes; finished gestures report absolute epoch millis to the screen,
 * which owns navigation and persistence.
 */
@Composable
fun TimeGrid(
    columns: List<GridColumn>,
    sigils: Map<CalendarKey, SigilTier>,
    calendarsById: Map<Long, CalendarSummary>,
    nowMillis: Long,
    zone: ZoneId,
    onCreateSlot: (LocalDate, Long, Long) -> Unit,
    onEventMoved: (Long, Long, Long) -> Unit,
    onEventResized: (Long, Long, Long) -> Unit,
    onEventClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val density = LocalDensity.current
    val pxPerMinute = with(density) { HOUR_HEIGHT.toPx() } / 60f
    val totalHeightPx = pxPerMinute * DAY_MINUTES
    var preview by remember { mutableStateOf<GesturePreview?>(null) }

    val today = TimeMath.localDateOf(nowMillis, zone)
    val todayColumn = columns.firstOrNull { it.date == today }

    Box(modifier.fillMaxSize().clipToBounds()) {
        // Order is load-bearing: verticalScroll first, then the height. Written
        // the other way round the height sizes the scroll viewport itself to a
        // full 24 hours, the content fits it exactly, and there is nothing left
        // to scroll — the grid just clips at the bottom of the screen. Scroll
        // first and the viewport takes the parent's constraints while the
        // content stays 24 hours tall, which is what makes it scrollable.
        Row(Modifier.verticalScroll(scrollState).height(HOUR_HEIGHT * 24)) {
            HourGutter(pxPerMinute = pxPerMinute)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxSize()) {
                    columns.forEach { column ->
                        DayColumn(
                            modifier = Modifier.weight(1f),
                            column = column,
                            dayStartMillis = TimeMath.localDayStart(column.date, zone),
                            dayEndMillis = TimeMath.localDayStart(column.date.plusDays(1), zone),
                            pxPerMinute = pxPerMinute,
                            sigils = sigils,
                            calendarsById = calendarsById,
                            preview = preview?.takeIf { it.date == column.date || it is GesturePreview.Transform },
                            onPreview = { preview = it },
                            onCreateSlot = { startMinute, endMinute ->
                                val dayStart = TimeMath.localDayStart(column.date, zone)
                                onCreateSlot(
                                    column.date,
                                    dayStart + startMinute * 60_000L,
                                    dayStart + endMinute * 60_000L,
                                )
                            },
                            onTransformFinished = { instance, startMinute, endMinute ->
                                val dayStart = TimeMath.localDayStart(column.date, zone)
                                val newStart = dayStart + startMinute * 60_000L
                                val newEnd = dayStart + endMinute * 60_000L
                                val originalStartMinute =
                                    ((maxOf(instance.startMillis, dayStart) - dayStart) / 60_000f).toInt()
                                if (instance.duration != null || instance.rrule != null ||
                                    originalStartMinute != startMinute
                                ) {
                                    // Move (recurring events shift the series; extent lives in DURATION).
                                    onEventMoved(instance.eventId, newStart, newEnd)
                                } else {
                                    onEventResized(instance.eventId, newStart, newEnd)
                                }
                            },
                            onEventClick = onEventClick,
                        )
                    }
                }
                if (todayColumn != null) {
                    val todayStart = TimeMath.localDayStart(today, zone)
                    val todayLength = (TimeMath.localDayStart(today.plusDays(1), zone) - todayStart).toFloat()
                    NowRuleOverlay(
                        fraction = ((nowMillis - todayStart) / todayLength).coerceIn(0f, 1f),
                        totalHeightPx = totalHeightPx,
                    )
                }
            }
        }
    }
}

@Composable
private fun HourGutter(pxPerMinute: Float) {
    val colors = LocalCalendarColors.current
    val density = LocalDensity.current
    val labelPx = with(density) { Time.lineHeight.toPx() }
    Box(Modifier.width(GUTTER_WIDTH).fillMaxHeight()) {
        for (hour in 0 until 24) {
            Text(
                "%02d:00".format(hour),
                style = Time,
                color = colors.muted,
                modifier = Modifier
                    .offset { IntOffset(0, (hour * 60 * pxPerMinute - labelPx / 2).roundToInt()) }
                    .padding(start = 6.dp),
            )
        }
    }
}

/** One day's rules canvas, event blocks, ghost slot, and create-gesture surface. */
@Composable
private fun DayColumn(
    modifier: Modifier = Modifier,
    column: GridColumn,
    dayStartMillis: Long,
    dayEndMillis: Long,
    pxPerMinute: Float,
    sigils: Map<CalendarKey, SigilTier>,
    calendarsById: Map<Long, CalendarSummary>,
    preview: GesturePreview?,
    onPreview: (GesturePreview?) -> Unit,
    onCreateSlot: (Int, Int) -> Unit,
    onTransformFinished: (CalendarInstance, Int, Int) -> Unit,
    onEventClick: (Long) -> Unit,
) {
    val colors = LocalCalendarColors.current
    val density = LocalDensity.current
    val minBlockPx = with(density) { BLOCK_MIN_HEIGHT.toPx() }
    val blocks = placeColumn(column.timed, column.date, dayStartMillis, dayEndMillis, preview)

    BoxWithConstraints(modifier.fillMaxHeight()) {
        // Rules canvas doubles as the empty-grid gesture surface: children
        // (blocks) sit above it and win the pointer where they exist.
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(pxPerMinute, column.date) {
                    var anchor = 0
                    var start = 0
                    var end = DEFAULT_SLOT_MINUTES
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            anchor = snapMinute(position.y / pxPerMinute).coerceIn(0, DAY_MINUTES - SNAP_MINUTES)
                            start = anchor
                            end = anchor + DEFAULT_SLOT_MINUTES
                            onPreview(GesturePreview.Create(column.date, start, end))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val finger = snapMinute(change.position.y / pxPerMinute).coerceIn(0, DAY_MINUTES)
                            val coerced = coerceSlot(minOf(anchor, finger), maxOf(anchor + SNAP_MINUTES, finger))
                            start = coerced.first
                            end = coerced.second
                            onPreview(GesturePreview.Create(column.date, start, end))
                        },
                        onDragEnd = {
                            onPreview(null)
                            onCreateSlot(start, end)
                        },
                        onDragCancel = { onPreview(null) },
                    )
                },
        ) {
            for (hour in 0..24) {
                val y = hour * 60 * pxPerMinute
                drawLine(colors.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            drawLine(colors.line, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx())
        }

        blocks.forEach { block ->
            val laneWidth = maxWidth / block.laneCount
            EventCell(
                block = block,
                width = laneWidth - 2.dp,
                heightDp = with(density) {
                    ((block.endMinute - block.startMinute) * pxPerMinute).coerceAtLeast(minBlockPx).toDp()
                },
                offsetXPx = with(density) { (laneWidth * block.lane).toPx() },
                offsetYPx = block.startMinute * pxPerMinute,
                pxPerMinute = pxPerMinute,
                tier = tierOf(block.instance.calendarId, sigils, calendarsById),
                past = block.instance.endMillis < System.currentTimeMillis(),
                onPreview = onPreview,
                onTransformFinished = onTransformFinished,
                onClick = { onEventClick(block.instance.eventId) },
            )
        }

        (preview as? GesturePreview.Create)?.let { ghost ->
            GhostCell(
                startMinute = ghost.startMinute,
                endMinute = ghost.endMinute,
                width = maxWidth - 2.dp,
                offsetYPx = ghost.startMinute * pxPerMinute,
                heightDp = with(density) {
                    ((ghost.endMinute - ghost.startMinute) * pxPerMinute).coerceAtLeast(minBlockPx).toDp()
                },
            )
        }
    }
}

@Composable
private fun EventCell(
    block: PlacedBlock,
    width: Dp,
    heightDp: Dp,
    offsetXPx: Float,
    offsetYPx: Float,
    pxPerMinute: Float,
    tier: SigilTier?,
    past: Boolean,
    onPreview: (GesturePreview?) -> Unit,
    onTransformFinished: (CalendarInstance, Int, Int) -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    val barColor = if (block.ghost) colors.strong else tier?.rampColor(colors) ?: colors.shade
    val fillColor = if (block.ghost) colors.slate else colors.inkRaised
    val titleColor = when {
        block.ghost -> colors.muted
        past -> colors.shade
        else -> colors.text
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetXPx.roundToInt(), offsetYPx.roundToInt()) }
            .width(width)
            .height(heightDp)
            .background(fillColor)
            .then(if (block.ghost) Modifier.border(1.dp, colors.strong) else Modifier)
            .clipToBounds()
            .clickable(enabled = !block.ghost, onClick = onClick)
            .then(
                Modifier.pointerInput(block.instance.eventId, pxPerMinute) {
                    // Move gesture: long-press the body, drag; snaps to 15 minutes.
                    var originalStart = 0
                    var length = 0
                    var accY = 0f
                    var last = 0 to 0
                    var moved = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = { _ ->
                            accY = 0f
                            moved = false
                            originalStart = block.startMinute.toInt()
                            length = (block.endMinute - block.startMinute).toInt().coerceAtLeast(SNAP_MINUTES)
                            last = originalStart to (originalStart + length)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            accY += amount.y
                            val delta = snapMinute(accY / pxPerMinute)
                            val start = (originalStart + delta).coerceIn(0, DAY_MINUTES - length)
                            last = start to (start + length)
                            moved = delta != 0
                            onPreview(
                                GesturePreview.Transform(block.date, block.instance.eventId, start, start + length),
                            )
                        },
                        onDragEnd = {
                            onPreview(null)
                            if (moved) onTransformFinished(block.instance, last.first, last.second)
                        },
                        onDragCancel = { onPreview(null) },
                    )
                },
            ),
    ) {
        Box(Modifier.fillMaxHeight().width(BLOCK_BAR_WIDTH).background(barColor))
        Column(Modifier.padding(start = 8.dp, top = 2.dp, end = 2.dp)) {
            Text(
                titleOf(block.instance),
                style = EventTitle,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if ((block.endMinute - block.startMinute).toInt() >= TIME_TEXT_MIN_MINUTES) {
                Text(
                    timeRangeText(block.startMinute.toInt(), block.endMinute.toInt()),
                    style = Time,
                    color = if (past) colors.shade else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ResizeEdge(
            modifier = Modifier.align(Alignment.TopCenter),
            top = true,
            block = block,
            pxPerMinute = pxPerMinute,
            onPreview = onPreview,
            onFinished = onTransformFinished,
        )
        ResizeEdge(
            modifier = Modifier.align(Alignment.BottomCenter),
            top = false,
            block = block,
            pxPerMinute = pxPerMinute,
            onPreview = onPreview,
            onFinished = onTransformFinished,
        )
    }
}

@Composable
private fun ResizeEdge(
    modifier: Modifier,
    top: Boolean,
    block: PlacedBlock,
    pxPerMinute: Float,
    onPreview: (GesturePreview?) -> Unit,
    onFinished: (CalendarInstance, Int, Int) -> Unit,
) {
    val resizable = !block.ghost && block.instance.duration == null && block.instance.rrule == null
    val currentBlock by rememberUpdatedState(block)
    val currentOnFinished by rememberUpdatedState(onFinished)

    val gesture = if (!resizable) {
        Modifier
    } else {
        Modifier.pointerInput(block.instance.eventId, pxPerMinute, top) {
            // Resize: immediate drag of the edge, no long-press needed.
            var accY = 0f
            var base = 0
            var fixed = 0
            var result = 0 to 0
            detectDragGestures(
                onDragStart = {
                    accY = 0f
                    val b = currentBlock
                    if (top) {
                        base = b.startMinute.toInt()
                        fixed = b.endMinute.toInt()
                    } else {
                        fixed = b.startMinute.toInt()
                        base = b.endMinute.toInt()
                    }
                },
                onDrag = { change, amount ->
                    change.consume()
                    accY += amount.y
                    val delta = snapMinute(accY / pxPerMinute)
                    result = if (top) coerceResizeTop(base + delta, fixed) else coerceResizeBottom(fixed, base + delta)
                    onPreview(
                        GesturePreview.Transform(
                            currentBlock.date,
                            currentBlock.instance.eventId,
                            result.first,
                            result.second,
                        ),
                    )
                },
                onDragEnd = {
                    onPreview(null)
                    currentOnFinished(currentBlock.instance, result.first, result.second)
                },
                onDragCancel = { onPreview(null) },
            )
        }
    }

    // [modifier] carries the BoxScope alignment chosen by the caller.
    Box(
        modifier
            .fillMaxWidth()
            .height(EDGE_TOUCH_HEIGHT)
            .then(gesture),
    )
}

@Composable
private fun GhostCell(
    startMinute: Int,
    endMinute: Int,
    width: Dp,
    offsetYPx: Float,
    heightDp: Dp,
) {
    val colors = LocalCalendarColors.current
    Box(
        Modifier
            .offset { IntOffset(0, offsetYPx.roundToInt()) }
            .width(width)
            .height(heightDp)
            .background(colors.slate)
            .border(1.dp, colors.strong)
            .padding(start = 8.dp, top = 2.dp),
    ) {
        Text(
            timeRangeText(startMinute, endMinute),
            style = Time,
            color = colors.muted,
            maxLines = 1,
        )
    }
}

/** The signal-white rule plus filled dot — the only full-white element (§7.1). */
@Composable
private fun NowRuleOverlay(fraction: Float, totalHeightPx: Float) {
    val colors = LocalCalendarColors.current
    val density = LocalDensity.current
    val dotPx = with(density) { NOW_DOT_SIZE.toPx() }
    val y = fraction * totalHeightPx
    Box(
        Modifier
            .offset { IntOffset(0, y.roundToInt()) }
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.signal),
    )
    Box(
        Modifier
            .offset { IntOffset(1.dp.roundToPx(), (y - dotPx / 2).roundToInt()) }
            .size(NOW_DOT_SIZE)
            .background(colors.signal, CircleShape),
    )
}

/** Pinned all-day header row above the grid (§8.3), aligned to the columns. */
@Composable
fun AllDayRow(
    columns: List<GridColumn>,
    sigils: Map<CalendarKey, SigilTier>,
    calendarsById: Map<Long, CalendarSummary>,
    onEventClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCalendarColors.current
    Row(modifier.fillMaxWidth()) {
        Spacer(Modifier.width(GUTTER_WIDTH))
        columns.forEach { column ->
            Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                column.allDay.forEach { instance ->
                    val tier = tierOf(instance.calendarId, sigils, calendarsById)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 22.dp)
                            .background(colors.inkRaised)
                            .clickable { onEventClick(instance.eventId) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(BLOCK_BAR_WIDTH).height(16.dp).background(tier?.rampColor(colors) ?: colors.shade))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            titleOf(instance),
                            style = Body.copy(fontSize = 13.sp, lineHeight = 17.sp),
                            color = colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp, horizontal = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** Weekday label + day numeral strip; today's numeral inverted (§8.3). */
@Composable
fun DaysHeaderRow(
    columns: List<GridColumn>,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCalendarColors.current
    Row(modifier.fillMaxWidth()) {
        Spacer(Modifier.width(GUTTER_WIDTH))
        columns.forEach { column ->
            Row(
                Modifier.weight(1f).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    column.date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
                        .uppercase(Locale.getDefault()),
                    style = Label,
                    color = colors.muted,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.width(4.dp))
                val isToday = column.date == today
                if (isToday) {
                    Text(
                        "${column.date.dayOfMonth}",
                        fontFamily = SpaceMono,
                        fontSize = 14.sp,
                        color = colors.emphasisFg,
                        modifier = Modifier
                            .background(colors.emphasisBg)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                } else {
                    Text(
                        "${column.date.dayOfMonth}",
                        fontFamily = SpaceMono,
                        fontSize = 14.sp,
                        color = colors.strong,
                    )
                }
            }
        }
    }
}

/** The in-screen window nav both grid screens share (chrome §8.1 stays shared). */
@Composable
fun WindowNavBar(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    showToday: Boolean,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCalendarColors.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous", tint = colors.strong)
        }
        Text(
            label,
            style = MonthHeader,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next", tint = colors.strong)
        }
        if (showToday) {
            TextButton(onClick = onToday) {
                Text("Today", style = Body, color = colors.text)
            }
        }
    }
}

/** §7.1: each tier renders at its named stop on the white ramp. */
internal fun SigilTier.rampColor(colors: CalendarColors): Color = when (rampName) {
    "text" -> colors.text
    "strong" -> colors.strong
    "muted" -> colors.muted
    else -> colors.shade
}

internal fun titleOf(instance: CalendarInstance): String =
    instance.title?.takeIf { it.isNotBlank() } ?: "(untitled)"

// `zone` was dropped: callers pass display minutes already resolved against a
// zone, so formatting is pure minute arithmetic (was an unused parameter).
internal fun timeRangeText(startMinute: Int, endMinute: Int): String {
    fun hhmm(minute: Int): String = "%02d:%02d".format(minute / 60 % 24, minute % 60)
    return "${hhmm(startMinute)} – ${hhmm(endMinute)}"
}
