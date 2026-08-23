package com.piercingxx.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

enum class Weekday { MO, TU, WE, TH, FR, SA, SU }

data class ByDay(val ordinal: Int?, val weekday: Weekday) {
    internal fun toToken(): String =
        if (ordinal == null) weekday.name else "$ordinal$weekday"
}

sealed interface EndCondition {
    data object Never : EndCondition

    /**
     * [dateOnly] mirrors RFC 5545 §3.3.10's value-type rule: UNTIL must be a
     * DATE (basic `yyyyMMdd`) when the series' DTSTART is VALUE=DATE (all-day),
     * a UTC DATE-TIME otherwise. Date-only form reads as UTC midnight.
     */
    data class Until(val untilMillisUtc: Long, val dateOnly: Boolean = false) : EndCondition

    data class Count(val occurrences: Int) : EndCondition
}

sealed interface RuleParse {
    data class Parsed(val rule: RRuleModel) : RuleParse
    data class Refused(val reason: String) : RuleParse
}

/**
 * Plain-data recurrence rule feeding [ScopeResolver] (design §6.3) and built by
 * the editor's rule builder (design §8.5). Pure JVM — no Android dependency.
 *
 * Wire form is the CalendarProvider RRULE value, canonical order:
 * `FREQ[;INTERVAL][;BYDAY][;BYMONTHDAY][;UNTIL|COUNT]` — UNTIL rendered as
 * UTC DATE-TIME `yyyyMMdd'T'HHmmss'Z'`, or basic DATE `yyyyMMdd` when the
 * series is all-day ([EndCondition.Until.dateOnly]); date-only input is
 * normalised to UTC midnight.
 */
data class RRuleModel(
    val frequency: Frequency,
    val interval: Int = 1,
    val byDay: List<ByDay> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val end: EndCondition = EndCondition.Never,
) {
    fun serialize(): String {
        val parts = mutableListOf("FREQ=$frequency")
        if (interval > 1) parts += "INTERVAL=$interval"
        if (byDay.isNotEmpty()) parts += "BYDAY=" + byDay.joinToString(",") { it.toToken() }
        if (byMonthDay.isNotEmpty()) parts += "BYMONTHDAY=" + byMonthDay.joinToString(",")
        when (val condition = end) {
            is EndCondition.Until -> parts += "UNTIL=" +
                (
                    if (condition.dateOnly) {
                        formatDate(condition.untilMillisUtc)
                    } else {
                        formatUntil(condition.untilMillisUtc)
                    }
                    )
            is EndCondition.Count -> parts += "COUNT=${condition.occurrences}"
            EndCondition.Never -> Unit
        }
        return parts.joinToString(";")
    }

    companion object {

        private val UNTIL_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
                .withResolverStyle(ResolverStyle.STRICT)

        private val UNTIL_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        fun daily(): RRuleModel = RRuleModel(frequency = Frequency.DAILY)

        fun weekly(byDay: List<Weekday>): RRuleModel =
            RRuleModel(frequency = Frequency.WEEKLY, byDay = byDay.map { ByDay(null, it) })

        fun monthlyByDate(monthDay: Int): RRuleModel =
            RRuleModel(frequency = Frequency.MONTHLY, byMonthDay = listOf(monthDay))

        fun monthlyByNthWeekday(byDay: ByDay): RRuleModel =
            RRuleModel(frequency = Frequency.MONTHLY, byDay = listOf(byDay))

        fun yearly(): RRuleModel = RRuleModel(frequency = Frequency.YEARLY)

        fun weekdays(): RRuleModel = weekly(
            listOf(Weekday.MO, Weekday.TU, Weekday.WE, Weekday.TH, Weekday.FR),
        )

        /**
         * Total parser: any input that does not satisfy the contract comes back
         * as [RuleParse.Refused] naming the problem — it never throws.
         */
        fun parse(raw: String): RuleParse = try {
            parseParts(raw)
        } catch (unexpected: Exception) {
            RuleParse.Refused("unparseable rule: ${unexpected.message}")
        }

        private fun parseParts(raw: String): RuleParse {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return RuleParse.Refused("rule is empty")

            var frequency: Frequency? = null
            var interval: Int? = null
            var untilMillis: Long? = null
            var untilDateOnly: Boolean = false
            var count: Int? = null
            val byDay = mutableListOf<ByDay>()
            val byMonthDay = mutableListOf<Int>()

            for (rawPart in trimmed.split(';')) {
                val part = rawPart.trim()
                val eq = part.indexOf('=')
                if (eq <= 0 || eq == part.length - 1) {
                    return RuleParse.Refused("malformed rule part \"$part\": expected KEY=VALUE")
                }
                val key = part.substring(0, eq).trim().uppercase()
                val value = part.substring(eq + 1).trim()
                when (key) {
                    "FREQ" -> {
                        frequency = when (value.uppercase()) {
                            "DAILY" -> Frequency.DAILY
                            "WEEKLY" -> Frequency.WEEKLY
                            "MONTHLY" -> Frequency.MONTHLY
                            "YEARLY" -> Frequency.YEARLY
                            "SECONDLY", "MINUTELY", "HOURLY" ->
                                return RuleParse.Refused(
                                    "unsupported frequency $value: sub-daily recurrence is out of scope",
                                )
                            else -> return RuleParse.Refused("unknown frequency \"$value\"")
                        }
                    }

                    "INTERVAL" -> {
                        val parsed = value.toIntOrNull()
                            ?: return RuleParse.Refused("INTERVAL must be an integer, was \"$value\"")
                        if (parsed < 1) {
                            return RuleParse.Refused("INTERVAL must be >= 1, was $parsed")
                        }
                        interval = parsed
                    }

                    "BYDAY" -> {
                        for (token in value.split(',')) {
                            val entry = parseByDayToken(token.trim())
                                ?: return RuleParse.Refused("malformed BYDAY entry \"$token\"")
                            byDay += entry
                        }
                    }

                    "BYMONTHDAY" -> {
                        for (token in value.split(',')) {
                            val day = token.trim().toIntOrNull()
                                ?: return RuleParse.Refused(
                                    "BYMONTHDAY must be an integer, was \"$token\"",
                                )
                            if (day !in -31..-1 && day !in 1..31) {
                                return RuleParse.Refused(
                                    "BYMONTHDAY must be 1..31 or -1..-31, was $day",
                                )
                            }
                            byMonthDay += day
                        }
                    }

                    "UNTIL" -> {
                        val parsed = parseUntilValue(value.uppercase())
                        if (parsed == null) {
                            return RuleParse.Refused(
                                "malformed UNTIL \"$value\": expected yyyyMMdd or yyyyMMdd'T'HHmmss'Z'",
                            )
                        }
                        untilMillis = parsed.first
                        untilDateOnly = parsed.second
                    }

                    "COUNT" -> {
                        val parsed = value.toIntOrNull()
                            ?: return RuleParse.Refused("COUNT must be an integer, was \"$value\"")
                        if (parsed < 1) {
                            return RuleParse.Refused("COUNT must be >= 1, was $parsed")
                        }
                        count = parsed
                    }

                    else -> return RuleParse.Refused("unsupported rule part: $key")
                }
            }

            val resolvedFrequency = frequency
                ?: return RuleParse.Refused("missing FREQ")

            if (untilMillis != null && count != null) {
                return RuleParse.Refused("UNTIL and COUNT cannot be combined in one rule")
            }

            for (entry in byDay) {
                val ordinal = entry.ordinal
                if (ordinal != null &&
                    resolvedFrequency != Frequency.MONTHLY &&
                    resolvedFrequency != Frequency.YEARLY
                ) {
                    return RuleParse.Refused(
                        "BYDAY ordinal $ordinal${entry.weekday} is only legal in MONTHLY or YEARLY",
                    )
                }
            }

            val end = when {
                untilMillis != null -> EndCondition.Until(untilMillis, untilDateOnly)
                count != null -> EndCondition.Count(count)
                else -> EndCondition.Never
            }

            return RuleParse.Parsed(
                RRuleModel(
                    frequency = resolvedFrequency,
                    interval = interval ?: 1,
                    byDay = byDay.toList(),
                    byMonthDay = byMonthDay.toList(),
                    end = end,
                ),
            )
        }

        private fun parseByDayToken(token: String): ByDay? {
            if (token.length < 2) return null
            val weekdayName = token.takeLast(2).uppercase()
            val weekday = Weekday.values().firstOrNull { it.name == weekdayName } ?: return null
            val number = token.dropLast(2)
            if (number.isEmpty()) return ByDay(null, weekday)
            val ordinal = number.toIntOrNull() ?: return null
            if (ordinal == 0) return null
            return ByDay(ordinal, weekday)
        }

        /** Millis + whether the input was the DATE form (`yyyyMMdd`). */
        private fun parseUntilValue(value: String): Pair<Long, Boolean>? = try {
            when {
                value.length == 8 -> Pair(
                    LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli(),
                    true,
                )

                else -> Pair(
                    LocalDateTime.parse(value, UNTIL_FORMAT)
                        .toInstant(ZoneOffset.UTC)
                        .toEpochMilli(),
                    false,
                )
            }
        } catch (_: DateTimeParseException) {
            null
        }

        private fun formatUntil(millisUtc: Long): String =
            UNTIL_FORMAT.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millisUtc))

        private fun formatDate(millisUtc: Long): String =
            // LocalDate, not ZonedDateTime: BASIC_ISO_DATE would append the
            // UTC offset ("...Z") for a zoned temporal.
            UNTIL_DATE_FORMAT.format(
                Instant.ofEpochMilli(millisUtc).atZone(ZoneOffset.UTC).toLocalDate(),
            )
    }
}
