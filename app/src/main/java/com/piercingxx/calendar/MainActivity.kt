package com.piercingxx.calendar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.piercingxx.calendar.alarm.ReminderPlanner
import com.piercingxx.calendar.alarm.ReminderReconciler
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.calendar.LocalCalendarBootstrap
import com.piercingxx.calendar.settings.AppBackground
import com.piercingxx.calendar.settings.AppFont
import com.piercingxx.calendar.settings.DefaultView
import com.piercingxx.calendar.settings.Density
import com.piercingxx.calendar.settings.IcsCodec
import com.piercingxx.calendar.settings.IcsExchange
import com.piercingxx.calendar.settings.Settings as AppSettings
import com.piercingxx.calendar.settings.SettingsStore
import com.piercingxx.calendar.ui.day.DayScreen
import com.piercingxx.calendar.ui.detail.DetailSheet
import com.piercingxx.calendar.ui.drawer.CalendarDrawer
import com.piercingxx.calendar.ui.editor.EditorScreen
import com.piercingxx.calendar.ui.month.MonthScreen
import com.piercingxx.calendar.ui.schedule.ScheduleScreen
import com.piercingxx.calendar.ui.schedule.ScheduleWindowState
import com.piercingxx.calendar.ui.settings.MAX_IMPORT_BYTES
import com.piercingxx.calendar.ui.settings.STATUS_CANCELED_INT
import com.piercingxx.calendar.ui.settings.WritableCalendarPickerSheet
import com.piercingxx.calendar.ui.settings.readBounded
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.DayNumeral
import com.piercingxx.calendar.ui.theme.MonthHeader
import com.piercingxx.calendar.ui.theme.SpaceMono
import com.piercingxx.calendar.ui.theme.CalendarTheme
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import com.piercingxx.calendar.ui.theme.ThemeGroundState
import com.piercingxx.calendar.ui.week.WeekScreen
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** Latest §12 deep link awaiting consumption below the permission gate. */
    private val pendingLink = mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Provider-change observation for reminder reconciliation (design §4.3).
        // Converges even if this process dies — the other four triggers cover it.
        ReminderReconciler.ensureObserving(this)
        pendingLink.value = parseDeepLink(intent)
        setContent {
            // §8.6 appearance rows reach the whole tree from here: the theme
            // consumes the stored background/font/scale values, and nothing
            // renders until the first emission so `default view` can pick the
            // opening route without a visible reset.
            val settingsStore = remember { SettingsStore(applicationContext) }
            val settings by settingsStore.settings.collectAsState(initial = null)
            CalendarTheme(
                background = settings?.background ?: AppBackground.AMOLED_NIGHT,
                font = settings?.font ?: AppFont.JETBRAINS_MONO,
                textSizeScale = settings?.textSizeScale ?: 1.0f,
                density = settings?.density ?: Density.COMFORTABLE,
            ) {
                val loaded = settings
                if (loaded == null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                } else {
                    AppRoot(
                        pending = pendingLink,
                        settings = loaded,
                        settingsStore = settingsStore,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Family theme sync: re-read the ground the launcher broadcast may
        // have persisted while this process was dead or backgrounded. Runs
        // before the first frame, so launch composes with the synced ground.
        ThemeGroundState.refresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Standard launchMode starts a fresh instance for most VIEW links, so
        // this fires only on single-top relaunches (launcher re-tap,
        // shortcuts); handled so those links are not silently dropped either.
        pendingLink.value = parseDeepLink(intent)
    }
}

/**
 * The deep links §12 routes to this activity, decoded. The INSERT
 * vnd.android.cursor.dir/event and VIEW/EDIT item/event filters live on
 * EditorActivity — which reads its data URI directly — so only two shapes can
 * arrive here: a point-in-time link and an .ics VIEW.
 */
internal sealed interface DeepLink {
    /** content://com.android.calendar/time/<epoch-millis> — show that day. */
    data class Time(val epochMillis: Long) : DeepLink

    /**
     * ACTION_VIEW with type text/calendar (15.7). [uri] is the file the OS
     * already handed over; it is parsed and imported right here rather than
     * discarded with a "pick the file from there" detour.
     */
    data class ImportIcs(val uri: Uri?) : DeepLink
}

/**
 * Malformed or foreign data must never crash launch: anything unparseable is
 * dropped and the app opens normally at Schedule.
 */
internal fun parseDeepLink(intent: Intent?): DeepLink? = runCatching {
    if (intent?.action != Intent.ACTION_VIEW) return@runCatching null
    if (intent.type == "text/calendar") return@runCatching DeepLink.ImportIcs(intent.data)
    val data = intent.data ?: return@runCatching null
    if (data.host != "com.android.calendar") return@runCatching null
    timeLinkMillis(data)?.let(DeepLink::Time)
}.getOrNull()

/** Accepts /time/<millis> and the /time/epoch/<millis> spelling alike. */
private fun timeLinkMillis(data: Uri): Long? {
    val segments = data.pathSegments
    val millis = when {
        segments.size == 2 && segments[0] == "time" -> segments[1]
        segments.size == 3 && segments[0] == "time" && segments[1] == "epoch" -> segments[2]
        else -> return null
    }
    return millis.toLongOrNull()
}

private const val ROUTE_SCHEDULE = "schedule"
private const val ROUTE_DAY = "day"
private const val ROUTE_WEEK = "week"
private const val ROUTE_MONTH = "month"

/**
 * Navigation routes carrying occurrence identity (14.1). Every tap knows the
 * expanded instance's BEGIN; `?start=` threads it to DetailSheet and
 * EditorScreen so This-instance / This-and-following stamp the tapped
 * occurrence rather than the series anchor. A missing or unparseable start
 * falls back to the parent DTSTART — the pre-14.1 behavior — never a crash.
 * Internal so the mapping can be pinned by JVM tests.
 */
internal fun detailRoute(eventId: Long, instanceStartMillis: Long?): String =
    if (instanceStartMillis == null) "detail/$eventId" else "detail/$eventId?start=$instanceStartMillis"

internal fun editorRoute(
    eventId: Long,
    instanceStartMillis: Long?,
    duplicate: Boolean = false,
    dropStartMillis: Long? = null,
    dropEndMillis: Long? = null,
): String = buildString {
    append("editor/").append(eventId)
    append("?duplicate=").append(if (duplicate) "1" else "")
    if (instanceStartMillis != null) append("&start=").append(instanceStartMillis)
    if (dropStartMillis != null) append("&dropStart=").append(dropStartMillis)
    if (dropEndMillis != null) append("&dropEnd=").append(dropEndMillis)
}

/** Nav query params arrive as "" when defaulted; only real millis count. */
internal fun parseNavMillis(raw: String?): Long? =
    raw?.takeIf { it.isNotEmpty() }?.toLongOrNull()

/**
 * §8.6 `default view` -> navigation route opened at launch. Internal (not
 * private) so the last-view persistence test can pin the mapping without a
 * device.
 */
internal fun routeFor(view: DefaultView): String = when (view) {
    DefaultView.SCHEDULE -> ROUTE_SCHEDULE
    DefaultView.DAY -> ROUTE_DAY
    DefaultView.WEEK -> ROUTE_WEEK
    DefaultView.MONTH -> ROUTE_MONTH
}

/**
 * Permission gate first (design §10): without calendar access there is no UI
 * at all - one explanatory screen, one grant button, and on permanent denial
 * an exit into system app settings.
 */
@Composable
internal fun AppRoot(
    pending: MutableState<DeepLink?>,
    settings: AppSettings,
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(hasCalendarAccess(context)) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    val grantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 15.3: the launcher also asks for POST_NOTIFICATIONS; only calendar
        // access gates entry. A notification refusal must never lock the user
        // out of the app — Settings carries the warn row for that instead.
        hasAccess = hasCalendarAccess(context)
        requestedOnce = true
    }

    if (!hasAccess) {
        val activity = context as? ComponentActivity
        val permanentlyDenied = requestedOnce &&
            (
                activity == null ||
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.READ_CALENDAR,
                    )
                )
        PermissionGate(
            showSettingsExit = permanentlyDenied,
            onGrant = {
                // One dialog, one tap (design §10): reminders ride along with
                // the calendar grant. Below API 33 the framework grants it at
                // install time, so including it here is always safe.
                grantLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
                )
            },
            onOpenSettings = { context.openAppSettings() },
            modifier = modifier,
        )
        return
    }

    AppShell(
        pending = pending,
        settings = settings,
        settingsStore = settingsStore,
        modifier = modifier,
    )
}

@Composable
private fun PermissionGate(
    showSettingsExit: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCalendarColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.ink)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "XX",
                style = DayNumeral.copy(fontWeight = FontWeight.Bold),
                color = colors.signal,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .width(56.dp)
                    .height(4.dp)
                    .background(colors.signal),
            )
            Spacer(Modifier.height(32.dp))
            Text(
                "XX-Calendar renders your days through Android's calendar " +
                    "provider. It needs calendar access to show or store " +
                    "anything, and on Android 13+ permission to post its " +
                    "reminder notifications.\n\nThere is nothing else to set " +
                    "up - and no way for anything to leave this device.",
                style = Body,
                color = colors.text,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.emphasisBg,
                    contentColor = colors.emphasisFg,
                ),
            ) {
                Text("Grant calendar access", style = Body)
            }
            if (showSettingsExit) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Calendar access was declined in system settings. Enable " +
                        "it there to continue.",
                    style = Body,
                    color = colors.muted,
                )
                TextButton(onClick = onOpenSettings) {
                    Text("Open app settings", style = Body, color = colors.muted)
                }
            }
        }
    }
}

/**
 * The chrome every screen shares (design §8.1): top bar with a tappable
 * month/year opening the mini-month picker, a Today button only when off
 * today, one FAB with one action, and the calendar drawer. No bottom
 * navigation.
 */
@Composable
private fun AppShell(
    pending: MutableState<DeepLink?>,
    settings: AppSettings,
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val colors = LocalCalendarColors.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The schedule window lives at chrome level so the top bar (Today, the
    // mini-month picker) and the list act on one shared state.
    val scheduleWindow = remember { ScheduleWindowState() }
    val repository = remember { CalendarRepository(context.contentResolver) }

    // -------------------------------------------------- 15.7 .ics VIEW import
    // The URI the OS handed over is parsed here through the same bounded
    // reader / codec / target-picker pipeline as Settings' SAF import (§9),
    // instead of being discarded with a "pick the file from there" detour.
    var icsBusy by remember { mutableStateOf(false) }
    var pendingIcsImport by remember { mutableStateOf<PendingIcsImport?>(null) }

    fun reportIcsFailure(t: Throwable) {
        Toast.makeText(context, "✗ ${t.message ?: "operation failed"}", Toast.LENGTH_LONG).show()
    }

    fun insertIcsDrafts(calendarId: Long, staged: PendingIcsImport) {
        icsBusy = true
        scope.launch {
            try {
                val inserted = withContext(Dispatchers.IO) {
                    IcsExchange.insertDrafts(context.contentResolver, calendarId, staged.drafts)
                }
                Toast.makeText(
                    context,
                    "✓ " + buildList {
                        add("$inserted imported")
                        if (staged.duplicates > 0) add("${staged.duplicates} duplicates skipped")
                        if (staged.canceled > 0) add("${staged.canceled} canceled skipped")
                    }.joinToString(" · "),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (t: Throwable) {
                reportIcsFailure(t)
            } finally {
                icsBusy = false
            }
        }
    }

    fun beginIcsImport(uri: Uri) {
        if (icsBusy) return
        icsBusy = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.readBounded(uri, MAX_IMPORT_BYTES)
                }
                val knownUids = withContext(Dispatchers.IO) {
                    // Graceful degradation preserved, as in Settings: if the
                    // UID read fails, intra-file deduplication still applies.
                    runCatching { IcsExchange.knownUids(context.contentResolver) }
                        .getOrDefault(emptySet())
                }
                val parsed = withContext(Dispatchers.IO) { IcsCodec.parse(bytes, knownUids) }
                val candidates = parsed.events.filter { it.status != STATUS_CANCELED_INT }
                val canceledCount = parsed.events.size - candidates.size
                val writable = repository.calendars().filter { it.isWritable }
                when {
                    candidates.isEmpty() -> Toast.makeText(
                        context,
                        listOfNotNull(
                            "nothing to import",
                            parsed.skippedDuplicateUids
                                .takeIf { it > 0 }?.let { "$it duplicates" },
                            canceledCount.takeIf { it > 0 }?.let { "$it canceled" },
                        ).joinToString(" — "),
                        Toast.LENGTH_SHORT,
                    ).show()

                    writable.isEmpty() ->
                        reportIcsFailure(IllegalStateException("no writable calendar"))

                    // The same target-calendar choice the Settings flow makes;
                    // never guessed on the user's behalf behind a system tap.
                    else -> pendingIcsImport = PendingIcsImport(
                        drafts = candidates,
                        writableCalendars = writable,
                        duplicates = parsed.skippedDuplicateUids,
                        canceled = canceledCount,
                    )
                }
            } catch (t: Throwable) {
                reportIcsFailure(t)
            } finally {
                icsBusy = false
            }
        }
    }

    // §4.4 local-calendar bootstrap: this composable sits below the permission
    // gate, so running it here means "calendar access granted". Idempotent (it
    // no-ops when a writable calendar exists); a fresh install without DAVx⁵
    // gets the one local calendar the editor's Save needs. The editor and .ics
    // import both snapshot the writable list once — wait for the insert so
    // they cannot stick on "no calendar" by racing it.
    var calendarReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val id = runCatching { LocalCalendarBootstrap.ensureWritableCalendar(context) }
            .getOrNull()
        if (id == null) {
            Toast.makeText(context, "✗ could not create a local calendar", Toast.LENGTH_LONG)
                .show()
        }
        calendarReady = true
    }

    // §8.6 all-day notification: mirror the setting into the planner (whose
    // call path this file cannot re-route) and re-reconcile so already-planned
    // alarms converge onto the new anchor.
    LaunchedEffect(settings.allDayNotification) {
        if (ReminderPlanner.allDayPolicy != settings.allDayNotification) {
            ReminderPlanner.allDayPolicy = settings.allDayNotification
            runCatching { ReminderReconciler.reconcile(context) }
        }
    }

    // §12 deep links are consumed only here, below the permission gate: no
    // external URI can navigate or read anything before calendar access is
    // granted. A link that arrives pre-grant simply waits in [pending].
    // calendarReady is a key so an .ics VIEW does not consume the URI before
    // the local calendar exists.
    LaunchedEffect(pending.value, calendarReady) {
        if (!calendarReady) return@LaunchedEffect
        when (val link = pending.value ?: return@LaunchedEffect) {
            is DeepLink.Time -> {
                scheduleWindow.jumpTo(
                    Instant.ofEpochMilli(link.epochMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate(),
                )
                // The link lands on Schedule; when a non-default §8.6 view is
                // the start destination there is no schedule entry to pop to.
                if (!navController.popBackStack(ROUTE_SCHEDULE, inclusive = false)) {
                    navController.navigate(ROUTE_SCHEDULE) { launchSingleTop = true }
                }
            }
            is DeepLink.ImportIcs -> {
                // 15.7: the file is already in hand — import it. A type-only
                // VIEW with no data cannot be read; say so rather than
                // pretending the import happened.
                val uri = link.uri
                if (uri == null) {
                    Toast.makeText(
                        context,
                        "✗ no .ics file was handed over",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    beginIcsImport(uri)
                }
            }
        }
        pending.value = null
    }

    if (!calendarReady) {
        Box(modifier.fillMaxSize().background(colors.ink))
        return
    }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = { CalendarDrawer(repository) },
    ) {
        Scaffold(
            containerColor = colors.ink,
            topBar = { CalendarTopBar(navController, scheduleWindow, settings, settingsStore) },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { navController.navigate("editor/null-placeholder") },
                    containerColor = colors.emphasisBg,
                    contentColor = colors.emphasisFg,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New event")
                }
            },
        ) { padding ->
            // §8.6 default view — which, since every top-bar view switch also
            // writes it, is the view the user was last in: launch reopens
            // there. Captured on first composition (this composable only
            // exists once settings have loaded), so neither a settings edit
            // nor the switcher's own writes yank the user out of the view
            // they are in mid-session.
            val startDestination = remember { routeFor(settings.defaultView) }
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                composable(ROUTE_SCHEDULE) {
                    ScheduleScreen(
                        // Schedule taps thread the tapped occurrence's BEGIN
                        // like every other view (handoff note a / 14.1).
                        onEventClick = { id, start ->
                            navController.navigate(detailRoute(id, start))
                        },
                        modifier = Modifier.fillMaxSize(),
                        state = scheduleWindow,
                    )
                }
                composable(ROUTE_DAY) {
                    DayScreen(
                        Modifier.fillMaxSize(),
                        onNavigate = { navController.navigate(it) },
                    )
                }
                composable(ROUTE_WEEK) {
                    WeekScreen(
                        Modifier.fillMaxSize(),
                        // §8.6 start day of week (15.6), same as MonthScreen.
                        firstDayOfWeek = DayOfWeek.valueOf(settings.startDayOfWeek.name),
                        onNavigate = { navController.navigate(it) },
                    )
                }
                composable(ROUTE_MONTH) {
                    MonthScreen(
                        Modifier.fillMaxSize(),
                        showWeekNumbers = settings.weekNumbers,
                        firstDayOfWeek = DayOfWeek.valueOf(settings.startDayOfWeek.name),
                        // Peek taps open the detail sheet (15.1), carrying the
                        // tapped occurrence's begin (14.1).
                        onEventClick = { id, start ->
                            navController.navigate(detailRoute(id, start))
                        },
                    )
                }
                // WS7: the real detail sheet. Ids arrive as strings and are
                // parsed defensively - a malformed id renders nothing rather
                // than crashing navigation. ?start= carries the tapped
                // occurrence's begin (14.1); absent, DetailSheet falls back to
                // the series anchor.
                composable(
                    route = "detail/{eventId}?start={start}",
                    arguments = listOf(
                        navArgument("eventId") { type = NavType.StringType },
                        navArgument("start") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { entry ->
                    val eventId = entry.arguments?.getString("eventId")?.toLongOrNull()
                    if (eventId != null) {
                        DetailSheet(
                            eventId = eventId,
                            instanceStartMillis =
                                parseNavMillis(entry.arguments?.getString("start")),
                            repository = repository,
                            onClose = { navController.popBackStack() },
                            onEdit = { id, start ->
                                navController.navigate(editorRoute(id, start))
                            },
                            onDuplicate = { id, start ->
                                navController.navigate(editorRoute(id, start, duplicate = true))
                            },
                        )
                    }
                }
                // Create gesture from the day/week grids: pinned initial times.
                composable(
                    route = "editor/new?start={start}&end={end}",
                    arguments = listOf(
                        navArgument("start") { type = NavType.StringType },
                        navArgument("end") { type = NavType.StringType },
                    ),
                ) { entry ->
                    EditorScreen(
                        eventId = null,
                        initialStartMillis = entry.arguments?.getString("start")?.toLongOrNull(),
                        initialEndMillis = entry.arguments?.getString("end")?.toLongOrNull(),
                        onClose = { navController.popBackStack() },
                    )
                }
                // Edit an existing row; ?duplicate=1 loads fields but strips
                // exception linkage so the save inserts a fresh event.
                // ?start= prefills from the tapped occurrence (14.1).
                composable(
                    route = "editor/{eventId}?duplicate={duplicate}&start={start}" +
                        "&dropStart={dropStart}&dropEnd={dropEnd}",
                    arguments = listOf(
                        navArgument("eventId") { type = NavType.StringType },
                        navArgument("duplicate") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("start") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("dropStart") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("dropEnd") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { entry ->
                    EditorScreen(
                        eventId = entry.arguments?.getString("eventId")?.toLongOrNull(),
                        duplicate = entry.arguments?.getString("duplicate") == "1",
                        instanceStartMillis =
                            parseNavMillis(entry.arguments?.getString("start")),
                        dropStartMillis =
                            parseNavMillis(entry.arguments?.getString("dropStart")),
                        dropEndMillis =
                            parseNavMillis(entry.arguments?.getString("dropEnd")),
                        onClose = { navController.popBackStack() },
                    )
                }
            }
        }

        // 15.7: target-calendar choice for an .ics handed over by a VIEW
        // intent — the same sheet the Settings import shows.
        pendingIcsImport?.let { staged ->
            WritableCalendarPickerSheet(
                calendars = staged.writableCalendars,
                onPick = { calendarId ->
                    pendingIcsImport = null
                    insertIcsDrafts(calendarId, staged)
                },
                onDismiss = { pendingIcsImport = null },
            )
        }
    }
}

/**
 * Events parsed from an OS-handed .ics VIEW, awaiting a target calendar:
 * the drafts plus what was skipped so the toast can say so. Mirrors
 * SettingsScreen's PendingImport; kept separate because it also carries the
 * writable-calendar list this screen resolved.
 */
private data class PendingIcsImport(
    val drafts: List<IcsCodec.IcsEventDraft>,
    val writableCalendars: List<CalendarSummary>,
    val duplicates: Int,
    val canceled: Int,
)

/**
 * The top-bar view switcher's menu, keyed by [DefaultView] rather than raw
 * routes so the entry the user taps is also the value persisted as the
 * last-used view — one list cannot drift from the other. Internal (not
 * private) so the last-view persistence test can prove it covers every
 * [DefaultView] without a device.
 */
internal val VIEWS = listOf(
    DefaultView.SCHEDULE to "Schedule",
    DefaultView.DAY to "Day",
    DefaultView.WEEK to "Week",
    DefaultView.MONTH to "Month",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTopBar(
    navController: NavController,
    scheduleWindow: ScheduleWindowState,
    settings: AppSettings,
    settingsStore: SettingsStore,
) {
    val colors = LocalCalendarColors.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    TopAppBar(
        title = {
            Text(
                currentMonthYear(),
                style = MonthHeader,
                color = colors.text,
                modifier = Modifier.clickable { pickerOpen = true },
            )
        },
        actions = {
            if (!scheduleWindow.onCurrentMonth()) {
                TextButton(
                    onClick = { scheduleWindow.jumpTo(LocalDate.now()) },
                ) {
                    Text("Today", style = Body, color = colors.text)
                }
            }
            // Search renders inert this wave; deferred to a later workstream.
            IconButton(onClick = {}, enabled = false) {
                Icon(Icons.Default.Search, contentDescription = null, tint = colors.muted)
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Views")
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                VIEWS.forEach { (view, label) ->
                    val route = routeFor(view)
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (route == currentRoute) "✓ $label" else label,
                                style = Body,
                                color = if (route == currentRoute) colors.text else colors.muted,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            switchView(navController, route)
                            // Last-view persistence: the view you switch to is
                            // the view the next launch opens ("if I am in month
                            // view, it should open that way"). Written through
                            // the §8.6 `default view` key on purpose — launch
                            // route and last-used view are one fact, so the
                            // Settings row always shows what will actually
                            // open, and backup/restore (§9) carries it free.
                            scope.launch { settingsStore.setDefaultView(view) }
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.ink,
            titleContentColor = colors.text,
            actionIconContentColor = colors.strong,
        ),
    )

    if (pickerOpen) {
        MiniMonthPickerSheet(
            initialMonth = scheduleWindow.pickerMonth(),
            firstDayOfWeek = DayOfWeek.valueOf(settings.startDayOfWeek.name),
            onPick = { date ->
                scheduleWindow.jumpTo(date)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * The mini-month picker (§8.1): one month grid with prev/next chevrons;
 * picking a day jumps the schedule window. Lean by design. First column
 * follows §8.6's start-day-of-week, like the month view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniMonthPickerSheet(
    initialMonth: YearMonth,
    firstDayOfWeek: DayOfWeek,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    var shown by remember { mutableStateOf(initialMonth) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.inkRaised) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            IconButton(onClick = { shown = shown.minusMonths(1) }) {
                Icon(Icons.Default.ChevronLeft, "Previous month", tint = colors.strong)
            }
            Text(
                "${shown.month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault()).uppercase(Locale.getDefault())} ${shown.year}",
                style = MonthHeader,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { shown = shown.plusMonths(1) }) {
                Icon(Icons.Default.ChevronRight, "Next month", tint = colors.strong)
            }
        }
        Spacer(Modifier.height(8.dp))
        val leading = (shown.atDay(1).dayOfWeek.value + 7 - firstDayOfWeek.value) % 7
        val cells: List<LocalDate?> =
            List(leading) { null } + (1..shown.lengthOfMonth()).map { shown.atDay(it) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            val today = LocalDate.now()
            items(cells) { date ->
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (date != null) {
                        // Today renders as the inverted block - the one
                        // full-white element of the picker (§7.1).
                        val isToday = date == today
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (isToday) colors.emphasisBg else Color.Transparent)
                                .clickable { onPick(date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${date.dayOfMonth}",
                                fontFamily = SpaceMono,
                                fontSize = 15.sp,
                                color = if (isToday) colors.emphasisFg else colors.strong,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

private fun switchView(navController: NavController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun currentMonthYear(): String {
    val date = LocalDate.now()
    val month = date.month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
    return "$month ${date.year}".uppercase(Locale.getDefault())
}

private fun hasCalendarAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
