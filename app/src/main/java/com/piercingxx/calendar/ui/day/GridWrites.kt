package com.piercingxx.calendar.ui.day

import com.piercingxx.calendar.calendar.CalendarRepository

/**
 * The grid's write path (design §8.3): move/resize gestures stage their save
 * through these suspend helpers, which load the full modeled + opaque row and
 * hand it back to [CalendarRepository.saveEvent] so unmodeled columns
 * round-trip untouched (D8). Recurring rows are refused by both helpers —
 * moving or resizing one block of a series needs an exception row plus the
 * §6.3 scope prompt, so the caller routes through the editor instead. Pure
 * plumbing — no state lives here.
 */

/**
 * Move a timed event to [newStartMillis] / [newEndMillis]. Recurring events
 * are refused exactly like [resizeTimedEvent] refuses them: rewriting the
 * parent DTSTART here would shift every occurrence of the series with no
 * scope prompt. Returns false when the row vanished, the shape is not a
 * plain timed event, or the row recurs.
 */
internal suspend fun CalendarRepository.moveTimedEvent(
    eventId: Long,
    newStartMillis: Long,
    newEndMillis: Long,
): Boolean {
    val loaded = loadEvent(eventId) ?: return false
    val draft = loaded.draft
    if (draft.allDay || draft.eventId == null) return false
    if (draft.duration != null || draft.rrule != null) return false

    saveEvent(draft.copy(startMillis = newStartMillis, endMillis = newEndMillis), loaded.opaque)
    return true
}

/**
 * Resize a timed event by rewriting its absolute bounds. Recurring events are
 * refused: changing one occurrence's extent means an exception row plus the
 * §6.3 scope prompt, which belong to the editor workstream.
 */
internal suspend fun CalendarRepository.resizeTimedEvent(
    eventId: Long,
    newStartMillis: Long,
    newEndMillis: Long,
): Boolean {
    val loaded = loadEvent(eventId) ?: return false
    val draft = loaded.draft
    if (draft.allDay || draft.eventId == null) return false
    if (draft.duration != null || draft.rrule != null) return false
    if (newEndMillis <= newStartMillis) return false

    saveEvent(draft.copy(startMillis = newStartMillis, endMillis = newEndMillis), loaded.opaque)
    return true
}
