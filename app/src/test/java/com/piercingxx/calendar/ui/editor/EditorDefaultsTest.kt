package com.piercingxx.calendar.ui.editor

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §8.6 editor defaults: a blank [EditorForm] takes its duration and seed
 * reminder from the settings store, while pinned grid times override the
 * duration path entirely.
 */
class EditorDefaultsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun minutesBetween(form: EditorForm): Long =
        java.time.Duration.between(
            LocalDateTime.of(form.startDate, form.startTime),
            LocalDateTime.of(form.endDate, form.endTime),
        ).toMinutes()

    @Test
    fun `default arguments reproduce the historical quiet defaults`() {
        val form = EditorForm.new(zone, startMillis = null, endMillis = null)

        assertEquals(30L, minutesBetween(form))
        assertEquals(listOf(10), form.reminders)
    }

    @Test
    fun `settings drive the unpinned duration and seed reminder`() {
        val form = EditorForm.new(
            zone,
            startMillis = null,
            endMillis = null,
            durationMinutes = 90L,
            reminderMinutes = 60,
        )

        assertEquals(90L, minutesBetween(form))
        assertEquals(listOf(60), form.reminders)
    }

    @Test
    fun `pinned grid times ignore the duration setting`() {
        val startMillis = Instant.parse("2026-08-22T09:00:00Z").toEpochMilli()
        val endMillis = Instant.parse("2026-08-22T09:45:00Z").toEpochMilli()

        val form = EditorForm.new(
            zone,
            startMillis = startMillis,
            endMillis = endMillis,
            durationMinutes = 120L,
            reminderMinutes = 5,
        )

        assertEquals(45L, minutesBetween(form))
        // The seed reminder still comes from settings even when times are pinned.
        assertEquals(listOf(5), form.reminders)
    }
}
