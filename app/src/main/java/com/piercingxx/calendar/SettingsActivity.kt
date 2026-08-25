package com.piercingxx.calendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.piercingxx.calendar.settings.AppBackground
import com.piercingxx.calendar.settings.AppFont
import com.piercingxx.calendar.settings.Density
import com.piercingxx.calendar.settings.SettingsStore
import androidx.compose.material3.MaterialTheme
import com.piercingxx.calendar.ui.settings.SettingsScreen
import com.piercingxx.calendar.ui.theme.CalendarTheme
import com.piercingxx.calendar.ui.theme.ThemeGroundState

/** Thin host for Settings (design §8.6). WS9 owns the real screen. */
class SettingsActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        ThemeGroundState.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Same §8.6 appearance consumption as MainActivity, so the scale
            // and density rows act on this screen too.
            val settingsStore = remember { SettingsStore(applicationContext) }
            val settings by settingsStore.settings.collectAsState(initial = null)
            CalendarTheme(
                background = settings?.background ?: AppBackground.AMOLED_NIGHT,
                font = settings?.font ?: AppFont.JETBRAINS_MONO,
                textSizeScale = settings?.textSizeScale ?: 1.0f,
                density = settings?.density ?: Density.COMFORTABLE,
            ) {
                if (settings == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                } else {
                    SettingsScreen()
                }
            }
        }
    }
}
