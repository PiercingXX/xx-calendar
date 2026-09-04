package com.piercingxx.calendar.ui.month

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.piercingxx.calendar.ui.day.timedSlotOnDate
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import com.piercingxx.calendar.ui.theme.MonthHeader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Month (design §8.4): a 7×N grid with horizontal paging between months,
 * up to three event chips per day cell, and a Schedule-style day peek beneath
 * the grid so the month stays visible when a day is selected. The leading
 * column and the optional week-number gutter follow §8.6's `startDayOfWeek`
 * and `weekNumbers`.
 *
 * Data window is the shown month ± [WINDOW_MARGIN_DAYS]; re-queried on every
 * provider change, then run through §8.6's consumption filters exactly like
 * the other views. The sigil map loads exactly as in ScheduleScreen (§6.1):
 * persisted map, allocate unseen calendars, persist what is new.
 *
 * [onEventClick] routes peek taps to the detail sheet, carrying the tapped
 * occurrence's BEGIN so recurring rows open on the instance actually tapped
 * (15.1 + 14.1); DayPeek forwards it straight from the tapped row.
 * [onCreate] opens a new event on a date-only tap: a second tap on the
 * already-selected day, or a tap on an empty peek.
 * [jumpToDate] / [jumpGeneration] are the chrome mini-month picker's jump:
 * generation ticks on every pick so the same date still re-scrolls after
 * the user has paged away.
 * [onVisibleMonthChange] reports the pager's shown month so the chrome
 * title tracks a swipe, not only a picker jump.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MonthScreen(
    modifier: Modifier = Modifier,
    showWeekNumbers: Boolean = false,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    onEventClick: (eventId: Long, instanceStartMillis: Long?) -> Unit = { _, _ -> },
    onCreate: (startMillis: Long, endMillis: Long) -> Unit = { _, _ -> },
    jumpToDate: LocalDate? = null,
    jumpGeneration: Int = 0,
    onVisibleMonthChange: (YearMonth) -> Unit = {},
) {
    val colors = LocalCalendarColors.current
    val context = LocalContext.current
    val repository = remember { CalendarRepository(context.contentResolver) }
    val sigilStore = remember { SigilStore(context.applicationContext) }
    val settingsStore = remember { SettingsStore(context.applicationContext) }
    val zone = remember { ZoneId.systemDefault() }

    var sigils by remember { mutableStateOf(emptyMap<CalendarKey, SigilTier>()) }
    var calendarsById by remember { mutableStateOf(emptyMap<Long, CalendarSummary>()) }
    val appSettings by settingsStore.settings.collectAsState(initial = AppSettings())

    // Sigil assignment pass (§6.1) — identical to ScheduleScreen.
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

    // Minute tick keeps today's inversion and the peek's past/shade line honest.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            nowMillis = now
            delay(60_000L - now % 60_000L)
        }
    }

    // Provider changes invalidate whatever windows are cached.
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository) {
        repository.changes().collect { revision++ }
    }

    val baseMonth = remember { YearMonth.now().minusMonths(START_PAGE.toLong()) }
    val pagerState = rememberPagerState(initialPage = START_PAGE) { PAGE_COUNT }
    val scope = rememberCoroutineScope()

    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }

    // Chrome mini-month picker / Today: generation ticks on every pick so a
    // second tap of the same date still re-scrolls after the user paged away.
    LaunchedEffect(jumpGeneration, jumpToDate) {
        val date = jumpToDate ?: return@LaunchedEffect
        if (jumpGeneration == 0) return@LaunchedEffect
        pagerState.scrollToPage(monthPagerPage(date, baseMonth))
        selectedDay = date
    }

    // Per-month caches; the effect loads the shown month plus both neighbours
    // so a swipe finds its chips already waiting.
    var monthCache by remember { mutableStateOf(emptyMap<YearMonth, MonthEvents>()) }
    // Filters change what a cached month should contain, so the cache records
    // which filter set built it and is dropped wholesale when they move.
    val currentFilters = Triple(
        appSettings.showDeclined,
        appSettings.hideAutoAdded,
        appSettings.autoAddedFilterMode,
    )
    var cacheFilters by remember { mutableStateOf(currentFilters) }
    val shownMonth = baseMonth.plusMonths((pagerState.currentPage - START_PAGE).toLong())
    LaunchedEffect(shownMonth) {
        onVisibleMonthChange(shownMonth)
    }

    LaunchedEffect(
        shownMonth,
        revision,
        sigils,
        calendarsById,
        appSettings.showDeclined,
        appSettings.hideAutoAdded,
        appSettings.autoAddedFilterMode,
    ) {
        if (cacheFilters != currentFilters) {
            cacheFilters = currentFilters
            monthCache = emptyMap()
        }
        for (offset in -1L..1L) {
            val month = shownMonth.plusMonths(offset)
            val cached = monthCache[month]
            if (cached == null) {
                val loaded = loadMonthEvents(repository, zone, month, appSettings, calendarsById)
                monthCache = monthCache + (month to loaded)
            }
        }
        // Keep the cache lean around the shown month.
        monthCache = monthCache.filterKeys {
            ChronoUnit.MONTHS.between(shownMonth, it) in -CACHE_RADIUS..CACHE_RADIUS
        }
    }

    // Tier resolution needs each calendar's account name; resolve once here.
    val tiersByCalendarId: Map<Long, SigilTier> = remember(sigils, calendarsById) {
        calendarsById.mapValues { (_, summary) ->
            sigils[CalendarKey(summary.id, summary.accountName ?: "")] ?: SigilTier.TIER_6
        }
    }

    val today = TimeMath.localDateOf(nowMillis, zone)
    val peekSelected = selectedDay

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.ink),
    ) {
        MonthNavRow(shownMonth, pagerState, scope)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (peekSelected == null) 1f else 3f),
        ) { page ->
            val pageMonth = baseMonth.plusMonths((page - START_PAGE).toLong())
            val pageData = monthCache[pageMonth]
            MonthGrid(
                showWeekNumbers = showWeekNumbers,
                firstDayOfWeek = firstDayOfWeek,
                today = today,
                selected = peekSelected,
                tiersByCalendarId = tiersByCalendarId,
                onSelect = { date ->
                    if (peekSelected == date) {
                        val slot = timedSlotOnDate(date, zone)
                        onCreate(slot.first, slot.second)
                    } else {
                        selectedDay = date
                    }
                },
                weeks = buildWeeks(pageMonth, pageData?.eventsByDate.orEmpty(), firstDayOfWeek),
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (peekSelected != null) {
            val peekEvents = monthCache[YearMonth.from(peekSelected)]?.eventsByDate
                ?.get(peekSelected).orEmpty()
            DayPeek(
                date = peekSelected,
                events = peekEvents,
                tiersByCalendarId = tiersByCalendarId,
                nowMillis = nowMillis,
                zone = zone,
                // The peek row carries the tapped occurrence's BEGIN itself
                // (14.1); null only if the cache moved mid-tap.
                onEventClick = onEventClick,
                onCreate = {
                    val slot = timedSlotOnDate(peekSelected, zone)
                    onCreate(slot.first, slot.second)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f, fill = false),
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Chevron row with the shown month/year — in-view paging control. The chrome
 * title tracks the same month via [onVisibleMonthChange].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthNavRow(
    month: YearMonth,
    pagerState: PagerState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val colors = LocalCalendarColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            scope.launch {
                pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
            }
        }) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month", tint = colors.strong)
        }
        Text(
            monthLabel(month),
            style = MonthHeader,
            color = colors.text,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        IconButton(onClick = {
            scope.launch {
                pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(PAGE_COUNT - 1))
            }
        }) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next month", tint = colors.strong)
        }
    }
}

/** `AUGUST 2026`, matching the chrome's uppercase month language. */
private fun monthLabel(month: YearMonth): String =
    month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        .uppercase(Locale.getDefault()) + " " + month.year

/** One month's provider window expanded, filtered per §8.6, grouped per day. */
private suspend fun loadMonthEvents(
    repository: CalendarRepository,
    zone: ZoneId,
    month: YearMonth,
    appSettings: AppSettings,
    calendarsById: Map<Long, CalendarSummary>,
): MonthEvents {
    val firstCell = month.atDay(1).minusDays(WINDOW_MARGIN_DAYS)
    val lastCell = month.atEndOfMonth().plusDays(WINDOW_MARGIN_DAYS)
    val instances = InstanceFilters.apply(
        repository.instances(
            TimeMath.localDayStart(firstCell, zone),
            TimeMath.localDayStart(lastCell.plusDays(1), zone),
        ),
        showDeclined = appSettings.showDeclined,
        hideAutoAdded = appSettings.hideAutoAdded,
        autoAddedFilterMode = appSettings.autoAddedFilterMode,
        calendarsById = calendarsById,
    )
    val byEventId = instances.associateBy { it.eventId }
    val buckets = AgendaGrouping.group(
        instances.map { InstanceSpan(it.eventId, it.startMillis, it.endMillis, it.allDay) },
        zone,
        window = firstCell..lastCell,
    )
    return MonthEvents(
        eventsByDate = buckets.associate { bucket ->
            bucket.date to (bucket.allDay + bucket.timed).mapNotNull { byEventId[it.eventId] }
        },
    )
}

/**
 * Whole-month weeks under §8.6's first day of week. Out-of-month cells
 * render from the same ± margin data, so their chips are real occurrences.
 */
internal fun buildWeeks(
    month: YearMonth,
    eventsByDate: Map<LocalDate, List<CalendarInstance>>,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
): List<List<MonthDayCell>> {
    val leading = leadingDays(month, firstDayOfWeek)
    val gridStart = month.atDay(1).minusDays(leading.toLong())
    val weekCount = (leading + month.lengthOfMonth() + 6) / 7
    return List(weekCount) { weekIndex ->
        List(7) { dayIndex ->
            val date = gridStart.plusDays((weekIndex * 7 + dayIndex).toLong())
            MonthDayCell(
                date = date,
                inMonth = YearMonth.from(date) == month,
                events = eventsByDate[date].orEmpty(),
            )
        }
    }
}

/** Days of the preceding week that lead the grid, first-day-of-week aware. */
internal fun leadingDays(month: YearMonth, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): Int =
    (month.atDay(1).dayOfWeek.value + 7 - firstDayOfWeek.value) % 7

/** Occurrences of one month grouped per day; all-day ahead of timed. */
private data class MonthEvents(
    val eventsByDate: Map<LocalDate, List<CalendarInstance>>,
)

private const val START_PAGE = 1200
private const val PAGE_COUNT = 2401
private const val WINDOW_MARGIN_DAYS = 7L
private const val CACHE_RADIUS = 2L

/** Pager index for [date] relative to a month grid whose page 0 is [baseMonth]. */
internal fun monthPagerPage(
    date: LocalDate,
    baseMonth: YearMonth,
    pageCount: Int = PAGE_COUNT,
): Int = ChronoUnit.MONTHS.between(baseMonth, YearMonth.from(date)).toInt()
    .coerceIn(0, pageCount - 1)
