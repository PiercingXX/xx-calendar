package com.piercingxx.calendar.ui.day

import com.piercingxx.calendar.calendar.CalendarRepository

/**
 * The grid's write path (design §8.3): move/resize gestures stage their save
 * through these suspend helpers, which load the full modeled + opaque row and
 * hand it back to [CalendarRepository.saveEvent] so unmodeled columns
 * round-trip untouched (D8). Pure plumbing — no state lives here.
 */

/**
 * Move a timed event to [newStartMillis] / [newEndMillis]. For recurring
 * events the shift moves the series start; DURATION carries the extent and is
 * left untouched. Returns false when the row vanished or the shape is not a
 * timed event.
 */
internal suspend fun CalendarRepository.moveTimedEvent(
    eventId: Long,
    newStartMillis: Long,
    newEndMillis: Long,
): Boolean {
    val loaded = loadEvent(eventId) ?: return false
    val draft = loaded.draft
    if (draft.allDay || draft.eventId == null) return false

    val shifted = if (draft.duration != null) {
        draft.copy(startMillis = newStartMillis)
    } else {
        draft.copy(startMillis = newStartMillis, endMillis = newEndMillis)
    }
    saveEvent(shifted, loaded.opaque)
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
