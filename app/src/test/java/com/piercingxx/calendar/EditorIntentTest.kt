package com.piercingxx.calendar

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.calendar.core.TimeMath
import com.piercingxx.calendar.ui.editor.EditorForm
import com.piercingxx.calendar.ui.editor.allDayEndDate
import com.piercingxx.calendar.ui.editor.asAllDayPrefill
import com.piercingxx.calendar.ui.editor.withDroppedTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 15.4: the external-editor intent contract — CalendarContract extras on
 * INSERT, data-path ids on VIEW/EDIT, and a blank form for anything else.
 * The all-day prefill shaping (exclusive-stop end, clamped span) is pure JVM.
 *
 * Robolectric (not plain JVM) because Uri/Intent parsing needs real Android
 * implementations; the project pins no returnDefaultValues on purpose.
 */
@RunWith(RobolectricTestRunner::class)
class EditorIntentTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun insertIntent(build: Intent.() -> Unit = {}): Intent =
        Intent(Intent.ACTION_INSERT)
            .setData(Uri.parse("content://com.android.calendar/events"))
            .setType("vnd.android.cursor.dir/event")
            .apply(build)

    @Test
    fun `insert with standard extras prefills times and all day`() {
        val intent = insertIntent {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 1_724_460_000_000L)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, 1_724_463_600_000L)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
        assertEquals(
            EditorIntentInput(
                eventId = null,
                initialStartMillis = 1_724_460_000_000L,
                initialEndMillis = 1_724_463_600_000L,
                allDay = true,
            ),
            parseEditorIntent(intent),
        )
    }

    @Test
    fun `insert without extras opens a blank new event`() {
        assertEquals(
            EditorIntentInput(null, null, null, false),
            parseEditorIntent(insertIntent()),
        )
    }

    @Test
    fun `insert with begin only leaves the end to the default duration`() {
        val intent = insertIntent {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 5_000L)
        }
        assertEquals(EditorIntentInput(null, 5_000L, null, false), parseEditorIntent(intent))
    }

    @Test
    fun `edit id comes from the data path and begin extra is the occurrence`() {
        val intent = Intent(Intent.ACTION_EDIT, Uri.parse("content://com.android.calendar/events/42"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 9_999L)
        assertEquals(
            EditorIntentInput(42L, null, null, false, instanceStartMillis = 9_999L),
            parseEditorIntent(intent),
        )
    }

    @Test
    fun `view uses the same data-path reading`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://calendar/events/7"))
        assertEquals(EditorIntentInput(7L, null, null, false), parseEditorIntent(intent))
    }

    @Test
    fun `malformed paths degrade to a blank new event`() {
        assertEquals(
            EditorIntentInput(null, null, null, false),
            parseEditorIntent(Intent(Intent.ACTION_EDIT, Uri.parse("content://x/events/notanumber"))),
        )
        assertNull(parseEditorIntent(Intent(Intent.ACTION_EDIT)).eventId)
    }

    @Test
    fun `null intent is a blank new event`() {
        assertEquals(EditorIntentInput(null, null, null, false), parseEditorIntent(null))
    }

    // ------------------------------------------------- all-day prefill (pure)

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    @Test
    fun `all day insert extras are UTC dates, not the device zone`() {
        val la = ZoneId.of("America/Los_Angeles")
        val june10 = TimeMath.allDayDateToStorage(LocalDate.of(2026, 6, 10))
        val june11 = TimeMath.allDayDateToStorage(LocalDate.of(2026, 6, 11))
        val form = EditorForm.new(la, june10, june11).asAllDayPrefill(june10, june11)
        assertTrue(form.allDay)
        assertEquals(LocalDate.of(2026, 6, 10), form.startDate)
        assertEquals(LocalDate.of(2026, 6, 10), form.endDate)
        assertEquals("UTC", form.timezone)
    }

    @Test
    fun `dropped grid times overlay the occurrence-anchored form`() {
        val zone = ZoneId.of("UTC")
        val base = EditorForm.new(zone, 1_724_460_000_000, 1_724_461_800_000)
        val dropped = base.withDroppedTimes(1_724_464_000_000, 1_724_465_800_000, zone)
        val start = java.time.Instant.ofEpochMilli(1_724_464_000_000).atZone(zone)
        val end = java.time.Instant.ofEpochMilli(1_724_465_800_000).atZone(zone)
        assertEquals(start.toLocalDate(), dropped.startDate)
        assertEquals(start.toLocalTime().withSecond(0).withNano(0), dropped.startTime)
        assertEquals(end.toLocalDate(), dropped.endDate)
        assertEquals(end.toLocalTime().withSecond(0).withNano(0), dropped.endTime)
    }

    @Test
    fun `all day prefill drops clock times and keeps the begin date`() {
        val base = EditorForm.new(zone, 1_700_000_000_000, 1_700_086_400_000 - 3_600_000)
        val allDay = base.asAllDayPrefill()
        assertTrue(allDay.allDay)
        assertNull(allDay.startTime)
        assertNull(allDay.endTime)
        assertEquals(base.startDate, allDay.startDate)
    }

    @Test
    fun `well formed exclusive midnight end is one day shorter`() {
        assertEquals(
            LocalDate.of(2026, 6, 10),
            allDayEndDate(
                LocalDate.of(2026, 6, 10),
                LocalDate.of(2026, 6, 11),
                LocalTime.MIDNIGHT,
            ),
        )
    }

    @Test
    fun `non midnight end counts its own date inclusively`() {
        assertEquals(
            LocalDate.of(2026, 6, 12),
            allDayEndDate(
                LocalDate.of(2026, 6, 10),
                LocalDate.of(2026, 6, 12),
                LocalTime.NOON,
            ),
        )
    }

    @Test
    fun `same midnight end means the start day only`() {
        assertEquals(
            LocalDate.of(2026, 6, 10),
            allDayEndDate(
                LocalDate.of(2026, 6, 10),
                LocalDate.of(2026, 6, 10),
                LocalTime.MIDNIGHT,
            ),
        )
    }

    @Test
    fun `inverted spans clamp to one day instead of an unsavable form`() {
        assertEquals(
            LocalDate.of(2026, 6, 10),
            allDayEndDate(
                LocalDate.of(2026, 6, 10),
                LocalDate.of(2026, 6, 8),
                LocalTime.NOON,
            ),
        )
    }
}
