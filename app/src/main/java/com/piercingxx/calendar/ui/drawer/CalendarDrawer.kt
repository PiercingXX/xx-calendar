package com.piercingxx.calendar.ui.drawer

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.settings.SigilStore
import com.piercingxx.calendar.SettingsActivity
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.CalendarColors
import com.piercingxx.calendar.ui.theme.JetBrainsMono
import com.piercingxx.calendar.ui.theme.Label
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import kotlinx.coroutines.launch

/**
 * The drawer (design §8.1): the calendar list with sigil, name and a
 * visibility toggle, and Settings at the bottom. Nothing else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDrawer(repository: CalendarRepository) {
    val colors = LocalCalendarColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sigilStore = remember { SigilStore(context.applicationContext) }

    var calendars by remember { mutableStateOf(emptyList<CalendarSummary>()) }
    var sigils by remember { mutableStateOf(emptyMap<CalendarKey, SigilTier>()) }

    LaunchedEffect(repository) {
        calendars = repository.calendars()
        sigils = sigilStore.load()
    }

    ModalDrawerSheet(drawerContainerColor = colors.graphite) {
        Spacer(Modifier.height(24.dp))
        Text(
            "CALENDARS",
            style = Label,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        calendars.forEach { calendar ->
            CalendarRow(
                calendar = calendar,
                tier = sigils[CalendarKey(calendar.id, calendar.accountName ?: "")],
                colors = colors,
                onToggle = { visible ->
                    scope.launch {
                        repository.setVisible(calendar.id, visible)
                        calendars = repository.calendars()
                    }
                },
            )
        }
        if (calendars.isEmpty()) {
            Text(
                "No calendars",
                style = Body,
                color = colors.shade,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        HorizontalDivider(color = colors.line, modifier = Modifier.padding(vertical = 12.dp))
        Spacer(Modifier.weight(1f))
        NavigationDrawerItem(
            label = { Text("Settings", style = Body, color = colors.text) },
            selected = false,
            onClick = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            },
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun CalendarRow(
    calendar: CalendarSummary,
    tier: SigilTier?,
    colors: CalendarColors,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tier?.glyph ?: "·",
            fontFamily = JetBrainsMono,
            fontSize = 15.sp,
            color = tier?.rampColor(colors) ?: colors.shade,
        )
        Text(
            calendar.displayName,
            style = Body,
            color = if (calendar.isVisible) colors.text else colors.shade,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        )
        Switch(
            checked = calendar.isVisible,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.emphasisBg,
                checkedTrackColor = colors.muted,
                checkedBorderColor = colors.muted,
                uncheckedThumbColor = colors.shade,
                uncheckedTrackColor = colors.slate,
                uncheckedBorderColor = colors.line,
            ),
        )
    }
}

/** §7.1: each tier renders its glyph at its named stop on the white ramp. */
private fun SigilTier.rampColor(colors: CalendarColors) = when (rampName) {
    "text" -> colors.text
    "strong" -> colors.strong
    "muted" -> colors.muted
    else -> colors.shade
}
