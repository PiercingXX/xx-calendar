package com.piercingxx.calendar.settings

import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.SigilTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS10: the §9 backup format — deterministic writer, total reader, corrupt
 * input falls back instead of half-applying.
 */
class BackupJsonTest {

    private val fullSettings = Settings(
        defaultView = DefaultView.MONTH,
        startDayOfWeek = StartDayOfWeek.SUNDAY,
        weekNumbers = true,
        showDeclined = true,
        dimPast = false,
        density = Density.COMPACT,
        defaultDurationMin = 90,
        defaultNotificationMin = 60,
        allDayNotification = AllDayNotification(hourOfDay = 8, daysBefore = 2),
        lockScreenTitle = true,
        dailyAgenda = true,
        headsUp = true,
        hideAutoAdded = false,
        background = AppBackground.AMOLED_NIGHT,
        font = AppFont.JETBRAINS_MONO,
        textSizeScale = 1.25f,
        autoAddedFilterMode = AutoAddedFilterMode.CALENDAR,
    )

    private val sigils = mapOf(
        CalendarKey(1, "you@example.com") to SigilTier.TIER_2,
        CalendarKey(42, "work \"shared\"; team\\ops") to SigilTier.TIER_5,
    )

    // ------------------------------------------------------------ round trip

    @Test
    fun `write then read round-trips settings and sigils`() {
        val json = BackupJson.write(fullSettings, sigils)
        val read = BackupJson.read(json)

        assertTrue(read is BackupRead.Ok)
        read as BackupRead.Ok
        assertEquals(fullSettings, read.snapshot.settings)
        assertEquals(sigils, read.snapshot.sigils)
    }

    @Test
    fun `defaults round-trip too`() {
        val read = BackupJson.read(BackupJson.write(Settings(), emptyMap()))
        assertTrue(read is BackupRead.Ok)
        assertEquals(Settings(), (read as BackupRead.Ok).snapshot.settings)
        assertTrue(read.snapshot.sigils.isEmpty())
    }

    @Test
    fun `writer is deterministic`() {
        assertEquals(
            BackupJson.write(fullSettings, sigils),
            BackupJson.write(fullSettings, sigils),
        )
    }

    @Test
    fun `document shape carries app tag and version`() {
        val json = BackupJson.write(Settings(), emptyMap())
        assertTrue(json.contains("\"app\": \"xx-calendar\""))
        assertTrue(json.contains("\"version\": 1"))
        assertTrue(json.contains("\"settings\""))
        assertTrue(json.contains("\"sigils\": []"))
    }

    @Test
    fun `account names with quotes and backslashes survive`() {
        val tricky = mapOf(CalendarKey(7, "a\"b\\c\td") to SigilTier.TIER_3)
        val read = BackupJson.read(BackupJson.write(Settings(), tricky))
        assertEquals(tricky, (read as BackupRead.Ok).snapshot.sigils)
    }

    // ------------------------------------------------------- missing pieces

    @Test
    fun `empty object restores pure defaults`() {
        val read = BackupJson.read("{}")
        assertTrue(read is BackupRead.Ok)
        assertEquals(Settings(), (read as BackupRead.Ok).snapshot.settings)
        assertTrue((read as BackupRead.Ok).snapshot.sigils.isEmpty())
    }

    @Test
    fun `missing settings and sigil sections keep defaults`() {
        val read = BackupJson.read("{\"app\":\"xx-calendar\",\"version\":1}")
        assertTrue(read is BackupRead.Ok)
        assertEquals(Settings(), (read as BackupRead.Ok).snapshot.settings)
    }

    @Test
    fun `partial settings keep defaults for absent keys`() {
        val json = """
            {
              "settings": {
                "daily_agenda": true,
                "text_size_scale": 1.5
              }
            }
        """.trimIndent()
        val read = BackupJson.read(json)
        read as BackupRead.Ok
        assertEquals(false, read.snapshot.settings.weekNumbers)
        assertEquals(true, read.snapshot.settings.dailyAgenda)
        assertEquals(1.5f, read.snapshot.settings.textSizeScale)
        assertEquals(DefaultView.SCHEDULE, read.snapshot.settings.defaultView)
        assertEquals(AutoAddedFilterMode.METADATA, read.snapshot.settings.autoAddedFilterMode)
    }

    @Test
    fun `unknown keys are ignored`() {
        val read = BackupJson.read(
            "{\"future_section\":{\"x\":1},\"settings\":{\"unknown_key\":\"whatever\",\"dim_past\":false}}",
        )
        read as BackupRead.Ok
        assertEquals(false, read.snapshot.settings.dimPast)
    }

    @Test
    fun `unknown enum names fall back to defaults`() {
        val read = BackupJson.read(
            "{\"settings\":{\"default_view\":\"TELEPORT\",\"density\":\"cozy\"," +
                "\"auto_added_filter_mode\":\"MAYBE\"}}",
        )
        read as BackupRead.Ok
        assertEquals(DefaultView.SCHEDULE, read.snapshot.settings.defaultView)
        assertEquals(Density.COMFORTABLE, read.snapshot.settings.density)
        assertEquals(AutoAddedFilterMode.METADATA, read.snapshot.settings.autoAddedFilterMode)
    }

    @Test
    fun `malformed sigil rows are skipped individually`() {
        val json = """
            {
              "sigils": [
                {"calendarId": 1, "accountName": "ok@x", "tier": 0},
                {"accountName": "no-id", "tier": 1},
                {"calendarId": 2, "tier": 1},
                {"calendarId": 3, "accountName": "bad-tier", "tier": 99},
                {"calendarId": 4, "accountName": "no-tier"},
                "not an object",
                {"calendarId": 5, "accountName": "also-ok@x", "tier": 5}
              ]
            }
        """.trimIndent()
        val read = BackupJson.read(json) as BackupRead.Ok
        assertEquals(
            mapOf(
                CalendarKey(1, "ok@x") to SigilTier.TIER_1,
                CalendarKey(5, "also-ok@x") to SigilTier.TIER_6,
            ),
            read.snapshot.sigils,
        )
        // Settings section entirely absent: untouched defaults.
        assertEquals(Settings(), read.snapshot.settings)
    }

    // -------------------------------------------------------------- corrupt

    @Test
    fun `corrupt input reports Corrupt instead of throwing`() {
        val corruptInputs = listOf(
            "",
            "   ",
            "not json",
            "{",
            "{\"settings\":}",
            "{\"settings\":{\"week_numbers\":tru}}",
            "[1,2,3]",
            "\"just a string\"",
            "{\"settings\":{\"week_numbers\":false},}", // trailing comma
        )
        for (input in corruptInputs) {
            assertEquals("expected Corrupt for <$input>", BackupRead.Corrupt, BackupJson.read(input))
        }
    }

    @Test
    fun `leading byte-order mark is tolerated`() {
        val read = BackupJson.read("﻿{\"settings\":{\"daily_agenda\":true}}")
        assertTrue(read is BackupRead.Ok)
        assertEquals(true, (read as BackupRead.Ok).snapshot.settings.dailyAgenda)
    }

    @Test
    fun `truncated real backup is Corrupt not partial`() {
        val full = BackupJson.write(fullSettings, sigils)
        assertEquals(BackupRead.Corrupt, BackupJson.read(full.substring(0, full.length / 2)))
    }

    @Test
    fun `deeply nested junk cannot blow the stack`() {
        val depth = 10_000
        val bomb = "[".repeat(depth) + "]".repeat(depth)
        assertEquals(BackupRead.Corrupt, BackupJson.read(bomb))
    }
}
