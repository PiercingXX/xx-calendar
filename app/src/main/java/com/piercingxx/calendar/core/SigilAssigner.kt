package com.piercingxx.calendar.core

/**
 * The six-sigil identity scheme (design §7.1). Tier order is the allocation
 * order; the ramp name names the white-opacity stop each sigil renders at.
 */
enum class SigilTier(val glyph: String, val rampName: String) {
    TIER_1("▌", "text"),   // 90%
    TIER_2("▏", "strong"), // 80%
    TIER_3("░", "muted"),  // 50%
    TIER_4("▒", "shade"),  // 25%
    TIER_5("▓", "muted"),  // 50%
    TIER_6("·", "shade"),  // 25%
}

/** Identity of a calendar across provider rows and the persisted map. */
data class CalendarKey(val calendarId: Long, val accountName: String)

/** Result of an assignment pass: the full map to persist, plus what is new. */
data class SigilAssignment(
    val assignments: Map<CalendarKey, SigilTier>,
    val newlyAssigned: List<CalendarKey>,
)

/**
 * Allocates sigils to calendars as pure functions over the persisted map —
 * persistence itself lives in DataStore (design §6.1), never here.
 *
 * Rules:
 * - existing assignments are immutable; only unseen calendars get allocated
 * - explicit overrides always win and are never reassigned
 * - overrides apply even when their key never appears in candidates: such a
 *   key still holds its tier out of the pool for others ("overrides always
 *   win" semantics, intentional)
 * - tiers freed by a removed calendar return to the pool and are reused
 *   lowest-tier-first on re-add
 * - six is the ceiling; calendar seven onward reuses [SigilTier.TIER_1]
 *   (documented fallback — past six no scheme works, hue included)
 */
object SigilAssigner {

    fun assign(
        existing: Map<CalendarKey, SigilTier>,
        candidates: List<CalendarKey>,
        overrides: Map<CalendarKey, SigilTier> = emptyMap(),
    ): SigilAssignment {
        val effective = overrides + existing.filterKeys { it !in overrides }
        val claimed = effective.values.toSet()
        val pool = SigilTier.entries.filter { it !in claimed }

        val result = effective.toMutableMap()
        val fresh = mutableListOf<CalendarKey>()
        var nextPoolIndex = 0

        for (key in candidates) {
            if (key in result) continue
            val tier = pool.getOrNull(nextPoolIndex++) ?: SigilTier.TIER_1
            result[key] = tier
            fresh += key
        }
        return SigilAssignment(result.toMap(), fresh)
    }
}
