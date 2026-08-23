package com.piercingxx.calendar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * The only theme. Dark-only by design (§7): AMOLED ink ground, signal white
 * reserved for the now-rule and the selected element. Nothing in the scheme
 * hands out pure white except through primary / emphasisBg.
 */
@Composable
fun CalendarTheme(content: @Composable () -> Unit) {
    val colors = calendarColors()
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
    CompositionLocalProvider(LocalCalendarColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = CalendarType,
            content = content,
        )
    }
}
