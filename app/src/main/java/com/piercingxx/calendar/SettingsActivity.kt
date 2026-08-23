package com.piercingxx.calendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.piercingxx.calendar.ui.settings.SettingsScreen
import com.piercingxx.calendar.ui.theme.CalendarTheme

/** Thin host for Settings (design §8.6). WS9 owns the real screen. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CalendarTheme {
                SettingsScreen()
            }
        }
    }
}
