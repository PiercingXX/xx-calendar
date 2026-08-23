package com.piercingxx.calendar.settings

import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.RuleParse
import com.piercingxx.calendar.core.TimeMath
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * The RFC 5545 subset XX-Calendar exchanges (design §9). Pure JVM — no Android
 * dependency — so the round-trip is unit-testable without a provider.
 *
 * Exported per VEVENT: UID, DTSTAMP, DTSTART/DTEND (date or datetime forms;
 * all-day = VALUE=DATE over UTC-midnight storage via [TimeMath]), DURATION when
 * the row carries one instead of an end, SUMMARY/LOCATION/DESCRIPTION,
 * RRULE (canonicalised through [RRuleModel.serialize]; unmodelled rules pass
 * through verbatim rather than being dropped), RDATE/EXDATE passthrough,
 * STATUS, TRANSP, and VALARM blocks (ACTION:DISPLAY) from reminder minutes.
 * Passthrough values are CR/LF-sanitised at parse and again at fold, so a
 * hostile row can never forge new properties on the way back out.
 *
 * Honest subset limits (each deliberate):
 *  - no VTIMEZONE emission; TZID params reference IANA ids directly, which
 *    every mainstream consumer resolves from its own tz database.
 *  - no attendees, organizer, categories, priority, attachments, alarms with
 *    REPEAT/DURATION or absolute triggers.
 *  - exception rows (ORIGINAL_ID set) are not modelled here at all.
 */
object IcsCodec {

    const val PRODID = "-//PiercingXX//XX-Calendar//EN"
    const val UID_DOMAIN = "xx-calendar"

    /** The deterministic UID this app writes on export (§9 duplicate detection). */
    fun uidOf(eventId: Long, calendarId: Long): String = "$eventId-$calendarId@$UID_DOMAIN"

    /**
     * One event to export — CalendarInstance/EventDraft-shaped plus reminder
     * minutes. [status] is a CalendarContract STATUS int, null when unknown
     * (the STATUS line is then omitted). [availability] is a CalendarContract
     * AVAILABILITY int. Reminder minutes follow provider semantics: positive =
     * minutes before start, negative = after.
     */
    data class IcsEvent(
        val eventId: Long,
        val calendarId: Long,
        val title: String?,
        val startMillis: Long,
        val endMillis: Long?,
        val allDay: Boolean,
        val eventTimezone: String?,
        val eventEndTimezone: String? = null,
        val location: String? = null,
        val description: String? = null,
        val rrule: String? = null,
        val duration: String? = null,
        val rdate: String? = null,
        val exdate: String? = null,
        val availability: Int = AVAILABILITY_BUSY,
        val status: Int? = null,
        val reminderMinutes: List<Int> = emptyList(),
    )

    /**
     * One parsed VEVENT ready for insertion into a chosen calendar. Times are
     * epoch millis resolved against the file's TZID (or UTC); `duration`
     * non-null means extent is carried there and [endMillis] is null, per the
     * provider's recurring-row contract. [status] is the CalendarContract
     * STATUS int, null when absent/unrecognised.
     */
    data class IcsEventDraft(
        val uid: String,
        val title: String?,
        val location: String?,
        val description: String?,
        val startMillis: Long,
        val endMillis: Long?,
        val allDay: Boolean,
        val eventTimezone: String?,
        val eventEndTimezone: String?,
        val duration: String?,
        val rrule: String?,
        val rdate: String?,
        val exdate: String?,
        val availability: Int,
        val status: Int?,
        val reminderMinutes: List<Int>,
    )

    data class IcsImport(
        val events: List<IcsEventDraft>,
        val skippedDuplicateUids: Int,
    )

    fun exportToString(
        events: List<IcsEvent>,
        nowMillisUtc: Long = System.currentTimeMillis(),
    ): String {
        val stamp = UTC_DATETIME_FORMAT.format(Instant.ofEpochMilli(nowMillisUtc))
        return buildString {
            line("BEGIN:VCALENDAR")
            line("VERSION:2.0")
            line("PRODID:$PRODID")
            line("CALSCALE:GREGORIAN")
            for (event in events) {
                line("BEGIN:VEVENT")
                line("UID:${uidOf(event.eventId, event.calendarId)}")
                line("DTSTAMP:$stamp")
                emitWhen(event)
                event.title?.let { textLine("SUMMARY", it) }
                event.location?.let { textLine("LOCATION", it) }
                event.description?.let { textLine("DESCRIPTION", it) }
                event.rrule?.takeIf { it.isNotBlank() }?.let {
                    rawLine("RRULE", canonicalRule(it))
                }
                event.rdate?.takeIf { it.isNotBlank() }?.let { rawLine("RDATE", it.trim()) }
                event.exdate?.takeIf { it.isNotBlank() }?.let { rawLine("EXDATE", it.trim()) }
                statusName(event.status)?.let { rawLine("STATUS", it) }
                transpName(event.availability)?.let { rawLine("TRANSP", it) }
                event.reminderMinutes
                    .distinct()
                    .sorted()
                    .forEach { emitAlarm(it) }
                line("END:VEVENT")
            }
            line("END:VCALENDAR")
        }
    }

    fun export(events: List<IcsEvent>, nowMillisUtc: Long = System.currentTimeMillis()): ByteArray =
        exportToString(events, nowMillisUtc).toByteArray(Charsets.UTF_8)

    fun parse(data: ByteArray, existingUids: Set<String> = emptySet()): IcsImport =
        parse(decode(data), existingUids)

    fun parse(text: String, existingUids: Set<String> = emptySet()): IcsImport {
        var skippedDuplicates = 0
        val events = mutableListOf<IcsEventDraft>()
        val seen = HashSet<String>(existingUids)
        val stack = ArrayDeque<String>()
        var builder: VEventBuilder? = null

        fun accept(finished: VEventBuilder?) {
            val draft = finished?.build() ?: return
            // Duplicate detection by UID (§9), against both the caller-supplied
            // store and earlier events in this file.
            if (draft.uid.isNotBlank() && !seen.add(draft.uid)) {
                skippedDuplicates += 1
            } else {
                events += draft
            }
        }

        for (rawLine in unfold(text)) {
            val lineText = rawLine.trim()
            if (lineText.isEmpty()) continue
            val upper = lineText.uppercase(Locale.ROOT)
            if (upper.startsWith("BEGIN:")) {
                val component = lineText.substring(6).trim().uppercase(Locale.ROOT)
                stack.addLast(component)
                if (component == "VEVENT") builder = VEventBuilder()
                continue
            }
            if (upper.startsWith("END:")) {
                val component = lineText.substring(4).trim().uppercase(Locale.ROOT)
                if (stack.isNotEmpty()) stack.removeLast()
                if (component == "VEVENT") {
                    accept(builder)
                    builder = null
                }
                continue
            }
            val context = stack.lastOrNull()
            val property = parseProperty(lineText) ?: continue
            when {
                context == "VALARM" && builder != null ->
                    if (property.name == "TRIGGER" &&
                        (property.params["VALUE"] ?: "DURATION").equals("DURATION", true)
                    ) {
                        builder.triggers += property.value.trim()
                    }

                context == "VEVENT" && builder != null -> builder.consume(property)
                // Wrapper metadata, VTIMEZONE, foreign components: outside the subset.
                else -> Unit
            }
        }
        // File truncated before END:VEVENT — emit what accumulated, leniently.
        accept(builder)
        return IcsImport(events, skippedDuplicates)
    }

    // ------------------------------------------------------------- folding

    private const val MAX_OCTETS = 75

    internal fun fold(raw: String): String {
        val line = sanitizeValue(raw)
        if (octets(line) <= MAX_OCTETS) return line
        val out = StringBuilder(line.length + 16)
        var budget = MAX_OCTETS
        var used = 0
        var i = 0
        while (i < line.length) {
            val codePoint = line.codePointAt(i)
            val width = utf8Octets(codePoint)
            if (used + width > budget) {
                out.append("\r\n ")
                // Continuation lines carry a leading space inside the same 75-octet budget.
                budget = MAX_OCTETS - 1
                used = 0
            }
            out.appendCodePoint(codePoint)
            used += width
            i += Character.charCount(codePoint)
        }
        return out.toString()
    }

    /** Reverses [fold]: strips CRLF (or LF) followed by exactly one space/tab. */
    internal fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                i++
                if (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
                    i++ // fold marker: continuation joins with no separator
                } else {
                    lines += current.toString()
                    current.setLength(0)
                }
            } else {
                current.append(ch)
                i++
            }
        }
        lines += current.toString()
        return lines
    }

    // ------------------------------------------------------------ escaping

    /**
     * Injection guard: a raw CR/LF inside any property value would serialize
     * as a real line break and forge whatever properties follow it. Passthrough
     * values (RRULE/RDATE/EXDATE/DURATION) are emitted unescaped, so every
     * parsed value is neutralised here and [fold] strips defensively again.
     */
    private fun sanitizeValue(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ')

    internal fun escapeText(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit // bare CR carries no meaning; CRLF collapses to LF
                else -> append(ch)
            }
        }
    }

    internal fun unescapeText(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    'n', 'N' -> append('\n')
                    '\\' -> append('\\')
                    ',' -> append(',')
                    ';' -> append(';')
                    else -> append(ch) // unknown escape keeps the backslash verbatim
                }
                i += 2
            } else {
                append(ch)
                i++
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun StringBuilder.line(content: String) {
        append(fold(content)).append("\r\n")
    }

    private fun StringBuilder.textLine(name: String, value: String) =
        line("$name:${escapeText(value)}")

    private fun StringBuilder.rawLine(name: String, value: String) = line("$name:$value")

    private fun StringBuilder.emitWhen(event: IcsEvent) {
        if (event.allDay) {
            val startDate = TimeMath.storageToAllDayDate(event.startMillis)
            val endDate = event.endMillis?.let(TimeMath::storageToAllDayDate) ?: startDate.plusDays(1)
            line("DTSTART;VALUE=DATE:${BASIC_DATE_FORMAT.format(startDate)}")
            line("DTEND;VALUE=DATE:${BASIC_DATE_FORMAT.format(endDate)}")
        } else {
            dateTimeLine("DTSTART", event.startMillis, event.eventTimezone)
            when {
                event.duration != null -> line("DURATION:${event.duration.trim()}")
                else -> dateTimeLine(
                    "DTEND",
                    event.endMillis ?: event.startMillis,
                    event.eventEndTimezone ?: event.eventTimezone,
                )
            }
        }
    }

    private fun StringBuilder.dateTimeLine(name: String, millis: Long, timezone: String?) {
        val instant = Instant.ofEpochMilli(millis)
        val zone = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        if (zone == null || zone == ZoneOffset.UTC) {
            line("$name:${UTC_DATETIME_FORMAT.format(instant)}")
        } else {
            line("$name;TZID=${paramToken(timezone!!)}:${LOCAL_DATETIME_FORMAT.format(instant.atZone(zone))}")
        }
    }

    private fun StringBuilder.emitAlarm(minutesBeforeStart: Int) {
        line("BEGIN:VALARM")
        line("ACTION:DISPLAY")
        textLine("DESCRIPTION", ALARM_DESCRIPTION)
        val trigger = when {
            minutesBeforeStart > 0 -> "-PT${minutesBeforeStart}M"
            minutesBeforeStart == 0 -> "PT0S"
            else -> "PT${-minutesBeforeStart}M"
        }
        line("TRIGGER:$trigger")
        line("END:VALARM")
    }

    /**
     * Modelable rules are re-emitted in RRuleModel canonical order; shapes the
     * model refuses (sub-daily frequencies, exotic parts) pass through
     * verbatim so import never silently loses recurrence.
     */
    private fun canonicalRule(raw: String): String =
        when (val parsed = RRuleModel.parse(raw)) {
            is RuleParse.Parsed -> parsed.rule.serialize()
            is RuleParse.Refused -> raw.trim()
        }

    private fun octets(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun utf8Octets(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    /** Quotes a parameter value that would otherwise break the property grammar. */
    private fun paramToken(value: String): String =
        if (value.any { it == ':' || it == ';' || it == ',' }) "\"$value\"" else value

    private fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            String(body, Charsets.ISO_8859_1)
        }
    }

    private fun statusName(status: Int?): String? = when (status) {
        STATUS_CONFIRMED -> "CONFIRMED"
        STATUS_CANCELED -> "CANCELLED"
        STATUS_TENTATIVE -> "TENTATIVE"
        else -> null
    }

    private fun statusFromIcs(value: String): Int? = when (value.trim().uppercase(Locale.ROOT)) {
        "CONFIRMED" -> STATUS_CONFIRMED
        "CANCELLED", "CANCELED" -> STATUS_CANCELED
        "TENTATIVE" -> STATUS_TENTATIVE
        else -> null
    }

    private fun transpName(availability: Int): String = when (availability) {
        AVAILABILITY_FREE -> "TRANSPARENT"
        AVAILABILITY_TENTATIVE -> "OPAQUE-TENTATIVE"
        else -> "OPAQUE"
    }

    private fun availabilityFromTransp(value: String): Int = when (
        value.trim().uppercase(Locale.ROOT)
    ) {
        "TRANSPARENT" -> AVAILABILITY_FREE
        "OPAQUE-TENTATIVE" -> AVAILABILITY_TENTATIVE
        else -> AVAILABILITY_BUSY
    }

    /**
     * Parses a TRIGGER duration into reminder minutes (positive = before
     * start, negative = after, matching provider MINUTES semantics).
     * Absolute date-time triggers and RELATED=END are unsupported and yield
     * null / are treated as start-relative respectively.
     */
    private fun parseTriggerMinutes(raw: String): Int? {
        val match = TRIGGER_DURATION.matchEntire(raw.trim().uppercase(Locale.ROOT)) ?: return null
        val sign = if (match.groupValues[1] == "-") -1L else 1L
        fun part(index: Int): Long = match.groupValues[index].ifEmpty { "0" }.toLongOrNull() ?: 0L
        val seconds = sign * (
            part(2) * 604_800 + part(3) * 86_400 +
                part(4) * 3_600 + part(5) * 60 + part(6)
            )
        val clamped = seconds.coerceIn(-MAX_TRIGGER_SECONDS, MAX_TRIGGER_SECONDS)
        val magnitudeMinutes = (Math.abs(clamped) + 59) / 60 // round toward sooner
        return (-magnitudeMinutes * (if (clamped < 0) -1L else 1L)).toInt()
    }

    private class VEventBuilder {
        var uid: String? = null
        var summary: String? = null
        var location: String? = null
        var description: String? = null
        var start: When? = null
        var end: When? = null
        var duration: String? = null
        var rrule: String? = null
        val rdates = mutableListOf<String>()
        val exdates = mutableListOf<String>()
        val triggers = mutableListOf<String>()
        var status: Int? = null
        var availability: Int? = null

        fun consume(property: Property) {
            when (property.name) {
                "UID" -> uid = property.value.trim()
                "SUMMARY" -> summary = unescapeText(property.value)
                "LOCATION" -> location = unescapeText(property.value)
                "DESCRIPTION" -> description = unescapeText(property.value)
                "DTSTART" -> start = parseWhen(property.params, property.value)
                "DTEND" -> end = parseWhen(property.params, property.value)
                "DURATION" -> duration = property.value.trim().uppercase(Locale.ROOT)
                "RRULE" -> rrule = property.value.trim()
                "RDATE" -> rdates += property.value.trim()
                "EXDATE" -> exdates += property.value.trim()
                "STATUS" -> status = statusFromIcs(property.value)
                "TRANSP" -> availability = availabilityFromTransp(property.value)
                else -> Unit // unknown property: ignored, per the subset contract
            }
        }

        fun build(): IcsEventDraft? {
            val startWhen = start ?: return null // no DTSTART: not an event we can place
            var endMillis: Long? = end?.millis
            if (duration != null) {
                endMillis = null // DURATION owns the extent; never both (provider contract)
            } else if (endMillis == null) {
                endMillis = if (startWhen.isAllDay) {
                    TimeMath.allDayDateToStorage(
                        TimeMath.storageToAllDayDate(startWhen.millis).plusDays(1),
                    )
                } else {
                    startWhen.millis // zero-length fallback for a missing DTEND
                }
            }
            return IcsEventDraft(
                uid = uid ?: "",
                title = summary,
                location = location,
                description = description,
                startMillis = startWhen.millis,
                endMillis = endMillis,
                allDay = startWhen.isAllDay,
                eventTimezone = startWhen.zoneId,
                eventEndTimezone = end?.zoneId?.takeIf { startWhen.zoneId != it },
                duration = duration,
                rrule = rrule,
                rdate = rdates.filter { it.isNotEmpty() }.joinToString(",").ifEmpty { null },
                exdate = exdates.filter { it.isNotEmpty() }.joinToString(",").ifEmpty { null },
                availability = availability ?: AVAILABILITY_BUSY,
                status = status,
                reminderMinutes = triggers.mapNotNull(::parseTriggerMinutes),
            )
        }
    }

    private data class When(val millis: Long, val isAllDay: Boolean, val zoneId: String?)

    private fun parseWhen(params: Map<String, String>, rawValue: String): When? {
        val value = rawValue.trim()
        if (value.isEmpty()) return null
        if (params["VALUE"]?.equals("DATE", true) == true || value.length == 8) {
            // RFC 5545 DATE form is basic ("20260704"); BASIC_ISO_DATE also
            // tolerates the extended spelling some producers emit.
            val date = runCatching {
                LocalDate.parse(value.uppercase(Locale.ROOT), DateTimeFormatter.BASIC_ISO_DATE)
            }.getOrNull() ?: return null
            return When(TimeMath.allDayDateToStorage(date), isAllDay = true, zoneId = null)
        }
        val normalized = value.replace('t', 'T').replace('z', 'Z').replace(' ', 'T')
        val isUtc = normalized.endsWith("Z")
        val body = if (isUtc) normalized.dropLast(1) else normalized
        val localDateTime =
            runCatching { LocalDateTime.parse(body, BASIC_DATETIME_PARSE_FORMAT) }.getOrNull()
                ?: return null
        val tzidParam = params["TZID"]
        val zone = tzidParam?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val resolvedZone: ZoneId = when {
            isUtc -> ZoneOffset.UTC
            zone != null -> zone
            else -> ZoneId.systemDefault() // floating time: interpreted locally
        }
        return When(
            millis = localDateTime.atZone(resolvedZone).toInstant().toEpochMilli(),
            isAllDay = false,
            zoneId = if (isUtc || zone == null) null else zone.id,
        )
    }

    private class Property(val name: String, val params: Map<String, String>, val value: String)

    private fun parseProperty(lineText: String): Property? {
        var inQuotes = false
        var colonIndex = -1
        for (i in lineText.indices) {
            val ch = lineText[i]
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ':' && !inQuotes -> {
                    colonIndex = i
                    break
                }
            }
        }
        if (colonIndex <= 0) return null
        val segments = splitUnquoted(lineText.substring(0, colonIndex), ';')
        val name = segments.firstOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return null
        val params = HashMap<String, String>()
        for (segment in segments.drop(1)) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            var value = segment.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1)
            }
            params[segment.substring(0, eq).trim().uppercase(Locale.ROOT)] = value
        }
        return Property(
            name.uppercase(Locale.ROOT),
            params,
            sanitizeValue(lineText.substring(colonIndex + 1)),
        )
    }

    private fun splitUnquoted(text: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in text) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    current.append(ch)
                }
                ch == delimiter && !inQuotes -> {
                    parts += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        parts += current.toString()
        return parts
    }

    private const val ALARM_DESCRIPTION = "reminder"

    // CalendarContract constants, mirrored as plain ints to stay pure-JVM.
    private const val AVAILABILITY_BUSY = 0
    private const val AVAILABILITY_FREE = 1
    private const val AVAILABILITY_TENTATIVE = 2
    private const val STATUS_CONFIRMED = 1
    private const val STATUS_CANCELED = 2
    private const val STATUS_TENTATIVE = 3

    /** Cap absurd triggers (~40 years) instead of overflowing the minutes column. */
    private val MAX_TRIGGER_SECONDS: Long = 40L * 365 * 86_400

    private val TRIGGER_DURATION = Regex(
        "^([+-]?)P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$",
    )

    private val BASIC_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    private val UTC_DATETIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withResolverStyle(ResolverStyle.STRICT)
            .withZone(ZoneOffset.UTC)

    private val LOCAL_DATETIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT)

    /** Reads the RFC 5545 DATE-TIME basic form ("20260302T090000"). */
    private val BASIC_DATETIME_PARSE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT)
}
