package com.piercingxx.calendar.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.calendar.InstanceFilters
import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.SigilAssigner
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.core.TimeMath
import com.piercingxx.calendar.detailRoute
import com.piercingxx.calendar.editorRoute
import com.piercingxx.calendar.settings.Settings as AppSettings
import com.piercingxx.calendar.settings.SettingsStore
import com.piercingxx.calendar.settings.SigilStore
import com.piercingxx.calendar.ui.day.AllDayRow
import com.piercingxx.calendar.ui.day.DaysHeaderRow
import com.piercingxx.calendar.ui.day.GridColumn
import com.piercingxx.calendar.ui.day.HOUR_HEIGHT
import com.piercingxx.calendar.ui.day.TimeGrid
import com.piercingxx.calendar.ui.day.WindowNavBar
import com.piercingxx.calendar.ui.day.buildGridColumns
import com.piercingxx.calendar.ui.day.initialScrollMinutes
import com.piercingxx.calendar.ui.day.moveTimedEvent
import com.piercingxx.calendar.ui.day.resizeTimedEvent
import com.piercingxx.calendar.ui.day.shortMonth
import com.piercingxx.calendar.ui.gesture.horizontalSwipeNavigate
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Week view (design §8.3): the same time grid as Day, seven columns wide.
 * Column width compresses instead of scrolling horizontally; today's numeral
 * in the header is the inverted block. The screen owns its visible week and
 * its write path; [onNavigate] receives the app's routes.
 *
 * [firstDayOfWeek] carries §8.6's start day of week (15.6), exactly like
 * MonthScreen's parameter; callers that do not pass it yet get Monday.
 */
@Composable
fun WeekScreen(
    modifier: Modifier = Modifier,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    onNavigate: (String) -> Unit = {},
    state: WeekWindowState = remember(firstDayOfWeek) { WeekWindowState(firstDayOfWeek) },
) {
    val colors = LocalCalendarColors.current
    val context = LocalContext.current
    val repository = remember { CalendarRepository(context.contentResolver) }
    val sigilStore = remember { SigilStore(context.applicationContext) }
    val settingsStore = remember { SettingsStore(context.applicationContext) }
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    var sigils by remember { mutableStateOf(emptyMap<CalendarKey, SigilTier>()) }
    var calendarsById by remember { mutableStateOf(emptyMap<Long, CalendarSummary>()) }
    // §8.6 consumption filters (declined / auto-added), as ScheduleScreen runs them.
    val appSettings by settingsStore.settings.collectAsState(initial = AppSettings())

    // Sigil assignment pass (§6.1), shared with every other view.
    LaunchedEffect(repository, sigilStore) {
        val calendars = repository.calendars()
        calendarsById = calendars.associateBy { it.id }
        val assignment = SigilAssigner.assign(
            sigilStore.load(),
            calendars.map { CalendarKey(it.id, it.accountName ?: "") },
        )
        sigils = assignment.assignments
        if (assignment.newlyAssigned.isNotEmpty()) {
            sigilStore.save(assignment.assignments)
        }
    }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            nowMillis = now
            delay(60_000L - now % 60_000L)
        }
    }

    LaunchedEffect(repository) {
        repository.changes().collect { state.forceRefresh() }
    }

    val columns by produceState<List<GridColumn>>(
        initialValue = emptyList(),
        state.weekStartDay,
        state.revision,
        appSettings.showDeclined,
        appSettings.hideAutoAdded,
        appSettings.autoAddedFilterMode,
        calendarsById,
    ) {
        val dates = state.dates
        val instances = InstanceFilters.apply(
            repository.instances(
                TimeMath.localDayStart(dates.first(), zone),
                TimeMath.localDayStart(dates.last().plusDays(1), zone),
            ),
            showDeclined = appSettings.showDeclined,
            hideAutoAdded = appSettings.hideAutoAdded,
            autoAddedFilterMode = appSettings.autoAddedFilterMode,
            calendarsById = calendarsById,
        )
        value = buildGridColumns(dates, zone, instances)
    }

    val pxPerMinute = with(LocalDensity.current) { HOUR_HEIGHT.toPx() } / 60f
    val scrollState = remember(state.weekStartDay) {
        ScrollState(
            initialScrollMinutes(state.dates, System.currentTimeMillis(), zone) * pxPerMinute.toInt(),
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.ink)
            .horizontalSwipeNavigate(
                onPrevious = { state.shiftWeeks(-1) },
                onNext = { state.shiftWeeks(1) },
            ),
    ) {
        WindowNavBar(
            label = "${state.startDate.dayOfMonth} ${shortMonth(state.startDate)} – " +
                "${state.endDate.dayOfMonth} ${shortMonth(state.endDate)}",
            onPrevious = { state.shiftWeeks(-1) },
            onNext = { state.shiftWeeks(1) },
            showToday = !state.onToday(),
            onToday = { state.jumpTo(LocalDate.now()) },
        )
        if (columns.isEmpty()) return@Column

        DaysHeaderRow(columns, today = TimeMath.localDateOf(nowMillis, zone))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        if (columns.any { it.allDay.isNotEmpty() }) {
            AllDayRow(columns, sigils, calendarsById, onEventClick = { id, start ->
                onNavigate(detailRoute(id, start))
            })
        }
        TimeGrid(
            columns = columns,
            sigils = sigils,
            calendarsById = calendarsById,
            nowMillis = nowMillis,
            zone = zone,
            onCreateSlot = { _, startMillis, endMillis ->
                onNavigate("editor/new?start=$startMillis&end=$endMillis")
            },
            onEventMoved = { eventId, draggedInstanceStart, startMillis, endMillis ->
                scope.launch {
                    // Recurring rows are refused (a drag must not rewrite the
                    // series); the editor is where the §6.3 scope prompt lives,
                    // opened at the DRAGGED occurrence so it stamps that
                    // instance's begin, not the series anchor (F3).
                    val moved = runCatching {
                        repository.moveTimedEvent(eventId, startMillis, endMillis)
                    }.getOrDefault(false)
                    if (moved) {
                        state.forceRefresh()
                    } else {
                        onNavigate(
                            editorRoute(
                                eventId,
                                draggedInstanceStart,
                                dropStartMillis = startMillis,
                                dropEndMillis = endMillis,
                            ),
                        )
                    }
                }
            },
            onEventResized = { eventId, startMillis, endMillis ->
                scope.launch {
                    runCatching { repository.resizeTimedEvent(eventId, startMillis, endMillis) }
                    state.forceRefresh()
                }
            },
            onEventClick = { id, start -> onNavigate(detailRoute(id, start)) },
            modifier = Modifier.weight(1f),
            scrollState = scrollState,
        )
    }
}
