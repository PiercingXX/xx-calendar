package com.piercingxx.calendar.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piercingxx.calendar.core.ByDay
import com.piercingxx.calendar.core.EndCondition
import com.piercingxx.calendar.core.Frequency
import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.Weekday
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.EventTitle
import com.piercingxx.calendar.ui.theme.Label
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import com.piercingxx.calendar.ui.theme.SpaceMono
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The §8.5 rule builder: five presets derived from the anchor date plus
 * "does not repeat" (`onSelect(null)`), then `custom…` for frequency,
 * interval, by-day, by-month-day / nth-weekday, and the end condition.
 * Output is an [RRuleModel]; nothing writes to the provider here — scope
 * resolution happens only when the editor saves (§6.3).
 */
@Composable
fun RepeatBuilderDialog(
    initial: RRuleModel?,
    anchorDate: LocalDate,
    allDay: Boolean,
    onSelect: (RRuleModel?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    var customOpen by remember { mutableStateOf(false) }

    val presets: List<Pair<String, RRuleModel>> = listOf(
        "daily" to RRuleModel.daily(),
        "every weekday" to RRuleModel.weekdays(),
        "weekly on ${dayName(weekdayOf(anchorDate))}" to
            RRuleModel.weekly(listOf(weekdayOf(anchorDate))),
        "monthly on the ${ordinalWord(anchorDate.dayOfMonth)}" to
            RRuleModel.monthlyByDate(anchorDate.dayOfMonth),
        "annually" to RRuleModel.yearly(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.graphite,
        textContentColor = colors.text,
        title = { Text("repeats", style = EventTitle, color = colors.text) },
        text = {
            if (!customOpen) {
                Column {
                    presets.forEach { (label, rule) ->
                        Text(
                            label,
                            style = Body,
                            color = colors.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(rule) }
                                .padding(vertical = 10.dp),
                        )
                    }
                    Text(
                        "does not repeat",
                        style = Body,
                        color = colors.muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(null) }
                            .padding(vertical = 10.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "custom…",
                        style = Body,
                        color = colors.strong,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { customOpen = true }
                            .padding(vertical = 10.dp),
                    )
                }
            } else {
                CustomRulePane(
                    initial = initial,
                    anchorDate = anchorDate,
                    allDay = allDay,
                    onDone = onDone@{ rule ->
                        customOpen = false
                        if (rule != null) onSelect(rule)
                    },
                    onCancel = { customOpen = false },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("cancel", style = Body, color = colors.muted)
            }
        },
    )
}

private enum class MonthMode { ByMonthDay, NthWeekday }

internal enum class EndKind { Never, OnDate, AfterCount }

/**
 * Pure rule assembly from the custom pane's state; JVM-testable. [allDay]
 * forces UNTIL into RFC 5545 §3.3.10 DATE form ([EndCondition.Until.dateOnly])
 * so the value type matches an all-day series' VALUE=DATE DTSTART.
 */
internal fun buildCustomRule(
    frequency: Frequency,
    interval: Int,
    weeklyDays: Set<Weekday>,
    monthlyByDate: Boolean,
    monthDay: Int,
    nthOrdinal: Int,
    endKind: EndKind,
    untilDate: LocalDate,
    count: Int,
    anchorDate: LocalDate,
    allDay: Boolean,
    weekStart: Weekday? = null,
    byMonth: List<Int> = emptyList(),
    byMonthDayYearly: List<Int> = emptyList(),
    bySetPos: List<Int> = emptyList(),
): RRuleModel {
    val primaryDay = weeklyDays.firstOrNull() ?: weekdayOf(anchorDate)
    val yearly = frequency == Frequency.YEARLY
    val monthly = frequency == Frequency.MONTHLY
    val base = when (frequency) {
        Frequency.DAILY -> RRuleModel(frequency = Frequency.DAILY, interval = interval)

        Frequency.WEEKLY -> RRuleModel(
            frequency = Frequency.WEEKLY,
            interval = interval,
            byDay = Weekday.entries.filter { it in weeklyDays }.map { ByDay(null, it) }
                .ifEmpty { listOf(ByDay(null, primaryDay)) },
        )

        Frequency.MONTHLY ->
            if (monthlyByDate) {
                RRuleModel(
                    frequency = Frequency.MONTHLY,
                    interval = interval,
                    byMonthDay = listOf(monthDay.coerceIn(1, 31)),
                )
            } else {
                RRuleModel(
                    frequency = Frequency.MONTHLY,
                    interval = interval,
                    byDay = listOf(ByDay(if (nthOrdinal == 0) 1 else nthOrdinal, primaryDay)),
                )
            }

        Frequency.YEARLY -> RRuleModel(
            frequency = Frequency.YEARLY,
            interval = interval,
            // Pass through loaded Google birthday parts; a plain FREQ=YEARLY
            // relies on DTSTART and must not invent BYMONTH / BYMONTHDAY.
            byMonth = byMonth.filter { it in 1..12 },
            byMonthDay = byMonthDayYearly.filter { it in -31..-1 || it in 1..31 },
        )
    }
    return base.copy(
        weekStart = weekStart,
        bySetPos = if (monthly || yearly) bySetPos else emptyList(),
        end = when (endKind) {
            EndKind.Never -> EndCondition.Never

            EndKind.OnDate -> EndCondition.Until(
                untilDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                dateOnly = allDay,
            )

            EndKind.AfterCount -> EndCondition.Count(count.coerceAtLeast(1))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CustomRulePane(
    initial: RRuleModel?,
    anchorDate: LocalDate,
    allDay: Boolean,
    onDone: (RRuleModel?) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalCalendarColors.current

    var frequency by remember { mutableStateOf(initial?.frequency ?: Frequency.WEEKLY) }
    var interval by remember { mutableIntStateOf((initial?.interval ?: 1).coerceAtLeast(1)) }
    var byDay by remember {
        mutableStateOf(initial?.byDay?.map { it.weekday }?.toSet()?.ifEmpty { null } ?: setOf(weekdayOf(anchorDate)))
    }
    var nthOrdinal by remember {
        mutableIntStateOf(initial?.byDay?.firstOrNull()?.ordinal?.takeIf { it != 0 } ?: 1)
    }
    var monthModeByDate by remember {
        mutableStateOf(initial?.byDay?.firstOrNull()?.ordinal == null)
    }
    var monthDay by remember {
        mutableIntStateOf(initial?.byMonthDay?.firstOrNull() ?: anchorDate.dayOfMonth)
    }
    var endKind by remember {
        mutableStateOf(
            when (initial?.end) {
                is EndCondition.Until -> EndKind.OnDate
                is EndCondition.Count -> EndKind.AfterCount
                else -> EndKind.Never
            },
        )
    }
    var untilDate by remember {
        mutableStateOf(
            (initial?.end as? EndCondition.Until)?.let {
                Instant.ofEpochMilli(it.untilMillisUtc).atZone(ZoneOffset.UTC).toLocalDate()
            } ?: anchorDate.plusMonths(1),
        )
    }
    var count by remember {
        mutableIntStateOf((initial?.end as? EndCondition.Count)?.occurrences ?: 10)
    }
    var datePickerOpen by remember { mutableStateOf(false) }

    fun build(): RRuleModel = buildCustomRule(
        frequency = frequency,
        interval = interval,
        weeklyDays = byDay,
        monthlyByDate = monthModeByDate,
        monthDay = monthDay,
        nthOrdinal = nthOrdinal,
        endKind = endKind,
        untilDate = untilDate,
        count = count,
        anchorDate = anchorDate,
        allDay = allDay,
        weekStart = initial?.weekStart,
        byMonth = if (frequency == Frequency.YEARLY) initial?.byMonth.orEmpty() else emptyList(),
        byMonthDayYearly = if (frequency == Frequency.YEARLY) {
            initial?.byMonthDay.orEmpty()
        } else {
            emptyList()
        },
        bySetPos = initial?.bySetPos.orEmpty(),
    )

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        PaneLabel("frequency")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                Frequency.DAILY to "daily",
                Frequency.WEEKLY to "weekly",
                Frequency.MONTHLY to "monthly",
                Frequency.YEARLY to "yearly",
            ).forEach { (freq, label) ->
                Chip(label, frequency == freq) { frequency = freq }
            }
        }

        PaneLabel("every")
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("-") { interval = (interval - 1).coerceAtLeast(1) }
            Text("$interval", fontFamily = SpaceMono, fontSize = 14.sp, color = colors.text,
                modifier = Modifier.padding(horizontal = 10.dp))
            StepButton("+") { interval = (interval + 1).coerceAtMost(52) }
        }

        when (frequency) {
            Frequency.WEEKLY -> {
                PaneLabel("on days")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Weekday.entries.forEach { day ->
                        Chip(day.name.lowercase(), day in byDay) { wantOn ->
                            byDay = toggleWeeklyDay(byDay, day, wantOn)
                        }
                    }
                }
            }

            Frequency.MONTHLY -> {
                PaneLabel("pattern")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Chip("by month day", monthModeByDate) { monthModeByDate = true }
                    Chip("by nth weekday", !monthModeByDate) { monthModeByDate = false }
                }
                if (monthModeByDate) {
                    NumberField(value = monthDay, hint = "1..31") {
                        monthDay = it.coerceIn(1, 31)
                    }
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        listOf(1, 2, 3, 4, -1).forEach { n ->
                            Chip(nthWord(n), nthOrdinal == n) { nthOrdinal = n }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Weekday.entries.forEach { day ->
                            Chip(day.name.lowercase(), (byDay.firstOrNull() ?: weekdayOf(anchorDate)) == day) { _ ->
                                byDay = setOf(day)
                            }
                        }
                    }
                }
            }

            else -> Unit
        }

        PaneLabel("ends")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("never", endKind == EndKind.Never) { endKind = EndKind.Never }
            Chip("on date", endKind == EndKind.OnDate) { endKind = EndKind.OnDate }
            Chip("after", endKind == EndKind.AfterCount) { endKind = EndKind.AfterCount }
        }
        if (endKind == EndKind.OnDate) {
            Text(
                untilDate.toString(),
                fontFamily = SpaceMono,
                fontSize = 14.sp,
                color = colors.strong,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { datePickerOpen = true },
            )
        }
        if (endKind == EndKind.AfterCount) {
            NumberField(value = count, hint = "occurrences") { count = it.coerceAtLeast(1) }
        }

        Spacer(Modifier.height(12.dp))
        Text(preview(build()), style = Body, color = colors.muted)
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = onCancel) {
                Text("back", style = Body, color = colors.muted)
            }
            TextButton(onClick = { onDone(build()) }) {
                Text("done", style = Body, color = colors.signal)
            }
        }
    }

    if (datePickerOpen) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis =
            untilDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        untilDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    datePickerOpen = false
                }) { Text("set", style = Body, color = colors.text) }
            },
            dismissButton = {
                TextButton(onClick = { datePickerOpen = false }) {
                    Text("cancel", style = Body, color = colors.muted)
                }
            },
        ) {
            DatePicker(state = picker)
        }
    }
}

/** One-line summary of what "done" would store, in the same words as the row. */
private fun preview(rule: RRuleModel): String = buildString {
    append(repeatLabel(rule, unreadable = false))
}

/**
 * At least one weekday must stay selected. [wantOn] is the chip's new state
 * after the click — the previous handler inverted that and could never
 * deselect a day.
 */
internal fun toggleWeeklyDay(
    current: Set<Weekday>,
    day: Weekday,
    wantOn: Boolean,
): Set<Weekday> =
    if (wantOn) current + day
    else if (current.size > 1) current - day
    else current

@Composable
private fun PaneLabel(text: String) {
    val colors = LocalCalendarColors.current
    Text(
        text.uppercase(),
        style = Label,
        color = colors.muted,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = LocalCalendarColors.current
    Text(
        label,
        fontFamily = SpaceMono,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) colors.emphasisFg else colors.muted,
        modifier = Modifier
            .clickable { onToggle(!selected) }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    val colors = LocalCalendarColors.current
    Text(
        glyph,
        fontFamily = SpaceMono,
        fontSize = 16.sp,
        color = colors.strong,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
private fun NumberField(value: Int, hint: String, onChange: (Int) -> Unit) {
    val colors = LocalCalendarColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { raw -> raw.toIntOrNull()?.let(onChange) },
            textStyle = Body.copy(fontFamily = SpaceMono, fontSize = 13.sp),
            singleLine = true,
            modifier = Modifier.width(96.dp),
        )
        Text(hint, style = Label, color = colors.muted, modifier = Modifier.padding(start = 12.dp))
    }
}
