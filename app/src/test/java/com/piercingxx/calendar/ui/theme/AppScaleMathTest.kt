package com.piercingxx.calendar.ui.theme

import com.piercingxx.calendar.settings.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.6 scale math consumed by CalendarTheme: the textSizeScale multiplier and
 * the density spacing factor. Pure JVM — no compose types involved.
 */
class AppScaleMathTest {

    @Test
    fun `text scale passes through sane values`() {
        assertEquals(1.0f, clampedTextSizeScale(1.0f))
        assertEquals(0.85f, clampedTextSizeScale(0.85f))
        assertEquals(1.3f, clampedTextSizeScale(1.3f))
    }

    @Test
    fun `text scale clamps to the supported range`() {
        assertEquals(MIN_TEXT_SIZE_SCALE, clampedTextSizeScale(0.1f))
        assertEquals(MAX_TEXT_SIZE_SCALE, clampedTextSizeScale(5.0f))
    }

    @Test
    fun `non-finite stored scales fall back to 100 percent`() {
        assertEquals(1.0f, clampedTextSizeScale(Float.NaN))
        assertEquals(1.0f, clampedTextSizeScale(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `density factor is 100 for comfortable and tightened for compact`() {
        assertEquals(1.0f, densityScaleFactor(Density.COMFORTABLE))
        assertEquals(COMPACT_DENSITY_FACTOR, densityScaleFactor(Density.COMPACT))
        assertTrue(COMPACT_DENSITY_FACTOR < 1.0f)
    }
}
