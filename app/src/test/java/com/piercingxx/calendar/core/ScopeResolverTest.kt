package com.piercingxx.calendar.core

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The priority suite (design §11): every row of the §6.3 scope table asserted
 * as the exact intended provider operation. Pure JVM, deterministic.
 */
class ScopeResolverTest {

    private val parentId = 42L

    private val timedInstanceMillis: Long =
        ZonedDateTime.of(2026, 8, 17, 14, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private val allDayMidnightUtc: Long =
        ZonedDateTime.of(2026, 8, 17, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private val weeklyNever =
        RRuleModel(frequency = Frequency.WEEKLY, byDay = listOf(ByDay(null, Weekday.MO)))

    private val countBounded =
        RRuleModel(frequency = Frequency.DAILY, end = EndCondition.Count(occurrences = 10))

    private val edits = EventFieldEdits(title = "moved", location = "annex")

    private fun ctx(
        rule: RRuleModel?,
        start: Long = timedInstanceMillis,
        allDay: Boolean = false,
    ) = RecurringEventContext(
        parentEventId = parentId,
        rule = rule,
        startMillis = start,
        allDay = allDay,
    )

    private fun inst(start: Long = timedInstanceMillis) = InstanceRef(parentId, start)

    // ---- §6.3 row: non-recurring events never get a scope path ----------

    @Test
    fun `non-recurring edit resolves to UpdateParentRow ignoring any scope`() {
        val r = ScopeResolver.resolveEdit(ctx(rule = null), RecurrenceScope.ThisInstance, inst(), edits)

        assertTrue("expected UpdateParentRow but was $r", r is Resolution.UpdateParentRow)
        r as Resolution.UpdateParentRow
        assertEquals(parentId, r.parentEventId)
        assertEquals("moved", r.edits.title)
        assertEquals("annex", r.edits.location)
        assertSame(edits, r.edits)
    }

    @Test
    fun `non-recurring delete resolves to DeleteParentRow ignoring any scope`() {
        val r = ScopeResolver.resolveDelete(ctx(rule = null), RecurrenceScope.ThisAndFollowing, inst())

        assertEquals(Resolution.DeleteParentRow(parentId), r)
    }

    // ---- §6.3 row: edit, this instance only ------------------------------

    @Test
    fun `edit this-instance inserts exception row carrying edits untouched`() {
        val r = ScopeResolver.resolveEdit(ctx(weeklyNever), RecurrenceScope.ThisInstance, inst(), edits)

        assertTrue("expected InsertExceptionRow but was $r", r is Resolution.InsertExceptionRow)
        r as Resolution.InsertExceptionRow
        assertEquals(parentId, r.parentEventId)
        assertEquals(timedInstanceMillis, r.originalInstanceTimeMillis)
        assertSame(edits, r.newRowEdits)
        assertEquals("moved", r.newRowEdits.title)
        assertEquals(null, r.newRowEdits.startMillis)
        assertEquals(null, r.newRowEdits.allDay)
    }

    @Test
    fun `exception row stamps the instance uri time not the series start`() {
        val seriesStart = timedInstanceMillis - 14L * 86_400_000L
        val occurrence = timedInstanceMillis

        val r = ScopeResolver.resolveEdit(
            ctx(weeklyNever, start = seriesStart),
            RecurrenceScope.ThisInstance,
            InstanceRef(parentId, occurrence),
            edits,
        ) as Resolution.InsertExceptionRow

        assertEquals(occurrence, r.originalInstanceTimeMillis)
    }

    // ---- §6.3 row: edit, this and following ------------------------------

    @Test
    fun `edit this-and-following splits parent with until just before instance and preserved rule`() {
        val rule = RRuleModel(
            frequency = Frequency.MONTHLY,
            interval = 2,
            byDay = listOf(ByDay(1, Weekday.WE), ByDay(-1, Weekday.FR)),
            byMonthDay = listOf(1, -1),
        )

        val r = ScopeResolver.resolveEdit(ctx(rule), RecurrenceScope.ThisAndFollowing, inst(), edits)

        assertTrue("expected SplitParent but was $r", r is Resolution.SplitParent)
        r as Resolution.SplitParent
        assertEquals(parentId, r.parentEventId)
        assertEquals(timedInstanceMillis - 1, r.newUntil.untilMillisUtc)
        assertFalse("timed series keeps DATE-TIME UNTIL", r.newUntil.dateOnly)
        assertEquals(timedInstanceMillis, r.newRowStartMillis)
        assertSame(edits, r.newRowEdits)
        assertEquals(Frequency.MONTHLY, r.remainingRule.frequency)
        assertEquals(2, r.remainingRule.interval)
        assertEquals(listOf(ByDay(1, Weekday.WE), ByDay(-1, Weekday.FR)), r.remainingRule.byDay)
        assertEquals(listOf(1, -1), r.remainingRule.byMonthDay)
        assertEquals(EndCondition.Never, r.remainingRule.end)
    }

    @Test
    fun `edit this-and-following on UNTIL-bounded parent keeps UNTIL on the remaining rule`() {
        val until = allDayMidnightUtc + 30L * 86_400_000L
        val rule = RRuleModel(frequency = Frequency.WEEKLY, end = EndCondition.Until(until))

        val r = ScopeResolver.resolveEdit(
            ctx(rule),
            RecurrenceScope.ThisAndFollowing,
            inst(),
            edits,
        ) as Resolution.SplitParent

        assertEquals(timedInstanceMillis - 1, r.newUntil.untilMillisUtc)
        assertFalse(r.newUntil.dateOnly)
        assertEquals(timedInstanceMillis, r.newRowStartMillis)
        // The continuation carries "the remaining rule" (§6.3): a bounded
        // series stays bounded instead of becoming an infinite one.
        assertEquals(EndCondition.Until(until), r.remainingRule.end)
        assertEquals(rule, r.remainingRule)
    }

    @Test
    fun `edit this-and-following on a never-ending parent stays never-ending`() {
        val r = ScopeResolver.resolveEdit(
            ctx(weeklyNever),
            RecurrenceScope.ThisAndFollowing,
            inst(),
            edits,
        ) as Resolution.SplitParent

        assertEquals(EndCondition.Never, r.remainingRule.end)
    }

    @Test
    fun `edit this-and-following refuses COUNT-bounded series`() {
        val r = ScopeResolver.resolveEdit(
            ctx(countBounded),
            RecurrenceScope.ThisAndFollowing,
            inst(),
            edits,
        )

        assertTrue("expected Refusal but was $r", r is Resolution.Refusal)
        assertEquals(
            "cannot split a COUNT-bounded series without expanding it",
            (r as Resolution.Refusal).reason,
        )
        assertTrue(r.reason.contains("COUNT"))
    }

    @Test
    fun `edit all-events updates parent row in place`() {
        val r = ScopeResolver.resolveEdit(ctx(weeklyNever), RecurrenceScope.AllEvents, inst(), edits)

        assertTrue("expected UpdateParentRow but was $r", r is Resolution.UpdateParentRow)
        r as Resolution.UpdateParentRow
        assertEquals(parentId, r.parentEventId)
        assertSame(edits, r.edits)
    }

    // ---- §6.3 rows: delete ------------------------------------------------

    @Test
    fun `delete this-instance resolves to canceling the tapped occurrence`() {
        val r = ScopeResolver.resolveDelete(ctx(weeklyNever), RecurrenceScope.ThisInstance, inst())

        assertEquals(Resolution.DeleteInstanceUri(parentId, timedInstanceMillis), r)
    }

    @Test
    fun `delete this-and-following sets until just before instance`() {
        val r = ScopeResolver.resolveDelete(ctx(weeklyNever), RecurrenceScope.ThisAndFollowing, inst())

        assertTrue("expected SetUntil but was $r", r is Resolution.SetUntil)
        r as Resolution.SetUntil
        assertEquals(parentId, r.parentEventId)
        assertEquals(timedInstanceMillis - 1, r.until.untilMillisUtc)
        assertFalse("timed series keeps DATE-TIME UNTIL", r.until.dateOnly)
    }

    @Test
    fun `delete this-and-following on UNTIL-bounded parent still sets until`() {
        val rule = RRuleModel(frequency = Frequency.WEEKLY, end = EndCondition.Until(allDayMidnightUtc))

        val r = ScopeResolver.resolveDelete(
            ctx(rule),
            RecurrenceScope.ThisAndFollowing,
            inst(),
        ) as Resolution.SetUntil

        assertEquals(parentId, r.parentEventId)
        assertEquals(timedInstanceMillis - 1, r.until.untilMillisUtc)
    }

    @Test
    fun `delete this-and-following refuses COUNT-bounded series`() {
        val r = ScopeResolver.resolveDelete(ctx(countBounded), RecurrenceScope.ThisAndFollowing, inst())

        assertTrue("expected Refusal but was $r", r is Resolution.Refusal)
        assertEquals(
            "cannot split a COUNT-bounded series without expanding it",
            (r as Resolution.Refusal).reason,
        )
        assertTrue(r.reason.contains("COUNT"))
    }

    @Test
    fun `delete all-events deletes parent row`() {
        val r = ScopeResolver.resolveDelete(ctx(weeklyNever), RecurrenceScope.AllEvents, inst())

        assertEquals(Resolution.DeleteParentRow(parentId), r)
    }

    // ---- boundary edges ----------------------------------------------------

    @Test
    fun `boundary timed 14-00 instance yields until 13-59-59-999 same local day`() {
        val offset = ZoneOffset.ofHours(-5)
        val start =
            ZonedDateTime.of(2026, 8, 17, 14, 0, 0, 0, offset).toInstant().toEpochMilli()

        val r = ScopeResolver.resolveDelete(
            ctx(weeklyNever),
            RecurrenceScope.ThisAndFollowing,
            InstanceRef(parentId, start),
        ) as Resolution.SetUntil

        val expected = ZonedDateTime.of(2026, 8, 17, 13, 59, 59, 999_000_000, offset)
            .toInstant().toEpochMilli()
        assertEquals(expected, r.until.untilMillisUtc)
        assertFalse(r.until.dateOnly)
    }

    @Test
    fun `boundary all-day utc-midnight instance yields until previous day 23-59-59-999 utc`() {
        val r = ScopeResolver.resolveDelete(
            ctx(weeklyNever, allDay = true, start = allDayMidnightUtc),
            RecurrenceScope.ThisAndFollowing,
            InstanceRef(parentId, allDayMidnightUtc),
        ) as Resolution.SetUntil

        val expected = ZonedDateTime.of(2026, 8, 16, 23, 59, 59, 999_000_000, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        assertEquals(expected, r.until.untilMillisUtc)
        assertTrue("all-day series must carry DATE-form UNTIL", r.until.dateOnly)
    }

    @Test
    fun `boundary first occurrence this-and-following sets until preceding entire series`() {
        val seriesStart = timedInstanceMillis

        val editR = ScopeResolver.resolveEdit(
            ctx(weeklyNever, start = seriesStart),
            RecurrenceScope.ThisAndFollowing,
            InstanceRef(parentId, seriesStart),
            edits,
        ) as Resolution.SplitParent
        assertEquals(seriesStart - 1, editR.newUntil.untilMillisUtc)
        assertEquals(seriesStart, editR.newRowStartMillis)

        val deleteR = ScopeResolver.resolveDelete(
            ctx(weeklyNever, start = seriesStart),
            RecurrenceScope.ThisAndFollowing,
            InstanceRef(parentId, seriesStart),
        ) as Resolution.SetUntil
        assertEquals(seriesStart - 1, deleteR.until.untilMillisUtc)
    }

    // ---- reference semantics ----------------------------------------------

    @Test
    fun `edits object passes through by reference in every resolution variant`() {
        val update = ScopeResolver.resolveEdit(
            ctx(rule = null),
            RecurrenceScope.AllEvents,
            inst(),
            edits,
        ) as Resolution.UpdateParentRow

        val exception = ScopeResolver.resolveEdit(
            ctx(weeklyNever),
            RecurrenceScope.ThisInstance,
            inst(),
            edits,
        ) as Resolution.InsertExceptionRow

        val split = ScopeResolver.resolveEdit(
            ctx(weeklyNever),
            RecurrenceScope.ThisAndFollowing,
            inst(),
            edits,
        ) as Resolution.SplitParent

        assertSame(edits, update.edits)
        assertSame(edits, exception.newRowEdits)
        assertSame(edits, split.newRowEdits)
    }
}
