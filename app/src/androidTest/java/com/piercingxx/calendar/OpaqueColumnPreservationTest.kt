package com.piercingxx.calendar

import android.Manifest
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.piercingxx.calendar.calendar.EventDraft
import com.piercingxx.calendar.calendar.OpaqueColumns
import com.piercingxx.calendar.calendar.OpaqueColumns.RawValue
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE R6 TEST, instrumented (design §11, D8): an event arrives carrying the
 * unmodeled columns a synced row really has; ONE modeled field changes on
 * save; every opaque column must come back byte-identical from the REAL
 * provider.
 *
 * Emulator-image tolerance per WS12 brief: which unmodeled columns a given
 * image accepts on insert varies (ORGANIZER in particular is sometimes
 * normalized away). Candidates the image dropped are excluded via Assume;
 * the byte-identity assertion itself always covers every column that DID
 * land, because it compares whole preservable maps captured before and after.
 */
@RunWith(AndroidJUnit4::class)
class OpaqueColumnPreservationTest : ProviderFixture() {

    @get:Rule
    val calendarAccess: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private val start: Long = LocalDate.now(ZoneOffset.UTC).plusDays(3)
        .atTime(13, 20).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Unmodeled, client-writable candidates a real synced event plausibly carries. */
    private val seeded: Map<String, RawValue> = mapOf(
        Events.ACCESS_LEVEL to RawValue.Integer(Events.ACCESS_CONFIDENTIAL.toLong()),
        Events.GUESTS_CAN_MODIFY to RawValue.Integer(1),
        Events.GUESTS_CAN_INVITE_OTHERS to RawValue.Integer(0),
        Events.GUESTS_CAN_SEE_GUESTS to RawValue.Integer(1),
        Events.ORGANIZER to RawValue.Text("organizer@example.com"),
        Events.CUSTOM_APP_PACKAGE to RawValue.Text("com.example.conferencing"),
        Events.CUSTOM_APP_URI to RawValue.Text("https://meet.example.com/room/42"),
    )

    @Test
    fun title_only_save_preserves_every_opaque_column_byte_identical() = runBlocking {
        val draft = EventDraft(
            calendarId = writableCalendarId(),
            startMillis = start,
            endMillis = start + 90L * 60_000L,
            eventTimezone = "America/Los_Angeles",
            eventEndTimezone = "Europe/Berlin",
            title = "Flight UA234 SFO → FRA",
            description = "gate assignments follow",
        )

        // Insert THROUGH the repository: the opaque bag rides along (Models.kt:
        // "[opaque] may also carry values for a fresh insert").
        val id = repository.saveEvent(draft, OpaqueColumns.HeldValues.of(seeded))

        // Which candidates did THIS image actually accept and store?
        val before = eventSnapshot(id)!!.preservable
        val accepted = seeded.filter { (column, value) -> before[column] == value }
        assumeTrue(
            "no seeded opaque column survived insert on this image: $before",
            accepted.isNotEmpty(),
        )

        val loaded = repository.loadEvent(id)!!
        for (column in accepted.keys) {
            assertEquals(
                "loadEvent capture missed accepted column $column",
                seeded[column],
                loaded.opaque.values[column],
            )
        }

        // Change ONLY the title; everything else travels verbatim (D8).
        repository.saveEvent(loaded.draft.copy(title = "Flight UA234 (renamed locally)"), loaded.opaque)

        val after = eventSnapshot(id)!!.preservable
        assertEquals("opaque columns mutated by a title-only save", before, after)

        // The one intended change happened…
        assertEquals("Flight UA234 (renamed locally)", repository.loadEvent(id)!!.draft.title)
        // …and no modeled-but-unedited field moved either.
        val row = repository.loadEvent(id)!!.draft
        assertEquals(draft.startMillis, row.startMillis)
        assertEquals(draft.endMillis, row.endMillis)
        assertEquals(draft.eventTimezone, row.eventTimezone)
        assertEquals(draft.eventEndTimezone, row.eventEndTimezone)
        assertEquals(draft.description, row.description)
        assertEquals(draft.calendarId, row.calendarId)
        assertEquals(false, row.allDay)
    }
}
