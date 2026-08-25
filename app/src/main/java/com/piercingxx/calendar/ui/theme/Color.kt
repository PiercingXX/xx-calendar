package com.piercingxx.calendar.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.piercingxx.calendar.R
import com.piercingxx.calendar.settings.AppBackground

/**
 * The PiercingXX token set (design §7), dark-only. The white-opacity ramp
 * carries all hierarchy; [signal] is the one pure white and stays reserved.
 */
@Immutable
data class CalendarColors(
    val ink: Color,
    val signal: Color,
    val emphasisBg: Color,
    val emphasisFg: Color,
    val inkRaised: Color,
    val graphite: Color,
    val slate: Color,
    val line: Color,
    val shade: Color,
    val muted: Color,
    val strong: Color,
    val text: Color,
    val warn: Color,
    val error: Color,
    val ok: Color,
    val info: Color,
)

/** The §8.6 background row resolves here; one variant ships this wave (§7). */
@Composable
fun calendarColors(background: AppBackground = AppBackground.AMOLED_NIGHT): CalendarColors =
    when (background) {
        AppBackground.AMOLED_NIGHT -> CalendarColors(
            ink = colorResource(R.color.pxx_ink),
            signal = colorResource(R.color.pxx_signal),
            emphasisBg = colorResource(R.color.pxx_emphasis_bg),
            emphasisFg = colorResource(R.color.pxx_emphasis_fg),
            inkRaised = colorResource(R.color.pxx_ink_raised),
            graphite = colorResource(R.color.pxx_graphite),
            slate = colorResource(R.color.pxx_slate),
            line = colorResource(R.color.pxx_white_10),
            shade = colorResource(R.color.pxx_white_25),
            muted = colorResource(R.color.pxx_white_50),
            strong = colorResource(R.color.pxx_white_80),
            text = colorResource(R.color.pxx_white_90),
            warn = colorResource(R.color.pxx_warn),
            error = colorResource(R.color.pxx_error),
            ok = colorResource(R.color.pxx_ok),
            info = colorResource(R.color.pxx_info),
        )
    }

val LocalCalendarColors = staticCompositionLocalOf<CalendarColors> {
    error("CalendarColors not provided - wrap content in CalendarTheme")
}

/**
 * Overlay a synced launcher [ground] (family theme sync) onto the shipped
 * token set. Scope is the GROUND only: background, the raised-surface ladder,
 * the foreground opacity ramp, and — on light grounds, where signal white
 * would vanish — the accent, which inverts to ink. `warn`/`error` keep their
 * shipped hues on every ground. Null or the AMOLED default returns `this`
 * unchanged, so the pxx_* resource path stays the source of truth by default.
 */
fun CalendarColors.withGround(ground: ThemeGround?): CalendarColors {
    if (ground == null || ground.background == ThemePreset.DEFAULT.background) return this
    val s = deriveGroundScheme(ground.background)
    return copy(
        ink = groundColor(s.background),
        inkRaised = groundColor(s.surfaceRaised),
        graphite = groundColor(s.surfaceContainer),
        slate = groundColor(s.surfaceHigh),
        line = groundColor(s.line),
        shade = groundColor(s.shade),
        muted = groundColor(s.muted),
        strong = groundColor(s.strong),
        text = groundColor(s.text),
        signal = groundColor(s.accent),
        emphasisBg = groundColor(s.accent),
        emphasisFg = groundColor(s.accentOn),
        // ok/info are ramp aliases in the shipped palette (white-90/white-50);
        // keep them aliased so status text stays legible on light grounds.
        ok = groundColor(s.text),
        info = groundColor(s.muted),
    )
}

/** 0xAARRGGBB long -> Compose sRGB color. */
private fun groundColor(argb: Long): Color = Color(argb.toInt())
