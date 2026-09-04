package com.piercingxx.calendar.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import com.piercingxx.calendar.settings.AutoAddedFilterMode
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
     * On update, only modeled columns are written. CalendarProvider2 merges
     * the patch onto the existing row, so unspecified columns stay put — that
     * is how D8 is satisfied for an edit. Writing captured opaque values back
     * on update is how a DAVx⁵ row's joined calendar columns
     * (`calendar_displayName`, `visible`, …) produced
     * `"Only the provider may write to …"`.
     *
     * On insert, [opaque] is merged so a duplicate / exception / continuation
     * can carry app-writable extras (CUSTOM_APP_*, ACCESS_LEVEL, ORGANIZER).
     * Calendar-join and sync-owned keys are already stripped by
     * [OpaqueColumns].
     */
    suspend fun saveEvent(
        draft: EventDraft,
        opaque: OpaqueColumns.HeldValues = OpaqueColumns.HeldValues.EMPTY,
    ): Long = withContext(ioDispatcher) {
        val values = ContentValues()
        draft.writeModeledInto(values)
        val id = draft.eventId
        if (id == null) {
            with(OpaqueColumns) { opaque.mergeInto(values) }
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
    writeExtentInto(values)
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
    // Omit, don't putNull. CalendarProvider2.insertInTransactionInner treats
    // containsKey(ORIGINAL_ID) as "this is an exception" and unboxes
    // getAsLong(ORIGINAL_ID) into getOriginalSyncId(long). A stored null still
    // occupies the key, so a new-event insert that put(ORIGINAL_ID, null) dies
    // with Long.longValue() on a null object. ORIGINAL_ALL_DAY already
    // followed this rule; the two Long linkage columns did not.
    originalId?.let { values.put(Events.ORIGINAL_ID, it) }
    originalInstanceTime?.let { values.put(Events.ORIGINAL_INSTANCE_TIME, it) }
    if (originalAllDay != null) {
        values.put(Events.ORIGINAL_ALL_DAY, if (originalAllDay) 1 else 0)
    }
}

/**
 * DTSTART's companion extent, written as exactly one of DURATION / DTEND with
 * the other explicitly NULLed. CalendarProvider2 merges an update onto the
 * existing row before validating it (`handleUpdateEvents`), so leaving the
 * unused column absent keeps any stale leftover of the other kind on the row
 * and the save dies with
 * `IllegalArgumentException("Cannot have both DTEND and DURATION in an event")`
 * — e.g. adding a repeat to a timed single event whose row carries DTEND, or
 * saving a DURATION-based recurring all-day row through the editor, whose
 * draft always arrives with an exclusive DTEND ([com.piercingxx.calendar.ui.editor.buildDraft]).
 *
 * Repeating all-day rows follow the provider recurrence shape: DURATION in
 * whole days, DTEND null — not the single-event exclusive end.
 */
private fun EventDraft.writeExtentInto(values: ContentValues) {
    val recurring = !rrule.isNullOrBlank() || !rdate.isNullOrBlank()
    when {
        duration != null -> {
            values.put(Events.DURATION, duration)
            values.putNull(Events.DTEND)
        }

        recurring && allDay && endMillis != null -> {
            // Exclusive end → P<n>D. A well-formed all-day row spans whole
            // days; anything else is malformed input and falls through to the
            // plain DTEND shape rather than inventing a duration.
            val days = (endMillis - startMillis) / DAY_MILLIS
            if (days > 0 && startMillis + days * DAY_MILLIS == endMillis) {
                values.put(Events.DURATION, "P${days}D")
                values.putNull(Events.DTEND)
            } else {
                values.put(Events.DTEND, endMillis)
                values.putNull(Events.DURATION)
            }
        }

        endMillis != null -> {
            values.put(Events.DTEND, endMillis)
            values.putNull(Events.DURATION)
        }
        // Neither end nor duration: leave both out. The draft describes no
        // extent at all; the provider validates that on insert/update merge.
    }
}

private const val DAY_MILLIS = 86_400_000L

/**
 * The §8.6 consumption filters applied ABOVE the query layer, so every
 * consumer of [CalendarRepository.instances] — Schedule, Day, Week, Month and
 * both widgets — shares one implementation. Pure JVM: no Android calls beyond
 * compile-time provider constants.
 *
 * HONESTY NOTE (design §17 open question 1): instance rows as projected by
 * [InstanceQuery] carry no `CUSTOM_APP_PACKAGE`, so the METADATA mode's
 * stage-2 evidence here is limited to booking URLs found in the event's
 * description. A detector hit through that column would additionally require
 * widening the projection; until then this layer simply cannot see it (the
 * detector still checks it whenever a caller can supply the value).
 */
object InstanceFilters {

    /**
     * Declined = the event's own attendee status ([Instances.SELF_ATTENDEE_STATUS]
     * == ATTENDEE_STATUS_DECLINED) — the same predicate ReminderPlanner applies.
     */
    fun isDeclined(instance: CalendarInstance): Boolean =
        instance.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED

    /** First http(s) URL in free text, or null. Stops at whitespace/quote/bracket/punctuation. */
    fun firstUrl(text: String?): String? {
        text ?: return null
        val start = listOf(
            text.indexOf("http://", ignoreCase = true),
            text.indexOf("https://", ignoreCase = true),
        ).filter { it >= 0 }.minOrNull() ?: return null
        val end = indexOfFirstTerminator(text, start)
        return text.substring(start, end).takeIf { it.length > MIN_URL_LENGTH }
    }

    private const val MIN_URL_LENGTH = "http://".length

    private fun indexOfFirstTerminator(text: String, from: Int): Int {
        for (i in from until text.length) {
            val ch = text[i]
            if (ch.isWhitespace() || ch in "<>\"')(,;[]") return i
        }
        return text.length
    }

    /** Stage-2 metadata reachable above the query layer: description URLs. */
    fun metadataOf(instance: CalendarInstance): AutoAddedDetector.Metadata =
        AutoAddedDetector.Metadata(url = firstUrl(instance.description))

    /**
     * Whether [instance] hides under [mode]:
     *  - OFF — never (fail open; the global toggle alone decides nothing).
     *  - CALENDAR — stage-1 signals only: source-calendar identity.
     *  - METADATA — stages 1 + 2: calendar identity plus description URLs.
     */
    fun isHiddenAsAutoAdded(
        instance: CalendarInstance,
        calendar: CalendarSummary?,
        mode: AutoAddedFilterMode,
    ): Boolean = when (mode) {
        AutoAddedFilterMode.OFF -> false
        AutoAddedFilterMode.CALENDAR ->
            AutoAddedDetector.isLikelyAutoAdded(calendar, AutoAddedDetector.Metadata())
        AutoAddedFilterMode.METADATA ->
            AutoAddedDetector.isLikelyAutoAdded(calendar, metadataOf(instance))
    }

    /**
     * One pass over [instances]: declined occurrences dropped unless
     * [showDeclined], auto-added ones dropped when [hideAutoAdded] arms
     * [autoAddedFilterMode]. Order preserved; input list untouched.
     */
    fun apply(
        instances: List<CalendarInstance>,
        showDeclined: Boolean,
        hideAutoAdded: Boolean,
        autoAddedFilterMode: AutoAddedFilterMode,
        calendarsById: Map<Long, CalendarSummary> = emptyMap(),
    ): List<CalendarInstance> = instances.filter { instance ->
        (showDeclined || !isDeclined(instance)) &&
            (!hideAutoAdded || !isHiddenAsAutoAdded(
                instance,
                calendarsById[instance.calendarId],
                autoAddedFilterMode,
            ))
    }
}
