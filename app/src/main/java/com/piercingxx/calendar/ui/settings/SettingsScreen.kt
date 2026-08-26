package com.piercingxx.calendar.ui.settings

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.piercingxx.calendar.alarm.AlarmScheduler
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.settings.AllDayNotification
import com.piercingxx.calendar.settings.AutoAddedFilterMode
import com.piercingxx.calendar.settings.BackupJson
import com.piercingxx.calendar.settings.BackupRead
import com.piercingxx.calendar.settings.BackupSnapshot
import com.piercingxx.calendar.settings.DefaultView
import com.piercingxx.calendar.settings.Density
import com.piercingxx.calendar.settings.IcsCodec
import com.piercingxx.calendar.settings.IcsExchange
import com.piercingxx.calendar.settings.SettingsStore
import com.piercingxx.calendar.settings.Settings as AppSettings
import com.piercingxx.calendar.settings.SigilStore
import com.piercingxx.calendar.settings.StartDayOfWeek
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.CalendarColors
import com.piercingxx.calendar.ui.theme.JetBrainsMono
import com.piercingxx.calendar.ui.theme.Label
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** DAVx⁵ is the sync path (§4.1); surfaced once here, never nagged elsewhere. */
private const val DAVDROID_PACKAGE = "at.bitfire.davdroid"

/**
 * Settings — the sixteen survivors (design §8.6), one screen, sectioned.
 * Quiet defaults throughout (D12); the ⚠ exact-alarms row appears only when
 * the permission is denied and is the only warn-coloured thing in the app.
 */
@Composable
fun SettingsScreen() {
    val colors = LocalCalendarColors.current
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember { CalendarRepository(context.contentResolver) }
    val settingsStore = remember { SettingsStore(appContext) }
    val sigilStore = remember { SigilStore(appContext) }
    val alarmScheduler = remember { AlarmScheduler(appContext) }
    val scope = rememberCoroutineScope()

    val settings by settingsStore.settings.collectAsState(initial = AppSettings())

    var calendars by remember { mutableStateOf<List<CalendarSummary>>(emptyList()) }
    var sigils by remember { mutableStateOf<Map<CalendarKey, SigilTier>>(emptyMap()) }
    var exactAlarmsDenied by remember { mutableStateOf(!alarmScheduler.canScheduleExactAlarms()) }
    // 15.3: ReminderReceiver silently drops reminders without the runtime
    // grant on API 33+, so denial surfaces here exactly like exact alarms.
    var notificationsDenied by remember { mutableStateOf(!notificationsGranted(appContext)) }
    var lastChange by remember { mutableStateOf<Long?>(null) }
    var pickingSigilFor by remember { mutableStateOf<CalendarKey?>(null) }
    // DATA (WS10) state: SAF work in flight, a parsed import awaiting its
    // target-calendar pick, and a parsed backup awaiting its overwrite
    // confirmation. Lives here because both the rows below and the picker
    // sheet at the bottom of the screen need it.
    var dataBusy by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var pendingRestore by remember { mutableStateOf<BackupSnapshot?>(null) }

    fun reportFailure(t: Throwable) {
        Toast.makeText(
            context,
            "✗ ${t.message ?: "operation failed"}",
            Toast.LENGTH_LONG,
        ).show()
    }

    val exportIcsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        if (uri != null && !dataBusy) {
            dataBusy = true
            scope.launch {
                try {
                    val events = withContext(Dispatchers.IO) {
                        IcsExchange.collectExportEvents(appContext.contentResolver)
                    }
                    val bytes = IcsCodec.export(events)
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: error("could not open the chosen file")
                    }
                    Toast.makeText(
                        context,
                        "✓ exported ${events.size} events",
                        Toast.LENGTH_SHORT,
                    ).show()
                } catch (t: Throwable) {
                    reportFailure(t)
                } finally {
                    dataBusy = false
                }
            }
        }
    }

    val importIcsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && !dataBusy) {
            dataBusy = true
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        appContext.contentResolver.readBounded(uri, MAX_IMPORT_BYTES)
                    }
                    val knownUids = withContext(Dispatchers.IO) {
                        // Graceful degradation preserved: if the UID read fails,
                        // intra-file deduplication still applies.
                        runCatching { IcsExchange.knownUids(appContext.contentResolver) }
                            .getOrDefault(emptySet())
                    }
                    val parsed = withContext(Dispatchers.IO) {
                        IcsCodec.parse(bytes, knownUids)
                    }
                    calendars = repository.calendars()
                    val candidates =
                        parsed.events.filter { it.status != STATUS_CANCELED_INT }
                    val canceledCount = parsed.events.size - candidates.size
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

                        calendars.none { it.isWritable } ->
                            reportFailure(IllegalStateException("no writable calendar"))

                        else -> pendingImport =
                            PendingImport(candidates, parsed.skippedDuplicateUids, canceledCount)
                    }
                } catch (t: Throwable) {
                    reportFailure(t)
                } finally {
                    dataBusy = false
                }
            }
        }
    }

    val backupJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null && !dataBusy) {
            dataBusy = true
            scope.launch {
                try {
                    val snapshot = BackupSnapshot(settingsStore.current(), sigilStore.load())
                    val bytes = BackupJson.write(snapshot.settings, snapshot.sigils)
                        .toByteArray(Charsets.UTF_8)
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: error("could not open the chosen file")
                    }
                    Toast.makeText(
                        context,
                        "✓ backed up settings + ${snapshot.sigils.size} sigils",
                        Toast.LENGTH_SHORT,
                    ).show()
                } catch (t: Throwable) {
                    reportFailure(t)
                } finally {
                    dataBusy = false
                }
            }
        }
    }

    val restoreJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && !dataBusy) {
            dataBusy = true
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        appContext.contentResolver.readBounded(uri, MAX_IMPORT_BYTES)
                            .toString(Charsets.UTF_8)
                    }
                    when (val read = BackupJson.read(text)) {
                        // Nothing applied yet: the dialog below owns the go/no-go,
                        // stating exactly what a restore overwrites before it does.
                        is BackupRead.Ok -> pendingRestore = read.snapshot
                        BackupRead.Corrupt -> reportFailure(
                            IllegalStateException("unreadable backup — nothing changed"),
                        )
                    }
                } catch (t: Throwable) {
                    reportFailure(t)
                } finally {
                    dataBusy = false
                }
            }
        }
    }

    // 15.3 [fix] path for the notifications warn row; the result is re-read
    // by the ON_RESUME observer, so this callback deliberately ignores it.
    val postNotificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // DAVx⁵ launcher intent; null when not installed → row hidden entirely.
    val davdroidIntent = remember {
        runCatching { appContext.packageManager.getLaunchIntentForPackage(DAVDROID_PACKAGE) }
            .getOrNull()
    }

    suspend fun reloadCalendars() {
        calendars = repository.calendars()
        sigils = sigilStore.load()
        lastChange = repository.lastProviderChange()
    }

    LaunchedEffect(Unit) { reloadCalendars() }

    // The exact-alarm grant lives in system settings and the notification
    // grant can be flipped in system settings too: re-check both on every
    // resume so [fix] round-trips actually update these rows.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAlarmsDenied = !alarmScheduler.canScheduleExactAlarms()
                notificationsDenied = !notificationsGranted(appContext)
                scope.launch { reloadCalendars() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        // ---------------------------------------------------------- VIEW

        Section("VIEW")
        // Shares its key with last-view persistence: switching views in the
        // top bar rewrites `default view`, so this row always reads as the
        // view the next launch will open — cycling it here still works, it
        // just holds only until the next in-app view switch.
        ValueRow(
            label = "default view",
            value = settings.defaultView.displayName(),
            onClick = {
                val next = DefaultView.entries[(settings.defaultView.ordinal + 1) % DefaultView.entries.size]
                scope.launch { settingsStore.setDefaultView(next) }
            },
        )
        ValueRow(
            label = "start day of week",
            value = settings.startDayOfWeek.displayName(),
            onClick = {
                val next = StartDayOfWeek.entries[
                    (settings.startDayOfWeek.ordinal + 1) % StartDayOfWeek.entries.size,
                ]
                scope.launch { settingsStore.setStartDayOfWeek(next) }
            },
        )
        CheckRow("week numbers", settings.weekNumbers) {
            scope.launch { settingsStore.setWeekNumbers(it) }
        }
        CheckRow(
            "show declined events",
            settings.showDeclined,
            annotation = "off, unlike Google",
        ) {
            scope.launch { settingsStore.setShowDeclined(it) }
        }
        // dim past: row hidden — the time grids (TimeGrid.kt) still shade
        // occurrences unconditionally, so the toggle cannot yet do what it
        // says everywhere. The key keeps persisting for backup round-trips.
        ValueRow(
            label = "density",
            value = settings.density.displayName(),
            onClick = {
                val next =
                    if (settings.density == Density.COMFORTABLE) Density.COMPACT else Density.COMFORTABLE
                scope.launch { settingsStore.setDensity(next) }
            },
        )

        // -------------------------------------------------------- EVENTS

        Section("EVENTS")
        ValueRow(
            label = "default duration",
            value = "${settings.defaultDurationMin} min",
            onClick = {
                val steps = intArrayOf(15, 30, 45, 60, 90, 120)
                val i = steps.indexOf(settings.defaultDurationMin)
                val next = if (i < 0) 30 else steps[(i + 1) % steps.size]
                scope.launch { settingsStore.setDefaultDurationMin(next) }
            },
        )
        ValueRow(
            label = "default notification",
            value = "${settings.defaultNotificationMin} min before",
            onClick = {
                val steps = intArrayOf(0, 5, 10, 15, 30, 60)
                val i = steps.indexOf(settings.defaultNotificationMin)
                val next = if (i < 0) 10 else steps[(i + 1) % steps.size]
                scope.launch { settingsStore.setDefaultNotificationMin(next) }
            },
        )
        ValueRow(
            label = "all-day notification",
            value = allDayLabel(settings.allDayNotification),
            onClick = {
                // Cycle the shipped anchors: hour-of-day first, then the
                // days-before step.
                val i = ALL_DAY_PRESETS.indexOf(settings.allDayNotification)
                val next = if (i < 0) {
                    AllDayNotification()
                } else {
                    ALL_DAY_PRESETS[(i + 1) % ALL_DAY_PRESETS.size]
                }
                scope.launch { settingsStore.setAllDayNotification(next) }
            },
        )

        // ------------------------------------------------- NOTIFICATIONS

        Section("NOTIFICATIONS")
        CheckRow(
            "show event title on lock screen",
            settings.lockScreenTitle,
            annotation = "off, the lock screen shows only the time",
        ) {
            scope.launch { settingsStore.setLockScreenTitle(it) }
        }
        // daily agenda: row hidden — wiring it needs a whole daily digest
        // scheduler that does not exist yet; a toggle with nothing behind it
        // is exactly what this screen must not ship. The key persists.
        CheckRow("heads-up alerts", settings.headsUp) {
            scope.launch { settingsStore.setHeadsUp(it) }
        }
        if (exactAlarmsDenied) {
            ExactAlarmsWarnRow(
                onFix = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            context.startActivity(
                                Intent(SystemSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(android.net.Uri.fromParts("package", context.packageName, null)),
                            )
                        } catch (_: Exception) {
                            // No resolver for the system screen; stay quiet (§10: never nagged).
                        }
                    }
                },
            )
        }
        if (notificationsDenied) {
            NotificationsWarnRow(
                onFix = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }

        // ----------------------------------------------------- CALENDARS

        Section("CALENDARS")
        calendars.forEach { calendar ->
            val key = CalendarKey(calendar.id, calendar.accountName ?: "")
            CalendarSettingRow(
                calendar = calendar,
                tier = sigils[key],
                onPickSigil = { pickingSigilFor = key },
                onToggleVisible = { visible ->
                    scope.launch {
                        repository.setVisible(calendar.id, visible)
                        calendars = repository.calendars()
                    }
                },
            )
        }
        if (calendars.isEmpty()) {
            Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("no calendars", style = Body, color = colors.shade)
            }
        }
        CheckRow(
            "hide auto-added events",
            settings.hideAutoAdded && settings.autoAddedFilterMode != AutoAddedFilterMode.OFF,
            annotation = "Gmail bookings (§4.5)",
        ) { enabled ->
            scope.launch {
                if (enabled) {
                    // Restore prior fidelity; default to the full detector.
                    if (settings.autoAddedFilterMode == AutoAddedFilterMode.OFF) {
                        settingsStore.setAutoAddedFilterMode(AutoAddedFilterMode.METADATA)
                    }
                    if (!settings.hideAutoAdded) settingsStore.setHideAutoAdded(true)
                } else {
                    // Keep the mode so re-enabling restores it; just gate off.
                    settingsStore.setHideAutoAdded(false)
                }
            }
        }

        // ----------------------------------------------------- APPEARANCE

        // background/font: rows hidden — one variant ships this wave (§7), so
        // there is nothing for a control to switch; both keys persist.
        Section("APPEARANCE")
        ValueRow(
            label = "text size",
            value = "${(settings.textSizeScale * 100).toInt()}%",
            onClick = {
                val i = TEXT_SIZE_STEPS.indexOf(settings.textSizeScale)
                val next = if (i < 0) 1.0f else TEXT_SIZE_STEPS[(i + 1) % TEXT_SIZE_STEPS.size]
                scope.launch { settingsStore.setTextSizeScale(next) }
            },
        )

        // ----------------------------------------------------------- SYNC

        Section("SYNC")
        ValueRow(label = "last change", value = relativeAge(lastChange))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Google sync is handled by DAVx⁵.",
                style = Body,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
            if (davdroidIntent != null) {
                TextButton(onClick = {
                    try {
                        context.startActivity(davdroidIntent)
                    } catch (_: Exception) {
                        // The package vanished between check and tap. Nothing to do.
                    }
                }) {
                    Text("[open DAVx⁵]", style = Body, color = colors.text)
                }
            }
        }

        // ----------------------------------------------------------- DATA

        // WS10: .ics interchange and JSON backup (design §9). All four rows
        // go through SAF; every failure lands as a ✗ toast and nothing else
        // in the app changes state (§10 posture). Launchers and their state
        // live at the top of the composable so the picker sheet below the
        // Column can share them.
        Section("DATA")
        ValueRow(
            label = "import .ics",
            value = null,
            enabled = !dataBusy,
            onClick = {
                importIcsLauncher.launch(arrayOf("text/calendar", "application/ics", "text/plain"))
            },
        )
        ValueRow(
            label = "export .ics",
            value = null,
            enabled = !dataBusy,
            onClick = {
                exportIcsLauncher.launch(suggestedFileName("xx-calendar", "ics"))
            },
        )
        ValueRow(
            label = "backup to JSON",
            value = null,
            enabled = !dataBusy,
            onClick = {
                backupJsonLauncher.launch(suggestedFileName("xx-calendar-backup", "json"))
            },
        )
        ValueRow(
            label = "restore from JSON",
            value = null,
            enabled = !dataBusy,
            onClick = {
                restoreJsonLauncher.launch(arrayOf("application/json", "text/json"))
            },
        )
    }

    pickingSigilFor?.let { key ->
        SigilPickerSheet(
            current = sigils[key],
            onPick = { tier ->
                pickingSigilFor = null
                scope.launch {
                    val updated = HashMap(sigils)
                    updated[key] = tier
                    sigilStore.save(updated)
                    sigils = sigilStore.load()
                }
            },
            onDismiss = { pickingSigilFor = null },
        )
    }

    pendingImport?.let { pending ->
        WritableCalendarPickerSheet(
            calendars = calendars.filter { it.isWritable },
            onPick = { calendarId ->
                pendingImport = null
                dataBusy = true
                scope.launch {
                    try {
                        val inserted = withContext(Dispatchers.IO) {
                            IcsExchange.insertDrafts(appContext.contentResolver, calendarId, pending.drafts)
                        }
                        val notes = buildList {
                            add("$inserted imported")
                            if (pending.duplicates > 0) add("${pending.duplicates} duplicates skipped")
                            if (pending.canceled > 0) add("${pending.canceled} canceled skipped")
                        }
                        Toast.makeText(context, "✓ " + notes.joinToString(" · "), Toast.LENGTH_SHORT)
                            .show()
                    } catch (t: Throwable) {
                        reportFailure(t)
                    } finally {
                        dataBusy = false
                    }
                }
            },
            onDismiss = { pendingImport = null },
        )
    }

    pendingRestore?.let { snapshot ->
        RestoreConfirmDialog(
            sigilCount = snapshot.sigils.size,
            onCancel = { pendingRestore = null },
            onConfirm = {
                pendingRestore = null
                dataBusy = true
                scope.launch {
                    try {
                        applyRestoredSettings(settingsStore, snapshot.settings)
                        sigilStore.save(snapshot.sigils)
                        sigils = sigilStore.load()
                        Toast.makeText(
                            context,
                            "✓ restored settings + ${snapshot.sigils.size} sigils",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } catch (t: Throwable) {
                        reportFailure(t)
                    } finally {
                        dataBusy = false
                    }
                }
            },
        )
    }
}

// ------------------------------------------------------------- section parts

@Composable
private fun Section(text: String) {
    val colors = LocalCalendarColors.current
    Text(
        text,
        style = Label,
        color = colors.muted,
        modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 8.dp),
    )
}

/** `label …… value` — label left, value right; whole row tappable when [onClick] is set. */
@Composable
private fun ValueRow(
    label: String,
    value: String?,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalCalendarColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Body, color = if (enabled) colors.text else colors.shade)
        Spacer(Modifier.weight(1f))
        if (value != null) {
            Text(value, style = Body, color = colors.muted)
        }
    }
}

/** `label …… ▣ / ▢` with the checkbox glyph as the control (§8.6's notation, rendered). */
@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    annotation: String? = null,
    onChecked: (Boolean) -> Unit,
) {
    val colors = LocalCalendarColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Body, color = colors.text, modifier = Modifier.weight(1f))
            Text(
                if (checked) "▣" else "▢",
                fontFamily = JetBrainsMono,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (checked) colors.text else colors.shade,
            )
        }
        if (annotation != null) {
            Text(annotation, style = Label, color = colors.shade)
        }
    }
}

/** The one warn-coloured thing in the app (§8.6), present only while denied. */
@Composable
private fun ExactAlarmsWarnRow(onFix: () -> Unit) {
    val colors = LocalCalendarColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "⚠ exact alarms are denied — reminders will be late",
            style = Body,
            color = colors.warn,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onFix) {
            Text("[fix]", style = Body, color = colors.warn)
        }
    }
}

/**
 * 15.3: same warn language as [ExactAlarmsWarnRow] — without POST_NOTIFICATIONS
 * on API 33+ ReminderReceiver drops every reminder silently, which is worse
 * than late.
 */
@Composable
private fun NotificationsWarnRow(onFix: () -> Unit) {
    val colors = LocalCalendarColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "⚠ notifications are denied — reminders cannot be shown",
            style = Body,
            color = colors.warn,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onFix) {
            Text("[fix]", style = Body, color = colors.warn)
        }
    }
}

@Composable
private fun CalendarSettingRow(
    calendar: CalendarSummary,
    tier: SigilTier?,
    onPickSigil: () -> Unit,
    onToggleVisible: (Boolean) -> Unit,
) {
    val colors = LocalCalendarColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tier?.glyph ?: "·",
            fontFamily = JetBrainsMono,
            fontSize = 15.sp,
            color = tier?.rampColor(colors) ?: colors.shade,
            modifier = Modifier.clickable(onClick = onPickSigil),
        )
        Text(
            calendar.displayName,
            style = Body,
            color = if (calendar.isVisible) colors.text else colors.shade,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        )
        Text("visible ", style = Label, color = colors.muted)
        Text(
            if (calendar.isVisible) "▣" else "▢",
            fontFamily = JetBrainsMono,
            fontSize = 16.sp,
            color = if (calendar.isVisible) colors.text else colors.shade,
            modifier = Modifier.clickable { onToggleVisible(!calendar.isVisible) },
        )
    }
}

/** Six glyphs, six tiers (§7.1) — an override writes straight through [SigilStore]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SigilPickerSheet(
    current: SigilTier?,
    onPick: (SigilTier) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.inkRaised) {
        Text(
            "SIGIL",
            style = Label,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        SigilTier.entries.forEach { tier ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(tier) }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    tier.glyph,
                    fontFamily = JetBrainsMono,
                    fontSize = 18.sp,
                    color = tier.rampColor(colors),
                    modifier = Modifier.width(28.dp),
                )
                Text(tier.rampName, style = Body, color = colors.text)
                if (tier == current) {
                    Spacer(Modifier.weight(1f))
                    Text("✓", fontFamily = JetBrainsMono, color = colors.signal)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// -------------------------------------------------------------------- helpers

/** §7.1: each tier renders its glyph at its named stop on the white ramp. */
private fun SigilTier.rampColor(colors: CalendarColors): androidx.compose.ui.graphics.Color =
    when (rampName) {
        "text" -> colors.text
        "strong" -> colors.strong
        "muted" -> colors.muted
        else -> colors.shade
    }

private fun DefaultView.displayName(): String = name.lowercase(Locale.ROOT)

private fun StartDayOfWeek.displayName(): String = name.lowercase(Locale.ROOT)

private fun Density.displayName(): String = name.lowercase(Locale.ROOT)

/** §8.6 all-day anchors the row cycles through: hour first, then days before. */
private val ALL_DAY_PRESETS = listOf(
    AllDayNotification(hourOfDay = 18, daysBefore = 1),
    AllDayNotification(hourOfDay = 12, daysBefore = 1),
    AllDayNotification(hourOfDay = 9, daysBefore = 1),
    AllDayNotification(hourOfDay = 18, daysBefore = 0),
    AllDayNotification(hourOfDay = 8, daysBefore = 0),
)

/** Text-size steps as multiples of the system font scale. */
private val TEXT_SIZE_STEPS = listOf(0.85f, 1.0f, 1.15f, 1.3f)

/**
 * Label for the §8.6 all-day anchor (15.5): `daysBefore == 0` fires on the
 * event's own date, so it must say so instead of claiming "day before".
 * Internal so the label contract is directly unit-testable.
 */
internal fun allDayLabel(n: AllDayNotification): String =
    "%02d:00, %s".format(
        Locale.ROOT,
        n.hourOfDay,
        when (n.daysBefore) {
            0 -> "same day"
            1 -> "day before"
            else -> "${n.daysBefore} days before"
        },
    )

/**
 * §4.1 honesty: the provider exposes no client-visible last-modified column,
 * so this reports only what this process observed. A fresh process renders
 * "unknown" rather than guessing.
 */
private fun relativeAge(millis: Long?, now: Long = System.currentTimeMillis()): String {
    millis ?: return "unknown"
    val seconds = ((now - millis) / 1000L).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}
/** 15.3: POST_NOTIFICATIONS is only a runtime permission from API 33 on. */
internal fun notificationsGranted(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------- DATA (WS10)

/**
 * CalendarContract.STATUS_CANCELED as a plain int — [IcsCodec.IcsEventDraft]
 * speaks provider ints so the codec stays pure JVM. Internal so MainActivity's
 * 15.7 .ics VIEW import filters with the exact same rule.
 */
internal const val STATUS_CANCELED_INT = 2

/**
 * Read cap for every picked SAF file (§9 imports): a stream larger than this
 * aborts with a ✗ toast instead of letting `readBytes()` size an allocation
 * to the file and OOM the process. Internal for MainActivity's 15.7 import.
 */
internal const val MAX_IMPORT_BYTES = 10L * 1024 * 1024

/**
 * Reads [uri] in fixed chunks so allocation never tracks the file's declared
 * length, aborting the moment [maxBytes] is exceeded. Internal for
 * MainActivity's 15.7 import, which must bound OS-handed URIs identically.
 */
internal fun ContentResolver.readBounded(uri: Uri, maxBytes: Long): ByteArray {
    val input = openInputStream(uri) ?: error("could not read the chosen file")
    input.use {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = it.read(chunk)
            if (read < 0) return out.toByteArray()
            total += read
            if (total > maxBytes) error("file too large")
            out.write(chunk, 0, read)
        }
    }
}

/**
 * Events staged for insertion once a target calendar is picked: the parsed,
 * deduplicated drafts plus what was skipped so the toast can say so.
 */
private data class PendingImport(
    val drafts: List<IcsCodec.IcsEventDraft>,
    val duplicates: Int,
    val canceled: Int,
)

/** SAF suggested name per §9: xx-calendar-<date>.<ext>. */
private fun suggestedFileName(prefix: String, extension: String): String =
    "$prefix-${LocalDate.now()}.$extension"

/** Writes all sixteen survivors plus the filter-fidelity switch (§8.6). */
private suspend fun applyRestoredSettings(store: SettingsStore, s: AppSettings) {
    store.setDefaultView(s.defaultView)
    store.setStartDayOfWeek(s.startDayOfWeek)
    store.setWeekNumbers(s.weekNumbers)
    store.setShowDeclined(s.showDeclined)
    store.setDimPast(s.dimPast)
    store.setDensity(s.density)
    store.setDefaultDurationMin(s.defaultDurationMin)
    store.setDefaultNotificationMin(s.defaultNotificationMin)
    store.setAllDayNotification(s.allDayNotification)
    store.setLockScreenTitle(s.lockScreenTitle)
    store.setDailyAgenda(s.dailyAgenda)
    store.setHeadsUp(s.headsUp)
    store.setHideAutoAdded(s.hideAutoAdded)
    store.setBackground(s.background)
    store.setFont(s.font)
    store.setTextSizeScale(s.textSizeScale)
    store.setAutoAddedFilterMode(s.autoAddedFilterMode)
}

/**
 * Import target chooser — writable calendars only, sigil-sheet styling.
 * Internal so MainActivity's 15.7 .ics VIEW import reuses the exact same
 * target-calendar choice instead of growing a second, drifting picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WritableCalendarPickerSheet(
    calendars: List<CalendarSummary>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.inkRaised) {
        Text(
            "IMPORT INTO",
            style = Label,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        calendars.forEach { calendar ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(calendar.id) }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "·",
                    fontFamily = JetBrainsMono,
                    fontSize = 18.sp,
                    color = colors.shade,
                    modifier = Modifier.width(28.dp),
                )
                Text(calendar.displayName, style = Body, color = colors.text)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Restore overwrite warning: the backup replaces every setting and the whole
 * sigil map (an empty file's sigil list wipes assignments), so the counts on
 * screen are the real parsed ones. Cancel leaves the device untouched.
 */
@Composable
private fun RestoreConfirmDialog(
    sigilCount: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = colors.inkRaised,
        title = {
            Text("RESTORE", style = Label, color = colors.muted)
        },
        text = {
            Text(
                "This overwrites all current settings and $sigilCount calendar sigils.",
                style = Body,
                color = colors.text,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("[overwrite]", style = Body, color = colors.warn)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("[cancel]", style = Body, color = colors.text)
            }
        },
    )
}

