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
import com.piercingxx.calendar.settings.AppBackground
import com.piercingxx.calendar.settings.AppFont
import com.piercingxx.calendar.settings.DefaultView
import com.piercingxx.calendar.settings.Density
import com.piercingxx.calendar.settings.Settings as AppSettings
import com.piercingxx.calendar.settings.SettingsStore
import com.piercingxx.calendar.ui.day.DayScreen
import com.piercingxx.calendar.ui.detail.DetailSheet
import com.piercingxx.calendar.ui.drawer.CalendarDrawer
import com.piercingxx.calendar.ui.editor.EditorScreen
import com.piercingxx.calendar.ui.month.MonthScreen
import com.piercingxx.calendar.ui.schedule.ScheduleScreen
import com.piercingxx.calendar.ui.schedule.ScheduleWindowState
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.DayNumeral
import com.piercingxx.calendar.ui.theme.MonthHeader
import com.piercingxx.calendar.ui.theme.SpaceMono
import com.piercingxx.calendar.ui.theme.CalendarTheme
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import com.piercingxx.calendar.ui.theme.calendarColors
import com.piercingxx.calendar.ui.week.WeekScreen
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

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
                            .background(calendarColors().ink),
                    )
                } else {
                    AppRoot(pending = pendingLink, settings = loaded)
                }
            }
        }
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

    /** ACTION_VIEW with type text/calendar — point the user at the import flow. */
    data object ImportIcs : DeepLink
}

/**
 * Malformed or foreign data must never crash launch: anything unparseable is
 * dropped and the app opens normally at Schedule.
 */
internal fun parseDeepLink(intent: Intent?): DeepLink? = runCatching {
    if (intent?.action != Intent.ACTION_VIEW) return@runCatching null
    if (intent.type == "text/calendar") return@runCatching DeepLink.ImportIcs
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

/** §8.6 `default view` -> navigation route opened at launch. */
private fun routeFor(view: DefaultView): String = when (view) {
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(hasCalendarAccess(context)) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    val grantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasAccess = grants.isNotEmpty() && grants.values.all { it }
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
                grantLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                    ),
                )
            },
            onOpenSettings = { context.openAppSettings() },
            modifier = modifier,
        )
        return
    }

    AppShell(pending = pending, settings = settings, modifier = modifier)
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
                    "anything.\n\nThere is nothing else to set up - and no " +
                    "way for anything to leave this device.",
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
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val colors = LocalCalendarColors.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // The schedule window lives at chrome level so the top bar (Today, the
    // mini-month picker) and the list act on one shared state.
    val scheduleWindow = remember { ScheduleWindowState() }
    val repository = remember { CalendarRepository(context.contentResolver) }

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
    LaunchedEffect(pending.value) {
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
            DeepLink.ImportIcs -> {
                // Deferred honesty: WS10's import lives inside SettingsScreen
                // behind a SAF picker and cannot accept a foreign URI yet, so
                // open Settings — where "import .ics" lives — rather than
                // wiring the file through.
                context.startActivity(Intent(context, SettingsActivity::class.java))
                Toast.makeText(
                    context,
                    ".ics import lives in Settings - pick the file from there",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        pending.value = null
    }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = { CalendarDrawer(repository) },
    ) {
        Scaffold(
            containerColor = colors.ink,
            topBar = { CalendarTopBar(navController, scheduleWindow, settings) },
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
            // §8.6 default view: captured on first composition (this composable
            // only exists once settings have loaded), so a later settings edit
            // never yanks the user out of the view they are in.
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
                        onEventClick = { id -> navController.navigate("detail/$id") },
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
                        onNavigate = { navController.navigate(it) },
                    )
                }
                composable(ROUTE_MONTH) {
                    MonthScreen(
                        Modifier.fillMaxSize(),
                        showWeekNumbers = settings.weekNumbers,
                        firstDayOfWeek = DayOfWeek.valueOf(settings.startDayOfWeek.name),
                    )
                }
                // WS7: the real detail sheet. Ids arrive as strings and are
                // parsed defensively - a malformed id renders nothing rather
                // than crashing navigation.
                composable(
                    route = "detail/{eventId}",
                    arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
                ) { entry ->
                    val eventId = entry.arguments?.getString("eventId")?.toLongOrNull()
                    if (eventId != null) {
                        DetailSheet(
                            eventId = eventId,
                            repository = repository,
                            onClose = { navController.popBackStack() },
                            onEdit = { id -> navController.navigate("editor/$id") },
                            onDuplicate = { id -> navController.navigate("editor/$id?duplicate=1") },
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
                composable(
                    route = "editor/{eventId}?duplicate={duplicate}",
                    arguments = listOf(
                        navArgument("eventId") { type = NavType.StringType },
                        navArgument("duplicate") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { entry ->
                    EditorScreen(
                        eventId = entry.arguments?.getString("eventId")?.toLongOrNull(),
                        duplicate = entry.arguments?.getString("duplicate") == "1",
                        onClose = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

private val VIEWS = listOf(
    ROUTE_SCHEDULE to "Schedule",
    ROUTE_DAY to "Day",
    ROUTE_WEEK to "Week",
    ROUTE_MONTH to "Month",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTopBar(
    navController: NavController,
    scheduleWindow: ScheduleWindowState,
    settings: AppSettings,
) {
    val colors = LocalCalendarColors.current
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
                VIEWS.forEach { (route, label) ->
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
