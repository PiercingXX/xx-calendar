package com.piercingxx.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * All-day conversion, owned here so the UTC-midnight contract (design §6.4)
 * lives in exactly one place. Storage is UTC midnight; display is a calendar
 * date that must never drift with the device zone.
 */
object TimeMath {

    /** Local calendar date -> provider storage (UTC midnight of that date). */
    fun allDayDateToStorage(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Provider storage (UTC midnight) -> calendar date, read back at UTC. */
    fun storageToAllDayDate(storageMillis: Long): LocalDate =
        Instant.ofEpochMilli(storageMillis).atZone(ZoneOffset.UTC).toLocalDate()

    /**
     * Start-of-day instant for a local date in [zone] — where a timed event
     * beginning at local midnight sits on the timeline.
     */
    fun localDayStart(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Calendar date of a timed instant as seen in [zone]. */
    fun localDateOf(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /**
     * Whether the detail sheet shows the event's timezone (design §6.4):
     * only when it differs from the device zone.
     *
     * Zones are compared by their observed offsets across a year of DST
     * behavior, which folds aliases ("US/Eastern" equals "America/New_York")
     * without relying on id normalization, which does not resolve region
     * aliases. An unparseable id against a valid one renders (the label may
     * be the only honest thing we can say); two unparseable ids hide.
     */
    fun shouldRenderTimezone(eventZone: String, deviceZone: String): Boolean {
        val event = offsetsOrNull(eventZone)
        val device = offsetsOrNull(deviceZone)
        if (event == null && device == null) return false
        if (event == null || device == null) return true
        return event != device
    }

    private fun offsetsOrNull(id: String): List<ZoneOffset>? =
        runCatching {
            val rules = ZoneId.of(id).rules
            SAMPLE_INSTANTS.map(rules::getOffset)
        }.getOrNull()

    private val SAMPLE_INSTANTS = listOf(
        Instant.parse("2026-01-15T12:00:00Z"),
        Instant.parse("2026-04-15T12:00:00Z"),
        Instant.parse("2026-07-15T12:00:00Z"),
        Instant.parse("2026-10-15T12:00:00Z"),
    )
}
