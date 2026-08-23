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
import androidx.glance.appwidget.lazy.LazyColumn
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
import com.piercingxx.calendar.calendar.CalendarInstance
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.InstanceFilters
import com.piercingxx.calendar.core.AgendaGrouping
import com.piercingxx.calendar.core.InstanceSpan
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.settings.SigilStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Up-next / schedule home screen widget (design §5, §13). WS11 body.
 *
 * The next 7 days as one flat list — day headers plus sigil-glyph + time +
 * title rows over [now, now+7d], rendered in Glance's LazyColumn. Occurrences
 * still running stay visible, dimmed to the shade stop like ScheduleScreen.
 *
 * Font note: Glance renders through RemoteViews and cannot load res/font
 * families, so JetBrains Mono is approximated with [FontFamily.Monospace].
 *
 * Updates: system APPWIDGET_UPDATE plus [WidgetRefresher] (provider changes);
 * tap opens MainActivity via a plain launch intent.
 */
class ScheduleWidget : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = ScheduleWidgetContent()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresher.register(context, ScheduleWidget::class.java)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRefresher.register(context, ScheduleWidget::class.java)
    }

    override fun onDisabled(context: Context) {
        // Per-provider drop-out: WidgetRefresher keeps observing while any
        // other widget type survives.
        WidgetRefresher.unregister(context, ScheduleWidget::class.java)
        super.onDisabled(context)
    }
}

internal class ScheduleWidgetContent : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A pinned widget before the permission gate degrades to the empty
        // state rather than crashing the host process.
        val sections = runCatching { loadSections(context) }.getOrDefault(emptyList())
        provideContent {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(WidgetTokens.Ink))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    // Plain launch intent; MainActivity's VIEW filter is for deep links.
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                if (sections.isEmpty()) {
                    Text(
                        text = EMPTY_LABEL,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = ColorProvider(WidgetTokens.Muted),
                        ),
                    )
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(count = sections.size) { index ->
                            DaySectionBlock(sections[index])
                        }
                    }
                }
            }
        }
    }
}

/** One day header plus its rows — a flat-list slice of the §8.2 agenda. */
private data class WidgetSection(val date: LocalDate, val rows: List<WidgetEvent>)

private data class WidgetEvent(
    val glyph: String,
    val tier: SigilTier?,
    val timeLabel: String,
    val title: String,
    val past: Boolean,
)

private suspend fun loadSections(context: Context): List<WidgetSection> {
    val zone = ZoneId.systemDefault()
    val nowMillis = System.currentTimeMillis()
    val repository = CalendarRepository(context.contentResolver)
    val appSettings = currentWidgetSettings(context)
    val calendars = repository.calendars()
    val tiersByCalendarId =
        sigilTiersByCalendarId(repository, SigilStore(context.applicationContext), calendars)

    // §8.6 consumption filters (declined / auto-added), same as the in-app views.
    val instances = InstanceFilters.apply(
        repository.instances(nowMillis, nowMillis + WINDOW_MILLIS),
        showDeclined = appSettings.showDeclined,
        hideAutoAdded = appSettings.hideAutoAdded,
        autoAddedFilterMode = appSettings.autoAddedFilterMode,
        calendarsById = calendars.associateBy { it.id },
    )
    // Multi-day occurrences land in several buckets; key back to the row once.
    val byOccurrence = instances.associateBy { it.eventId to it.startMillis }
    return AgendaGrouping.group(
        instances.map { InstanceSpan(it.eventId, it.startMillis, it.endMillis, it.allDay) },
        zone,
    ).map { bucket ->
        WidgetSection(
            date = bucket.date,
            rows = (bucket.allDay + bucket.timed).map { span ->
                val instance = requireNotNull(byOccurrence[span.eventId to span.startMillis]) {
                    "grouped span without source instance"
                }
                instance.toWidgetEvent(
                    tier = tiersByCalendarId[instance.calendarId],
                    nowMillis = nowMillis,
                    zone = zone,
                )
            },
        )
    }
}

private fun CalendarInstance.toWidgetEvent(
    tier: SigilTier?,
    nowMillis: Long,
    zone: ZoneId,
): WidgetEvent = WidgetEvent(
    glyph = tier?.glyph ?: SIGIL_UNMAPPED,
    tier = tier,
    timeLabel = if (allDay) ALL_DAY_LABEL else timeRange(this, zone),
    title = title?.takeIf { it.isNotBlank() } ?: UNTITLED,
    past = endMillis < nowMillis,
)

@Composable
private fun DaySectionBlock(section: WidgetSection) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Spacer(modifier = GlanceModifier.height(6.dp))
        Text(
            text = dayHeaderText(section.date),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ColorProvider(WidgetTokens.Strong),
            ),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        for (event in section.rows) {
            EventLine(event)
        }
    }
}

@Composable
private fun EventLine(event: WidgetEvent) {
    val rampColor = event.tier?.rampArgb() ?: WidgetTokens.Shade
    val dimmed = event.past
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            text = event.glyph,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = ColorProvider(if (dimmed) WidgetTokens.Shade else rampColor),
            ),
            maxLines = 1,
        )
        Text(
            text = " " + event.timeLabel + "  ",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = ColorProvider(if (dimmed) WidgetTokens.Shade else WidgetTokens.Muted),
            ),
            maxLines = 1,
        )
        Text(
            text = event.title,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = ColorProvider(if (dimmed) WidgetTokens.Shade else WidgetTokens.Text90),
            ),
            maxLines = 1,
        )
    }
}

/** `MON 24 AUG`, matching ScheduleScreen's day-header language. */
private fun dayHeaderText(date: LocalDate): String {
    val dow = date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
        .uppercase(Locale.getDefault())
    val month = date.month.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
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

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private const val ALL_DAY_LABEL = "all day"
private const val UNTITLED = "(untitled)"
private const val SIGIL_UNMAPPED = "·"
private const val EMPTY_LABEL = "nothing scheduled"

private const val WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
