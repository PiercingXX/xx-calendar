package com.piercingxx.calendar

import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.piercingxx.calendar.settings.AppBackground
import com.piercingxx.calendar.settings.AppFont
import com.piercingxx.calendar.settings.Density
import com.piercingxx.calendar.settings.SettingsStore
import com.piercingxx.calendar.ui.editor.EditorScreen
import com.piercingxx.calendar.ui.theme.CalendarTheme
import com.piercingxx.calendar.ui.theme.ThemeGroundState

/**
 * Thin host for the event editor (design §8.5). singleTask per §12 so repeated
 * INSERT intents reuse this instance — [onNewIntent] reparses the intent and
 * [key]s a fresh editor, so a second INSERT shows its own form instead of the
 * stale one (15.4).
 */
class EditorActivity : ComponentActivity() {

    /** The launch/new intent as editor input; every change rebuilds the form. */
    private val input = mutableStateOf(parseEditorIntent(null))

    override fun onResume() {
        super.onResume()
        ThemeGroundState.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        input.value = parseEditorIntent(intent)
        setContent {
            // Same §8.6 appearance consumption as SettingsActivity, so the
            // scale and density rows act on this screen too.
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
                    val current = input.value
                    // key(): a new intent discards ALL remembered editor state.
                    key(current) {
                        EditorScreen(
                            eventId = current.eventId,
                            initialStartMillis = current.initialStartMillis,
                            initialEndMillis = current.initialEndMillis,
                            allDay = current.allDay,
                            instanceStartMillis = current.instanceStartMillis,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        input.value = parseEditorIntent(intent)
    }
}

/** What one launch intent means for the editor; produced by [parseEditorIntent]. */
internal data class EditorIntentInput(
    val eventId: Long?,
    val initialStartMillis: Long?,
    val initialEndMillis: Long?,
    val allDay: Boolean,
    val instanceStartMillis: Long? = null,
)

/**
 * §12's external-editor contract, decoded (15.4):
 *
 * - ACTION_INSERT (`vnd.android.cursor.dir/event`): a blank new event whose
 *   times come from the standard CalendarContract extras — BEGIN_TIME,
 *   END_TIME, ALL_DAY — so other apps' "add to calendar" lands prefilled
 *   rather than at the next half hour.
 * - ACTION_VIEW / ACTION_EDIT (`vnd.android.cursor.item/event`): the data
 *   URI's last segment is the event row id. EXTRA_EVENT_BEGIN_TIME, when
 *   present, is the tapped occurrence of a recurring series — the same
 *   stamp every in-app tap now threads through `?start=`.
 *
 * Malformed or foreign intents degrade to a blank new-event form, never a
 * crash. Internal so the parsing is directly JVM-testable.
 */
internal fun parseEditorIntent(intent: Intent?): EditorIntentInput {
    if (intent == null) return EditorIntentInput(null, null, null, false)

    fun extraMillis(key: String): Long? =
        if (intent.hasExtra(key)) intent.getLongExtra(key, 0L) else null

    return when (intent.action) {
        Intent.ACTION_INSERT -> EditorIntentInput(
            eventId = null,
            initialStartMillis = extraMillis(CalendarContract.EXTRA_EVENT_BEGIN_TIME),
            initialEndMillis = extraMillis(CalendarContract.EXTRA_EVENT_END_TIME),
            allDay = intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false),
        )

        else -> EditorIntentInput(
            eventId = intent.data?.lastPathSegment?.toLongOrNull(),
            initialStartMillis = null,
            initialEndMillis = null,
            allDay = false,
            instanceStartMillis = extraMillis(CalendarContract.EXTRA_EVENT_BEGIN_TIME),
        )
    }
}
