package com.piercingxx.calendar.ui.theme

import kotlin.math.roundToInt

/**
 * The seven named background presets of the PiercingXX family (brand guide
 * §3.3), mirrored verbatim from the xx-launcher / TxxT so theme auto-sync can
 * match by display name. Pure Kotlin — no `android.*` imports — so the model,
 * the contrast rule and the ground-scheme derivation are JVM-testable.
 */
enum class ThemePreset(
    /** Stable identifier used in persisted settings. */
    val key: String,
    /** Display name as carried in the launcher broadcast, e.g. "AMOLED Night". */
    val displayName: String,
    /** Background color as a 0xAARRGGBB long. */
    val background: Long,
    /** Whether the preset is a dark ground (white foreground ramp). */
    val isDark: Boolean,
) {
    AMOLED_NIGHT("amoled-night", "AMOLED Night", 0xFF000000, true),
    GRAPHITE("graphite", "Graphite", 0xFF131316, true),
    FOREST_NIGHT("forest-night", "Forest Night", 0xFF10261B, true),
    OCEAN_DRIFT("ocean-drift", "Ocean Drift", 0xFF0F1C2E, true),
    BURGUNDY("burgundy", "Burgundy", 0xFF2A1018, true),
    PAPER("paper", "Paper", 0xFFF3EEE2, false),
    MIST("mist", "Mist", 0xFFE6EDF5, false);

    companion object {
        /** The default preset (AMOLED Night — this app's shipped ground). */
        val DEFAULT: ThemePreset = AMOLED_NIGHT

        /** Persisted key for a launcher "Custom" theme (not a preset row). */
        const val CUSTOM_KEY = "custom"

        /** Display name the launcher broadcasts for a custom theme. */
        const val CUSTOM_DISPLAY_NAME = "Custom"

        /** Resolve by stable [key]; null for unknown so callers can fall back. */
        fun fromKey(key: String?): ThemePreset? =
            entries.firstOrNull { it.key == key }

        /** Resolve by display name (case-insensitive, per the family contract). */
        fun fromDisplayName(name: String?): ThemePreset? =
            entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
    }
}

/**
 * The synced ground: a preset (or custom) key plus the resolved background
 * ARGB. This is exactly what the launcher broadcast carries and what the
 * receiver persists; everything else is derived from [background].
 */
data class ThemeGround(
    /** [ThemePreset.key] or [ThemePreset.CUSTOM_KEY]. */
    val presetKey: String,
    /** Resolved background color as a 0xAARRGGBB long. */
    val background: Long,
)

/**
 * Resolve a launcher broadcast into a [ThemeGround].
 *
 * A known display name wins and uses the preset table's canonical background
 * (the broadcast's BACKGROUND int is redundant for named presets); "Custom"
 * requires the carried background; anything else — unknown name, missing
 * custom background — resolves to null so the receiver ignores it.
 */
fun resolveGround(displayName: String?, backgroundArgb: Long?): ThemeGround? {
    val preset = ThemePreset.fromDisplayName(displayName)
    if (preset != null) return ThemeGround(preset.key, preset.background)
    if (ThemePreset.CUSTOM_DISPLAY_NAME.equals(displayName, ignoreCase = true) &&
        backgroundArgb != null
    ) {
        return ThemeGround(ThemePreset.CUSTOM_KEY, backgroundArgb and 0xFFFFFFFFL)
    }
    return null
}

/**
 * Where a synced [ThemeGround] persists (SharedPreferences in production, an
 * in-memory fake in tests). Pure interface so the receiver's routing is
 * JVM-testable via seams.
 */
interface ThemeGroundStore {
    fun save(ground: ThemeGround)
    fun load(): ThemeGround?
}

// ---- family contrast rule (identical across all PiercingXX apps) ----

/** Ink foreground used on light grounds (Paper, Mist, light customs). */
const val INK_FOREGROUND = 0xFF1A1A1AL

/** Pure white — the signal foreground on dark grounds. */
const val WHITE_FOREGROUND = 0xFFFFFFFFL

/** Grounds brighter than this luminance take the ink foreground ramp. */
const val INK_LUMINANCE_THRESHOLD = 182.0

/** Perceived luminance (0..255): 0.299r + 0.587g + 0.114b. */
fun groundLuminance(argb: Long): Double {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return 0.299 * r + 0.587 * g + 0.114 * b
}

/** The family contrast rule: luminance > 182 → dark #FF1A1A1A foreground. */
fun prefersInkForeground(argb: Long): Boolean =
    groundLuminance(argb) > INK_LUMINANCE_THRESHOLD

// ---- ground scheme derivation ----

/**
 * The color slots the ground drives, as 0xAARRGGBB longs. Only ground-scoped
 * slots live here: background, the raised-surface ladder, the foreground
 * opacity ramp, and the accent (which inverts to ink on light grounds so it
 * stays visible). Everything else in the app keeps its shipped values.
 */
data class GroundScheme(
    val background: Long,
    /** Raised surface (cards / sheets) — background nudged toward the foreground. */
    val surfaceRaised: Long,
    /** Container surface — a stronger nudge (graphite analog). */
    val surfaceContainer: Long,
    /** Highest surface — the strongest nudge (slate analog). */
    val surfaceHigh: Long,
    /** Hairline (10% of the foreground). */
    val line: Long,
    /** Shade (25%). */
    val shade: Long,
    /** Secondary text (50%). */
    val muted: Long,
    /** Prominent glyphs (80%). */
    val strong: Long,
    /** Body text — the type ceiling (90%). */
    val text: Long,
    /** The accent: signal white on dark grounds, ink on light ones. */
    val accent: Long,
    /** Text on an accent block (inverted emphasis). */
    val accentOn: Long,
    /** False when the contrast rule picked the ink foreground. */
    val isDark: Boolean,
)

// Foreground opacity stops, matching the shipped pxx_white_* resources.
private const val LINE_STOP = 0x1A // 10%
private const val SHADE_STOP = 0x40 // 25%
private const val MUTED_STOP = 0x80 // 50%
private const val STRONG_STOP = 0xCC // 80%
private const val TEXT_STOP = 0xE6 // 90%

// Surface nudges toward the foreground, echoing the shipped ink-raised /
// graphite / slate steps off pure black (9, 19, 24 out of 255).
private const val RAISED_STEP = 0.035f
private const val CONTAINER_STEP = 0.075f
private const val HIGH_STEP = 0.095f

/**
 * Derive the ground-scoped scheme for [background] via the family contrast
 * rule: dark grounds ramp white and keep the signal-white accent; light
 * grounds ramp #FF1A1A1A ink and invert the accent to ink so it stays
 * visible. Surfaces step from the ground toward the foreground.
 */
fun deriveGroundScheme(background: Long): GroundScheme {
    val ink = prefersInkForeground(background)
    val fgRgb = if (ink) 0x1A1A1AL else 0xFFFFFFL
    val surfaceTarget = if (ink) 0xFF000000L else 0xFFFFFFFFL
    return GroundScheme(
        background = background,
        surfaceRaised = mix(background, surfaceTarget, RAISED_STEP),
        surfaceContainer = mix(background, surfaceTarget, CONTAINER_STEP),
        surfaceHigh = mix(background, surfaceTarget, HIGH_STEP),
        line = withAlpha(fgRgb, LINE_STOP),
        shade = withAlpha(fgRgb, SHADE_STOP),
        muted = withAlpha(fgRgb, MUTED_STOP),
        strong = withAlpha(fgRgb, STRONG_STOP),
        text = withAlpha(fgRgb, TEXT_STOP),
        accent = if (ink) INK_FOREGROUND else WHITE_FOREGROUND,
        accentOn = if (ink) WHITE_FOREGROUND else 0xFF000000L,
        isDark = !ink,
    )
}

/** Linearly interpolate [color] toward [target] by [fraction], keeping alpha. */
private fun mix(color: Long, target: Long, fraction: Float): Long {
    val a = (color shr 24) and 0xFF
    fun channel(shift: Int): Long {
        val from = ((color shr shift) and 0xFF).toFloat()
        val to = ((target shr shift) and 0xFF).toFloat()
        return (from + (to - from) * fraction).roundToInt().toLong()
    }
    return (a shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** Build an ARGB long from an RGB base and an alpha stop. */
private fun withAlpha(rgb: Long, alpha: Int): Long =
    (alpha.toLong() shl 24) or (rgb and 0xFFFFFF)
