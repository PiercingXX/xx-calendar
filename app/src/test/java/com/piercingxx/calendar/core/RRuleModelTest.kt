package com.piercingxx.calendar.core

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive suite for the RRuleModel contract: preset construction and wire
 * form, parse/serialize round-trips, canonical reparse, and every refusal the
 * parser owes its callers (design §8.5 rule builder, §6.3 scope writes).
 * Pure JVM, deterministic.
 */
class RRuleModelTest {

    private fun untilUtc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

    private fun midnightUtc(isoDate: String): Long =
        LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun assertParsesTo(raw: String, expected: RRuleModel) {
        val result = RRuleModel.parse(raw)
        assertTrue("expected Parsed for \"$raw\" but was $result", result is RuleParse.Parsed)
        assertEquals(expected, (result as RuleParse.Parsed).rule)
    }

    private fun refusedReason(raw: String): String {
        val result = RRuleModel.parse(raw)
        assertTrue("expected Refused for \"$raw\" but was $result", result is RuleParse.Refused)
        return (result as RuleParse.Refused).reason
    }

    // ---- presets build and serialize correctly ---------------------------

    @Test
    fun `daily preset serializes to FREQ only`() {
        assertEquals("FREQ=DAILY", RRuleModel.daily().serialize())
        assertEquals(RRuleModel(frequency = Frequency.DAILY), RRuleModel.daily())
    }

    @Test
    fun `weekly preset serializes its by-days in order`() {
        val rule = RRuleModel.weekly(listOf(Weekday.TU, Weekday.TH))
        assertEquals(
            RRuleModel(Frequency.WEEKLY, byDay = listOf(ByDay(null, Weekday.TU), ByDay(null, Weekday.TH))),
            rule,
        )
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", rule.serialize())
    }

    @Test
    fun `monthlyByDate preset serializes byMonthDay`() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", RRuleModel.monthlyByDate(15).serialize())
    }

    @Test
    fun `monthlyByNthWeekday preset serializes ordinal weekday`() {
        assertEquals(
            "FREQ=MONTHLY;BYDAY=2TU",
            RRuleModel.monthlyByNthWeekday(ByDay(2, Weekday.TU)).serialize(),
        )
    }

    @Test
    fun `yearly preset serializes to FREQ only`() {
        assertEquals("FREQ=YEARLY", RRuleModel.yearly().serialize())
    }

    @Test
    fun `weekdays preset is weekly MO through FR`() {
        val expected = listOf(Weekday.MO, Weekday.TU, Weekday.WE, Weekday.TH, Weekday.FR)
            .map { ByDay(null, it) }
        assertEquals(RRuleModel(Frequency.WEEKLY, byDay = expected), RRuleModel.weekdays())
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", RRuleModel.weekdays().serialize())
    }

    // ---- round-trip: parse(serialize(m)) == m ----------------------------

    private val roundTripCases = listOf(
        RRuleModel.daily(),
        RRuleModel.weekdays(),
        RRuleModel.yearly(),
        RRuleModel.monthlyByDate(31),
        RRuleModel.monthlyByDate(-1),
        RRuleModel.monthlyByNthWeekday(ByDay(1, Weekday.MO)),
        RRuleModel.monthlyByNthWeekday(ByDay(-1, Weekday.FR)),
        RRuleModel(Frequency.WEEKLY, interval = 2, byDay = listOf(ByDay(null, Weekday.WE))),
        RRuleModel(Frequency.MONTHLY, interval = 3, byDay = listOf(ByDay(2, Weekday.TU)), byMonthDay = listOf(1, -1)),
        RRuleModel(Frequency.YEARLY, byMonthDay = listOf(29)),
        RRuleModel(Frequency.DAILY, end = EndCondition.Count(occurrences = 12)),
        RRuleModel(Frequency.DAILY, end = EndCondition.Until(untilUtc(2026, 12, 31, 23, 59, 59))),
        RRuleModel(Frequency.DAILY, end = EndCondition.Until(midnightUtc("2026-08-31"))),
        RRuleModel(
            Frequency.WEEKLY,
            byDay = listOf(ByDay(null, Weekday.MO)),
            end = EndCondition.Until(midnightUtc("2026-08-31"), dateOnly = true),
        ),
        RRuleModel(
            frequency = Frequency.WEEKLY,
            interval = 2,
            byDay = listOf(ByDay(null, Weekday.MO), ByDay(null, Weekday.FR)),
            end = EndCondition.Count(occurrences = 6),
        ),
    )

    @Test
    fun `round trip preserves every model shape`() {
        for (model in roundTripCases) {
            val serialized = model.serialize()
            val result = RRuleModel.parse(serialized)
            assertTrue("expected Parsed for \"$serialized\" but was $result", result is RuleParse.Parsed)
            assertEquals(model, (result as RuleParse.Parsed).rule)
        }
    }

    // ---- canonical reparse -------------------------------------------------

    @Test
    fun `case-varied input reparses to canonical serialization`() {
        val raw = "freq=Weekly;Interval=2;byday=Mo,We,Fr;count=10"
        val parsed = RRuleModel.parse(raw)
        assertTrue(parsed is RuleParse.Parsed)
        val expected = RRuleModel(
            Frequency.WEEKLY,
            interval = 2,
            byDay = listOf(ByDay(null, Weekday.MO), ByDay(null, Weekday.WE), ByDay(null, Weekday.FR)),
            end = EndCondition.Count(10),
        )
        assertEquals(expected, (parsed as RuleParse.Parsed).rule)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR;COUNT=10", parsed.rule.serialize())
    }

    @Test
    fun `shuffled part order serializes canonically`() {
        val raw = "BYMONTHDAY=-1;BYDAY=2TU,-1FR;FREQ=monthly"
        val parsed = RRuleModel.parse(raw)
        assertTrue(parsed is RuleParse.Parsed)
        assertEquals(
            "FREQ=MONTHLY;BYDAY=2TU,-1FR;BYMONTHDAY=-1",
            (parsed as RuleParse.Parsed).rule.serialize(),
        )
    }

    @Test
    fun `explicit INTERVAL=1 is normalized away`() {
        assertParsesTo("INTERVAL=1;freq=daily", RRuleModel.daily())
        val parsed = RRuleModel.parse("INTERVAL=1;FREQ=DAILY")
        assertTrue(parsed is RuleParse.Parsed)
        assertEquals("FREQ=DAILY", (parsed as RuleParse.Parsed).rule.serialize())
    }

    // ---- nth-weekday parse and build ----------------------------------------

    @Test
    fun `nth weekday 2TU parses into MONTHLY by-day`() {
        assertParsesTo(
            "FREQ=MONTHLY;BYDAY=2TU",
            RRuleModel.monthlyByNthWeekday(ByDay(2, Weekday.TU)),
        )
    }

    @Test
    fun `last weekday -1FR parses and round-trips`() {
        val parsed = RRuleModel.parse("FREQ=MONTHLY;BYDAY=-1FR")
        assertTrue(parsed is RuleParse.Parsed)
        assertEquals(ByDay(-1, Weekday.FR), (parsed as RuleParse.Parsed).rule.byDay.single())

        val built = RRuleModel.monthlyByNthWeekday(ByDay(-1, Weekday.FR))
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", built.serialize())
        assertParsesTo(built.serialize(), built)
    }

    @Test
    fun `multiple nth weekdays keep input order`() {
        val parsed = RRuleModel.parse("FREQ=MONTHLY;BYDAY=1MO,-1SU")
        assertTrue(parsed is RuleParse.Parsed)
        assertEquals(
            listOf(ByDay(1, Weekday.MO), ByDay(-1, Weekday.SU)),
            (parsed as RuleParse.Parsed).rule.byDay,
        )
    }

    @Test
    fun `ordinals are legal in YEARLY too`() {
        assertParsesTo(
            "FREQ=YEARLY;BYDAY=-1SU",
            RRuleModel(Frequency.YEARLY, byDay = listOf(ByDay(-1, Weekday.SU))),
        )
    }

    // ---- UNTIL forms ---------------------------------------------------------

    @Test
    fun `datetime UNTIL parses with dateOnly false and round-trips`() {
        val expectedMillis = untilUtc(2026, 8, 31, 23, 59, 59)
        val parsed = RRuleModel.parse("FREQ=DAILY;UNTIL=20260831T235959Z")
        assertTrue(parsed is RuleParse.Parsed)
        val end = (parsed as RuleParse.Parsed).rule.end as EndCondition.Until
        assertEquals(expectedMillis, end.untilMillisUtc)
        assertEquals(false, end.dateOnly)
        assertEquals("FREQ=DAILY;UNTIL=20260831T235959Z", parsed.rule.serialize())
    }

    @Test
    fun `date-only UNTIL becomes UTC midnight with dateOnly true and round-trips as DATE`() {
        val expectedMillis = midnightUtc("2026-08-31")
        val parsed = RRuleModel.parse("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260831")
        assertTrue(parsed is RuleParse.Parsed)
        val end = (parsed as RuleParse.Parsed).rule.end as EndCondition.Until
        assertEquals(expectedMillis, end.untilMillisUtc)
        assertEquals(true, end.dateOnly)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260831", parsed.rule.serialize())
    }

    // ---- refusals ------------------------------------------------------------

    @Test
    fun `sub-daily frequencies are refused by name`() {
        for (freq in listOf("SECONDLY", "MINUTELY", "HOURLY")) {
            val reason = refusedReason("FREQ=$freq")
            assertTrue(
                "refusal for $freq should name it, was: $reason",
                reason.contains(freq, ignoreCase = true),
            )
        }
    }

    @Test
    fun `unknown frequency is refused`() {
        assertTrue(refusedReason("FREQ=FORTNIGHTLY").contains("FORTNIGHTLY", ignoreCase = true))
    }

    @Test
    fun `missing FREQ is refused even with other valid parts`() {
        assertTrue(RRuleModel.parse("BYDAY=MO") is RuleParse.Refused)
    }

    @Test
    fun `INTERVAL below one or non-numeric is refused`() {
        assertTrue(RRuleModel.parse("FREQ=DAILY;INTERVAL=0") is RuleParse.Refused)
        assertTrue(RRuleModel.parse("FREQ=DAILY;INTERVAL=-3") is RuleParse.Refused)
        assertTrue(RRuleModel.parse("FREQ=DAILY;INTERVAL=abc") is RuleParse.Refused)
    }

    @Test
    fun `BYDAY ordinals are refused outside MONTHLY and YEARLY`() {
        assertTrue(refusedReason("FREQ=WEEKLY;BYDAY=2MO").contains("ordinal", ignoreCase = true))
        assertTrue(RRuleModel.parse("FREQ=DAILY;BYDAY=-1TU") is RuleParse.Refused)
    }

    @Test
    fun `BYMONTHDAY out of range or non-numeric is refused`() {
        assertTrue(RRuleModel.parse("FREQ=MONTHLY;BYMONTHDAY=0") is RuleParse.Refused)
        assertTrue(RRuleModel.parse("FREQ=MONTHLY;BYMONTHDAY=32") is RuleParse.Refused)
        assertTrue(RRuleModel.parse("FREQ=MONTHLY;BYMONTHDAY=-32") is RuleParse.Refused)
        assertTrue(RRuleModel.parse("FREQ=MONTHLY;BYMONTHDAY=x") is RuleParse.Refused)
    }

    @Test
    fun `UNTIL combined with COUNT is refused`() {
        val reason = refusedReason("FREQ=DAILY;COUNT=5;UNTIL=20260831T000000Z")
        assertTrue(reason.contains("UNTIL", ignoreCase = true) && reason.contains("COUNT", ignoreCase = true))
    }

    @Test
    fun `unknown parts are refused naming the part`() {
        for (part in listOf("BYSETPOS=1", "BYMONTH=3", "WKST=MO")) {
            val key = part.substringBefore('=')
            val reason = refusedReason("FREQ=DAILY;$part")
            assertTrue(
                "refusal should name $key, was: $reason",
                reason.contains(key, ignoreCase = true),
            )
        }
    }

    @Test
    fun `malformed parts are refused without throwing`() {
        val hostileInputs = listOf(
            "",
            "   ",
            ";",
            "=",
            "FREQ",
            "FREQ=",
            "FREQ=DAILY;",
            "FREQ=DAILY;;COUNT=2",
            "FREQ=DAILY;BYDAY=",
            "FREQ=DAILY;BYDAY=MO,",
            "FREQ=DAILY;BYDAY=0MO",
            "FREQ=DAILY;BYDAY=99ZZ",
            "FREQ=DAILY;BYMONTHDAY=x",
            "FREQ=DAILY;BYMONTHDAY=1,0",
            "FREQ=DAILY;UNTIL=NOTADATE",
            "FREQ=DAILY;UNTIL=20261331T000000Z",
            "FREQ=DAILY;UNTIL=20260230T000000Z",
            "FREQ=DAILY;UNTIL=20260831T25:00:00Z",
            "FREQ=DAILY;UNTIL=2026-08-31",
            "FREQ=DAILY;INTERVAL=",
            "FREQ=DAILY;COUNT=abc",
            "FREQ=DAILY;COUNT=0",
            "freq=daily;until=20260831t000000z;count=1",
        )
        for (raw in hostileInputs) {
            assertTrue("expected Refused (never throw) for \"$raw\"", RRuleModel.parse(raw) is RuleParse.Refused)
        }
    }
}
