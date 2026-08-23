package com.piercingxx.calendar.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * The whole provider surface of the app (D1). No cache of rows anywhere:
 * every call is a fresh query, and [changes] exists so views can re-query
 * their window when the provider moves underneath them.
 *
 * All writes go through `ContentResolver` as a NORMAL CLIENT. The
 * `CALLER_IS_SYNCADAPTER` URI parameter is never appended — that is what makes
 * the provider mark our writes dirty for DAVx⁵ (design §4.1).
 */
class CalendarRepository(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val calProjection = arrayOf(
        Calendars._ID,
        Calendars.ACCOUNT_NAME,
        Calendars.ACCOUNT_TYPE,
        Calendars.CALENDAR_DISPLAY_NAME,
        Calendars.CALENDAR_COLOR,
        Calendars.VISIBLE,
        Calendars.OWNER_ACCOUNT,
        Calendars.CALENDAR_ACCESS_LEVEL,
    )

    @Volatile
    private var lastObservedChangeMillis: Long? = null

    /** All calendars visible to the app, ordered by id. */
    suspend fun calendars(): List<CalendarSummary> = withContext(ioDispatcher) {
        resolver.query(Calendars.CONTENT_URI, calProjection, null, null, "${Calendars._ID} ASC")
            ?.use { c -> buildList { while (c.moveToNext()) add(c.toCalendarSummary()) } }
            ?: emptyList()
    }

    /**
     * Drawer visibility toggle. Writes `VISIBLE` only; `SYNC_EVENTS` is left to
     * DAVx⁵ — hiding a calendar on this device must never stop syncing it.
     */
    suspend fun setVisible(calendarId: Long, visible: Boolean) = withContext(ioDispatcher) {
        val values = ContentValues().apply { put(Calendars.VISIBLE, if (visible) 1 else 0) }
        val updated = resolver.update(
            Calendars.CONTENT_URI, values, "${Calendars._ID}=?", arrayOf(calendarId.toString()),
        )
        check(updated == 1) { "setVisible failed for calendar $calendarId" }
        stampChange()
    }

    /** Expanded occurrences over `[start, end)` plus caller-chosen [marginMillis]. */
    suspend fun instances(
        startMillis: Long,
        endMillis: Long,
        marginMillis: Long = 0L,
    ): List<CalendarInstance> = InstanceQuery.query(
        resolver, startMillis, endMillis, marginMillis, ioDispatcher,
    )

    /** Full modeled + opaque load for the editor, or null if the row is gone. */
    suspend fun loadEvent(eventId: Long): LoadedEvent? = withContext(ioDispatcher) {
        resolver.query(
            Events.CONTENT_URI, null, "${Events._ID}=?", arrayOf(eventId.toString()), null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else LoadedEvent(draft = c.toEventDraft(eventId), opaque = OpaqueColumns.capture(c))
        }
    }

    /**
     * Insert ([EventDraft.eventId] == null) or update an event.
     *
     * On update only modeled columns plus [opaque] are written (D8): the held
     * values round-trip untouched, so unmodeled columns can be neither cleared
     * nor altered by this layer. [opaque] may also carry values for a fresh
     * insert (the detail sheet's duplicate action).
     */
    suspend fun saveEvent(
        draft: EventDraft,
        opaque: OpaqueColumns.HeldValues = OpaqueColumns.HeldValues.EMPTY,
    ): Long = withContext(ioDispatcher) {
        val values = ContentValues()
        draft.writeModeledInto(values)
        with(OpaqueColumns) { opaque.mergeInto(values) }
        val id = draft.eventId
        if (id == null) {
            val uri = resolver.insert(Events.CONTENT_URI, values)
                ?: error("provider refused event insert")
            stampChange()
            ContentUris.parseId(uri)
        } else {
            val updated = resolver.update(
                Events.CONTENT_URI, values, "${Events._ID}=?", arrayOf(id.toString()),
            )
            check(updated == 1) { "event $id vanished before save" }
            stampChange()
            id
        }
    }

    suspend fun deleteEvent(eventId: Long) = withContext(ioDispatcher) {
        // Normal client: provider cascades reminders/attendees/exceptions and
        // marks the deletion for sync.
        resolver.delete(Events.CONTENT_URI, "${Events._ID}=?", arrayOf(eventId.toString()))
        stampChange()
    }

    /** Reminders rows for an event, soonest first, any METHOD as stored. */
    suspend fun remindersFor(eventId: Long): List<EventReminder> = withContext(ioDispatcher) {
        resolver.query(
            Reminders.CONTENT_URI,
            arrayOf(Reminders.MINUTES, Reminders.METHOD),
            "${Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            "${Reminders.MINUTES} ASC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        EventReminder(
                            minutes = c.getInt(c.getColumnIndexOrThrow(Reminders.MINUTES)),
                            method = c.getInt(c.getColumnIndexOrThrow(Reminders.METHOD)),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    /**
     * Wall-clock instant (epoch millis) of the most recent provider change this
     * process has observed — a write made through this repository, or a
     * ContentObserver notification on the Events/Instances URIs. Null until the
     * first such observation in this process.
     *
     * This is deliberately NOT `MAX(Events.LAST_DATE)`. Despite the name,
     * LAST_DATE is the recurrence-expansion horizon ("the last date this event
     * syncs to"), not a modification timestamp — surfacing it as "last change"
     * would fabricate a story the provider does not tell. CalendarContract
     * exposes no client-visible last-modified column at all: DIRTY, MUTATORS
     * and LAST_SYNCED are sync-adapter machinery, and SyncState rows are
     * adapter-opaque blobs. Per design §4.1, this app reports exactly what it
     * can know — when the data last moved underneath it, within this process's
     * lifetime — and nothing more. A fresh process reports unknown, and the
     * Settings sync row (WS9) must render that as unknown rather than guessing.
     */
    suspend fun lastProviderChange(): Long? = lastObservedChangeMillis

    /**
     * Emits once per provider notification on the Instances or Events trees
     * (descendants included). Views re-query their window on each emission;
     * nothing is cached here (D1). Emissions use a bounded buffer — bursts
     * collapse, which is correct because only the latest state matters.
     * The observer detaches when collection stops.
     */
    fun changes(): Flow<Unit> = callbackFlow {
        // Null handler: onChange runs inline on whatever thread notifies. That
        // is deterministic under test and thread-safe here because trySend is.
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                stampChange()
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(Instances.CONTENT_URI, true, observer)
        resolver.registerContentObserver(Events.CONTENT_URI, true, observer)
        awaitClose {
            resolver.unregisterContentObserver(observer)
        }
    }

    private fun stampChange() {
        lastObservedChangeMillis = System.currentTimeMillis()
    }
}

private fun Cursor.toCalendarSummary(): CalendarSummary {
    val accessLevel = intOr(Calendars.CALENDAR_ACCESS_LEVEL) ?: Calendars.CAL_ACCESS_NONE
    return CalendarSummary(
        id = requireNotNull(longOr(Calendars._ID)) { "calendar row without _id" },
        accountName = stringOr(Calendars.ACCOUNT_NAME),
        accountType = stringOr(Calendars.ACCOUNT_TYPE),
        displayName = stringOr(Calendars.CALENDAR_DISPLAY_NAME) ?: "",
        color = intOr(Calendars.CALENDAR_COLOR) ?: 0,
        isVisible = boolOr(Calendars.VISIBLE) ?: true,
        isWritable = accessLevel >= Calendars.CAL_ACCESS_CONTRIBUTOR,
    )
}

private fun Cursor.toEventDraft(eventId: Long): EventDraft = EventDraft(
    calendarId = requireNotNull(longOr(Events.CALENDAR_ID)) { "event without CALENDAR_ID" },
    startMillis = longOr(Events.DTSTART) ?: 0L,
    endMillis = longOr(Events.DTEND),
    eventTimezone = stringOr(Events.EVENT_TIMEZONE) ?: "UTC",
    eventId = eventId,
    title = stringOr(Events.TITLE),
    location = stringOr(Events.EVENT_LOCATION),
    description = stringOr(Events.DESCRIPTION),
    duration = stringOr(Events.DURATION),
    allDay = boolOr(Events.ALL_DAY) ?: false,
    eventEndTimezone = stringOr(Events.EVENT_END_TIMEZONE),
    rrule = stringOr(Events.RRULE),
    rdate = stringOr(Events.RDATE),
    exdate = stringOr(Events.EXDATE),
    availability = intOr(Events.AVAILABILITY) ?: 0,
    colorKey = stringOr(Events.EVENT_COLOR_KEY),
    originalId = longOr(Events.ORIGINAL_ID),
    originalInstanceTime = longOr(Events.ORIGINAL_INSTANCE_TIME),
    originalAllDay = boolOr(Events.ORIGINAL_ALL_DAY),
)

private fun EventDraft.writeModeledInto(values: ContentValues) {
    values.put(Events.CALENDAR_ID, calendarId)
    values.put(Events.DTSTART, startMillis)
    // Recurring events carry DURATION instead of DTEND per RFC 5545 / provider contract.
    if (duration != null) {
        values.put(Events.DURATION, duration)
    } else if (endMillis != null) {
        values.put(Events.DTEND, endMillis)
    }
    values.put(Events.ALL_DAY, if (allDay) 1 else 0)
    values.put(Events.EVENT_TIMEZONE, eventTimezone)
    values.put(Events.AVAILABILITY, availability)
    values.put(Events.TITLE, title)
    values.put(Events.EVENT_LOCATION, location)
    values.put(Events.DESCRIPTION, description)
    values.put(Events.EVENT_END_TIMEZONE, eventEndTimezone)
    values.put(Events.RRULE, rrule)
    values.put(Events.RDATE, rdate)
    values.put(Events.EXDATE, exdate)
    values.put(Events.EVENT_COLOR_KEY, colorKey)
    values.put(Events.ORIGINAL_ID, originalId)
    values.put(Events.ORIGINAL_INSTANCE_TIME, originalInstanceTime)
    if (originalAllDay != null) {
        values.put(Events.ORIGINAL_ALL_DAY, if (originalAllDay) 1 else 0)
    }
}
