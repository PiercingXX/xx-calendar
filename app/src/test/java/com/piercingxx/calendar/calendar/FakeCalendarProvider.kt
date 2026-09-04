package com.piercingxx.calendar.calendar

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.robolectric.android.controller.ContentProviderController
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * In-memory stand-in for CalendarProvider2, registered under the real
 * `com.android.calendar` authority per test.
 *
 * Expansion is deliberately naive about RULE SHAPE: non-recurring events yield
 * one occurrence when `[dtstart, dtend)` intersects the window; RRULEs are
 * expanded only for FREQ=DAILY/WEEKLY (+ INTERVAL, UNTIL), everything else
 * falls back to the single occurrence at DTSTART. That is enough to exercise
 * range filtering, sorting, all-day surfacing and margin plumbing — NOT enough
 * to model Google's expansion semantics, which is exactly why WS12 runs
 * instrumented tests against the real provider. What it DOES honor (WS14/17.1,
 * so recurring-scope tests can observe their writes): EXDATE and RDATE tokens,
 * exception rows surfaced through their parent series, canceled exceptions
 * (STATUS_CANCELED + ORIGINAL_INSTANCE_TIME) suppressing that occurrence, and
 * non-canceled exceptions replacing it at their own times.
 *
 * Write validation is NOT naive (WS17.1): like CalendarProvider2, inserts and
 * updates of Events reject the sync-adapter-owned columns (SYNC_DATA*, _SYNC_ID,
 * DIRTY, MUTATORS, DELETED, ORIGINAL_SYNC_ID, LAST_SYNCED) from a
 * normal client (`verifyNoSyncColumns`) and refuse rows carrying both DTEND
 * and DURATION — updates after merging the patch onto the stored row, exactly where the real
 * provider validates (`handleUpdateEvents` → `validateEventData`). Inserts
 * also unbox ORIGINAL_ID when the key is present without ORIGINAL_SYNC_ID —
 * a stored null NPEs the same way CalendarProvider2 does. The
 * `exception/{eventId}` insert (CONTENT_EXCEPTION_URI shape) clones the
 * original row into an exception row, applying the caller's overrides — the
 * mechanism RecurrenceEditor uses for delete-this-instance.
 */
class FakeCalendarProvider : ContentProvider() {

    val calendars = LinkedHashMap<Long, MutableMap<String, Any?>>()
    val events = LinkedHashMap<Long, MutableMap<String, Any?>>()
    val reminders = LinkedHashMap<Long, MutableMap<String, Any?>>()

    /** URIs received by [insert], in call order — lets tests pin call shapes. */
    val insertUris = mutableListOf<Uri>()

    private var calendarSeq = 0L
    private var eventSeq = 0L
    private var reminderSeq = 0L
    private var occurrenceSeq = -1_000_000L

    /** Seed helpers allocate from the SAME sequences as resolver inserts. */
    internal fun nextCalendarId(): Long = ++calendarSeq

    internal fun nextEventId(): Long = ++eventSeq

    internal fun nextReminderId(): Long = ++reminderSeq

    /** For seeds that pin an explicit _id, keep the sequence above it. */
    internal fun claimEventId(id: Long) {
        if (id > eventSeq) eventSeq = id
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = when (uri.pathSegments.firstOrNull()) {
        "calendars" -> "vnd.android.cursor.dir/calendars"
        "events" -> "vnd.android.cursor.dir/event"
        "instances" -> "vnd.android.cursor.dir/instance"
        "reminders" -> "vnd.android.cursor.dir/reminders"
        else -> null
    }

    // ---------------------------------------------------------------- query

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val segments = uri.pathSegments
        val table = segments.firstOrNull() ?: throw IllegalArgumentException("bad uri $uri")
        if (table == "instances") {
            return instancesCursor(segments, projection, selection, selectionArgs, sortOrder)
        }
        val rows = when (table) {
            "calendars" -> calendars.values
            "events" -> events.values
            "reminders" -> reminders.values
            else -> throw IllegalArgumentException("unknown table $table")
        }
        val (itemSelection, itemArgs) = itemSelection(segments)
        val combined = combine(itemSelection, itemArgs, selection, selectionArgs)
        return tableCursor(rows, projection, combined.first, combined.second, sortOrder)
    }

    /** `events/42` → `_id=?` + args; dir URIs → null/null. */
    private fun itemSelection(segments: List<String>): Pair<String?, Array<String>?> {
        if (segments.size < 2) return null to null
        val id = segments[1].toLongOrNull() ?: return null to null
        return "_id=?" to arrayOf(id.toString())
    }

    private fun combine(
        selA: String?,
        argsA: Array<out String>?,
        selB: String?,
        argsB: Array<out String>?,
    ): Pair<String?, Array<String>> {
        if (selA == null && selB == null) return null to arrayOf()
        val early: Pair<String, Array<String>>? = when {
            selA == null && selB == null -> null
            selA == null -> selB!! to (argsB?.toList()?.toTypedArray() ?: arrayOf())
            selB == null -> selA to (argsA?.toList()?.toTypedArray() ?: arrayOf())
            else -> null
        }
        if (early != null) return early
        val args = buildList {
            argsA?.let { addAll(it) }
            argsB?.let { addAll(it) }
        }
        return "$selA AND $selB" to args.toTypedArray()
    }

    private fun tableCursor(
        rows: Collection<Map<String, Any?>>,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String? = null,
    ): Cursor {
        val matched = rows.filter { matches(it, selection, selectionArgs) }
            .let { applySort(it, sortOrder) }
        val columns = resolveColumns(matched, projection)
        val cursor = MatrixCursor(columns)
        matched.forEach { row -> cursor.addRow(columns.map { row[it] }) }
        return cursor
    }

    /** Single-column `col [ASC|DESC]` — everything this layer issues. */
    private fun applySort(
        rows: List<Map<String, Any?>>,
        sortOrder: String?,
    ): List<Map<String, Any?>> {
        if (sortOrder.isNullOrBlank()) return rows
        val parts = sortOrder.trim().split(Regex("\\s+"))
        val column = parts[0].trim()
        val descending = parts.getOrNull(1)?.equals("DESC", ignoreCase = true) == true
        val comparator = compareBy<Map<String, Any?>> {
            (it[column] as? Number)?.toDouble() ?: Double.NEGATIVE_INFINITY
        }
        return rows.sortedWith(if (descending) comparator.reversed() else comparator)
    }

    private fun instancesCursor(
        segments: List<String>,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val begin = segments.getOrNull(2)?.toLongOrNull() ?: Long.MIN_VALUE
        val end = segments.getOrNull(3)?.toLongOrNull() ?: Long.MAX_VALUE
        require(begin <= end) { "invalid instance window [$begin, $end)" }
        val occurrences = expandedOccurrences(begin, end)
            .filter { matches(it, selection, selectionArgs) }
        val sorted = if (sortOrder.isNullOrBlank()) {
            occurrences.sortedWith(compareBy { beginOf(it) })
        } else {
            applySort(occurrences, sortOrder)
        }
        val columns = resolveColumns(sorted, projection)
        val cursor = MatrixCursor(columns)
        sorted.forEach { row -> cursor.addRow(columns.map { row[it] }) }
        return cursor
    }

    private fun beginOf(row: Map<String, Any?>): Long =
        (row[CalendarContract.Instances.BEGIN] as? Number)?.toLong() ?: Long.MIN_VALUE

    private fun resolveColumns(
        rows: Collection<Map<String, Any?>>,
        projection: Array<out String>?,
    ): Array<String> {
        if (projection != null) return projection.toList().toTypedArray()
        val seen = LinkedHashSet<String>()
        rows.forEach { seen += it.keys }
        if (seen.isEmpty()) return arrayOf("_id")
        return seen.toTypedArray()
    }

    // --------------------------------------------------------------- writes

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        values ?: return null
        insertUris += uri
        val segments = uri.pathSegments
        return when (segments.firstOrNull()) {
            "calendars" -> {
                val id = ++calendarSeq
                calendars[id] = normalize(values).apply { put(CalendarContract.Calendars._ID, id) }
                notify("calendars", id)
                tableUri("calendars", calendarSeq)
            }

            "events" -> {
                requireNoSyncColumnWrites(uri, values)
                requireOriginalIdUnboxable(values)
                val row = normalize(values)
                requireValidEventExtent(row, "insert")
                val id = ++eventSeq
                events[id] = row.apply { put(CalendarContract.Events._ID, id) }
                notify("events", id)
                tableUri("events", eventSeq)
            }

            // CONTENT_EXCEPTION_URI shape: exception/{originalEventId}. The
            // provider clones the original event into an exception row and
            // applies the caller's overrides on top; ORIGINAL_INSTANCE_TIME is
            // mandatory, DTSTART lands on the instance, RRULE/RDATE/EXDATE do
            // not carry over (an exception never repeats).
            "exception" -> insertException(segments, uri, values)

            "reminders" -> {
                val id = ++reminderSeq
                reminders[id] = normalize(values).apply { put(CalendarContract.Reminders._ID, id) }
                tableUri("reminders", reminderSeq)
            }

            "instances" -> throw IllegalStateException("Instances is generated; insert into Events")
            else -> throw IllegalArgumentException("unknown insert uri $uri")
        }
    }

    private fun insertException(
        segments: List<String>,
        uri: Uri,
        values: ContentValues,
    ): Uri {
        requireNoSyncColumnWrites(uri, values)
        val parentId = segments.getOrNull(1)?.toLongOrNull()
            ?: throw IllegalArgumentException("exception uri must carry the original event id")
        val parent = events[parentId]
            ?: throw IllegalArgumentException("no original event $parentId for exception")
        val patch = normalize(values)
        require(patch.containsKey(CalendarContract.Events.ORIGINAL_INSTANCE_TIME)) {
            "exception insert requires ORIGINAL_INSTANCE_TIME"
        }
        val instanceTime = (patch[CalendarContract.Events.ORIGINAL_INSTANCE_TIME] as Number).toLong()
        val parentStart = (parent[CalendarContract.Events.DTSTART] as Number).toLong()
        val row = LinkedHashMap(parent)
        row.remove(CalendarContract.Instances.EVENT_ID)
        row.remove(CalendarContract.Events.RRULE)
        row.remove(CalendarContract.Events.RDATE)
        row.remove(CalendarContract.Events.EXDATE)
        row.putAll(patch)
        row[CalendarContract.Events.ORIGINAL_ID] = parentId
        if (!patch.containsKey(CalendarContract.Events.ORIGINAL_ALL_DAY)) {
            row[CalendarContract.Events.ORIGINAL_ALL_DAY] = parent[CalendarContract.Events.ALL_DAY]
        }
        row[CalendarContract.Events.DTSTART] = instanceTime
        (parent[CalendarContract.Events.DTEND] as? Number)?.let {
            row[CalendarContract.Events.DTEND] = instanceTime + (it.toLong() - parentStart)
        }
        requireValidEventExtent(row, "exception insert for $parentId")
        val id = ++eventSeq
        row[CalendarContract.Events._ID] = id
        events[id] = row
        notify("events", id)
        return tableUri("events", eventSeq)
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        values ?: return 0
        val table = uri.pathSegments.firstOrNull()
            ?: throw IllegalArgumentException("bad update uri $uri")
        if (table == "events") requireNoSyncColumnWrites(uri, values)
        val store = storeFor(uri)
        val segments = uri.pathSegments
        val (itemSelection, itemArgs) = itemSelection(segments)
        val combined = combine(itemSelection, itemArgs, selection, selectionArgs)
        val patch = normalize(values)
        var count = 0
        // CalendarProvider2 merges the update onto the existing row and then
        // validates the merged event; mirror that, so a stale DTEND/DURATION
        // leftover is caught exactly where the real provider would throw.
        val matched = store.values.filter { matches(it, combined.first, combined.second) }
        if (table == "events") {
            matched.forEach { row ->
                val merged = LinkedHashMap(row).apply { putAll(patch) }
                requireValidEventExtent(merged, "update of _id=${row[CalendarContract.Events._ID]}")
            }
        }
        matched.forEach { row ->
            row.putAll(patch)
            count++
        }
        if (count > 0 && segments.isNotEmpty()) notify(segments[0], null)
        return count
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val store = storeFor(uri)
        val segments = uri.pathSegments
        val (itemSelection, itemArgs) = itemSelection(segments)
        val combined = combine(itemSelection, itemArgs, selection, selectionArgs)
        val doomed = store.entries.filter { matches(it.value, combined.first, combined.second) }
        doomed.forEach { store.remove(it.key) }
        if (doomed.isNotEmpty() && segments.isNotEmpty()) notify(segments[0], null)
        return doomed.size
    }

    private fun storeFor(uri: Uri): LinkedHashMap<Long, MutableMap<String, Any?>> =
        when (uri.pathSegments.firstOrNull()) {
            "calendars" -> calendars
            "events" -> events
            "reminders" -> reminders
            "instances" -> throw IllegalStateException("Instances is generated")
            else -> throw IllegalArgumentException("unknown table ${uri.path}")
        }

    // ------------------------------------------------------------ internals

    /**
     * CalendarProvider2's `verifyNoSyncColumns`: a normal client may not write
     * the events table's sync-adapter-owned columns — the SYNC_DATA1..10 blobs
     * DAVx⁵ keeps href/etag in, plus the sync bookkeeping _SYNC_ID, DIRTY,
     * MUTATORS, DELETED, ORIGINAL_SYNC_ID and LAST_SYNCED identified from
     * CalendarContract's sync-column interfaces. UID_2445 and CUSTOM_APP_* stay
     * client-writable here (the former's app-writability on real devices is an
     * open question gated on hardware evidence — do not encode either answer).
     * `containsKey`, not value, decides: even an explicit null from a normal
     * client is rejected.
     */
    private fun requireNoSyncColumnWrites(uri: Uri, values: ContentValues) {
        if (isCallerSyncAdapter(uri)) return
        for (column in SYNC_OWNED_COLUMNS) {
            if (values.containsKey(column)) {
                throw IllegalArgumentException("Only sync adapters may write to $column")
            }
        }
        for (column in PROVIDER_ONLY_COLUMNS) {
            if (values.containsKey(column)) {
                throw IllegalArgumentException("Only the provider may write to $column")
            }
        }
    }

    private fun isCallerSyncAdapter(uri: Uri): Boolean =
        uri.getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER) == "true"

    /**
     * CalendarProvider2.insertInTransactionInner: when ORIGINAL_SYNC_ID is
     * absent and ORIGINAL_ID is present, it unboxes getAsLong(ORIGINAL_ID)
     * into getOriginalSyncId(long). A stored null still occupies the key, so
     * a new-event insert that put(ORIGINAL_ID, null) dies with
     * Long.longValue() on a null object — the crash a blank editor save
     * produced on device. Mirror that here so JVM tests cannot green-pass it.
     */
    private fun requireOriginalIdUnboxable(values: ContentValues) {
        if (!values.containsKey(CalendarContract.Events.ORIGINAL_ID)) return
        if (values.containsKey(CalendarContract.Events.ORIGINAL_SYNC_ID)) return
        values.getAsLong(CalendarContract.Events.ORIGINAL_ID)
            ?: throw NullPointerException(
                "Attempt to invoke virtual method 'long java.lang.Long.longValue()' on a null object reference",
            )
    }

    /**
     * CalendarProvider2's `validateEventData` DTEND/DURATION rule, with its
     * null-safe semantics: an explicitly NULLed column does not count as
     * present, which is exactly what lets a normal client switch a row between
     * the two extent shapes on update.
     */
    private fun requireValidEventExtent(row: Map<String, Any?>, context: String) {
        val hasDtend = (row[CalendarContract.Events.DTEND] as? Number) != null
        val duration = row[CalendarContract.Events.DURATION] as? String
        if (hasDtend && !duration.isNullOrBlank()) {
            throw IllegalArgumentException(
                "Cannot have both DTEND and DURATION in an event ($context)",
            )
        }
    }

    private fun normalize(values: ContentValues): MutableMap<String, Any?> {
        val row = LinkedHashMap<String, Any?>()
        for ((key, value) in values.valueSet()) {
            row[key] = when (value) {
                is Boolean -> if (value) 1L else 0L
                is Int -> value.toLong()
                is Short -> value.toLong()
                is Byte -> value.toLong()
                is Float -> value.toDouble()
                else -> value
            }
        }
        return row
    }

    /**
     * Selection matcher: clauses joined by AND, each a comparison of a column
     * against a positional arg or literal — `col = ?` plus the numeric
     * `col >= ?` / `col <= ?` (migrateTailExceptions' ORIGINAL_INSTANCE_TIME
     * window). Anything else fails loudly rather than silently matching.
     */
    private fun matches(
        row: Map<String, Any?>,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Boolean {
        if (selection.isNullOrBlank()) return true
        var argIndex = 0
        fun nextArg(clause: String): String =
            selectionArgs?.getOrNull(argIndex++)
                ?: throw IllegalArgumentException("missing arg for $clause")

        return selection.split(Regex("\\s+AND\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .all { clause ->
                when {
                    clause.contains(">=") ->
                        compareNumeric(row, clause, ">=", nextArg(clause))

                    clause.contains("<=") ->
                        compareNumeric(row, clause, "<=", nextArg(clause))

                    else -> {
                        val parts = clause.split("=")
                        require(parts.size == 2) {
                            "fake supports 'col = ?', 'col >= ?', 'col <= ?' only: $clause"
                        }
                        val column = parts[0].trim()
                        val expectedRaw = parts[1].trim()
                        val expected = if (expectedRaw == "?") {
                            nextArg(clause)
                        } else {
                            expectedRaw.removeSurrounding("'")
                        }
                        require(column.isNotBlank())
                        valuesEqual(row[column], expected)
                    }
                }
            }
    }

    /** Whether `column operator bound` holds, failing loudly on bad shapes. */
    private fun compareNumeric(
        row: Map<String, Any?>,
        clause: String,
        operator: String,
        boundRaw: String,
    ): Boolean {
        val index = clause.indexOf(operator)
        val column = clause.substring(0, index).trim()
        require(column.isNotBlank()) { "fake supports 'col $operator ?': $clause" }
        val actual = row[column] as? Number
            ?: throw IllegalArgumentException("non-numeric column $column in '$clause'")
        val bound = boundRaw.toLongOrNull()
            ?: throw IllegalArgumentException("numeric bound required for '$clause'")
        return when (operator) {
            ">=" -> actual.toLong() >= bound
            else -> actual.toLong() <= bound
        }
    }

    private fun valuesEqual(actual: Any?, expected: String): Boolean = when (actual) {
        null -> false
        is Number ->
            actual.toLong().toString() == expected ||
                actual.toInt().toString() == expected ||
                actual.toDouble().toString() == expected
        is ByteArray -> actual.contentEquals(expected.toByteArray())
        else -> actual.toString() == expected
    }

    /**
     * Series expansion with exception folding (WS14/17.1): recurring and
     * single rows expand as before, minus EXDATE slots, plus RDATE slots;
     * exception rows (ORIGINAL_ID set) surface through their parent — a
     * canceled one suppresses its generated occurrence entirely, a live one
     * replaces it at the exception's own times. Orphaned live exceptions
     * still appear; orphaned canceled ones stay gone.
     */
    private fun expandedOccurrences(windowStart: Long, windowEnd: Long): List<Map<String, Any?>> {
        val exceptionsByParent = LinkedHashMap<Long, MutableList<Map<String, Any?>>>()
        val parents = ArrayList<Map<String, Any?>>()
        for (row in events.values) {
            val originalId = (row[CalendarContract.Events.ORIGINAL_ID] as? Number)?.toLong()
            if (originalId == null) {
                parents += row
            } else {
                exceptionsByParent.getOrPut(originalId) { ArrayList() }.add(row)
            }
        }
        val parentIds = parents.mapTo(HashSet()) { (it[CalendarContract.Events._ID] as Number).toLong() }
        val out = ArrayList<Map<String, Any?>>()
        for (parent in parents) {
            val id = (parent[CalendarContract.Events._ID] as Number).toLong()
            out += expand(parent, windowStart, windowEnd, exceptionsByParent[id].orEmpty())
        }
        for ((parentId, exceptions) in exceptionsByParent) {
            if (parentId in parentIds) continue
            for (exception in exceptions) {
                if (!isCanceled(exception)) {
                    out += expand(exception, windowStart, windowEnd, emptyList())
                }
            }
        }
        return out
    }

    /** Naive rule expansion — see class KDoc. */
    private fun expand(
        event: Map<String, Any?>,
        windowStart: Long,
        windowEnd: Long,
        exceptions: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        val dtstart = (event[CalendarContract.Events.DTSTART] as? Number)?.toLong()
            ?: return emptyList()
        val durationSeconds = durationSecondsOf(event[CalendarContract.Events.DURATION] as? String)
        val fixedEnd = (event[CalendarContract.Events.DTEND] as? Number)?.toLong() ?: dtstart
        val rrule = event[CalendarContract.Events.RRULE] as? String
        val zone = zoneIdOfEvent(event)
        val spans = ArrayList<Pair<Long, Long>>()
        if (rrule.isNullOrBlank()) {
            spans += dtstart to (durationSeconds?.let { dtstart + it * 1000 } ?: fixedEnd)
        } else {
            val upper = rrule.uppercase()
            val interval = Regex("INTERVAL=(\\d+)").find(upper)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 1L
            val until = parseUntilMillis(upper)
            val freq = Regex("FREQ=(\\w+)").find(upper)?.groupValues?.get(1)
            val stepMillis = when (freq) {
                "WEEKLY" -> 7 * DAY_MILLIS
                else -> DAY_MILLIS
            }
            if (freq == "DAILY" || freq == "WEEKLY") {
                var s = dtstart
                var guard = 0
                while (s < windowEnd && guard < MAX_EXPANSION && (until == null || s <= until)) {
                    val e = durationSeconds?.let { s + it * 1000 } ?: (fixedEnd + (s - dtstart))
                    spans += s to e
                    s += interval * stepMillis
                    guard++
                }
            } else {
                spans += dtstart to (durationSeconds?.let { dtstart + it * 1000 } ?: fixedEnd)
            }
            // RDATE: extra occurrences at the token times, same extent shape.
            for (r in recurrenceTokenMillisList(event[CalendarContract.Events.RDATE], zone)) {
                spans += r to (durationSeconds?.let { r + it * 1000 } ?: (fixedEnd + (r - dtstart)))
            }
        }
        // EXDATE and exception slots both remove the generated occurrence;
        // a live exception row then re-appears at its own times below.
        val exdates = recurrenceTokenMillisList(event[CalendarContract.Events.EXDATE], zone).toSet()
        val suppressed = exceptions.mapNotNull {
            (it[CalendarContract.Events.ORIGINAL_INSTANCE_TIME] as? Number)?.toLong()
        }.toSet() + exdates
        val rows = spans.asSequence()
            .filter { (s, _) -> s !in suppressed }
            .filter { (s, e) -> s < windowEnd && e > windowStart }
            .map { (s, e) -> occurrenceRow(event, s, e) }
            .toMutableList()
        for (exception in exceptions) {
            if (isCanceled(exception)) continue
            rows += expand(exception, windowStart, windowEnd, emptyList())
        }
        return rows
    }

    /** A canceled exception suppresses its occurrence instead of replacing it. */
    private fun isCanceled(row: Map<String, Any?>): Boolean =
        (row[CalendarContract.Events.STATUS] as? Number)?.toInt() ==
            CalendarContract.Events.STATUS_CANCELED

    private fun zoneIdOfEvent(row: Map<String, Any?>): ZoneId =
        (row[CalendarContract.Events.EVENT_TIMEZONE] as? String)
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneOffset.UTC

    /**
     * Epoch millis of every comma-separated RDATE/EXDATE token — the three
     * spellings the provider and this app emit: DATE `yyyyMMdd` read in the
     * event's zone, DATE-TIME with trailing `Z` (UTC), or floating (event's
     * zone). Unrecognised tokens are skipped.
     */
    private fun recurrenceTokenMillisList(raw: Any?, zone: ZoneId): List<Long> {
        val value = raw as? String ?: return emptyList()
        return value.split(',').mapNotNull { token ->
            val t = token.trim()
            if (t.isEmpty()) return@mapNotNull null
            try {
                when {
                    t.length == 8 -> LocalDate.parse(t, DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay(zone).toInstant().toEpochMilli()

                    t.endsWith("Z") -> LocalDateTime.parse(
                        t.dropLast(1),
                        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"),
                    ).toInstant(ZoneOffset.UTC).toEpochMilli()

                    else -> LocalDateTime.parse(
                        t,
                        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"),
                    ).atZone(zone).toInstant().toEpochMilli()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun occurrenceRow(event: Map<String, Any?>, start: Long, end: Long): Map<String, Any?> {
        val row = LinkedHashMap(event)
        // Series columns ride along verbatim, exactly as CalendarProvider2
        // returns them on every expanded row: Instances inherits EventsColumns,
        // so DTSTART/DTEND describe the SERIES (and DTEND is absent when the
        // series is duration-based). Only BEGIN/END carry this occurrence.
        if (!(event[CalendarContract.Events.DURATION] as? String).isNullOrBlank()) {
            row.remove(CalendarContract.Events.DTEND)
        }
        // Generated-table columns a real Instances query always carries; the
        // Instances URI also joins Calendars, so VISIBLE filters like on-device.
        row[CalendarContract.Instances.EVENT_ID] =
            (event[CalendarContract.Events._ID] as? Number)?.toLong() ?: 0L
        row[CalendarContract.Instances.BEGIN] = start
        row[CalendarContract.Instances.END] = end
        row[CalendarContract.Instances._ID] = --occurrenceSeq
        val calendarId = (event[CalendarContract.Events.CALENDAR_ID] as? Number)?.toLong()
        row[CalendarContract.Calendars.VISIBLE] =
            calendarId?.let { calendars[it]?.get(CalendarContract.Calendars.VISIBLE) } ?: 0L
        return row
    }

    private fun tableUri(table: String, id: Long): Uri =
        Uri.parse("content://${CalendarContract.AUTHORITY}/$table/$id")

    private fun notify(table: String, id: Long?) {
        val builder = Uri.parse("content://${CalendarContract.AUTHORITY}/$table").buildUpon()
        if (id != null) ContentUris.appendId(builder, id)
        context?.contentResolver?.notifyChange(builder.build(), null)
    }

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        private const val MAX_EXPANSION = 500
        private val SYNC_OWNED_COLUMNS = listOf(
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.DIRTY,
            CalendarContract.Events.MUTATORS,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.ORIGINAL_SYNC_ID,
            CalendarContract.Events.LAST_SYNCED,
            CalendarContract.Events.SYNC_DATA1,
            CalendarContract.Events.SYNC_DATA2,
            CalendarContract.Events.SYNC_DATA3,
            CalendarContract.Events.SYNC_DATA4,
            CalendarContract.Events.SYNC_DATA5,
            CalendarContract.Events.SYNC_DATA6,
            CalendarContract.Events.SYNC_DATA7,
            CalendarContract.Events.SYNC_DATA8,
            CalendarContract.Events.SYNC_DATA9,
            CalendarContract.Events.SYNC_DATA10,
        )

        /** Events.PROVIDER_WRITABLE_COLUMNS — joined calendar fields on view_events. */
        private val PROVIDER_ONLY_COLUMNS = listOf(
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.ALLOWED_REMINDERS,
            CalendarContract.Calendars.ALLOWED_AVAILABILITY,
            CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES,
            CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE,
            CalendarContract.Calendars.CAN_ORGANIZER_RESPOND,
            CalendarContract.Calendars.CAN_PARTIALLY_UPDATE,
            CalendarContract.Calendars.CAL_SYNC1,
        )
    }
}

// ------------------------------------------------------------------ helpers

private fun durationSecondsOf(duration: String?): Long? {
    if (duration.isNullOrBlank()) return null
    val match = Regex(
        "^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$",
    ).find(duration.trim()) ?: return null
    val (w, d, h, m, s) = match.destructured
    return w.toLongOrZero() * 604_800 + d.toLongOrZero() * 86_400 +
        h.toLongOrZero() * 3_600 + m.toLongOrZero() * 60 + s.toLongOrZero()
}

private fun String.toLongOrZero(): Long = toLongOrNull() ?: 0L

private fun parseUntilMillis(rruleUpper: String): Long? {
    val match = Regex("UNTIL=(\\d{8})(T\\d{6}Z?)?").find(rruleUpper) ?: return null
    return try {
        val datePart = match.groupValues[1]
        val timePart = match.groupValues[2]
        if (timePart.isBlank()) {
            LocalDate.parse(datePart, DateTimeFormatter.BASIC_ISO_DATE)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            LocalDateTime.parse(
                datePart + timePart.take(7),
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"),
            ).toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    } catch (_: Exception) {
        null
    }
}

/** Registers [provider] under the real calendar authority for one test. */
internal fun attachFakeCalendarProvider(
    provider: FakeCalendarProvider,
): ContentProviderController<FakeCalendarProvider> =
    ContentProviderController.of(provider).create(CalendarContract.AUTHORITY)

/**
 * Base for tests needing the fake wired to a Robolectric resolver. Subclasses
 * must carry `@RunWith(RobolectricTestRunner::class)`; sdk is pinned globally
 * by robolectric.properties.
 */
abstract class FakeProviderFixture {

    protected val fake = FakeCalendarProvider()

    protected lateinit var resolver: ContentResolver
        private set

    private lateinit var controller: ContentProviderController<FakeCalendarProvider>

    @Before
    fun wireFakeProvider() {
        resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
        controller = attachFakeCalendarProvider(fake)
    }

    @After
    fun unwireFakeProvider() {
        controller.shutdown()
    }
}
