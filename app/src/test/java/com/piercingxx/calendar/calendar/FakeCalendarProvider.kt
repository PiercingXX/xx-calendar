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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * In-memory stand-in for CalendarProvider2, registered under the real
 * `com.android.calendar` authority per test.
 *
 * Expansion is deliberately naive: non-recurring events yield one occurrence
 * when `[dtstart, dtend)` intersects the window; RRULEs are expanded only for
 * FREQ=DAILY/WEEKLY (+ INTERVAL, UNTIL), everything else falls back to the
 * single occurrence at DTSTART. That is enough to exercise range filtering, sorting,
 * all-day surfacing and margin plumbing — NOT enough to model Google's
 * expansion semantics, which is exactly why WS12 runs instrumented tests
 * against the real provider.
 */
class FakeCalendarProvider : ContentProvider() {

    val calendars = LinkedHashMap<Long, MutableMap<String, Any?>>()
    val events = LinkedHashMap<Long, MutableMap<String, Any?>>()
    val reminders = LinkedHashMap<Long, MutableMap<String, Any?>>()

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
        val occurrences = events.values.flatMap { expand(it, begin, end) }
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
        val table = uri.pathSegments.firstOrNull()
            ?: throw IllegalArgumentException("bad insert uri $uri")
        when (table) {
            "calendars" -> {
                val id = ++calendarSeq
                calendars[id] = normalize(values).apply { put(CalendarContract.Calendars._ID, id) }
                notify(table, id)
            }
            "events" -> {
                val id = ++eventSeq
                events[id] = normalize(values).apply { put(CalendarContract.Events._ID, id) }
                notify(table, id)
            }
            "reminders" -> {
                val id = ++reminderSeq
                reminders[id] = normalize(values).apply { put(CalendarContract.Reminders._ID, id) }
            }
            "instances" -> throw IllegalStateException("Instances is generated; insert into Events")
            else -> throw IllegalArgumentException("unknown table $table")
        }
        return Uri.parse("content://${CalendarContract.AUTHORITY}/$table/${
            when (table) {
                "calendars" -> calendarSeq
                "events" -> eventSeq
                else -> reminderSeq
            }
        }")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        values ?: return 0
        val store = storeFor(uri)
        val segments = uri.pathSegments
        val (itemSelection, itemArgs) = itemSelection(segments)
        val combined = combine(itemSelection, itemArgs, selection, selectionArgs)
        val patch = normalize(values)
        var count = 0
        store.values.filter { matches(it, combined.first, combined.second) }.forEach { row ->
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
     * Minimal selection matcher: clauses joined by AND, each `col = ?` with
     * positional args (or `col = literal`). Enough for every query this layer
     * issues; anything else fails loudly rather than silently matching.
     */
    private fun matches(
        row: Map<String, Any?>,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Boolean {
        if (selection.isNullOrBlank()) return true
        var argIndex = 0
        return selection.split(Regex("\\s+AND\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .all { clause ->
                val parts = clause.split("=")
                require(parts.size == 2) { "fake supports 'col = ?' only: $clause" }
                val column = parts[0].trim()
                val expectedRaw = parts[1].trim()
                val expected = if (expectedRaw == "?") {
                    selectionArgs?.getOrNull(argIndex++)
                        ?: throw IllegalArgumentException("missing arg for $clause")
                } else {
                    expectedRaw.removeSurrounding("'")
                }
                require(column.isNotBlank())
                valuesEqual(row[column], expected)
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

    /** Naive expansion — see class KDoc. */
    private fun expand(
        event: Map<String, Any?>,
        windowStart: Long,
        windowEnd: Long,
    ): List<Map<String, Any?>> {
        val dtstart = (event[CalendarContract.Events.DTSTART] as? Number)?.toLong()
            ?: return emptyList()
        val durationSeconds = durationSecondsOf(event[CalendarContract.Events.DURATION] as? String)
        val fixedEnd = (event[CalendarContract.Events.DTEND] as? Number)?.toLong() ?: dtstart
        val rrule = event[CalendarContract.Events.RRULE] as? String
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
        }
        return spans.mapNotNull { (s, e) ->
            if (s < windowEnd && e > windowStart) occurrenceRow(event, s, e) else null
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

    private fun notify(table: String, id: Long?) {
        val builder = Uri.parse("content://${CalendarContract.AUTHORITY}/$table").buildUpon()
        if (id != null) ContentUris.appendId(builder, id)
        context?.contentResolver?.notifyChange(builder.build(), null)
    }

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        private const val MAX_EXPANSION = 500
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
