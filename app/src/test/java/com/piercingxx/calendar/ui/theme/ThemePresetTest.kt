package com.piercingxx.calendar.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The family theme-sync model: the seven presets and their canonical
 * backgrounds, the shared contrast rule (luminance > 182 -> ink foreground),
 * broadcast resolution into a ThemeGround, and the ground-scheme derivation
 * CalendarTheme overlays onto the shipped tokens. Pure JVM.
 */
class ThemePresetTest {

    // ---- preset resolution ----

    @Test
    fun `all seven display names resolve to their canonical preset`() {
        val expected = mapOf(
            "AMOLED Night" to (ThemePreset.AMOLED_NIGHT to 0xFF000000L),
            "Graphite" to (ThemePreset.GRAPHITE to 0xFF131316L),
            "Forest Night" to (ThemePreset.FOREST_NIGHT to 0xFF10261BL),
            "Ocean Drift" to (ThemePreset.OCEAN_DRIFT to 0xFF0F1C2EL),
            "Burgundy" to (ThemePreset.BURGUNDY to 0xFF2A1018L),
            "Paper" to (ThemePreset.PAPER to 0xFFF3EEE2L),
            "Mist" to (ThemePreset.MIST to 0xFFE6EDF5L),
        )
        assertEquals(expected.size, ThemePreset.entries.size)
        expected.forEach { (name, want) ->
            val (preset, background) = want
            assertSame(name, preset, ThemePreset.fromDisplayName(name))
            assertEquals(name, background, preset.background)
        }
    }

    @Test
    fun `display name resolution is case-insensitive`() {
        assertSame(ThemePreset.AMOLED_NIGHT, ThemePreset.fromDisplayName("amoled night"))
        assertSame(ThemePreset.FOREST_NIGHT, ThemePreset.fromDisplayName("FOREST NIGHT"))
        assertSame(ThemePreset.MIST, ThemePreset.fromDisplayName("mIsT"))
    }

    @Test
    fun `unknown display names resolve to null`() {
        assertNull(ThemePreset.fromDisplayName(null))
        assertNull(ThemePreset.fromDisplayName(""))
        assertNull(ThemePreset.fromDisplayName("Solarized"))
        // "Custom" is not a preset row; it resolves only through resolveGround.
        assertNull(ThemePreset.fromDisplayName("Custom"))
    }

    @Test
    fun `keys resolve and the default is amoled night`() {
        ThemePreset.entries.forEach { assertSame(it, ThemePreset.fromKey(it.key)) }
        assertNull(ThemePreset.fromKey(null))
        assertNull(ThemePreset.fromKey("no-such-key"))
        assertSame(ThemePreset.AMOLED_NIGHT, ThemePreset.DEFAULT)
    }

    @Test
    fun `isDark matches the contrast rule for every preset`() {
        ThemePreset.entries.forEach { preset ->
            assertEquals(preset.name, preset.isDark, !prefersInkForeground(preset.background))
        }
        assertFalse(ThemePreset.PAPER.isDark)
        assertFalse(ThemePreset.MIST.isDark)
    }

    // ---- contrast rule ----

    @Test
    fun `luminance uses the family weights`() {
        assertEquals(0.0, groundLuminance(0xFF000000), 1e-9)
        assertEquals(255.0, groundLuminance(0xFFFFFFFF), 1e-9)
        assertEquals(0.299 * 255, groundLuminance(0xFFFF0000), 1e-9)
        assertEquals(0.587 * 255, groundLuminance(0xFF00FF00), 1e-9)
        assertEquals(0.114 * 255, groundLuminance(0xFF0000FF), 1e-9)
    }

    @Test
    fun `luminance just above 182 flips to the ink foreground`() {
        // Gray 0xB7 = 183: strictly above the threshold.
        assertTrue(prefersInkForeground(0xFFB7B7B7))
    }

    @Test
    fun `luminance at 182 keeps the white foreground`() {
        // Gray 0xB6 = 182: the rule is strictly greater-than.
        assertFalse(prefersInkForeground(0xFFB6B6B6))
    }

    // ---- broadcast -> ThemeGround resolution ----

    @Test
    fun `a named preset resolves to its canonical ground ignoring the extra`() {
        // The BACKGROUND extra is redundant for named presets; the table wins.
        assertEquals(
            ThemeGround("forest-night", 0xFF10261B),
            resolveGround("Forest Night", 0xFF123456),
        )
        assertEquals(
            ThemeGround("graphite", 0xFF131316),
            resolveGround("graphite", null),
        )
    }

    @Test
    fun `custom resolves through the carried background`() {
        assertEquals(
            ThemeGround(ThemePreset.CUSTOM_KEY, 0xFF224466),
            resolveGround("Custom", 0xFF224466),
        )
        assertEquals(
            ThemeGround(ThemePreset.CUSTOM_KEY, 0xFF224466),
            resolveGround("cUsToM", 0xFF224466),
        )
    }

    @Test
    fun `custom without a background resolves to nothing`() {
        assertNull(resolveGround("Custom", null))
    }

    @Test
    fun `unknown or missing names resolve to nothing`() {
        assertNull(resolveGround("Solarized", 0xFF224466))
        assertNull(resolveGround(null, 0xFF224466))
    }

    // ---- ground scheme derivation ----

    private fun red(argb: Long): Long = (argb shr 16) and 0xFF

    @Test
    fun `a dark ground derives the white ramp over its own background`() {
        val s = deriveGroundScheme(ThemePreset.GRAPHITE.background)
        assertTrue(s.isDark)
        assertEquals(0xFF131316L, s.background)
        // White opacity ramp, matching the shipped pxx_white_* stops.
        assertEquals(0xE6FFFFFFL, s.text)
        assertEquals(0xCCFFFFFFL, s.strong)
        assertEquals(0x80FFFFFFL, s.muted)
        assertEquals(0x40FFFFFFL, s.shade)
        assertEquals(0x1AFFFFFFL, s.line)
        // Accent stays the signal white with ink on it.
        assertEquals(WHITE_FOREGROUND, s.accent)
        assertEquals(0xFF000000L, s.accentOn)
        // Surfaces step toward white, in order.
        assertTrue(red(s.surfaceRaised) > red(s.background))
        assertTrue(red(s.surfaceContainer) > red(s.surfaceRaised))
        assertTrue(red(s.surfaceHigh) > red(s.surfaceContainer))
    }

    @Test
    fun `a light ground derives the ink ramp and inverts the accent`() {
        val s = deriveGroundScheme(ThemePreset.PAPER.background)
        assertFalse(s.isDark)
        assertEquals(0xFFF3EEE2L, s.background)
        // Ink opacity ramp based on the family's #FF1A1A1A foreground.
        assertEquals(0xE61A1A1AL, s.text)
        assertEquals(0xCC1A1A1AL, s.strong)
        assertEquals(0x801A1A1AL, s.muted)
        assertEquals(0x401A1A1AL, s.shade)
        assertEquals(0x1A1A1A1AL, s.line)
        // Accent inverts to ink so it stays visible on the light ground.
        assertEquals(INK_FOREGROUND, s.accent)
        assertEquals(WHITE_FOREGROUND, s.accentOn)
        // Surfaces step toward black, in order.
        assertTrue(red(s.surfaceRaised) < red(s.background))
        assertTrue(red(s.surfaceContainer) < red(s.surfaceRaised))
        assertTrue(red(s.surfaceHigh) < red(s.surfaceContainer))
    }

    @Test
    fun `a custom ground follows the contrast rule not a preset flag`() {
        assertTrue(deriveGroundScheme(0xFF0F1C2E).isDark)
        assertFalse(deriveGroundScheme(0xFFE6EDF5).isDark)
    }

    // ---- CalendarColors overlay ----

    private val base = CalendarColors(
        ink = Color(0xFF000000),
        signal = Color(0xFFFFFFFF),
        emphasisBg = Color(0xFFFFFFFF),
        emphasisFg = Color(0xFF000000),
        inkRaised = Color(0xFF09090B),
        graphite = Color(0xFF131316),
        slate = Color(0xFF18181B),
        line = Color(0x1AFFFFFF),
        shade = Color(0x40FFFFFF),
        muted = Color(0x80FFFFFF),
        strong = Color(0xCCFFFFFF),
        text = Color(0xE6FFFFFF),
        warn = Color(0xFFFDBA74),
        error = Color(0xFFFF6767),
        ok = Color(0xE6FFFFFF),
        info = Color(0x80FFFFFF),
    )

    @Test
    fun `no synced ground keeps the shipped tokens untouched`() {
        assertSame(base, base.withGround(null))
    }

    @Test
    fun `the amoled ground keeps the shipped resource path`() {
        assertSame(base, base.withGround(ThemeGround("amoled-night", 0xFF000000)))
    }

    @Test
    fun `a dark preset ground swaps background and surfaces only`() {
        val themed = base.withGround(ThemeGround("burgundy", 0xFF2A1018))
        assertEquals(Color(0xFF2A1018), themed.ink)
        // Ramp and accent stay the shipped white system on dark grounds.
        assertEquals(base.text, themed.text)
        assertEquals(base.signal, themed.signal)
        assertEquals(base.emphasisFg, themed.emphasisFg)
        // Hue tokens are never ground-scoped.
        assertEquals(base.warn, themed.warn)
        assertEquals(base.error, themed.error)
    }

    @Test
    fun `a light preset ground inverts the ramp and the accent`() {
        val themed = base.withGround(ThemeGround("paper", 0xFFF3EEE2))
        assertEquals(Color(0xFFF3EEE2), themed.ink)
        assertEquals(Color(0xE61A1A1A), themed.text)
        assertEquals(Color(0x801A1A1A), themed.muted)
        assertEquals(Color(0xFF1A1A1A), themed.signal)
        assertEquals(Color(0xFF1A1A1A), themed.emphasisBg)
        assertEquals(Color(0xFFFFFFFF), themed.emphasisFg)
        // Status aliases follow the ramp so they stay legible.
        assertEquals(themed.text, themed.ok)
        assertEquals(themed.muted, themed.info)
        assertEquals(base.warn, themed.warn)
        assertEquals(base.error, themed.error)
    }
}
