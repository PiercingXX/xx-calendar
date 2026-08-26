package com.piercingxx.calendar.ui.editor

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.OperationApplicationException
import android.os.RemoteException
import android.provider.CalendarContract
import android.provider.CalendarContract.Reminders
import com.piercingxx.calendar.calendar.EventDraft
import com.piercingxx.calendar.calendar.LoadedEvent
import com.piercingxx.calendar.core.EndCondition
import com.piercingxx.calendar.core.EventFieldEdits
import com.piercingxx.calendar.core.Frequency
import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.RuleParse
import com.piercingxx.calendar.core.TimeMath
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Editor plumbing behind design §8.5: the form state, its conversions to and
 * from provider storage (§6.4 all-day contract included), the field diff that
 * feeds [com.piercingxx.calendar.core.ScopeResolver], human labels for rules
 * and reminders, and the Reminders write path. No UI lives here.
 */

/** The editor's single source of truth while open. */
data class EditorForm(
    val title: String,
    val allDay: Boolean,
    val startDate: LocalDate,
    val startTime: LocalTime?,
    val endDate: LocalDate,
    val endTime: LocalTime?,
    val timezone: String,
    val rule: RRuleModel?,
    /** A recurrence rule arrived from the provider but does not parse. */
    val ruleUnreadable: Boolean,
    val calendarId: Long,
    val location: String,
    val description: String,
    val reminders: List<Int>,
    val busy: Boolean,
) {
    val canSave: Boolean
        get() = if (allDay) !endDate.isBefore(startDate) else {
            val start = LocalDateTime.of(startDate, startTime ?: LocalTime.MIDNIGHT)
            val end = LocalDateTime.of(endDate, endTime ?: LocalTime.MIDNIGHT)
            end.isAfter(start)
        }

    companion object {
        /**
         * A blank event; a create gesture may pin the initial times to the
         * grid. Duration and seed reminder come from §8.6's editor defaults.
         */
        fun new(
            deviceZone: ZoneId,
            startMillis: Long?,
            endMillis: Long?,
            durationMinutes: Long = DEFAULT_DURATION_MINUTES,
            reminderMinutes: Int = DEFAULT_REMINDER_MINUTES,
        ): EditorForm {
            val now = LocalDateTime.now(deviceZone)
            val start = if (startMillis != null) {
                Instant.ofEpochMilli(startMillis).atZone(deviceZone).toLocalDateTime()
            } else {
                // Next half-hour mark, per the grid's snap rhythm.
                now.plusMinutes(durationMinutes).withSecond(0).withNano(0)
                    .let { if (it.minute < 30) it.withMinute(30) else it.plusHours(1).withMinute(0) }
            }
            val end = if (endMillis != null) {
                Instant.ofEpochMilli(endMillis).atZone(deviceZone).toLocalDateTime()
            } else {
                start.plusMinutes(durationMinutes)
            }
            return EditorForm(
                title = "",
                allDay = false,
                startDate = start.toLocalDate(),
                startTime = start.toLocalTime(),
                endDate = end.toLocalDate(),
                endTime = end.toLocalTime(),
                timezone = deviceZone.id,
                rule = null,
                ruleUnreadable = false,
                calendarId = 0L,
                location = "",
                description = "",
                reminders = listOf(reminderMinutes),
                busy = true,
            )
        }

        /** The loaded row as editable form state; DURATION becomes an absolute end. */
        fun fromLoaded(loaded: LoadedEvent, deviceZone: ZoneId): EditorForm {
            val draft = loaded.draft
            val parsedRule: RRuleModel? = draft.rrule?.let { raw ->
                when (val p = RRuleModel.parse(raw)) {
                    is RuleParse.Parsed -> p.rule
                    is RuleParse.Refused -> null
                }
            }
            val zone = deviceZone
            val startDate: LocalDate
            val startTime: LocalTime?
            val endDate: LocalDate
            val endTime: LocalTime?
            if (draft.allDay) {
                startDate = TimeMath.storageToAllDayDate(draft.startMillis)
                startTime = null
                // DTEND on all-day rows is exclusive (provider contract).
                endDate = draft.endMillis?.let { TimeMath.storageToAllDayDate(it).minusDays(1) } ?: startDate
                endTime = null
            } else {
                val start = Instant.ofEpochMilli(draft.startMillis).atZone(zone)
                val durationMillis = draft.endMillis?.let { it - draft.startMillis }
                    ?: parseDuration(draft.duration)
                startDate = start.toLocalDate()
                startTime = start.toLocalTime()
                val endInstant = durationMillis?.let { start.toInstant().plusMillis(it) }
                endDate = endInstant?.atZone(zone)?.toLocalDate() ?: startDate
                endTime = endInstant?.atZone(zone)?.toLocalTime()
            }
            return EditorForm(
                title = draft.title ?: "",
                allDay = draft.allDay,
                startDate = startDate,
                startTime = startTime,
                endDate = endDate,
                endTime = endTime,
                timezone = draft.eventTimezone.ifBlank { deviceZone.id },
                rule = parsedRule,
                ruleUnreadable = draft.rrule != null && parsedRule == null,
                calendarId = draft.calendarId,
                location = draft.location ?: "",
                description = draft.description ?: "",
                reminders = emptyList(), // seeded separately from repository.remindersFor
                busy = draft.availability == EventDraft.AVAILABILITY_BUSY,
            )
        }
    }
}

private const val DEFAULT_DURATION_MINUTES = 30L
private const val DEFAULT_REMINDER_MINUTES = 10

/** All-day storage zone (design §6.4); shared by editor + detail rendering. */
internal val ZONE_UTC: ZoneId = ZoneId.of("UTC")

private const val AVAILABILITY_FREE = 1

/**
 * Form -> provider draft. Timed recurring rows carry DURATION instead of
 * DTEND; all-day recurring rows keep an exclusive DTEND so multi-day spans
 * survive expansion; all-day rows are stored at UTC midnight (design §6.4).
 * When [original] is present its opaque-linked modeled fields (rdate/exdate/
 * colorKey/original linkage) round-trip untouched — except in duplicate mode,
 * which strips every linkage so the save inserts a fresh independent event.
 */
fun buildDraft(
    form: EditorForm,
    original: LoadedEvent?,
    duplicate: Boolean,
    deviceZone: ZoneId,
): EventDraft {
    val recurring = form.rule != null || form.ruleUnreadable ||
        (!duplicate && original?.draft?.rrule != null)
    val startMillis: Long
    val endMillis: Long?
    if (form.allDay) {
        startMillis = TimeMath.allDayDateToStorage(form.startDate)
        endMillis = TimeMath.allDayDateToStorage(form.endDate.plusDays(1))
    } else {
        startMillis = LocalDateTime.of(form.startDate, form.startTime ?: LocalTime.MIDNIGHT)
            .atZone(deviceZone).toInstant().toEpochMilli()
        endMillis = LocalDateTime.of(form.endDate, form.endTime ?: LocalTime.MIDNIGHT)
            .atZone(deviceZone).toInstant().toEpochMilli()
    }
    val base = original?.draft
    val keepLinkage = base != null && !duplicate
    return EventDraft(
        calendarId = form.calendarId,
        startMillis = startMillis,
        endMillis = if (recurring && !form.allDay) null else endMillis,
        eventTimezone = if (form.allDay) "UTC" else form.timezone,
        eventId = if (keepLinkage) base!!.eventId else null,
        title = form.title.trim().ifEmpty { null },
        location = form.location.trim().ifEmpty { null },
        description = form.description.trim().ifEmpty { null },
        duration = if (recurring && !form.allDay && !form.ruleUnreadable) {
            formatDuration(startMillis, endMillis)
        } else {
            null
        },
        allDay = form.allDay,
        eventEndTimezone = if (keepLinkage) base!!.eventEndTimezone else null,
        rrule = form.rule?.serialize() ?: if (keepLinkage) base!!.rrule else null,
        rdate = if (keepLinkage) base!!.rdate else null,
        exdate = if (keepLinkage) base!!.exdate else null,
        availability = if (form.busy) EventDraft.AVAILABILITY_BUSY else AVAILABILITY_FREE,
        colorKey = if (keepLinkage) base!!.colorKey else null,
        originalId = if (keepLinkage) base!!.originalId else null,
        originalInstanceTime = if (keepLinkage) base!!.originalInstanceTime else null,
        originalAllDay = if (keepLinkage) base!!.originalAllDay else null,
    )
}

/**
 * Modeled-field diff between the loaded row and what the form would store.
 * Per field: non-null result = "set to this value"; null result = "leave as
 * loaded"; a set clear flag = "write null" — a cleared field cannot travel in
 * the nullable payload, whose null is the unchanged sentinel (see
 * [EventFieldEdits]). Consumed by [com.piercingxx.calendar.core.ScopeResolver]
 * and applied by [com.piercingxx.calendar.calendar.RecurrenceEditor].
 */
fun diffEdits(original: EventDraft, updated: EventDraft): EventFieldEdits {
    fun <T> changed(value: T, was: T): T? = if (value != was) value else null

    return EventFieldEdits(
        title = changed(updated.title, original.title),
        clearTitle = updated.title == null && original.title != null,
        location = changed(updated.location, original.location),
        clearLocation = updated.location == null && original.location != null,
        description = changed(updated.description, original.description),
        clearDescription = updated.description == null && original.description != null,
        startMillis = changed(updated.startMillis, original.startMillis),
        endMillis = changed(updated.endMillis, original.endMillis),
        clearEndMillis = updated.endMillis == null && original.endMillis != null,
        duration = changed(updated.duration, original.duration),
        clearDuration = updated.duration == null && original.duration != null,
        allDay = changed(updated.allDay, original.allDay),
        eventTimezone = changed(updated.eventTimezone, original.eventTimezone),
        eventEndTimezone = changed(updated.eventEndTimezone, original.eventEndTimezone),
        clearEventEndTimezone = updated.eventEndTimezone == null && original.eventEndTimezone != null,
        availability = changed(updated.availability, original.availability),
    )
}

// ---- RFC 5545 DURATION subset ---------------------------------------------

/** Millis between instants as `P[nW][nD][T[nH][nM][nS]]`, never negative. */
fun formatDuration(startMillis: Long, endMillis: Long): String {
    var seconds = ((endMillis - startMillis) / 1000L).coerceAtLeast(0)
    val weeks = seconds / (7 * 24 * 3600); seconds %= 7 * 24 * 3600
    val days = seconds / (24 * 3600); seconds %= 24 * 3600
    val hours = seconds / 3600; seconds %= 3600
    val minutes = seconds / 60; seconds %= 60
    val timePart = listOf(
        if (hours > 0) "${hours}H" else "",
        if (minutes > 0) "${minutes}M" else "",
        if (seconds > 0) "${seconds}S" else "",
    ).joinToString("")
    val sb = StringBuilder("P")
    if (weeks > 0) sb.append(weeks).append('W')
    if (days > 0) sb.append(days).append('D')
    if (timePart.isNotEmpty()) sb.append('T').append(timePart)
    if (sb.length == 1) sb.append('T').append('0').append('S')
    return sb.toString()
}

/** Parses the same subset [formatDuration] emits plus weeks; null if invalid. */
fun parseDuration(raw: String?): Long? {
    val s = raw?.trim()?.uppercase() ?: return null
    if (!s.startsWith("P") || s.length < 3) return null
    var totalMillis = 0L
    var anyComponent = false
    var number = ""
    var inTime = false
    for (c in s.substring(1)) {
        when {
            c.isDigit() -> number += c

            c == 'T' && number.isEmpty() -> inTime = true

            c in "WDHMS" -> {
                val value = number.toLongOrNull() ?: return null
                if (value < 0) return null
                val unitMillis = when (c) {
                    'W' -> if (inTime) return null else 7 * 24 * 3600_000L
                    'D' -> if (inTime) return null else 24 * 3600_000L
                    'H' -> 3600_000L
                    'M' -> 60_000L
                    'S' -> 1000L
                    else -> return null
                }
                totalMillis += value * unitMillis
                anyComponent = true
                number = ""
            }

            else -> return null
        }
    }
    return if (anyComponent && number.isEmpty()) totalMillis else null
}

// ---- Labels -----------------------------------------------------------------

private val DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM  HH:mm")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

fun startsLabel(form: EditorForm): String =
    if (form.allDay) form.startDate.format(DATE_FORMAT)
    else LocalDateTime.of(form.startDate, form.startTime ?: LocalTime.MIDNIGHT).format(DATE_TIME_FORMAT)

fun endsLabel(form: EditorForm): String =
    if (form.allDay) form.endDate.format(DATE_FORMAT)
    else LocalDateTime.of(form.endDate, form.endTime ?: LocalTime.MIDNIGHT).format(DATE_TIME_FORMAT)

/** §8.5 repeats row: plain words first, structure only when it exists. */
fun repeatLabel(rule: RRuleModel?, unreadable: Boolean): String = when {
    unreadable -> "unrecognised rule"
    rule == null -> "does not repeat"
    else -> describe(rule)
}

private fun describe(rule: RRuleModel): String {
    val every = if (rule.interval > 1) "every ${rule.interval} " else ""
    val core = when (rule.frequency) {
        Frequency.DAILY -> if (rule.interval > 1) "every ${rule.interval} days" else "daily"

        Frequency.WEEKLY -> when {
            isWeekdaysOnly(rule) -> "every weekday"
            rule.byDay.isEmpty() -> if (rule.interval > 1) "every ${rule.interval} weeks" else "weekly"

            else -> "$every${if (rule.interval > 1) "weeks" else "week"} on " +
                rule.byDay.joinToString(", ") { dayName(it.weekday) }
        }

        Frequency.MONTHLY -> when {
            rule.byMonthDay.isNotEmpty() ->
                "monthly on day ${rule.byMonthDay.sorted().joinToString(", ") { ordinalWord(it) }}"

            rule.byDay.isNotEmpty() -> "monthly on the " + rule.byDay.joinToString(", ") {
                nthWord(it.ordinal ?: 1) + " " + dayName(it.weekday)
            }

            else -> "monthly"
        }

        Frequency.YEARLY -> {
            val stem = if (rule.interval > 1) "every ${rule.interval} years" else "annually"
            val monthNames = rule.byMonth.map { monthName(it) }
            val days = rule.byMonthDay.joinToString(", ") { ordinalWord(it) }
            when {
                monthNames.isNotEmpty() && days.isNotEmpty() ->
                    "$stem on $days of ${monthNames.joinToString(", ")}"
                monthNames.isNotEmpty() -> "$stem in ${monthNames.joinToString(", ")}"
                days.isNotEmpty() -> "$stem on the $days"
                else -> stem
            }
        }
    }
    return when (val end = rule.end) {
        is EndCondition.Until ->
            "$core until " +
                Instant.ofEpochMilli(end.untilMillisUtc).atZone(ZONE_UTC)
                    .toLocalDate().format(DATE_FORMAT)

        is EndCondition.Count -> "$core · ${end.occurrences} times"

        EndCondition.Never -> core
    }
}

private fun isWeekdaysOnly(rule: RRuleModel): Boolean =
    rule.interval == 1 &&
        rule.byDay.map { it.weekday }.toSet() ==
        setOf(
            com.piercingxx.calendar.core.Weekday.MO,
            com.piercingxx.calendar.core.Weekday.TU,
            com.piercingxx.calendar.core.Weekday.WE,
            com.piercingxx.calendar.core.Weekday.TH,
            com.piercingxx.calendar.core.Weekday.FR,
        )

fun dayName(weekday: com.piercingxx.calendar.core.Weekday): String =
    weekday.name.let { java.time.DayOfWeek.valueOf(mapWeekday(it)).getDisplayName(TextStyle.FULL, Locale.getDefault()) }

private fun mapWeekday(token: String): String = when (token) {
    "MO" -> "MONDAY"; "TU" -> "TUESDAY"; "WE" -> "WEDNESDAY"; "TH" -> "THURSDAY"
    "FR" -> "FRIDAY"; "SA" -> "SATURDAY"; "SU" -> "SUNDAY"
    else -> token
}

fun weekdayOf(date: LocalDate): com.piercingxx.calendar.core.Weekday =
    when (date.dayOfWeek) {
        java.time.DayOfWeek.MONDAY -> com.piercingxx.calendar.core.Weekday.MO
        java.time.DayOfWeek.TUESDAY -> com.piercingxx.calendar.core.Weekday.TU
        java.time.DayOfWeek.WEDNESDAY -> com.piercingxx.calendar.core.Weekday.WE
        java.time.DayOfWeek.THURSDAY -> com.piercingxx.calendar.core.Weekday.TH
        java.time.DayOfWeek.FRIDAY -> com.piercingxx.calendar.core.Weekday.FR
        java.time.DayOfWeek.SATURDAY -> com.piercingxx.calendar.core.Weekday.SA
        else -> com.piercingxx.calendar.core.Weekday.SU
    }

fun ordinalWord(day: Int): String = when {
    day % 10 == 1 && day % 100 != 11 -> "${day}st"
    day % 10 == 2 && day % 100 != 12 -> "${day}nd"
    day % 10 == 3 && day % 100 != 13 -> "${day}rd"
    else -> "${day}th"
}

fun nthWord(nth: Int): String = when (nth) {
    -1 -> "last"
    else -> ordinalWord(nth)
}

private fun monthName(month: Int): String =
    if (month in 1..12) {
        java.time.Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault())
    } else {
        month.toString()
    }

fun reminderLabel(minutes: Int): String = when {
    minutes == 0 -> "at time of event"
    minutes % 1440 == 0 -> plural(minutes / 1440, "day")
    minutes % 60 == 0 -> plural(minutes / 60, "hour")
    else -> plural(minutes, "minute")
} + " before"

private fun plural(n: Int, unit: String): String = if (n == 1) "1 $unit" else "$n $unit" + "s"

// ---- Reminders write path ----------------------------------------------------

/**
 * Replace the Reminders rows for [eventId] with exactly [minutesList], all
 * METHOD_ALERT (§6.2 never METHOD_EMAIL), in a single applyBatch so a failure
 * leaves the previous reminders intact instead of a delete-only partial state.
 * On batch failure this throws and the editor stays open (§10).
 */
suspend fun replaceReminders(resolver: ContentResolver, eventId: Long, minutesList: List<Int>) =
    withContext(Dispatchers.IO) {
        val operations = ArrayList<ContentProviderOperation>()
        operations += ContentProviderOperation.newDelete(Reminders.CONTENT_URI)
            .withSelection("${Reminders.EVENT_ID}=?", arrayOf(eventId.toString()))
            .build()
        for (minutes in minutesList.distinct().sorted()) {
            operations += ContentProviderOperation.newInsert(Reminders.CONTENT_URI)
                .withValue(Reminders.EVENT_ID, eventId)
                .withValue(Reminders.MINUTES, minutes.coerceIn(0, 40320))
                .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
                .build()
        }
        try {
            resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        } catch (e: OperationApplicationException) {
            throw IllegalStateException("reminder replacement failed for event $eventId", e)
        } catch (e: RemoteException) {
            throw IllegalStateException("reminder replacement failed for event $eventId", e)
        }
    }
