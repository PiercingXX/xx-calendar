package com.piercingxx.calendar.ui.month

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piercingxx.calendar.calendar.CalendarInstance
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.EventTitle
import com.piercingxx.calendar.ui.theme.JetBrainsMono
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import com.piercingxx.calendar.ui.theme.MonthHeader
import com.piercingxx.calendar.ui.theme.Time
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val TIME_COLUMN_WIDTH = 112.dp

private const val ALL_DAY_LABEL = "all day"
private const val UNTITLED = "(untitled)"
private const val SIGIL_UNMAPPED = "·"

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The day peek (§8.4): the selected day rendered as a Schedule-style list
 * beneath the grid, so the month stays visible. All-day events pin ahead of
 * timed ones — the caller passes them already ordered by AgendaGrouping.
 */
@Composable
internal fun DayPeek(
    date: LocalDate,
    events: List<CalendarInstance>,
    tiersByCalendarId: Map<Long, SigilTier>,
    nowMillis: Long,
    zone: ZoneId,
    // 14.1: the row carries the tapped occurrence's BEGIN directly, so the
    // caller needs no first-match lookup in the peeked day's list.
    onEventClick: (eventId: Long, instanceStartMillis: Long?) -> Unit,
    onCreate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCalendarColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = colors.line, thickness = 1.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            dayHeaderText(date),
            style = MonthHeader,
            color = colors.strong,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        if (events.isEmpty()) {
            Text(
                "Nothing scheduled.",
                style = Body,
                color = colors.muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onCreate != null) Modifier.clickable(onClick = onCreate) else Modifier,
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(events, key = { _, instance ->
                    "${instance.eventId}:${instance.startMillis}"
                }) { _, instance ->
                    PeekRow(instance, tiersByCalendarId, nowMillis, zone, onEventClick)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun PeekRow(
    instance: CalendarInstance,
    tiersByCalendarId: Map<Long, SigilTier>,
    nowMillis: Long,
    zone: ZoneId,
    onEventClick: (eventId: Long, instanceStartMillis: Long?) -> Unit,
) {
    val colors = LocalCalendarColors.current
    val tier = tiersByCalendarId[instance.calendarId]
    val past = instance.endMillis < nowMillis
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 34.dp)
            .clickable { onEventClick(instance.eventId, instance.startMillis) }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tier?.glyph ?: SIGIL_UNMAPPED,
            fontFamily = JetBrainsMono,
            fontSize = 15.sp,
            color = if (past || tier == null) colors.shade else tier.rampColor(colors),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            if (instance.allDay) ALL_DAY_LABEL else timeRange(instance, zone),
            style = if (instance.allDay) {
                Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
            } else {
                Time
            },
            color = if (past) colors.shade else colors.muted,
            modifier = Modifier.width(TIME_COLUMN_WIDTH),
        )
        Text(
            instance.title?.takeIf { it.isNotBlank() } ?: UNTITLED,
            style = EventTitle,
            color = if (past) colors.shade else colors.text,
            modifier = Modifier.weight(1f),
        )
    }
}

/** `MON 24 AUG` — same header language as the schedule view. */
internal fun dayHeaderText(date: LocalDate): String {
    val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        .uppercase(Locale.getDefault())
    val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        .uppercase(Locale.getDefault())
    return "$dow ${date.dayOfMonth} $month"
}

/** `09:00 – 09:15`, or a bare `19:30` when the occurrence has no extent. */
private fun timeRange(instance: CalendarInstance, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(instance.startMillis).atZone(zone).format(TIME_FORMAT)
    if (instance.endMillis <= instance.startMillis) return start
    val end = Instant.ofEpochMilli(instance.endMillis).atZone(zone).format(TIME_FORMAT)
    return "$start – $end"
}
