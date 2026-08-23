package com.piercingxx.calendar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.piercingxx.calendar.settings.AppBackground
import com.piercingxx.calendar.settings.AppFont
import com.piercingxx.calendar.settings.Density as AppDensity

/**
 * §8.6 scale math, pure so the unit suite can pin it. `textSizeScale`
 * multiplies the system font scale (sp text only); COMPACT density tightens
 * dp spacing (grids included) without touching type.
 */
const val MIN_TEXT_SIZE_SCALE = 0.5f
const val MAX_TEXT_SIZE_SCALE = 2.0f

/** Non-finite or out-of-range stored scales clamp instead of crashing theme. */
fun clampedTextSizeScale(scale: Float): Float =
    if (scale.isFinite()) scale.coerceIn(MIN_TEXT_SIZE_SCALE, MAX_TEXT_SIZE_SCALE) else 1f

/** Spacing factor per §8.6 density; COMFORTABLE is the design's 100%. */
const val COMPACT_DENSITY_FACTOR = 0.85f

fun densityScaleFactor(density: AppDensity): Float =
    if (density == AppDensity.COMPACT) COMPACT_DENSITY_FACTOR else 1f

/**
 * The only theme. Dark-only by design (§7): AMOLED ink ground, signal white
 * reserved for the now-rule and the selected element. Nothing in the scheme
 * hands out pure white except through primary / emphasisBg.
 *
 * Consumes §8.6's appearance rows: [background]/[font] select among the
 * shipped variants (one each today), while [textSizeScale] and [density]
 * rescale type and spacing through a scoped [LocalDensity].
 */
@Composable
fun CalendarTheme(
    background: AppBackground = AppBackground.AMOLED_NIGHT,
    font: AppFont = AppFont.JETBRAINS_MONO,
    textSizeScale: Float = 1.0f,
    density: AppDensity = AppDensity.COMFORTABLE,
    content: @Composable () -> Unit,
) {
    val colors = calendarColors(background)
    val scheme = darkColorScheme(
        primary = colors.signal,
        onPrimary = colors.ink,
        primaryContainer = colors.emphasisBg,
        onPrimaryContainer = colors.emphasisFg,
        inversePrimary = colors.ink,
        secondary = colors.strong,
        onSecondary = colors.ink,
        secondaryContainer = colors.graphite,
        onSecondaryContainer = colors.text,
        tertiary = colors.strong,
        onTertiary = colors.ink,
        tertiaryContainer = colors.slate,
        onTertiaryContainer = colors.text,
        background = colors.ink,
        onBackground = colors.text,
        surface = colors.inkRaised,
        onSurface = colors.text,
        surfaceVariant = colors.slate,
        onSurfaceVariant = colors.muted,
        surfaceTint = colors.ink,
        inverseSurface = colors.slate,
        inverseOnSurface = colors.text,
        error = colors.error,
        onError = colors.ink,
        errorContainer = colors.error,
        onErrorContainer = colors.ink,
        outline = colors.line,
        outlineVariant = colors.line,
        scrim = colors.ink,
        surfaceBright = colors.slate,
        surfaceDim = colors.ink,
        surfaceContainerLowest = colors.ink,
        surfaceContainerLow = colors.inkRaised,
        surfaceContainer = colors.graphite,
        surfaceContainerHigh = colors.slate,
        surfaceContainerHighest = colors.slate,
    )
    // sp sizes resolve through fontScale, dp through density — one override
    // point scales the whole app (time grids included) coherently. Density is
    // an interface: rebuild it via the factory rather than copy().
    val base = LocalDensity.current
    val scaled = Density(
        density = base.density * densityScaleFactor(density),
        fontScale = base.fontScale * clampedTextSizeScale(textSizeScale),
    )
    CompositionLocalProvider(
        LocalCalendarColors provides colors,
        LocalDensity provides scaled,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = calendarTypography(font),
            content = content,
        )
    }
}
