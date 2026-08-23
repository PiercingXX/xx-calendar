package com.piercingxx.calendar.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.piercingxx.calendar.R

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

@Composable
fun calendarColors(): CalendarColors = CalendarColors(
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

val LocalCalendarColors = staticCompositionLocalOf<CalendarColors> {
    error("CalendarColors not provided - wrap content in CalendarTheme")
}
