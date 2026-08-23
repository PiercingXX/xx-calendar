package com.piercingxx.calendar.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.piercingxx.calendar.R

// Both faces are monospace, so tabular figures are automatic: every digit
// column aligns by construction and no fontFeature work is ever needed.
val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/** Day numerals - Space Mono 32sp. */
val DayNumeral = TextStyle(
    fontFamily = SpaceMono,
    fontWeight = FontWeight.Normal,
    fontSize = 32.sp,
    lineHeight = 40.sp,
)

/** Month header - Space Mono 20sp. */
val MonthHeader = TextStyle(
    fontFamily = SpaceMono,
    fontWeight = FontWeight.Normal,
    fontSize = 20.sp,
    lineHeight = 28.sp,
)

/** Times - Space Mono 13sp. */
val Time = TextStyle(
    fontFamily = SpaceMono,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

/** Event titles - JetBrains Mono 15sp. */
val EventTitle = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
)

/** Body / list rows / editor - JetBrains Mono 14sp. */
val Body = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

/** Weekday headers and labels - JetBrains Mono medium 11sp, letter-spaced. */
val Label = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.05.em,
)

val CalendarType = Typography(
    displayLarge = DayNumeral.copy(fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = DayNumeral.copy(fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = DayNumeral.copy(fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = DayNumeral,
    headlineMedium = DayNumeral.copy(fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = DayNumeral.copy(fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = MonthHeader,
    titleMedium = MonthHeader.copy(fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = EventTitle,
    bodyLarge = EventTitle,
    bodyMedium = Body,
    bodySmall = Body.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = Label.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = Label,
    labelSmall = Label.copy(fontSize = 10.sp, lineHeight = 14.sp),
)
