package com.piercingxx.calendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.piercingxx.calendar.ui.editor.EditorScreen
import com.piercingxx.calendar.ui.theme.CalendarTheme
import com.piercingxx.calendar.ui.theme.ThemeGroundState

/**
 * Thin host for the event editor (design §8.5). singleTask per §12 so
 * repeated INSERT intents reuse this instance. WS7 owns the real editor and
 * the full intent parsing; until then the event id is read from the data URI.
 */
class EditorActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        ThemeGroundState.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val eventId = intent?.data?.lastPathSegment?.toLongOrNull()
        setContent {
            CalendarTheme {
                EditorScreen(eventId = eventId)
            }
        }
    }
}
