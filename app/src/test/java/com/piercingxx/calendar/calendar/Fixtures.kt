package com.piercingxx.calendar.calendar

import android.provider.CalendarContract
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Seed helpers that write straight into the fake's in-memory tables — rawer
 * than going through the resolver, which is what opaque-preservation tests
 * need (they must control exactly what a "pre-existing synced row" contains).
 */
internal object Fixtures {

    fun utc(y: Int, month: Int, d: Int, h: Int, min: Int = 0): Long =
        LocalDateTime.of(y, month, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    fun FakeCalendarProvider.seedCalendar(
        id: Long? = null,
        accountName: String? = "you@example.com",
        accountType: String? = CalendarContract.ACCOUNT_TYPE_LOCAL,
        displayName: String = "Test",
        color: Int = 0xFF888888.toInt(),
        visible: Boolean = true,
        accessLevel: Int = CalendarContract.Calendars.CAL_ACCESS_OWNER,
    ): Long {
        val key = id ?: nextCalendarId()
        calendars[key] = linkedMapOf(
            CalendarContract.Calendars._ID to key,
            CalendarContract.Calendars.ACCOUNT_NAME to accountName,
            CalendarContract.Calendars.ACCOUNT_TYPE to accountType,
            CalendarContract.Calendars.NAME to displayName,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to displayName,
            CalendarContract.Calendars.CALENDAR_COLOR to color,
            CalendarContract.Calendars.VISIBLE to if (visible) 1 else 0,
            CalendarContract.Calendars.OWNER_ACCOUNT to accountName,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL to accessLevel.toLong(),
            CalendarContract.Calendars.SYNC_EVENTS to 1L,
        )
        return key
    }

    fun FakeCalendarProvider.seedEvent(
        calendarId: Long,
        vararg columns: Pair<String, Any?>,
    ): Long {
        val explicitId = columns.firstOrNull { it.first == CalendarContract.Events._ID }
            ?.second?.let { (it as Number).toLong() }
        val id = explicitId ?: nextEventId()
        if (explicitId != null) claimEventId(explicitId)
        val row = linkedMapOf<String, Any?>(
            CalendarContract.Events._ID to id,
            CalendarContract.Instances.EVENT_ID to id,
            CalendarContract.Events.CALENDAR_ID to calendarId,
            CalendarContract.Events.TITLE to "Event $id",
            CalendarContract.Events.DTSTART to 0L,
            CalendarContract.Events.DTEND to 3_600_000L,
            CalendarContract.Events.ALL_DAY to 0L,
            CalendarContract.Events.EVENT_TIMEZONE to "UTC",
            CalendarContract.Events.STATUS to CalendarContract.Events.STATUS_CONFIRMED,
            CalendarContract.Events.AVAILABILITY to CalendarContract.Events.AVAILABILITY_BUSY,
        )
        row.putAll(columns)
        events[id] = row
        return id
    }

    fun FakeCalendarProvider.seedReminder(
        eventId: Long,
        minutes: Int,
        method: Int,
    ): Long {
        val id = nextReminderId()
        reminders[id] = linkedMapOf(
            CalendarContract.Reminders._ID to id,
            CalendarContract.Reminders.EVENT_ID to eventId,
            CalendarContract.Reminders.MINUTES to minutes.toLong(),
            CalendarContract.Reminders.METHOD to method.toLong(),
        )
        return id
    }
}
