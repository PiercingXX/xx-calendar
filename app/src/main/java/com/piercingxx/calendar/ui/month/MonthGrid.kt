package com.piercingxx.calendar.ui.month

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piercingxx.calendar.calendar.CalendarInstance
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.ui.theme.CalendarColors
import com.piercingxx.calendar.ui.theme.JetBrainsMono
import com.piercingxx.calendar.ui.theme.Label
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import com.piercingxx.calendar.ui.theme.SpaceMono
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Width of the leading week-number gutter column (§8.4). */
internal val WEEK_GUTTER_WIDTH = 26.dp

internal const val MAX_CHIPS = 3

private const val UNTITLED = "(untitled)"
private const val SIGIL_UNMAPPED = "·"
private const val DAYS_PER_WEEK = 7

/** One day cell of the month grid: its date and its ordered occurrences. */
data class MonthDayCell(
    val date: LocalDate,
    val inMonth: Boolean,
    val events: List<CalendarInstance>,
)

/**
 * The 7×N month grid (design §8.4): day numerals in Space Mono at `strong`,
 * out-of-month numerals at `shade` — chips stay lit, per the §7.1 mock —
 * today's numeral, and the selected day's, inverted on an emphasis block,
 * this view's one full-white element. Up to three chips per cell, then `+N`.
 * Optional leading week-number gutter at `shade`.
 *
 * [tiersByCalendarId] is pre-resolved by the screen from the persisted sigil
 * map plus the calendar list (the CalendarKey needs each calendar's account).
 */
@Composable
internal fun MonthGrid(
    showWeekNumbers: Boolean,
    firstDayOfWeek: DayOfWeek,
    today: LocalDate,
    selected: LocalDate?,
    tiersByCalendarId: Map<Long, SigilTier>,
    onSelect: (LocalDate) -> Unit,
    weeks: List<List<MonthDayCell>>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        WeekdayHeaderRow(showWeekNumbers, firstDayOfWeek)
        weeks.forEachIndexed { weekIndex, week ->
            WeekRow(
                week = week,
                showWeekNumbers = showWeekNumbers,
                today = today,
                selected = selected,
                tiersByCalendarId = tiersByCalendarId,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            if (weekIndex != weeks.lastIndex) Spacer(Modifier.height(1.dp))
        }
    }
}

@Composable
private fun WeekdayHeaderRow(showWeekNumbers: Boolean, firstDayOfWeek: DayOfWeek) {
    val colors = LocalCalendarColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        if (showWeekNumbers) {
            Spacer(Modifier.width(WEEK_GUTTER_WIDTH))
        }
        repeat(DAYS_PER_WEEK) { column ->
            Text(
                weekdayLabel(column, firstDayOfWeek),
                style = Label,
                color = colors.muted,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            )
        }
    }
}

/** Column index -> weekday abbreviation, §8.6 start-day-of-week aware. */
internal fun weekdayLabel(column: Int, firstDayOfWeek: DayOfWeek): String {
    val day = firstDayOfWeek.plus(column.toLong())
    return day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        .uppercase(Locale.getDefault())
}

@Composable
private fun WeekRow(
    week: List<MonthDayCell>,
    showWeekNumbers: Boolean,
    today: LocalDate,
    selected: LocalDate?,
    tiersByCalendarId: Map<Long, SigilTier>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCalendarColors.current
    Row(modifier = modifier) {
        if (showWeekNumbers) {
            Text(
                weekNumberLabel(week.first().date),
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = colors.shade,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .width(WEEK_GUTTER_WIDTH)
                    .fillMaxHeight()
                    .padding(top = 4.dp),
            )
        }
        week.forEach { cell ->
            DayCell(
                cell = cell,
                today = today,
                selected = selected,
                tiersByCalendarId = tiersByCalendarId,
                onSelect = onSelect,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Week number under the locale's week definition, for the gutter column. */
internal fun weekNumberLabel(date: LocalDate): String =
    date.get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear()).toString()

@Composable
private fun DayCell(
    cell: MonthDayCell,
    today: LocalDate,
    selected: LocalDate?,
    tiersByCalendarId: Map<Long, SigilTier>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCalendarColors.current
    val inverted = cell.date == today || cell.date == selected
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clipToBounds()
            .clickable { onSelect(cell.date) }
            .padding(start = 3.dp, end = 2.dp, top = 2.dp),
    ) {
        Box(
            modifier = Modifier.background(if (inverted) colors.emphasisBg else Color.Transparent),
        ) {
            Text(
                "${cell.date.dayOfMonth}",
                fontFamily = SpaceMono,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = when {
                    inverted -> colors.emphasisFg
                    !cell.inMonth -> colors.shade
                    else -> colors.strong
                },
                modifier = Modifier.padding(horizontal = 3.dp),
            )
        }
        Spacer(Modifier.height(1.dp))
        cell.events.take(MAX_CHIPS).forEach { instance ->
            EventChip(instance, tiersByCalendarId[instance.calendarId])
        }
        if (cell.events.size > MAX_CHIPS) {
            Text(
                "+${cell.events.size - MAX_CHIPS}",
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** Sigil glyph + truncated title, one line (§8.4); glyph at its tier stop. */
@Composable
private fun EventChip(
    instance: CalendarInstance,
    tier: SigilTier?,
) {
    val colors = LocalCalendarColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            tier?.glyph ?: SIGIL_UNMAPPED,
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = tier?.rampColor(colors) ?: colors.shade,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            instance.title?.takeIf { it.isNotBlank() } ?: UNTITLED,
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = colors.strong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** §7.1: each tier renders its glyph at its named stop on the white ramp. */
internal fun SigilTier.rampColor(colors: CalendarColors): Color = when (rampName) {
    "text" -> colors.text
    "strong" -> colors.strong
    "muted" -> colors.muted
    else -> colors.shade
}
