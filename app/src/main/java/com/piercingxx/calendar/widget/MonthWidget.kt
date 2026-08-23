package com.piercingxx.calendar.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.piercingxx.calendar.MainActivity
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.InstanceFilters
import com.piercingxx.calendar.core.AgendaGrouping
import com.piercingxx.calendar.core.InstanceSpan
import com.piercingxx.calendar.core.TimeMath
import com.piercingxx.calendar.settings.Settings as AppSettings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Month-grid home screen widget (design §5, §13). WS11 body.
 *
 * Honest minimalism: a static current-month 7×N text grid — day numerals,
 * today inverted via the emphasis block, and any day with occurrences reduced
 * to a "+N" count. Full chip fidelity (up-to-3 sigil chips per cell, week
 * numbers, selection peek) lives in-app; a RemoteViews-sized surface cannot
 * carry it legibly.
 *
 * Font note: Glance renders through RemoteViews and cannot load res/font
 * families, so JetBrains Mono is approximated with [FontFamily.Monospace].
 *
 * Updates: system APPWIDGET_UPDATE plus [WidgetRefresher] (provider changes);
 * tap opens MainActivity via a plain launch intent.
 */
class MonthWidget : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = MonthWidgetContent()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresher.register(context, MonthWidget::class.java)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRefresher.register(context, MonthWidget::class.java)
    }

    override fun onDisabled(context: Context) {
        // Per-provider drop-out: WidgetRefresher keeps observing while any
        // other widget type survives.
        WidgetRefresher.unregister(context, MonthWidget::class.java)
        super.onDisabled(context)
    }
}

internal class MonthWidgetContent : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val zone = ZoneId.systemDefault()
        val month = YearMonth.now(zone)
        val today = LocalDate.now(zone)
        // §8.6: the grid follows startDayOfWeek; filters match the in-app views.
        val appSettings = currentWidgetSettings(context)
        // A pinned widget before the permission gate must degrade to an empty
        // grid, never crash the host process.
        val counts = runCatching { loadMonthCounts(context, zone, month, appSettings) }
            .getOrDefault(emptyMap())
        provideContent {
            MonthSurface(
                month = month,
                counts = counts,
                today = today,
                firstDayOfWeek = DayOfWeek.valueOf(appSettings.startDayOfWeek.name),
            )
        }
    }
}

/** Whole-month occurrence counts grouped per day (§8.4 window ± margin). */
private suspend fun loadMonthCounts(
    context: Context,
    zone: ZoneId,
    month: YearMonth,
    appSettings: AppSettings,
): Map<LocalDate, Int> {
    val repository = CalendarRepository(context.contentResolver)
    val firstCell = month.atDay(1).minusDays(MARGIN_DAYS)
    val lastCell = month.atEndOfMonth().plusDays(MARGIN_DAYS)
    val calendars = repository.calendars()
    val instances = InstanceFilters.apply(
        repository.instances(
            TimeMath.localDayStart(firstCell, zone),
            TimeMath.localDayStart(lastCell.plusDays(1), zone),
        ),
        showDeclined = appSettings.showDeclined,
        hideAutoAdded = appSettings.hideAutoAdded,
        autoAddedFilterMode = appSettings.autoAddedFilterMode,
        calendarsById = calendars.associateBy { it.id },
    )
    return AgendaGrouping.group(
        instances.map {
            InstanceSpan(it.eventId, it.startMillis, it.endMillis, it.allDay)
        },
        zone,
    ).associate { bucket -> bucket.date to (bucket.allDay.size + bucket.timed.size) }
}

@Composable
private fun MonthSurface(
    month: YearMonth,
    counts: Map<LocalDate, Int>,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(WidgetTokens.Ink))
            .padding(all = 8.dp)
            // Plain launch intent; MainActivity's VIEW filter is for deep links.
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Text(
                text = monthLabel(month),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(WidgetTokens.Text90),
                ),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            WeekdayHeader(firstDayOfWeek)
            for (week in weeksOfMonth(month, firstDayOfWeek)) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    for (date in week) {
                        // RowScope does not cross the call boundary; pass it in.
                        DayCell(
                            date = date,
                            month = month,
                            today = today,
                            count = counts[date] ?: 0,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        for (label in weekdayLabels(firstDayOfWeek)) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = ColorProvider(WidgetTokens.Shade),
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    month: YearMonth,
    today: LocalDate,
    count: Int,
    modifier: GlanceModifier = GlanceModifier,
) {
    val isToday = date == today
    val numeralColor = when {
        isToday -> WidgetTokens.EmphasisFg
        YearMonth.from(date) == month -> WidgetTokens.Strong
        else -> WidgetTokens.Shade
    }
    Column(modifier = modifier) {
        Box(
            modifier = if (isToday) {
                GlanceModifier
                    .background(ColorProvider(WidgetTokens.EmphasisBg))
                    .padding(horizontal = 1.dp, vertical = 0.dp)
            } else {
                GlanceModifier
            },
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = ColorProvider(numeralColor),
                ),
                maxLines = 1,
            )
        }
        if (count > 0) {
            Text(
                text = "+$count",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    color = ColorProvider(WidgetTokens.Muted),
                ),
                maxLines = 1,
            )
        }
    }
}

/** `AUGUST 2026`, matching MonthScreen's uppercase month language. */
private fun monthLabel(month: YearMonth): String =
    month.month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
        .uppercase(Locale.getDefault()) + " " + month.year

/**
 * Weeks under §8.6's start day of week, same math as MonthScreen's
 * buildWeeks; out-of-month cells render so every row holds exactly 7 days.
 */
private fun weeksOfMonth(month: YearMonth, firstDayOfWeek: DayOfWeek): List<List<LocalDate>> {
    val leading = leadingDays(month, firstDayOfWeek)
    val gridStart = month.atDay(1).minusDays(leading.toLong())
    val weekCount = (leading + month.lengthOfMonth() + 6) / 7
    return List(weekCount) { weekIndex ->
        List(7) { dayIndex -> gridStart.plusDays((weekIndex * 7 + dayIndex).toLong()) }
    }
}

private fun leadingDays(month: YearMonth, firstDayOfWeek: DayOfWeek): Int {
    val firstDowIso = month.atDay(1).dayOfWeek.value
    return (firstDowIso + 7 - firstDayOfWeek.value) % 7
}

/** Short labels aligned with [weeksOfMonth]'s column order. */
private fun weekdayLabels(firstDayOfWeek: DayOfWeek): List<String> {
    val anchorMonday = LocalDate.of(2024, 1, 1) // a known Monday
    val firstColumnIso = firstDayOfWeek.value
    return (0 until 7).map { offset ->
        anchorMonday
            .plusDays(((firstColumnIso - DayOfWeek.MONDAY.value) + offset).mod(7).toLong())
            .dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
            .uppercase(Locale.getDefault())
    }
}

private const val MARGIN_DAYS = 7L
