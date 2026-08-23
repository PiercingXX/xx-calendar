package com.piercingxx.calendar

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §12 deep-link decoding for the filters MainActivity actually declares:
 * VIEW content://com.android.calendar/time/... and VIEW text/calendar. The
 * INSERT/item-event filters belong to EditorActivity and are covered by its
 * own reading of the data URI.
 *
 * Robolectric (not plain JVM) because Uri/Intent parsing needs real Android
 * implementations; the project pins no returnDefaultValues on purpose.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityDeepLinkTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun view(data: String) = Intent(Intent.ACTION_VIEW, Uri.parse(data))

    @Test
    fun `time millis link parses`() {
        assertEquals(
            DeepLink.Time(1_724_460_000_000),
            parseDeepLink(view("content://com.android.calendar/time/1724460000000")),
        )
    }

    @Test
    fun `time epoch spelling parses`() {
        assertEquals(
            DeepLink.Time(1_724_460_000_000),
            parseDeepLink(view("content://com.android.calendar/time/epoch/1724460000000")),
        )
    }

    @Test
    fun `text calendar view routes to import`() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("file:///sd/plan.ics"), "text/calendar")
        assertEquals(DeepLink.ImportIcs, parseDeepLink(intent))
    }

    @Test
    fun `non-view actions are ignored`() {
        assertNull(parseDeepLink(Intent(Intent.ACTION_SEND)))
    }

    @Test
    fun `foreign hosts are ignored`() {
        assertNull(parseDeepLink(view("content://com.example.app/time/1724460000000")))
    }

    @Test
    fun `malformed numbers paths and uris never crash`() {
        assertNull(parseDeepLink(view("content://com.android.calendar/time/not-a-number")))
        assertNull(parseDeepLink(view("content://com.android.calendar/time")))
        assertNull(parseDeepLink(view("content://com.android.calendar/time/epoch/1/oops")))
        assertNull(parseDeepLink(view("content://com.android.calendar/somewhere/else")))
        assertNull(parseDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse("::broken::"))))
        assertNull(parseDeepLink(null))
        assertNull(parseDeepLink(Intent(context, MainActivity::class.java)))
    }
}
