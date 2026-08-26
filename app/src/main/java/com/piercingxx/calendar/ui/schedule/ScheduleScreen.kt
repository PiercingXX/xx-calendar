package com.piercingxx.calendar.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piercingxx.calendar.calendar.CalendarInstance
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.calendar.InstanceFilters
import com.piercingxx.calendar.core.AgendaGrouping
import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.InstanceSpan
import com.piercingxx.calendar.core.SigilAssigner
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.core.TimeMath
import com.piercingxx.calendar.settings.Settings as AppSettings
import com.piercingxx.calendar.settings.SettingsStore
import com.piercingxx.calendar.settings.SigilStore
import com.piercingxx.calendar.ui.gesture.horizontalSwipeNavigate
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.CalendarColors
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
import kotlinx.coroutines.delay

/**
 * Schedule — the default view (design §8.2): a flat, infinitely-scrolling
 * list of what is next. Days holding nothing are never rendered; the
 * current-time rule in signal white is the only full-white element here.
 */
@Composable
fun ScheduleScreen(
    // 14.1 / handoff note a: taps carry the tapped occurrence's BEGIN, same
    // as Month's peek and the Day/Week grids.
    onEventClick: (eventId: Long, instanceStartMillis: Long?) -> Unit,
    modifier: Modifier = Modifier,
    state: ScheduleWindowState = remember { ScheduleWindowState() },
) {
    val colors = LocalCalendarColors.current
    val context = LocalContext.current
    val repository = remember { CalendarRepository(context.contentResolver) }
    val sigilStore = remember { SigilStore(context.applicationContext) }
    val settingsStore = remember { SettingsStore(context.applicationContext) }
    val zone = remember { ZoneId.systemDefault() }

    var sigils by remember { mutableStateOf(emptyMap<CalendarKey, SigilTier>()) }
    var calendarsById by remember { mutableStateOf(emptyMap<Long, CalendarSummary>()) }
    // §8.6 consumption filters (declined / auto-added) re-run the window query.
    val appSettings by settingsStore.settings.collectAsState(initial = AppSettings())

    // Sigil assignment pass (§6.1): persisted map, allocate unseen calendars,
    // persist when anything new was assigned.
    LaunchedEffect(repository, sigilStore) {
        val calendars = repository.calendars()
        calendarsById = calendars.associateBy { it.id }
        val existing = sigilStore.load()
        val assignment = SigilAssigner.assign(
            existing,
            calendars.map { CalendarKey(it.id, it.accountName ?: "") },
        )
        sigils = assignment.assignments
        if (assignment.newlyAssigned.isNotEmpty()) {
            sigilStore.save(assignment.assignments)
        }
    }

    // Minute tick: drives the past/shade boundary and the now-rule position.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            nowMillis = now
            delay(60_000L - now % 60_000L)
        }
    }

    // Provider changes invalidate whatever window is currently held.
    LaunchedEffect(repository) {
        repository.changes().collect { state.forceRefresh() }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    val sections by produceState<List<DaySection>>(
        initialValue = emptyList(),
        state.startDay,
        state.endDay,
        state.revision,
        appSettings.showDeclined,
        appSettings.hideAutoAdded,
        appSettings.autoAddedFilterMode,
        calendarsById,
    ) {
        val instances = InstanceFilters.apply(
            repository.instances(
                TimeMath.localDayStart(LocalDate.ofEpochDay(state.startDay), zone),
                TimeMath.localDayStart(LocalDate.ofEpochDay(state.endDay).plusDays(1), zone),
            ),
            showDeclined = appSettings.showDeclined,
            hideAutoAdded = appSettings.hideAutoAdded,
            autoAddedFilterMode = appSettings.autoAddedFilterMode,
            calendarsById = calendarsById,
        )
        val byEventId = instances.associateBy { it.eventId }
        value = AgendaGrouping.group(
            instances.map { InstanceSpan(it.eventId, it.startMillis, it.endMillis, it.allDay) },
            zone,
            window = LocalDate.ofEpochDay(state.startDay)..LocalDate.ofEpochDay(state.endDay),
        ).mapNotNull { bucket ->
            DaySection(
                date = bucket.date,
                allDay = bucket.allDay.mapNotNull { byEventId[it.eventId] },
                timed = bucket.timed.mapNotNull { byEventId[it.eventId] },
            )
        }
        loadedOnce = true
    }

    val listState = rememberLazyListState()
    var focusDate by remember { mutableStateOf<LocalDate?>(null) }

    fun shiftFocus(days: Long) {
        val next = (focusDate ?: LocalDate.now()).plusDays(days)
        focusDate = next
        if (next.isBefore(state.startDate) || next.isAfter(state.endDate)) {
            state.jumpTo(next)
        }
    }

    LaunchedEffect(focusDate, sections) {
        val target = focusDate ?: return@LaunchedEffect
        val idx = sections.indexOfFirst { !it.date.isBefore(target) }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    // Infinite scroll both directions: near either edge of the loaded data,
    // grow the window. Keyed items keep the scroll anchored across prepends;
    // an extension whose new range holds no events adds no items and changes
    // no index, so this trigger cannot loop on itself.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.totalItemsCount }
            .collect { (firstVisible, total) ->
                if (total == 0) return@collect
                val lastVisible =
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible
                if (firstVisible <= NEAR_EDGE_ITEMS) state.extendBackward()
                if (lastVisible >= total - 1 - NEAR_EDGE_ITEMS) state.extendForward()
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.ink)
            .clipToBounds()
            .horizontalSwipeNavigate(
                onPrevious = { shiftFocus(-1) },
                onNext = { shiftFocus(1) },
            ),
    ) {
        when {
            !loadedOnce -> Unit
            sections.isEmpty() -> Text(
                "Nothing scheduled.",
                style = Body,
                color = colors.muted,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(sections, key = { it.date.toEpochDay() }) { section ->
                        DayBlock(section, sigils, calendarsById, nowMillis, zone, onEventClick)
                    }
                }
                NowRule(listState, nowMillis, zone)
            }
        }
    }
}

private const val NEAR_EDGE_ITEMS = 8

/** Midnight-to-midnight mapped linearly across today's block height. */
private const val DAY_MILLIS = 86_400_000f

/** One day's render model: the provider instance behind every placed span. */
private data class DaySection(
    val date: LocalDate,
    val allDay: List<CalendarInstance>,
    val timed: List<CalendarInstance>,
)

@Composable
private fun DayBlock(
    section: DaySection,
    sigils: Map<CalendarKey, SigilTier>,
    calendarsById: Map<Long, CalendarSummary>,
    nowMillis: Long,
    zone: ZoneId,
    onEventClick: (eventId: Long, instanceStartMillis: Long?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(28.dp))
        Text(
            dayHeaderText(section.date),
            style = MonthHeader,
            color = LocalCalendarColors.current.strong,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        section.allDay.forEach { instance ->
            EventRow(instance, sigils, calendarsById, nowMillis, zone, onEventClick)
        }
        section.timed.forEach { instance ->
            EventRow(instance, sigils, calendarsById, nowMillis, zone, onEventClick)
        }
    }
}

/** `MON 24 AUG` */
private fun dayHeaderText(date: LocalDate): String {
    val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        .uppercase(Locale.getDefault())
    val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        .uppercase(Locale.getDefault())
    return "$dow ${date.dayOfMonth} $month"
}

@Composable
private fun EventRow(
    instance: CalendarInstance,
    sigils: Map<CalendarKey, SigilTier>,
    calendarsById: Map<Long, CalendarSummary>,
    nowMillis: Long,
    zone: ZoneId,
    onEventClick: (eventId: Long, instanceStartMillis: Long?) -> Unit,
) {
    val colors = LocalCalendarColors.current
    val accountName = calendarsById[instance.calendarId]?.accountName ?: ""
    val tier = sigils[CalendarKey(instance.calendarId, accountName)]
    val past = instance.endMillis < nowMillis
    val ramp = if (past || tier == null) colors.shade else tier.rampColor(colors)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 34.dp)
            .clickable { onEventClick(instance.eventId, instance.startMillis) }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tier?.glyph ?: SIGIL_UNMAPPED, fontFamily = JetBrainsMono, fontSize = 15.sp, color = ramp)
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

/**
 * The current-time rule (§8.2 / §7.1): 1dp signal white inside today's block,
 * at midnight-to-midnight proportion of its height. Rendered only while
 * today's day block is composed on screen.
 */
@Composable
private fun NowRule(listState: LazyListState, nowMillis: Long, zone: ZoneId) {
    val colors = LocalCalendarColors.current
    val today = TimeMath.localDateOf(nowMillis, zone)
    val info = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.key == today.toEpochDay() } ?: return
    val dayFraction =
        ((nowMillis - TimeMath.localDayStart(today, zone)) / DAY_MILLIS).coerceIn(0f, 1f)
    val y = info.offset + (info.size * dayFraction).toInt()
    Box(
        modifier = Modifier
            .offset { IntOffset(0, y) }
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.signal),
    )
}

private val TIME_COLUMN_WIDTH = 112.dp

private const val ALL_DAY_LABEL = "all day"
private const val UNTITLED = "(untitled)"
private const val SIGIL_UNMAPPED = "·"

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** `09:00 – 09:15`, or a bare `19:30` when the occurrence has no extent. */
private fun timeRange(instance: CalendarInstance, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(instance.startMillis).atZone(zone).format(TIME_FORMAT)
    if (instance.endMillis <= instance.startMillis) return start
    val end = Instant.ofEpochMilli(instance.endMillis).atZone(zone).format(TIME_FORMAT)
    return "$start – $end"
}

/** §7.1: each tier renders its glyph at its named stop on the white ramp. */
private fun SigilTier.rampColor(colors: CalendarColors): Color = when (rampName) {
    "text" -> colors.text
    "strong" -> colors.strong
    "muted" -> colors.muted
    else -> colors.shade
}
