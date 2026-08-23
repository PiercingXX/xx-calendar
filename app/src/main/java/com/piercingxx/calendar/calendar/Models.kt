package com.piercingxx.calendar.calendar

/**
 * One row of the calendar drawer. [isWritable] is derived from
 * `CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR`.
 */
data class CalendarSummary(
    val id: Long,
    val accountName: String?,
    val accountType: String?,
    val displayName: String,
    val color: Int,
    val isVisible: Boolean,
    val isWritable: Boolean,
)

/**
 * One expanded occurrence as the four views consume it. Everything here comes
 * off a single `Instances` query; nothing is joined, cached, or enriched
 * afterwards (D1: the provider is the system of record).
 */
data class CalendarInstance(
    val eventId: Long,
    val calendarId: Long,
    val title: String?,
    val location: String?,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val eventTimezone: String?,
    val eventEndTimezone: String?,
    val rrule: String?,
    val duration: String?,
    val availability: Int,
    val status: Int,
    val originalId: Long?,
    val originalInstanceTime: Long?,
    val selfAttendeeStatus: Int,
)

/** One `Reminders` row as read back from the provider. */
data class EventReminder(
    val minutes: Int,
    val method: Int,
)

/**
 * The modeled (§6.2) fields the editor owns. Exactly these are written on
 * save; everything else travels via [OpaqueColumns.HeldValues].
 *
 * `eventId == null` means "new event".
 */
data class EventDraft(
    val calendarId: Long,
    val startMillis: Long,
    val endMillis: Long?,
    val eventTimezone: String,
    val eventId: Long? = null,
    val title: String? = null,
    val location: String? = null,
    val description: String? = null,
    val duration: String? = null,
    val allDay: Boolean = false,
    val eventEndTimezone: String? = null,
    val rrule: String? = null,
    val rdate: String? = null,
    val exdate: String? = null,
    val availability: Int = AVAILABILITY_BUSY,
    val colorKey: String? = null,
    val originalId: Long? = null,
    val originalInstanceTime: Long? = null,
    val originalAllDay: Boolean? = null,
) {
    companion object {
        const val AVAILABILITY_BUSY = 0
    }
}

/**
 * A fully-loaded event for the editor flow: the modeled fields as an
 * [EventDraft] plus every non-modeled column held untouched (D8). Saving
 * must pass [opaque] back to [CalendarRepository.saveEvent] verbatim.
 */
data class LoadedEvent(
    val draft: EventDraft,
    val opaque: OpaqueColumns.HeldValues,
) {
    val eventId: Long
        get() = requireNotNull(draft.eventId) { "LoadedEvent always carries an eventId" }
}
