package com.piercingxx.calendar.ui.editor

import android.provider.CalendarContract.Reminders
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.calendar.LoadedEvent
import com.piercingxx.calendar.calendar.OpaqueColumns
import com.piercingxx.calendar.calendar.RecurrenceEditor
import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.InstanceRef
import com.piercingxx.calendar.core.RecurringEventContext
import com.piercingxx.calendar.core.RecurrenceScope
import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.RuleParse
import com.piercingxx.calendar.core.ScopeResolver
import com.piercingxx.calendar.core.SigilAssigner
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.core.TimeMath
import com.piercingxx.calendar.settings.Settings as AppSettings
import com.piercingxx.calendar.settings.SettingsStore
import com.piercingxx.calendar.settings.SigilStore
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.EventTitle
import com.piercingxx.calendar.ui.theme.JetBrainsMono
import com.piercingxx.calendar.ui.theme.Label
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * The event editor (design §8.5): a full screen, fields in the spec order,
 * zero autocomplete anywhere. Saving an existing recurring event raises the
 * §6.3 scope prompt - only then - and routes through [RecurrenceEditor].
 * Failed writes toast at pxx_error and leave every keystroke intact (§10).
 *
 * [eventId] null means a new event; [initialStartMillis]/[initialEndMillis]
 * pin the initial times to a grid create gesture. [duplicate] loads [eventId]'s
 * fields but strips every exception linkage so the save inserts fresh.
 */
@Composable
fun EditorScreen(
    eventId: Long?,
    initialStartMillis: Long? = null,
    initialEndMillis: Long? = null,
    duplicate: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    val colors = LocalCalendarColors.current
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
    val repository = remember { CalendarRepository(context.contentResolver) }
    val sigilStore = remember { SigilStore(context.applicationContext) }
    val settingsStore = remember { SettingsStore(context.applicationContext) }
    val executor = remember { RecurrenceEditor(repository, context.contentResolver) }

    val finish: () -> Unit = {
        if (onClose != null) onClose() else (context as? ComponentActivity)?.finish()
    }

    var form by remember { mutableStateOf<EditorForm?>(null) }
    var loaded by remember { mutableStateOf<LoadedEvent?>(null) }
    /** Non-null once a new-event insert succeeded; retries become updates. */
    var savedId by remember { mutableStateOf<Long?>(null) }

    var calendarsById by remember { mutableStateOf(emptyMap<Long, CalendarSummary>()) }
    var sigils by remember { mutableStateOf(emptyMap<CalendarKey, SigilTier>()) }

    var showScopePrompt by remember { mutableStateOf(false) }
    var showRepeatBuilder by remember { mutableStateOf(false) }
    var showCalendarPicker by remember { mutableStateOf(false) }
    var showNotifyPicker by remember { mutableStateOf(false) }
    var dateDialogFor by remember { mutableStateOf<String?>(null) }
    var timeDialogFor by remember { mutableStateOf<String?>(null) }
    var zoneDialogOpen by remember { mutableStateOf(false) }
    var refusalReason by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun fail(t: Throwable) {
        Toast.makeText(context, "✗ ${t.message ?: "could not save"}", Toast.LENGTH_LONG).show()
        busy = false
    }

    // Load: existing row (edit or duplicate), or blank with pinned times.
    LaunchedEffect(eventId) {
        if (eventId == null) {
            // §8.6 editor defaults; a broken read degrades to the quiet defaults.
            val defaults = runCatching { settingsStore.current() }.getOrDefault(AppSettings())
            form = EditorForm.new(
                zone,
                initialStartMillis,
                initialEndMillis,
                durationMinutes = defaults.defaultDurationMin.toLong(),
                reminderMinutes = defaults.defaultNotificationMin,
            )
        } else {
            val l = repository.loadEvent(eventId)
            if (l == null) {
                Toast.makeText(context, "✗ event no longer exists", Toast.LENGTH_LONG).show()
                finish()
            } else {
                if (!duplicate) loaded = l
                form = EditorForm.fromLoaded(l, zone).copy(
                    reminders = repository.remindersFor(l.eventId)
                        .filter { it.method == Reminders.METHOD_ALERT }
                        .map { it.minutes },
                )
            }
        }
    }

    // Sigil assignment pass, exactly as the views run it (§6.1).
    LaunchedEffect(repository, sigilStore) {
        val calendars = repository.calendars()
        calendarsById = calendars.associateBy { it.id }
        val assignment = SigilAssigner.assign(
            sigilStore.load(),
            calendars.map { CalendarKey(it.id, it.accountName ?: "") },
        )
        sigils = assignment.assignments
        if (assignment.newlyAssigned.isNotEmpty()) sigilStore.save(assignment.assignments)
        // A blank editor defaults to the first writable calendar (§4.4).
        val f = form
        if (eventId == null && f?.calendarId == 0L) {
            calendars.firstOrNull { it.isWritable }?.let { form = f.copy(calendarId = it.id) }
        }
    }

    val current = form
    if (current == null) {
        Box(Modifier.fillMaxSize().background(colors.ink))
        return
    }

    fun update(next: EditorForm) {
        form = next
    }

    fun directSave() {
        val f = form ?: return
        busy = true
        scope.launch {
            try {
                val draft = buildDraft(f, loaded, duplicate = false, zone)
                    .let { d -> if (savedId != null && d.eventId == null) d.copy(eventId = savedId) else d }
                val id = repository.saveEvent(draft, loaded?.opaque ?: OpaqueColumns.HeldValues.EMPTY)
                savedId = id
                replaceReminders(context.contentResolver, id, f.reminders)
                finish()
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun scopedSave(chosen: RecurrenceScope) {
        val f = form ?: return
        val original = loaded ?: return
        showScopePrompt = false
        busy = true
        scope.launch {
            try {
                val parsedRule = RRuleModel.parse(requireNotNull(original.draft.rrule))
                val rule = (parsedRule as? RuleParse.Parsed)?.rule
                    ?: throw IllegalStateException(
                        "refusing to save: the recurrence rule is not recognised",
                    )
                val updated = buildDraft(f, original, duplicate = false, zone)
                val resolution = ScopeResolver.resolveEdit(
                    context = RecurringEventContext(
                        parentEventId = original.eventId,
                        rule = rule,
                        startMillis = original.draft.startMillis,
                        allDay = original.draft.allDay,
                    ),
                    scope = chosen,
                    instance = InstanceRef(original.eventId, original.draft.startMillis),
                    edits = diffEdits(original.draft, updated),
                )
                // A replaced rule rides beside EventFieldEdits - it cannot fit
                // inside them - and lands on the parent (all events) or the new
                // series (this and following).
                val replacement = f.rule?.takeIf { it.serialize() != original.draft.rrule }
                when (val outcome = executor.apply(resolution, replacement)) {
                    is RecurrenceEditor.Outcome.Written -> replaceReminders(
                        context.contentResolver,
                        outcome.touchedEventId ?: original.eventId,
                        f.reminders,
                    )

                    is RecurrenceEditor.Outcome.Refused -> refusalReason = outcome.reason

                    is RecurrenceEditor.Outcome.Missing ->
                        Toast.makeText(context, "✗ event no longer exists", Toast.LENGTH_LONG).show()
                }
                busy = false
                if (refusalReason == null) finish()
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun requestSave() {
        val f = form ?: return
        if (busy || !f.canSave || f.calendarId == 0L) return
        if (f.ruleUnreadable && loaded?.draft?.rrule != null) {
            // §10: refuse and explain rather than guess.
            refusalReason =
                "this event repeats with a rule this app does not model. " +
                    "Saving any change could silently corrupt the series, " +
                    "so the write is refused."
            return
        }
        if (loaded?.draft?.rrule != null) {
            showScopePrompt = true // §6.3: recurring saves prompt - only they do
        } else {
            directSave()
        }
    }

    fun pickDate(target: String) {
        dateDialogFor = target
    }

    val fieldColors = TextFieldDefaults.colors(
        focusedTextColor = colors.text,
        unfocusedTextColor = colors.text,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        cursorColor = colors.signal,
        focusedIndicatorColor = colors.line,
        unfocusedIndicatorColor = colors.line,
        focusedPlaceholderColor = colors.shade,
        unfocusedPlaceholderColor = colors.shade,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ink)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = finish) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.strong)
            }
            Spacer(Modifier.weight(1f))
            val saveEnabled = current.canSave && current.calendarId != 0L && !busy
            TextButton(onClick = ::requestSave, enabled = saveEnabled) {
                Text(
                    "save",
                    style = Body,
                    color = if (saveEnabled) colors.signal else colors.shade,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            TextField(
                value = current.title,
                onValueChange = { update(current.copy(title = it)) },
                placeholder = { Text("title", style = EventTitle, color = colors.shade) },
                textStyle = EventTitle.copy(color = colors.text),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            FieldDivider()

            LabeledRow("all-day") {
                Checkbox(
                    checked = current.allDay,
                    onCheckedChange = { checked -> update(current.copy(allDay = checked)) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.emphasisBg,
                        uncheckedColor = colors.muted,
                        checkmarkColor = colors.emphasisFg,
                    ),
                )
            }

            ValueRow(
                label = "starts",
                value = startsLabel(current),
                onClick = { pickDate("start") },
            )
            ValueRow(
                label = "ends",
                value = endsLabel(current),
                onClick = { pickDate("end") },
            )

            if (!current.allDay && TimeMath.shouldRenderTimezone(current.timezone, zone.id)) {
                ValueRow(
                    label = "timezone",
                    value = current.timezone,
                    onClick = { zoneDialogOpen = true },
                )
            }

            ValueRow(
                label = "repeats",
                value = repeatLabel(current.rule, current.ruleUnreadable),
                onClick = { showRepeatBuilder = true },
            )
            FieldDivider()

            val calendarName = calendarsById[current.calendarId]?.displayName
            val calendarSigil = sigils.entries
                .firstOrNull { it.key.calendarId == current.calendarId }?.value
            ValueRow(
                label = "calendar",
                value = calendarName ?: "(no calendar)",
                leading = calendarSigil?.glyph,
                onClick = if (calendarsById.isNotEmpty()) ({ showCalendarPicker = true }) else null,
            )
            TextField(
                value = current.location,
                onValueChange = { update(current.copy(location = it)) },
                placeholder = { Text("location", style = Body, color = colors.shade) },
                textStyle = Body.copy(color = colors.text),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = current.description,
                onValueChange = { update(current.copy(description = it)) },
                placeholder = { Text("description", style = Body, color = colors.shade) },
                textStyle = Body.copy(color = colors.text),
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            FieldDivider()

            current.reminders.sorted().forEach { minutes ->
                LabeledRow(
                    label = if (minutes == current.reminders.sorted().first()) "notify" else "",
                    trailing = {
                        Text(
                            "✕",
                            fontFamily = JetBrainsMono,
                            color = colors.muted,
                            modifier = Modifier
                                .clickable {
                                    update(current.copy(reminders = current.reminders - minutes))
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    },
                ) {
                    Text(reminderLabel(minutes), style = Body, color = colors.text)
                }
            }
            Text(
                "+ add notification",
                style = Body,
                color = colors.strong,
                modifier = Modifier
                    .clickable { showNotifyPicker = true }
                    .padding(vertical = 10.dp),
            )
            LabeledRow("busy / free") {
                Text(
                    if (current.busy) "busy" else "free",
                    style = Body,
                    color = colors.text,
                    modifier = Modifier.clickable { update(current.copy(busy = !current.busy)) },
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    // ---- dialogs -----------------------------------------------------------

    if (showScopePrompt) {
        ScopePrompt(
            title = current.title,
            onScope = { chosen -> scopedSave(chosen) },
            onDismiss = { showScopePrompt = false },
        )
    }

    refusalReason?.let { reason ->
        RefusalDialog(reason = reason, onDismiss = { refusalReason = null })
    }

    if (showRepeatBuilder) {
        RepeatBuilderDialog(
            initial = current.rule,
            anchorDate = current.startDate,
            allDay = current.allDay,
            onSelect = { rule ->
                showRepeatBuilder = false
                update(current.copy(rule = rule, ruleUnreadable = false))
            },
            onDismiss = { showRepeatBuilder = false },
        )
    }

    if (showCalendarPicker) {
        WritableCalendarPickerDialog(
            calendarsById = calendarsById,
            sigils = sigils,
            selectedId = current.calendarId,
            onSelect = { id ->
                showCalendarPicker = false
                update(current.copy(calendarId = id))
            },
            onDismiss = { showCalendarPicker = false },
        )
    }

    if (showNotifyPicker) {
        NotificationPickerDialog(
            existing = current.reminders,
            onAdd = { minutes ->
                showNotifyPicker = false
                update(current.copy(reminders = (current.reminders + minutes).distinct()))
            },
            onDismiss = { showNotifyPicker = false },
        )
    }

    dateDialogFor?.let { target ->
        DateFieldDialog(
            initial = if (target == "start") current.startDate else current.endDate,
            onPicked = { picked ->
                if (target == "start") {
                    val endDate = if (current.endDate.isBefore(picked)) picked else current.endDate
                    update(current.copy(startDate = picked, endDate = endDate))
                } else {
                    update(current.copy(endDate = picked))
                }
                dateDialogFor = null
                if (!current.allDay) timeDialogFor = target // date then time, one gesture
            },
            onDismiss = { dateDialogFor = null },
        )
    }

    timeDialogFor?.let { target ->
        TimeFieldDialog(
            initial = (if (target == "start") current.startTime else current.endTime)
                ?: LocalTime.MIDNIGHT,
            onPicked = { picked ->
                update(
                    if (target == "start") {
                        current.copy(startTime = picked)
                    } else {
                        current.copy(endTime = picked)
                    },
                )
                timeDialogFor = null
            },
            onDismiss = { timeDialogFor = null },
        )
    }

    if (zoneDialogOpen) {
        ZonePickerDialog(
            onPicked = { picked -> update(current.copy(timezone = picked)) },
            onDismiss = { zoneDialogOpen = false },
        )
    }
}

// ---- row primitives ----------------------------------------------------------

@Composable
private fun FieldDivider() {
    val colors = LocalCalendarColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.line),
    )
}

/** Label column + content slot; the sketch's two-column rhythm (§8.5). */
@Composable
private fun LabeledRow(
    label: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalCalendarColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Body, color = colors.muted, modifier = Modifier.width(96.dp))
        content()
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    leading: String? = null,
    onClick: (() -> Unit)?,
) {
    val colors = LocalCalendarColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Body, color = colors.muted, modifier = Modifier.width(96.dp))
        if (leading != null) {
            Text(leading, fontFamily = JetBrainsMono, color = colors.text,
                modifier = Modifier.padding(end = 10.dp))
        }
        Text(value, style = Body, color = colors.text)
    }
}

// ---- pickers -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFieldDialog(
    initial: LocalDate,
    onPicked: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    val state = rememberDatePickerState(
        initialSelectedDateMillis = TimeMath.allDayDateToStorage(initial),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onPicked(Instant.ofEpochMilli(millis).atZone(ZONE_UTC).toLocalDate())
                }
                onDismiss()
            }) { Text("set", style = Body, color = colors.text) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("cancel", style = Body, color = colors.muted) }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeFieldDialog(
    initial: LocalTime,
    onPicked: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.graphite,
        confirmButton = {
            TextButton(onClick = {
                onPicked(LocalTime.of(state.hour, state.minute))
                onDismiss()
            }) { Text("set", style = Body, color = colors.text) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("cancel", style = Body, color = colors.muted) }
        },
        text = { TimePicker(state = state) },
    )
}

@Composable
private fun ZonePickerDialog(onPicked: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalCalendarColors.current
    val zones = remember { ZoneId.getAvailableZoneIds().sorted() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.graphite,
        title = { Text("timezone", style = EventTitle, color = colors.text) },
        text = {
            Column(modifier = Modifier.height(420.dp)) {
                LazyColumn {
                    items(zones.size) { index ->
                        Text(
                            zones[index],
                            style = Body,
                            color = colors.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPicked(zones[index])
                                    onDismiss()
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("cancel", style = Body, color = colors.muted) }
        },
    )
}

@Composable
private fun WritableCalendarPickerDialog(
    calendarsById: Map<Long, CalendarSummary>,
    sigils: Map<CalendarKey, SigilTier>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    val writable = calendarsById.values.filter { it.isWritable }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.graphite,
        title = { Text("calendar", style = EventTitle, color = colors.text) },
        text = {
            Column {
                writable.forEach { calendar ->
                    val tier = sigils.entries.firstOrNull { it.key.calendarId == calendar.id }?.value
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(calendar.id)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(tier?.glyph ?: "·", fontFamily = JetBrainsMono, color = colors.text)
                        Text(
                            calendar.displayName,
                            style = Body,
                            color = if (calendar.id == selectedId) colors.signal else colors.text,
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                }
                if (writable.isEmpty()) {
                    Text("no writable calendar", style = Body, color = colors.muted)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("cancel", style = Body, color = colors.muted) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationPickerDialog(
    existing: List<Int>,
    onAdd: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    val presets = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 10080)
    var customText by remember { mutableStateOf("") }
    val customValue = customText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.graphite,
        title = { Text("notification", style = EventTitle, color = colors.text) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { minutes ->
                        val taken = minutes in existing
                        Text(
                            reminderLabel(minutes),
                            fontFamily = JetBrainsMono,
                            fontSize = 12.sp,
                            color = if (taken) colors.shade else colors.text,
                            modifier = Modifier
                                .clickable(enabled = !taken) { onAdd(minutes) }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = customText,
                        onValueChange = { raw ->
                            if (raw.length <= 5) customText = raw.filter { it.isDigit() }
                        },
                        placeholder = { Text("minutes", style = Label, color = colors.shade) },
                        textStyle = Body.copy(color = colors.text),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = colors.text,
                            unfocusedTextColor = colors.text,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = colors.signal,
                            focusedIndicatorColor = colors.line,
                            unfocusedIndicatorColor = colors.line,
                            focusedPlaceholderColor = colors.shade,
                            unfocusedPlaceholderColor = colors.shade,
                        ),
                        modifier = Modifier.width(120.dp),
                    )
                    TextButton(onClick = { customValue?.let(onAdd) }, enabled = customValue != null) {
                        Text(
                            "add",
                            style = Body,
                            color = if (customValue != null) colors.signal else colors.shade,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("cancel", style = Body, color = colors.muted) }
        },
    )
}
