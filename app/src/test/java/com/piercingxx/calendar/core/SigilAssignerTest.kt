package com.piercingxx.calendar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six-sigil allocation scheme (design §7.1): pure allocation over the
 * persisted map, lowest-tier-first reuse, overrides immutable, six ceiling.
 */
class SigilAssignerTest {

    private fun key(n: Long) = CalendarKey(calendarId = n, accountName = "test@example.com")

    // ---- §7.1: initial allocation fills TIER_1..TIER_6 in order

    @Test
    fun `six candidates receive tiers one through six in declaration order`() {
        val candidates = (1L..6L).map(::key)

        val result = SigilAssigner.assign(existing = emptyMap(), candidates = candidates)

        assertEquals(listOf(SigilTier.TIER_1, SigilTier.TIER_2, SigilTier.TIER_3, SigilTier.TIER_4, SigilTier.TIER_5, SigilTier.TIER_6), candidates.map(result.assignments::getValue))
        assertEquals(candidates, result.newlyAssigned)
    }

    // ---- §7.1: stability — persisted map replays without churn

    @Test
    fun `rerun over an already assigned set changes nothing and newlyAssigned stays empty`() {
        val candidates = (1L..6L).map(::key)
        val first = SigilAssigner.assign(emptyMap(), candidates)

        val second = SigilAssigner.assign(first.assignments, candidates)

        assertEquals(first.assignments, second.assignments)
        assertTrue(second.newlyAssigned.isEmpty())
    }

    // ---- §7.1: removal frees the tier; re-add reuses lowest freed tier first

    @Test
    fun `re-adding a removed calendar reclaims exactly its freed tier`() {
        val a = key(1)
        val b = key(2)
        val first = SigilAssigner.assign(emptyMap(), listOf(a, b))
        val withoutB = first.assignments - b

        val reassigned = SigilAssigner.assign(withoutB, listOf(a, b))

        assertEquals(SigilTier.TIER_2, reassigned.assignments[b])
        assertEquals(first.assignments, reassigned.assignments)
    }

    @Test
    fun `freed tiers are reused lowest-first when several are missing`() {
        val kept1 = key(1) to SigilTier.TIER_1
        val kept4 = key(4) to SigilTier.TIER_4
        val returning = key(9)

        val result = SigilAssigner.assign(
            existing = mapOf(kept1, kept4),
            candidates = listOf(key(1), key(4), returning),
        )

        assertEquals(SigilTier.TIER_2, result.assignments[returning])
    }

    // ---- §7.1: overrides are absolute and leave the pool untouched beneath them

    @Test
    fun `explicit override wins and the pool skips the overridden tier`() {
        val pinned = key(1)
        val fresh = key(2)

        val result = SigilAssigner.assign(
            existing = emptyMap(),
            candidates = listOf(pinned, fresh),
            overrides = mapOf(pinned to SigilTier.TIER_4),
        )

        assertEquals(SigilTier.TIER_4, result.assignments[pinned])
        assertEquals("allocation resumes below the overridden tier", SigilTier.TIER_1, result.assignments[fresh])
    }

    @Test
    fun `override sticks across passes and is never reallocated as new`() {
        val pinned = key(1)
        val overrides = mapOf(pinned to SigilTier.TIER_6)
        val first = SigilAssigner.assign(emptyMap(), listOf(pinned), overrides)

        val second = SigilAssigner.assign(first.assignments, listOf(pinned), overrides)

        assertEquals(SigilTier.TIER_6, second.assignments[pinned])
        assertTrue(second.newlyAssigned.isEmpty())
    }

    @Test
    fun `override replaces the existing entry for the same key instead of duplicating it`() {
        val k = key(1)

        val result = SigilAssigner.assign(
            existing = mapOf(k to SigilTier.TIER_3),
            candidates = listOf(k),
            overrides = mapOf(k to SigilTier.TIER_5),
        )

        assertEquals(mapOf(k to SigilTier.TIER_5), result.assignments)
    }

    // ---- §7.1: six is the ceiling; calendar seven onward falls back to TIER_1

    @Test
    fun `seventh and eighth calendars exhaust the pool and fall back to tier one`() {
        val candidates = (1L..8L).map(::key)

        val result = SigilAssigner.assign(emptyMap(), candidates)

        assertEquals(SigilTier.entries.toList().take(6), candidates.take(6).map(result.assignments::getValue))
        assertEquals(SigilTier.TIER_1, result.assignments[key(7)])
        assertEquals(SigilTier.TIER_1, result.assignments[key(8)])
    }
}
