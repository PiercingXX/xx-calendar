package com.piercingxx.calendar.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.settings.SigilStore
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.Label
import com.piercingxx.calendar.ui.theme.LocalCalendarColors
import kotlinx.coroutines.launch

/**
 * The calendar visibility list, as a settings-style window. Replaces the
 * swipe-open drawer: same toggles, opened from the top-bar overflow.
 */
@Composable
fun CalendarsScreen() {
    val colors = LocalCalendarColors.current
    val context = LocalContext.current
    val repository = remember { CalendarRepository(context.contentResolver) }
    val sigilStore = remember { SigilStore(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var calendars by remember { mutableStateOf<List<CalendarSummary>>(emptyList()) }
    var sigils by remember { mutableStateOf<Map<CalendarKey, SigilTier>>(emptyMap()) }
    var pickingSigilFor by remember { mutableStateOf<CalendarKey?>(null) }

    suspend fun reload() {
        calendars = repository.calendars()
        sigils = sigilStore.load()
    }

    LaunchedEffect(Unit) { reload() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { reload() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Text(
            "CALENDARS",
            style = Label,
            color = colors.muted,
            modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 8.dp),
        )
        calendars.forEach { calendar ->
            val key = CalendarKey(calendar.id, calendar.accountName ?: "")
            CalendarSettingRow(
                calendar = calendar,
                tier = sigils[key],
                onPickSigil = { pickingSigilFor = key },
                onToggleVisible = { visible ->
                    scope.launch {
                        repository.setVisible(calendar.id, visible)
                        calendars = repository.calendars()
                    }
                },
            )
        }
        if (calendars.isEmpty()) {
            Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("no calendars", style = Body, color = colors.shade)
            }
        }
    }

    pickingSigilFor?.let { key ->
        SigilPickerSheet(
            current = sigils[key],
            onPick = { tier ->
                pickingSigilFor = null
                scope.launch {
                    val updated = HashMap(sigils)
                    updated[key] = tier
                    sigilStore.save(updated)
                    sigils = sigilStore.load()
                }
            },
            onDismiss = { pickingSigilFor = null },
        )
    }
}
