package com.piercingxx.calendar.ui.detail

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.calendar.EventDraft
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
import com.piercingxx.calendar.settings.SigilStore
import com.piercingxx.calendar.ui.editor.RefusalDialog
import com.piercingxx.calendar.ui.editor.ScopePrompt
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.CalendarColors
import com.piercingxx.calendar.ui.theme.EventTitle
import com.piercingxx.calendar.ui.theme.JetBrainsMono
import com.piercingxx.calendar.ui.theme.Label
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import com.piercingxx.calendar.ui.editor.ZONE_UTC
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The event detail sheet (design §8.5): bottom sheet on ink_raised. Title,
 * time, calendar sigil and name, location, description, reminders; a
 * conferencing URL only if the event arrived with one - never generated;
 * an attachment count, never contents. Edit / duplicate / delete. No guest
 * section, no RSVP controls (teardown §3.4).
 *
 * Delete of a recurring event raises the §6.3 scope prompt; a plain row
 * deletes with an Undo snackbar that re-inserts the captured draft plus its
 * opaque columns verbatim (D8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    eventId: Long,
    repository: CalendarRepository,
    onClose: () -> Unit,
    onEdit: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
) {
    val colors = LocalCalendarColors.current
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
    val sigilStore = remember { SigilStore(context.applicationContext) }
    val executor = remember { RecurrenceEditor(repository, context.contentResolver) }

    val sheetState = rememberModalBottomSheetState()
    var visible by remember { mutableStateOf(true) }

    val loaded by produceState<LoadedEvent?>(null, eventId) {
        value = runCatching { repository.loadEvent(eventId) }.getOrNull()
    }
    val reminders by produceState<List<Int>>(emptyList(), eventId) {
        value = runCatching {
            repository.remindersFor(eventId)
                .filter { it.method == Reminders.METHOD_ALERT }
                .map { it.minutes }
        }.getOrDefault(emptyList())
    }
    val attachmentCount by produceState(0, eventId) {
        value = attachmentCount(context.contentResolver, eventId)
    }
    var calendarsById by remember { mutableStateOf(emptyMap<Long, CalendarSummary>()) }
    var sigils by remember { mutableStateOf(emptyMap<CalendarKey, SigilTier>()) }
    LaunchedEffect(repository, sigilStore) {
        val calendars = runCatching { repository.calendars() }.getOrDefault(emptyList())
        calendarsById = calendars.associateBy { it.id }
        val assignment = SigilAssigner.assign(
            sigilStore.load(),
            calendars.map { CalendarKey(it.id, it.accountName ?: "") },
        )
        sigils = assignment.assignments
        if (assignment.newlyAssigned.isNotEmpty()) sigilStore.save(assignment.assignments)
    }

    var showScopePrompt by remember { mutableStateOf(false) }
    var refusalReason by remember { mutableStateOf<String?>(null) }
    /** Captured row between a successful delete and the undo decision. */
    var pendingUndo by remember { mutableStateOf<LoadedEvent?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun fail(t: Throwable) {
        Toast.makeText(context, "✗ ${t.message ?: "could not change the event"}", Toast.LENGTH_LONG).show()
    }

    fun closeSheet() {
        visible = false
        onClose()
    }

    fun requestDelete() {
        val current = loaded ?: return
        if (current.draft.rrule != null &&
            RRuleModel.parse(current.draft.rrule) is RuleParse.Refused
        ) {
            refusalReason =
                "this event repeats with a rule this app does not model, " +
                    "so deleting any part of it is refused rather than guessed."
            return
        }
        if (current.draft.rrule == null) {
            // Plain row: capture everything, delete, offer undo.
            scope.launch {
                runCatching { repository.deleteEvent(eventId) }
                    .onSuccess { pendingUndo = current }
                    .onFailure { fail(it) }
            }
        } else {
            showScopePrompt = true
        }
    }

    fun deleteScoped(chosen: RecurrenceScope) {
        val current = loaded ?: return
        showScopePrompt = false
        val parsedRule = current.draft.rrule?.let { RRuleModel.parse(it) }
        val rule = (parsedRule as? RuleParse.Parsed)?.rule
        if (rule == null) {
            refusalReason = "the recurrence rule is not recognised; nothing was deleted."
            return
        }
        scope.launch {
            val resolution = ScopeResolver.resolveDelete(
                context = RecurringEventContext(
                    parentEventId = current.eventId,
                    rule = rule,
                    startMillis = current.draft.startMillis,
                    allDay = current.draft.allDay,
                ),
                scope = chosen,
                instance = InstanceRef(current.eventId, current.draft.startMillis),
            )
            when (val outcome = executor.apply(resolution)) {
                is RecurrenceEditor.Outcome.Written -> closeSheet()

                is RecurrenceEditor.Outcome.Refused -> refusalReason = outcome.reason

                is RecurrenceEditor.Outcome.Missing -> {
                    Toast.makeText(context, "✗ event no longer exists", Toast.LENGTH_LONG).show()
                    closeSheet()
                }
            }
        }
    }

    // Undo window: sheet is already hidden; the snackbar outlives it here.
    LaunchedEffect(pendingUndo) {
        val captured = pendingUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "event deleted",
            actionLabel = "undo",
            duration = SnackbarDuration.Long,
        )
        when (result) {
            SnackbarResult.ActionPerformed ->
                runCatching {
                    repository.saveEvent(captured.draft.copy(eventId = null), captured.opaque)
                }.onFailure { fail(it) }

            SnackbarResult.Dismissed -> Unit
        }
        closeSheet()
    }

    Box(Modifier.fillMaxSize()) {
        if (visible) {
            ModalBottomSheet(
                onDismissRequest = onClose,
                containerColor = colors.inkRaised,
                sheetState = sheetState,
            ) {
                val current = loaded
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 28.dp),
                ) {
                    if (current == null) {
                        Text(
                            if (pendingUndo == null) "loading…" else "event deleted",
                            style = Body,
                            color = colors.muted,
                        )
                    } else {
                        val draft = current.draft

                        // Title
                        Text(
                            draft.title ?: "(no title)",
                            style = EventTitle.copy(fontWeight = FontWeight.Medium),
                            color = colors.text,
                        )

                        // Time (+ timezone only when it differs from device, §6.4)
                        Spacer(Modifier.height(6.dp))
                        Text(detailTimeText(draft, zone), style = Body, color = colors.strong)
                        if (!draft.allDay &&
                            TimeMath.shouldRenderTimezone(draft.eventTimezone, zone.id)
                        ) {
                            Text(
                                draft.eventTimezone,
                                style = Label,
                                color = colors.muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }

                        // Calendar sigil + name
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val tier =
                                sigils.entries.firstOrNull { it.key.calendarId == draft.calendarId }?.value
                            Text(
                                tier?.glyph ?: "·",
                                fontFamily = JetBrainsMono,
                                fontSize = 14.sp,
                                color = tier.rampColor(colors),
                            )
                            Text(
                                calendarsById[draft.calendarId]?.displayName ?: "",
                                style = Body,
                                color = colors.muted,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }

                        // Location
                        if (!draft.location.isNullOrBlank()) {
                            SheetField("location", draft.location)
                        }

                        // Description
                        if (!draft.description.isNullOrBlank()) {
                            SheetField("notes", draft.description)
                        }

                        // Reminders
                        if (reminders.isNotEmpty()) {
                            reminders.sorted().forEach { minutes ->
                                SheetField("notify", reminderLabelFor(minutes))
                            }
                        }

                        // Conferencing URL: only what arrived with the event.
                        val conferenceUrl = conferenceUrlOf(current)
                        if (conferenceUrl != null) {
                            SheetField("conference") {
                                Text(
                                    conferenceUrl,
                                    style = Body,
                                    color = colors.info,
                                    modifier = Modifier.clickable {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, conferenceUrl.toUri())
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }.onFailure {
                                            Toast.makeText(
                                                context,
                                                "nothing can open this link",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                )
                            }
                        }

                        // Attachment count only - never names, never contents.
                        if (attachmentCount > 0) {
                            SheetField(
                                "attachments",
                                "$attachmentCount",
                            )
                        }

                        Spacer(Modifier.height(20.dp))

                        // Actions: edit / duplicate / delete. Nothing else.
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "edit",
                                style = Body,
                                color = colors.text,
                                modifier = Modifier
                                    .clickable {
                                        closeSheet()
                                        onEdit(eventId)
                                    }
                                    .padding(vertical = 10.dp),
                            )
                            Spacer(Modifier.width(28.dp))
                            Text(
                                "duplicate",
                                style = Body,
                                color = colors.text,
                                modifier = Modifier
                                    .clickable {
                                        closeSheet()
                                        onDuplicate(eventId)
                                    }
                                    .padding(vertical = 10.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "delete",
                                style = Body,
                                color = colors.error,
                                modifier = Modifier
                                    .clickable { requestDelete() }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp),
        )
    }

    if (showScopePrompt) {
        ScopePrompt(
            title = loaded?.draft?.title ?: "",
            onScope = { chosen -> deleteScoped(chosen) },
            onDismiss = { showScopePrompt = false },
        )
    }

    refusalReason?.let { reason ->
        RefusalDialog(reason = reason, onDismiss = { refusalReason = null })
    }
}

@Composable
private fun SheetField(label: String, value: String) {
    val colors = LocalCalendarColors.current
    Spacer(Modifier.height(12.dp))
    Text(label.uppercase(), style = Label, color = colors.shade)
    Text(value, style = Body, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun SheetField(label: String, value: @Composable () -> Unit) {
    val colors = LocalCalendarColors.current
    Spacer(Modifier.height(12.dp))
    Text(label.uppercase(), style = Label, color = colors.shade)
    Box(modifier = Modifier.padding(top = 2.dp)) { value() }
}

/** §7.1 ramp, as the drawer renders it. */
private fun SigilTier?.rampColor(colors: CalendarColors) =
    when (this?.rampName) {
        "text" -> colors.text
        "strong" -> colors.strong
        "muted" -> colors.muted
        "shade" -> colors.shade
        else -> colors.shade
    }

private val RANGE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM  HH:mm")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

private fun detailTimeText(draft: EventDraft, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(draft.startMillis).atZone(zone)
    return if (draft.allDay) {
        // DTEND is exclusive on all-day rows (§6.4).
        val endDate = draft.endMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZONE_UTC).toLocalDate().minusDays(1)
        }
        val base = if (endDate != null && endDate != start.toLocalDate()) {
            "${start.format(DATE_FORMAT)} - ${endDate.format(DATE_FORMAT)}"
        } else {
            start.format(DATE_FORMAT)
        }
        "$base · all-day"
    } else {
        val endInstant = draft.endMillis?.let { Instant.ofEpochMilli(it) }
        if (endInstant == null) {
            start.format(RANGE_FORMAT)
        } else {
            val end = endInstant.atZone(zone)
            if (end.toLocalDate() == start.toLocalDate()) {
                "${start.format(DATE_FORMAT)} ${start.format(TIME_ONLY)} - ${end.format(TIME_ONLY)}"
            } else {
                "${start.format(RANGE_FORMAT)} - ${end.format(RANGE_FORMAT)}"
            }
        }
    }
}

private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** The CUSTOM_APP_URI travels inside the opaque bag (D8); surface it verbatim. */
private fun conferenceUrlOf(loaded: LoadedEvent): String? =
    (loaded.opaque.values[Events.CUSTOM_APP_URI] as? OpaqueColumns.RawValue.Text)
        ?.value
        ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

// The Attachments table is @hide in the public SDK; the provider still serves
// it to normal clients. Count only - names/contents are never read (§8.5).
private val ATTACHMENTS_URI: Uri = "content://com.android.calendar/attachments".toUri()

private suspend fun attachmentCount(resolver: ContentResolver, eventId: Long): Int =
    withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(
                ATTACHMENTS_URI,
                arrayOf("_id"),
                "event_id=?",
                arrayOf(eventId.toString()),
                null,
            )?.use { it.count } ?: 0
        }.getOrDefault(0)
    }

private fun reminderLabelFor(minutes: Int): String = when {
    minutes <= 0 -> "at time of event"
    minutes % 1440 == 0 -> "${minutes / 1440} day" + (if (minutes / 1440 > 1) "s" else "") + " before"
    minutes % 60 == 0 -> "${minutes / 60} hour" + (if (minutes / 60 > 1) "s" else "") + " before"
    else -> "$minutes minutes before"
}
