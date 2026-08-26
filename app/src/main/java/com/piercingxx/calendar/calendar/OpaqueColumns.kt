package com.piercingxx.calendar.calendar

import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract.Events

/**
 * The D8 mechanism: columns outside the modeled whitelist (design §6.2) are
 * read on load, held untouched in editor state, and written back unchanged
 * on save. An editor physically cannot clear a column it has no field for —
 * [HeldValues] refuses to hold modeled or provider-managed columns at all,
 * so the invariant holds by construction rather than by caller discipline.
 *
 * Pure logic operates on `Map<String, RawValue>`; the Cursor/ContentValues
 * adapters at the bottom are deliberately thin.
 */
object OpaqueColumns {

    /** The §6.2 whitelist — everything the app models and writes directly. */
    val MODELED_EVENT_COLUMNS: Set<String> = setOf(
        Events.TITLE,
        Events.DTSTART,
        Events.DTEND,
        Events.DURATION,
        Events.ALL_DAY,
        Events.EVENT_TIMEZONE,
        Events.EVENT_END_TIMEZONE,
        Events.EVENT_LOCATION,
        Events.DESCRIPTION,
        Events.CALENDAR_ID,
        Events.EVENT_COLOR_KEY,
        Events.AVAILABILITY,
        Events.RRULE,
        Events.RDATE,
        Events.EXDATE,
        Events.ORIGINAL_ID,
        Events.ORIGINAL_INSTANCE_TIME,
        Events.ORIGINAL_ALL_DAY,
    )

    /**
     * Non-modeled columns that must NOT be held and written back.
     *
     * For these, absence on update is already preservation (the provider keeps
     * the old value), while writing them back risks rejecting the whole save or
     * corrupting provider-managed state:
     * - `_id`, `ACCOUNT_NAME`/`ACCOUNT_TYPE`, `_SYNC_ID`: identity.
     * - `DIRTY`, `DELETED`, `LAST_SYNCED`, `MUTATORS`: the dirty-marking that
     *   tells DAVx⁵ to push our write; overwriting them breaks sync (§4.1).
     * - `ORIGINAL_SYNC_ID`, `UID_2445`: sync-adapter-owned identity.
     * - `SYNC_DATA1`..`SYNC_DATA10`: sync-adapter-owned blobs — DAVx⁵ stores
     *   href/etag there. A normal client writing any of them makes
     *   CalendarProvider2 throw `IllegalArgumentException("Only sync adapters
     *   may write to …")`, so a title-only edit of a synced row would reject
     *   the whole save; cloning them onto an inserted exception or continuation
     *   row would do the same. Absence preserves them on update, and inserts
     *   simply start without them.
     * - `IS_ORGANIZER`, `EVENT_COLOR`, `HAS_ALARM`,
     *   `HAS_EXTENDED_PROPERTIES`, `LAST_DATE`: derived by the provider.
     * - `SELF_ATTENDEE_STATUS`: attendee-domain state this app never edits
     *   (teardown §3.4 — attendee rows are untouched).
     *
     * `CUSTOM_APP_PACKAGE`/`CUSTOM_APP_URI` stay preservable — those are
     * app-writable and carry conferencing URIs (design §6.2).
     */
    val NON_PRESERVED_COLUMNS: Set<String> = setOf(
        Events._ID,
        Events.ACCOUNT_NAME,
        Events.ACCOUNT_TYPE,
        Events.DIRTY,
        Events.DELETED,
        Events.LAST_SYNCED,
        Events.MUTATORS,
        Events._SYNC_ID,
        Events.ORIGINAL_SYNC_ID,
        Events.UID_2445,
        Events.SYNC_DATA1,
        Events.SYNC_DATA2,
        Events.SYNC_DATA3,
        Events.SYNC_DATA4,
        Events.SYNC_DATA5,
        Events.SYNC_DATA6,
        Events.SYNC_DATA7,
        Events.SYNC_DATA8,
        Events.SYNC_DATA9,
        Events.SYNC_DATA10,
        Events.IS_ORGANIZER,
        Events.EVENT_COLOR,
        Events.HAS_ALARM,
        Events.HAS_EXTENDED_PROPERTIES,
        Events.LAST_DATE,
        Events.SELF_ATTENDEE_STATUS,
    )

    fun isModeled(column: String): Boolean = column in MODELED_EVENT_COLUMNS

    /** True when the column must be captured on load and merged on save. */
    fun isPreservable(column: String): Boolean =
        !isModeled(column) && column !in NON_PRESERVED_COLUMNS

    /** A raw cell value with its provider type preserved exactly. */
    sealed interface RawValue {
        data object Null : RawValue
        data class Integer(val value: Long) : RawValue
        data class Real(val value: Double) : RawValue
        data class Text(val value: String) : RawValue
        class Blob(val bytes: ByteArray) : RawValue {
            override fun equals(other: Any?): Boolean =
                other is Blob && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
        }
    }

    /**
     * Immutable bag of opaque values. Construct via [of]/[capture]; both drop
     * modeled and non-preserved columns silently, so no code path can ever
     * write over them through this mechanism.
     */
    class HeldValues private constructor(private val held: Map<String, RawValue>) {

        val values: Map<String, RawValue> get() = held

        override fun equals(other: Any?): Boolean = other is HeldValues && held == other.held

        override fun hashCode(): Int = held.hashCode()

        companion object {
            val EMPTY = HeldValues(emptyMap())

            /** Sanitizing factory: the single gate into the opaque bag. */
            fun of(raw: Map<String, RawValue>): HeldValues =
                HeldValues(raw.filterKeys { isPreservable(it) })
        }
    }

    /** Pure: keep only preservable entries from a raw column map. */
    fun preserveAll(source: Map<String, RawValue>): Map<String, RawValue> =
        source.filterKeys { isPreservable(it) }

    /** Pure: copy held values into an output map, types untouched. */
    fun applyTo(held: Map<String, RawValue>, out: MutableMap<String, RawValue>) {
        for ((column, value) in held) out[column] = value
    }

    /** Read every preservable column currently visible in [cursor]. */
    fun capture(cursor: Cursor): HeldValues {
        val names = cursor.columnNames
        val raw = HashMap<String, RawValue>(names.size)
        for (i in names.indices) {
            val column = names[i]
            if (!isPreservable(column)) continue
            raw[column] = when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> RawValue.Null
                Cursor.FIELD_TYPE_INTEGER -> RawValue.Integer(cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> RawValue.Real(cursor.getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> RawValue.Blob(cursor.getBlob(i))
                else -> RawValue.Text(cursor.getString(i) ?: "")
            }
        }
        return HeldValues.of(raw)
    }

    /** Copy held values back into update ContentValues, byte-identical intent. */
    fun HeldValues.mergeInto(values: ContentValues) {
        for ((column, value) in this.values) {
            when (value) {
                is RawValue.Null -> values.putNull(column)
                is RawValue.Integer -> values.put(column, value.value)
                is RawValue.Real -> values.put(column, value.value)
                is RawValue.Text -> values.put(column, value.value)
                is RawValue.Blob -> values.put(column, value.bytes)
            }
        }
    }
}
