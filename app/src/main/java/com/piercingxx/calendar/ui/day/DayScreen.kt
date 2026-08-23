package com.piercingxx.calendar.ui.day

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
import com.piercingxx.calendar.settings.Settings as AppSettings
import com.piercingxx.calendar.settings.SettingsStore
import com.piercingxx.calendar.settings.SigilStore
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Day view (design §8.3): one time-grid column for the visible day, all-day
 * events pinned above, in-screen window navigation. The screen owns its
 * visible date and its write path; [onNavigate] receives the app's routes
 * ("editor/new?start=..&end=.." after a create gesture, "detail/{id}" on tap).
 */
@Composable
fun DayScreen(
    modifier: Modifier = Modifier,
    state: DayWindowState = remember { DayWindowState() },
    onNavigate: (String) -> Unit = {},
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

    // Sigil assignment pass (§6.1), as the schedule runs it.
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

    // Minute tick: keeps the now-rule honest (mirrors ScheduleScreen).
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            nowMillis = now
            delay(60_000L - now % 60_000L)
        }
    }

    // Provider changes invalidate the visible day.
    LaunchedEffect(repository) {
        repository.changes().collect { state.forceRefresh() }
    }

    val columns by produceState<List<GridColumn>>(
        initialValue = emptyList(),
        state.epochDay,
        state.revision,
        appSettings.showDeclined,
        appSettings.hideAutoAdded,
        appSettings.autoAddedFilterMode,
        calendarsById,
    ) {
        val date = LocalDate.ofEpochDay(state.epochDay)
        val instances = InstanceFilters.apply(
            repository.instances(
                TimeMath.localDayStart(date, zone),
                TimeMath.localDayStart(date.plusDays(1), zone),
            ),
            showDeclined = appSettings.showDeclined,
            hideAutoAdded = appSettings.hideAutoAdded,
            autoAddedFilterMode = appSettings.autoAddedFilterMode,
            calendarsById = calendarsById,
        )
        value = buildGridColumns(listOf(date), zone, instances)
    }

    val pxPerMinute = with(LocalDensity.current) { HOUR_HEIGHT.toPx() } / 60f
    val scrollState = remember(state.epochDay) {
        ScrollState(
            initialScrollMinutes(listOf(LocalDate.ofEpochDay(state.epochDay)), System.currentTimeMillis(), zone) *
                pxPerMinute.toInt(),
        )
    }

    Column(modifier.fillMaxSize().background(colors.ink)) {
        WindowNavBar(
            label = "${shortDayOfWeek(state.date)} ${state.date.dayOfMonth} ${shortMonth(state.date)}",
            onPrevious = { state.shift(-1) },
            onNext = { state.shift(1) },
            showToday = !state.onToday(),
            onToday = { state.jumpTo(LocalDate.now()) },
        )
        if (columns.isEmpty()) return@Column

        DaysHeaderRow(columns, today = TimeMath.localDateOf(nowMillis, zone))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        if (columns.any { it.allDay.isNotEmpty() }) {
            AllDayRow(columns, sigils, calendarsById, onEventClick = { id -> onNavigate("detail/$id") })
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
            onEventMoved = { eventId, startMillis, endMillis ->
                scope.launch {
                    runCatching { repository.moveTimedEvent(eventId, startMillis, endMillis) }
                    state.forceRefresh()
                }
            },
            onEventResized = { eventId, startMillis, endMillis ->
                scope.launch {
                    runCatching { repository.resizeTimedEvent(eventId, startMillis, endMillis) }
                    state.forceRefresh()
                }
            },
            onEventClick = { id -> onNavigate("detail/$id") },
            modifier = Modifier.weight(1f),
            scrollState = scrollState,
        )
    }
}

internal fun shortDayOfWeek(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(Locale.getDefault())

internal fun shortMonth(date: LocalDate): String =
    date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(Locale.getDefault())
