package com.piercingxx.calendar.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One Instances query over `[startMillis + margin, endMillis - margin)`... more
 * precisely over the widened window `[startMillis - marginMillis,
 * endMillis + marginMillis)`. The window comes from the caller; the only
 * widening this layer ever does is the explicit [marginMillis] parameter,
 * defaulting to zero. Views pass their own margin (design §4.2: query the
 * visible window plus one period of margin, off-thread, observer-invalidated).
 */
object InstanceQuery {

    // Occurrence extent lives in BEGIN/END; DTSTART/DTEND are EventsColumns
    // inherited verbatim onto every expanded row, so for a recurrence they
    // carry the SERIES start/end on each occurrence — never read them as
    // instance times.
    const val DEFAULT_SORT_ORDER: String = "${Instances.BEGIN} ASC"

    private val PROJECTION = arrayOf(
        Instances.EVENT_ID,
        Instances.CALENDAR_ID,
        Instances.TITLE,
        Instances.EVENT_LOCATION,
        Instances.DESCRIPTION,
        Instances.BEGIN,
        Instances.END,
        Instances.ALL_DAY,
        Instances.EVENT_TIMEZONE,
        Instances.EVENT_END_TIMEZONE,
        Instances.RRULE,
        Instances.DURATION,
        Instances.AVAILABILITY,
        Instances.STATUS,
        Instances.ORIGINAL_ID,
        Instances.ORIGINAL_INSTANCE_TIME,
        Instances.SELF_ATTENDEE_STATUS,
    )

    suspend fun query(
        resolver: ContentResolver,
        startMillis: Long,
        endMillis: Long,
        marginMillis: Long = 0L,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): List<CalendarInstance> = withContext(ioDispatcher) {
        require(startMillis <= endMillis) { "empty instance window [$startMillis, $endMillis)" }
        val from = Math.addExact(startMillis, -marginMillis)
        val to = Math.addExact(endMillis, marginMillis)

        // Range is encoded in the URI path per CalendarContract.Instances docs;
        // the provider's expansion owns the range filtering, while the
        // standard client filter (Calendars columns join onto the Instances
        // URI) keeps hidden calendars out of every view.
        val uri: Uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()

        val out = ArrayList<CalendarInstance>()
        resolver.query(
            uri,
            PROJECTION,
            "${Calendars.VISIBLE}=?",
            arrayOf("1"),
            DEFAULT_SORT_ORDER,
        )?.use { c ->
            while (c.moveToNext()) out += c.toCalendarInstance()
        }
        out
    }
}

private fun android.database.Cursor.toCalendarInstance(): CalendarInstance = CalendarInstance(
    eventId = requireNotNull(longOr(Instances.EVENT_ID)) { "Instances row without EVENT_ID" },
    calendarId = requireNotNull(longOr(Instances.CALENDAR_ID)) { "Instances row without CALENDAR_ID" },
    title = stringOr(Instances.TITLE),
    location = stringOr(Instances.EVENT_LOCATION),
    description = stringOr(Instances.DESCRIPTION),
    startMillis = longOr(Instances.BEGIN) ?: 0L,
    // END is always present on instance rows: the provider derives it from
    // DTEND or, for duration-based series rows, from DURATION.
    endMillis = longOr(Instances.END) ?: 0L,
    allDay = boolOr(Instances.ALL_DAY) ?: false,
    eventTimezone = stringOr(Instances.EVENT_TIMEZONE),
    eventEndTimezone = stringOr(Instances.EVENT_END_TIMEZONE),
    rrule = stringOr(Instances.RRULE),
    duration = stringOr(Instances.DURATION),
    availability = intOr(Instances.AVAILABILITY) ?: 0,
    status = intOr(Instances.STATUS) ?: 0,
    originalId = longOr(Instances.ORIGINAL_ID),
    originalInstanceTime = longOr(Instances.ORIGINAL_INSTANCE_TIME),
    selfAttendeeStatus = intOr(Instances.SELF_ATTENDEE_STATUS) ?: 0,
)
