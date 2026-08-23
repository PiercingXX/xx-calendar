package com.piercingxx.calendar

import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.LocalCalendarBootstrap
import com.piercingxx.calendar.calendar.OpaqueColumns
import kotlinx.coroutines.runBlocking

/**
 * Shared plumbing for the WS12 instrumented suite. Unlike the Robolectric
 * fixtures in src/test, everything here runs against the REAL CalendarProvider2
 * on the device/emulator image — the fake cannot model the provider's
 * recurrence-expansion semantics, which is exactly why WS12 exists (todo WS12,
 * design §11 "Instrumented, against a real provider").
 *
 * Permission grants are NOT here: each suite declares its own
 * [androidx.test.rule.GrantPermissionRule] so the failure of one suite never
 * muddies another's report.
 */
abstract class ProviderFixture {

    protected val context: Context = ApplicationProvider.getApplicationContext()

    protected val resolver: android.content.ContentResolver = context.contentResolver

    protected val repository: CalendarRepository = CalendarRepository(resolver)

    /**
     * Design §4.4: guarantee a writable calendar before any write test,
     * creating a local-account calendar when the image has none.
     */
    protected fun writableCalendarId(): Long =
        runBlocking { LocalCalendarBootstrap.ensureWritableCalendar(context) }
            ?: error("no writable calendar on this image and bootstrap failed to create one")

    /** Every Events row matching [selection], as modeled reads + opaque capture. */
    protected fun eventsWhere(
        selection: String?,
        args: Array<String>? = null,
    ): List<EventRowSnapshot> {
        val out = ArrayList<EventRowSnapshot>()
        resolver.query(Events.CONTENT_URI, null, selection, args, "${Events.DTSTART} ASC")
            ?.use { c -> while (c.moveToNext()) out += EventRowSnapshot(c) }
        return out
    }

    protected fun eventSnapshot(eventId: Long): EventRowSnapshot? =
        eventsWhere("${Events._ID}=?", arrayOf(eventId.toString())).firstOrNull()
}

/**
 * One raw Events row: the modeled columns this suite reasons about, plus the
 * D8 preservable bag ([OpaqueColumns.capture]) for byte-identity comparisons.
 */
class EventRowSnapshot(cursor: Cursor) {
    val id: Long = cursorLong(cursor, Events._ID) ?: -1L
    val title: String? = cursorString(cursor, Events.TITLE)
    val location: String? = cursorString(cursor, Events.EVENT_LOCATION)
    val description: String? = cursorString(cursor, Events.DESCRIPTION)
    val dtstart: Long? = cursorLong(cursor, Events.DTSTART)
    val dtend: Long? = cursorLong(cursor, Events.DTEND)
    val duration: String? = cursorString(cursor, Events.DURATION)
    val allDay: Boolean = cursorLong(cursor, Events.ALL_DAY) == 1L
    val rrule: String? = cursorString(cursor, Events.RRULE)
    val rdate: String? = cursorString(cursor, Events.RDATE)
    val exdate: String? = cursorString(cursor, Events.EXDATE)
    val originalId: Long? = cursorLong(cursor, Events.ORIGINAL_ID)
    val originalInstanceTime: Long? = cursorLong(cursor, Events.ORIGINAL_INSTANCE_TIME)

    /** Every non-modeled, non-provider-managed column with its exact stored type. */
    val preservable: Map<String, OpaqueColumns.RawValue> = OpaqueColumns.capture(cursor).values
}

private fun cursorLong(cursor: Cursor, column: String): Long? {
    val i = cursor.getColumnIndex(column)
    return if (i < 0 || cursor.isNull(i)) null else cursor.getLong(i)
}

private fun cursorString(cursor: Cursor, column: String): String? {
    val i = cursor.getColumnIndex(column)
    return if (i < 0 || cursor.isNull(i)) null else cursor.getString(i)
}
