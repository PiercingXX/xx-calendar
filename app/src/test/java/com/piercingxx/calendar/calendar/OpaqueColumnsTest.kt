package com.piercingxx.calendar.calendar

import android.content.ContentValues
import android.database.MatrixCursor
import android.provider.CalendarContract.Events
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.hamcrest.CoreMatchers.`is`

@RunWith(RobolectricTestRunner::class)
class OpaqueColumnsTest {

    // ---------------------------------------------------------- whitelist

    @Test
    fun `modeled whitelist is exactly design section 6_2`() {
        assertThat(
            OpaqueColumns.MODELED_EVENT_COLUMNS,
            `is`(
                setOf(
                    "title", "dtstart", "dtend", "duration", "allDay",
                    "eventTimezone", "eventEndTimezone", "eventLocation",
                    "description", "calendar_id", "eventColor_index", "availability",
                    "rrule", "rdate", "exdate", "original_id",
                    "originalInstanceTime", "originalAllDay",
                ),
            ),
        )
        // The literal spellings above must keep matching the provider constants.
        assertEquals("allDay", Events.ALL_DAY)
        assertEquals("eventEndTimezone", Events.EVENT_END_TIMEZONE)
        assertEquals("originalAllDay", Events.ORIGINAL_ALL_DAY)
        assertEquals("eventColor_index", Events.EVENT_COLOR_KEY)
    }

    @Test
    fun `preservable means non-modeled and not provider-managed`() {
        assertTrue(OpaqueColumns.isModeled(Events.TITLE))
        assertFalse(OpaqueColumns.isPreservable(Events.TITLE))

        assertTrue(OpaqueColumns.isPreservable(Events.ACCESS_LEVEL))
        assertTrue(OpaqueColumns.isPreservable(Events.GUESTS_CAN_MODIFY))
        assertTrue(OpaqueColumns.isPreservable(Events.GUESTS_CAN_INVITE_OTHERS))
        assertTrue(OpaqueColumns.isPreservable(Events.GUESTS_CAN_SEE_GUESTS))
        assertTrue(OpaqueColumns.isPreservable(Events.HAS_ATTENDEE_DATA))
        assertTrue(OpaqueColumns.isPreservable(Events.ORGANIZER))
        assertTrue(OpaqueColumns.isPreservable(Events.CUSTOM_APP_PACKAGE))
        assertTrue(OpaqueColumns.isPreservable(Events.CUSTOM_APP_URI))
        assertTrue(OpaqueColumns.isPreservable("sync_data1"))
        assertTrue(OpaqueColumns.isPreservable("sync_data10"))

        assertFalse(OpaqueColumns.isPreservable(Events._ID))
        assertFalse(OpaqueColumns.isPreservable(Events.ACCOUNT_NAME))
        assertFalse(OpaqueColumns.isPreservable(Events._SYNC_ID))
        assertFalse(OpaqueColumns.isPreservable(Events.DIRTY))
        assertFalse(OpaqueColumns.isPreservable(Events.DELETED))
        assertFalse(OpaqueColumns.isPreservable(Events.LAST_SYNCED))
        assertFalse(OpaqueColumns.isPreservable(Events.MUTATORS))
        assertFalse(OpaqueColumns.isPreservable(Events.LAST_DATE))
        assertFalse(OpaqueColumns.isPreservable(Events.SELF_ATTENDEE_STATUS))
    }

    // ------------------------------------------------------------- capture

    @Test
    fun `capture holds every preservable column with its type intact`() {
        val payload = byteArrayOf(1, 2, 3, 0x7F)
        val cursor = MatrixCursor(
            arrayOf(
                "_id", Events.TITLE, Events.ACCESS_LEVEL, Events.GUESTS_CAN_MODIFY,
                Events.ORGANIZER, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI,
                "sync_data1", "sync_data2", "confidence", "payload", Events.IS_ORGANIZER,
                Events.DIRTY,
            ),
        )
        cursor.addRow(
            arrayOf<Any?>(
                9L, "Flight UA234", 3L, 0L,
                "airline@example.com", "com.example.app", "content://x/42",
                "sd-one", null, 0.75, payload, 0L,
                1L,
            ),
        )
        cursor.moveToFirst()

        val held = OpaqueColumns.capture(cursor)

        assertThat(
            held.values.keys,
            `is`(
                setOf(
                    Events.ACCESS_LEVEL, Events.GUESTS_CAN_MODIFY, Events.ORGANIZER,
                    Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI,
                    "sync_data1", "sync_data2", "confidence", "payload",
                ),
            ),
        )
        assertEquals(OpaqueColumns.RawValue.Integer(3L), held.values[Events.ACCESS_LEVEL])
        assertEquals(OpaqueColumns.RawValue.Integer(0L), held.values[Events.GUESTS_CAN_MODIFY])
        assertEquals(
            OpaqueColumns.RawValue.Text("airline@example.com"),
            held.values[Events.ORGANIZER],
        )
        assertEquals(OpaqueColumns.RawValue.Null, held.values["sync_data2"])
        assertEquals(OpaqueColumns.RawValue.Real(0.75), held.values["confidence"])
        assertEquals(OpaqueColumns.RawValue.Blob(payload), held.values["payload"])

        // Modeled and provider-managed columns are structurally excluded.
        assertFalse(held.values.containsKey(Events.TITLE))
        assertFalse(held.values.containsKey("_id"))
        assertFalse(held.values.containsKey(Events.IS_ORGANIZER))
        assertFalse(held.values.containsKey(Events.DIRTY))
    }

    @Test
    fun `held values refuse to hold modeled columns even if handed them`() {
        val held = OpaqueColumns.HeldValues.of(
            mapOf(
                Events.TITLE to OpaqueColumns.RawValue.Text("overwrite attempt"),
                Events.DTSTART to OpaqueColumns.RawValue.Integer(123L),
                Events.ACCESS_LEVEL to OpaqueColumns.RawValue.Integer(1L),
            ),
        )
        assertThat(held.values.keys, `is`(setOf(Events.ACCESS_LEVEL)))
    }

    @Test
    fun `held values equality compares blob contents`() {
        val a = OpaqueColumns.HeldValues.of(mapOf("p" to OpaqueColumns.RawValue.Blob(byteArrayOf(1, 2))))
        val b = OpaqueColumns.HeldValues.of(mapOf("p" to OpaqueColumns.RawValue.Blob(byteArrayOf(1, 2))))
        val c = OpaqueColumns.HeldValues.of(mapOf("p" to OpaqueColumns.RawValue.Blob(byteArrayOf(1, 3))))
        assertEquals(a, b)
        org.junit.Assert.assertNotEquals(a, c)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ---------------------------------------------------------------- merge

    @Test
    fun `mergeInto writes every value back with its original android type`() {
        val bytes = byteArrayOf(9, 8)
        val held = OpaqueColumns.HeldValues.of(
            mapOf(
                Events.ACCESS_LEVEL to OpaqueColumns.RawValue.Integer(2L),
                "ratio" to OpaqueColumns.RawValue.Real(0.5),
                Events.ORGANIZER to OpaqueColumns.RawValue.Text("o@x.com"),
                "sync_data4" to OpaqueColumns.RawValue.Null,
                "payload" to OpaqueColumns.RawValue.Blob(bytes),
            ),
        )
        val values = ContentValues()

        with(OpaqueColumns) { held.mergeInto(values) }

        assertEquals(2L, values.get(Events.ACCESS_LEVEL))
        assertEquals(0.5, values.get("ratio") as Double, 0.0)
        assertEquals("o@x.com", values.getAsString(Events.ORGANIZER))
        assertTrue(values.containsKey("sync_data4")) // putNull, not silently dropped
        assertNullValue(values.get("sync_data4"))
        assertArrayEquals(bytes, values.getAsByteArray("payload"))
    }

    private fun assertNullValue(actual: Any?) {
        org.junit.Assert.assertNull(actual)
    }

    @Test
    fun `pure applyTo round-trips through preserveAll`() {
        val source: Map<String, OpaqueColumns.RawValue> = mapOf(
            Events.TITLE to OpaqueColumns.RawValue.Text("modeled — dropped"),
            "sync_data1" to OpaqueColumns.RawValue.Text("keep"),
        )
        val out = mutableMapOf<String, OpaqueColumns.RawValue>()

        OpaqueColumns.applyTo(OpaqueColumns.preserveAll(source), out)

        assertThat(out, `is`(mapOf("sync_data1" to OpaqueColumns.RawValue.Text("keep"))))
    }
}
