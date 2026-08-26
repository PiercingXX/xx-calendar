package com.piercingxx.calendar.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract.Events
import com.piercingxx.calendar.core.EndCondition
import com.piercingxx.calendar.core.EventFieldEdits
import com.piercingxx.calendar.core.Resolution
import com.piercingxx.calendar.core.RRuleModel
import com.piercingxx.calendar.core.RuleParse
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

/**
 * The §6.3 executor: turns a [Resolution] — the pure data object [com.piercingxx.calendar.core.ScopeResolver]
 * produced — into actual repository/provider operations. This is the only place
 * the dangerous recurring writes happen, so the mapping table lives in exactly
 * one file:
 *
 * | Resolution         | Write                                                              |
 * |--------------------|--------------------------------------------------------------------|
 * | UpdateParentRow    | saveEvent(loaded draft + edits, opaque preserved verbatim)         |
 * | InsertExceptionRow | insert row with ORIGINAL_ID/ORIGINAL_INSTANCE_TIME/ORIGINAL_ALL_DAY|
 * | SplitParent        | UNTIL on parent RRULE; RDATE/EXDATE entries before the             |
 * |                    | split stay on it, at-or-after ones move to the new row;            |
 * |                    | then tail exception rows are re-pointed onto the new row           |
 * | DeleteParentRow    | deleteEvent(parent)                                                |
 * | SetUntil           | parent RRULE rewritten with UNTIL; nothing else                    |
 * | DeleteInstanceUri  | insert a canceled exception via CONTENT_EXCEPTION_URI              |
 * |                    | (ORIGINAL_INSTANCE_TIME + STATUS_CANCELED, the AOSP                |
 * |                    | DeleteEventHelper path); the provider suppresses that occurrence   |
 * | Refusal            | nothing is written; the reason surfaces to the UI                  |
 *
 * [apply]'s `replacementRule` carries the one thing [EventFieldEdits] cannot
 * hold: a rule the editor replaced wholesale. It overrides the parent's RRULE
 * on UpdateParentRow and the inserted row's RRULE on SplitParent (where the
 * default remains `remainingRule.serialize()` per §6.3). It is ignored by the
 * other variants — an exception row never repeats, deletes have no rule.
 */
/**
 * A series split that got as far as truncating the parent's RRULE but failed
 * to insert the continuation row. The split write path is NOT atomic — the
 * CalendarProvider offers a normal client no transaction spanning two Events
 * rows (ContentResolver has none, and `applyBatch` reports per-operation
 * results without rolling back) — so this exception is the truthful report of
 * the one partial state that can persist: the parent now ends at the split
 * point while no continuation exists.
 *
 * [parentEventId] names the truncated row so the UI can offer the recovery
 * path: the tail is not silently lost, it is recoverable by deleting (or
 * re-extending the UNTIL of) the truncated parent and re-creating the series.
 */
class SplitPartialException(
    val parentEventId: Long,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** RDATE/EXDATE DATE-TIME body, UTC (`...Z`) or floating: `yyyyMMdd'T'HHmmss`. */
private val RECURRENCE_DATETIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

/** One row's RDATE/EXDATE strings after a partition (null when nothing remains). */
private data class RecurrenceDates(val rdate: String?, val exdate: String?)

class RecurrenceEditor(
    private val repository: CalendarRepository,
    private val resolver: ContentResolver,
    private val instanceExceptionUri: (Long) -> Uri = { parentEventId ->
        // CalendarContract: appending the ORIGINAL event id to the exception
        // base URI and inserting ORIGINAL_INSTANCE_TIME + STATUS realizes a
        // single-occurrence write (canceled = deleted) on that series.
        ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, parentEventId)
    },
) {

    /**
     * What happened. [Refused] means no write occurred anywhere.
     * [Written.touchedEventId] names the row that now carries the event's
     * identity going forward: the parent on in-place updates, the inserted
     * row on exception/split inserts, null when a row was only removed or
     * truncated - callers use it e.g. to rewrite Reminders rows.
     */
    sealed interface Outcome {
        data class Written(val touchedEventId: Long? = null) : Outcome

        data class Refused(val reason: String) : Outcome

        data class Missing(val eventId: Long) : Outcome
    }

    suspend fun apply(resolution: Resolution, replacementRule: RRuleModel? = null): Outcome =
        when (resolution) {
            is Resolution.UpdateParentRow -> updateParentRow(resolution, replacementRule)
            is Resolution.InsertExceptionRow -> insertExceptionRow(resolution)
            is Resolution.SplitParent -> splitParent(resolution, replacementRule)
            is Resolution.DeleteParentRow -> deleteParentRow(resolution)
            is Resolution.SetUntil -> setUntil(resolution)
            is Resolution.DeleteInstanceUri -> cancelInstance(resolution)
            is Resolution.Refusal -> Outcome.Refused(resolution.reason)
        }

    private suspend fun updateParentRow(
        resolution: Resolution.UpdateParentRow,
        replacementRule: RRuleModel?,
    ): Outcome {
        val loaded = repository.loadEvent(resolution.parentEventId)
            ?: return Outcome.Missing(resolution.parentEventId)
        var merged = resolution.edits.appliedTo(loaded.draft)
        if (replacementRule != null) {
            // RDATE/EXDATE stay as loaded: they are not part of the edit model,
            // and silently rewriting either would guess at the user's intent.
            merged = merged.copy(rrule = replacementRule.serialize())
        }
        repository.saveEvent(merged, loaded.opaque)
        return Outcome.Written(resolution.parentEventId)
    }

    private suspend fun insertExceptionRow(resolution: Resolution.InsertExceptionRow): Outcome {
        val parent = repository.loadEvent(resolution.parentEventId)
            ?: return Outcome.Missing(resolution.parentEventId)
        val edits = resolution.newRowEdits
        val base = parent.draft
        val newStart = edits.startMillis ?: resolution.originalInstanceTimeMillis
        val newEnd = edits.endMillis
            ?: if (edits.clearEndMillis) {
                null
            } else {
                base.endMillis?.let { parentEnd -> newStart + (parentEnd - base.startMillis) }
            }
        val exception = EventDraft(
            calendarId = edits.calendarId ?: base.calendarId,
            startMillis = newStart,
            endMillis = newEnd,
            eventTimezone = edits.eventTimezone ?: base.eventTimezone,
            eventId = null,
            title = if (edits.clearTitle) null else edits.title ?: base.title,
            location = if (edits.clearLocation) null else edits.location ?: base.location,
            description = if (edits.clearDescription) null else edits.description ?: base.description,
            // Recurring parents carry DURATION instead of DTEND; without an
            // absolute end the extent travels as the same duration string.
            duration = if (newEnd == null && !edits.clearDuration) {
                edits.duration ?: base.duration
            } else {
                null
            },
            allDay = edits.allDay ?: base.allDay,
            eventEndTimezone = edits.eventEndTimezone ?: base.eventEndTimezone,
            rrule = null,
            rdate = null,
            exdate = null,
            availability = edits.availability ?: base.availability,
            colorKey = base.colorKey,
            originalId = resolution.parentEventId,
            originalInstanceTime = resolution.originalInstanceTimeMillis,
            originalAllDay = base.allDay,
        )
        // The exception is the same logical event as its parent: unmodeled
        // columns (access level, organizer, guest permissions, custom URI)
        // carry over. Sync-owned identity is excluded by OpaqueColumns itself.
        val insertedId = repository.saveEvent(exception, parent.opaque)
        return Outcome.Written(insertedId)
    }

    /**
     * Split = truncate the parent's RRULE, insert the continuation row, then
     * re-point tail exception rows onto it.
     *
     * Why no transaction: this editor drives the provider as a normal
     * ContentResolver client (design §4.1), and neither `insert`/`update` nor
     * an `applyBatch` over these two Events rows is atomic — `applyBatch`
     * yields per-operation results and never rolls back. The writes are also
     * deliberately ordered truncate-FIRST: inserting the continuation before
     * truncating would briefly double-expand every overlapping occurrence.
     * The residual risk of that ordering — step 1 accepted, step 2 refused —
     * is reported as [SplitPartialException] carrying the parent id, so the
     * UI can show exactly what happened instead of a generic failure.
     * Recovery path for the user: delete the truncated parent (or re-extend
     * its UNTIL) and re-create the series; nothing else was written.
     */
    private suspend fun splitParent(
        resolution: Resolution.SplitParent,
        replacementRule: RRuleModel?,
    ): Outcome {
        val parent = repository.loadEvent(resolution.parentEventId)
            ?: return Outcome.Missing(resolution.parentEventId)
        val truncated = truncateRule(parent.draft.rrule, resolution.newUntil)
            ?: return Outcome.Refused(
                "cannot split the series: its recurrence rule is not recognised",
            )

        // 1) Truncate the parent at just before this instance, keeping on it
        //    only the RDATE/EXDATE entries for occurrences it still owns.
        val (parentDates, tailDates) =
            splitRecurrenceDates(parent.draft, resolution.newRowStartMillis)
        repository.saveEvent(
            parent.draft.copy(
                rrule = truncated,
                rdate = parentDates.rdate,
                exdate = parentDates.exdate,
            ),
            parent.opaque,
        )

        // 2) Insert the new series starting at this instance.
        val edits = resolution.newRowEdits
        val base = parent.draft
        val newStart = edits.startMillis ?: resolution.newRowStartMillis
        val newEnd = edits.endMillis
            ?: if (edits.clearEndMillis) {
                null
            } else {
                base.endMillis?.let { parentEnd -> newStart + (parentEnd - base.startMillis) }
            }
        val rule = replacementRule ?: resolution.remainingRule
        val newRow = EventDraft(
            calendarId = edits.calendarId ?: base.calendarId,
            startMillis = newStart,
            endMillis = newEnd,
            eventTimezone = edits.eventTimezone ?: base.eventTimezone,
            eventId = null,
            title = if (edits.clearTitle) null else edits.title ?: base.title,
            location = if (edits.clearLocation) null else edits.location ?: base.location,
            description = if (edits.clearDescription) null else edits.description ?: base.description,
            duration = if (newEnd == null && !edits.clearDuration) {
                edits.duration ?: base.duration
            } else {
                null
            },
            allDay = edits.allDay ?: base.allDay,
            eventEndTimezone = edits.eventEndTimezone ?: base.eventEndTimezone,
            rrule = rule.serialize(),
            rdate = tailDates.rdate,
            exdate = tailDates.exdate,
            availability = edits.availability ?: base.availability,
            colorKey = base.colorKey,
            originalId = null,
            originalInstanceTime = null,
            originalAllDay = null,
        )
        val newRowId = try {
            repository.saveEvent(newRow, parent.opaque)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The parent is already truncated: report the partial state
            // truthfully rather than letting a generic error hide the loss.
            throw SplitPartialException(
                resolution.parentEventId,
                "series truncated; continuation not created",
                e,
            )
        }

        // 3) Exception rows describing tail occurrences belong to the new series.
        migrateTailExceptions(
            parentId = resolution.parentEventId,
            splitMillis = resolution.newRowStartMillis,
            newRowId = newRowId,
        )
        return Outcome.Written(newRowId)
    }

    /**
     * Invariant: after a successful split, every exception row whose original
     * instance slot lies at or after [splitMillis] describes an occurrence of
     * the CONTINUATION and must carry ORIGINAL_ID = [newRowId]; rows before
     * the split point still describe occurrences of the truncated parent and
     * keep pointing at it. Skipping the re-point would let deleted or moved
     * instances resurrect inside the continuation's expansion. Not called when
     * the continuation insert failed — there is no row to point at, and that
     * failure is reported separately as [SplitPartialException].
     */
    private fun migrateTailExceptions(parentId: Long, splitMillis: Long, newRowId: Long) {
        val tailIds = resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.ORIGINAL_ID}=? AND ${Events.ORIGINAL_INSTANCE_TIME}>=?",
            arrayOf(parentId.toString(), splitMillis.toString()),
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(cursor.getColumnIndexOrThrow(Events._ID)))
                }
            }
        } ?: emptyList()
        for (id in tailIds) {
            val values = ContentValues().apply { put(Events.ORIGINAL_ID, newRowId) }
            resolver.update(
                Events.CONTENT_URI,
                values,
                "${Events._ID}=?",
                arrayOf(id.toString()),
            )
        }
    }

    private suspend fun deleteParentRow(resolution: Resolution.DeleteParentRow): Outcome {
        repository.deleteEvent(resolution.parentEventId)
        return Outcome.Written(touchedEventId = null)
    }

    private suspend fun setUntil(resolution: Resolution.SetUntil): Outcome {
        val parent = repository.loadEvent(resolution.parentEventId)
            ?: return Outcome.Missing(resolution.parentEventId)
        val truncated = truncateRule(parent.draft.rrule, resolution.until)
            ?: return Outcome.Refused(
                "cannot shorten the series: its recurrence rule is not recognised",
            )
        // Parent RRULE only — every other modeled column round-trips unchanged.
        repository.saveEvent(parent.draft.copy(rrule = truncated), parent.opaque)
        return Outcome.Written(resolution.parentEventId)
    }

    /**
     * Delete: this instance (§6.3). Inserts a canceled exception through
     * [Events.CONTENT_EXCEPTION_URI] — the AOSP `DeleteEventHelper` path:
     * ORIGINAL_INSTANCE_TIME names the tapped occurrence and STATUS_CANCELED
     * makes the provider suppress it. The old events/{millis} DELETE was a
     * lie: CalendarProvider2 matches that id as an event `_ID`, so the call
     * was a no-op at best and never wrote any exclusion.
     *
     * A null return from [ContentResolver.insert] means the provider refused
     * the exception row; reporting Written here would close the sheet as if
     * the occurrence was suppressed while it would keep expanding.
     */
    private suspend fun cancelInstance(resolution: Resolution.DeleteInstanceUri): Outcome {
        val parent = repository.loadEvent(resolution.parentEventId)
            ?: return Outcome.Missing(resolution.parentEventId)
        val values = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, resolution.instanceStartMillis)
            put(Events.STATUS, Events.STATUS_CANCELED)
            // AOSP DeleteEventHelper writes this; without it an all-day
            // occurrence's ORIGINAL_INSTANCE_TIME (UTC midnight) can fail
            // to match the expanded instance.
            put(Events.ORIGINAL_ALL_DAY, if (parent.draft.allDay) 1 else 0)
        }
        resolver.insert(instanceExceptionUri(resolution.parentEventId), values)
            ?: return Outcome.Refused(
                "the calendar provider refused this deletion; the occurrence remains",
            )
        return Outcome.Written(touchedEventId = resolution.parentEventId)
    }

    /**
     * Old rule with UNTIL bolted on, or null when the shape is not modelled.
     * The [EndCondition.Until] arrives whole from [com.piercingxx.calendar.core.ScopeResolver]
     * so its dateOnly flag (parent ALL_DAY, RFC 5545 §3.3.10 value-type match)
     * survives the rewrite.
     */
    private fun truncateRule(rawRrule: String?, until: EndCondition.Until): String? {
        if (rawRrule.isNullOrBlank()) return null
        return when (val parsed = RRuleModel.parse(rawRrule)) {
            is RuleParse.Parsed -> parsed.rule.copy(end = until).serialize()

            is RuleParse.Refused -> null
        }
    }

    /**
     * RDATE/EXDATE partition for a split. This-instance deletes are canceled
     * exception rows, migrated by [migrateTailExceptions]; these strings are
     * only the exclusions/additions already stored on the parent (foreign
     * EXDATE, or an older client). Returns parent-kept entries (strictly
     * before [splitMillis], the same boundary its new UNTIL enforces) first
     * and the continuation's entries (at or after the split) second.
     */
    private fun splitRecurrenceDates(
        draft: EventDraft,
        splitMillis: Long,
    ): Pair<RecurrenceDates, RecurrenceDates> {
        val zone = zoneIdOf(draft.eventTimezone)
        val (headExdate, tailExdate) = partitionDateValue(draft.exdate, splitMillis, zone)
        val (headRdate, tailRdate) = partitionDateValue(draft.rdate, splitMillis, zone)
        return RecurrenceDates(headRdate, headExdate) to RecurrenceDates(tailRdate, tailExdate)
    }

    /**
     * Splits one comma-separated RDATE/EXDATE column value at [splitMillis]:
     * entries strictly before it come back first, entries at or after second.
     * Tokens keep their stored RFC 5545 form verbatim — only membership moves,
     * never spelling. Unparseable tokens stay on the parent: dropping an
     * exclusion could resurrect an occurrence, while keeping a dead one only
     * costs bytes on a row that ends at UNTIL anyway.
     */
    private fun partitionDateValue(
        raw: String?,
        splitMillis: Long,
        zone: ZoneId,
    ): Pair<String?, String?> {
        if (raw == null) return null to null
        val tokens = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return raw to null
        val head = mutableListOf<String>()
        val tail = mutableListOf<String>()
        for (token in tokens) {
            val millis = recurrenceTokenMillis(token, zone)
            if (millis != null && millis >= splitMillis) tail += token else head += token
        }
        return head.joinToString(",").ifEmpty { null } to tail.joinToString(",").ifEmpty { null }
    }

    /**
     * Epoch millis of one RDATE/EXDATE token — the three spellings the
     * provider and this app emit (cf. the tolerant reader in the instrumented
     * suite): DATE `yyyyMMdd` read in the event's zone, or DATE-TIME
     * `yyyyMMdd'T'HHmmss` with a trailing `Z` (UTC) or floating (also read in
     * the event's zone). Null when the token is not recognised.
     */
    private fun recurrenceTokenMillis(token: String, zone: ZoneId): Long? {
        val normalized = token.replace('t', 'T').replace('z', 'Z')
        return try {
            when {
                normalized.length == 8 -> LocalDate.parse(
                    normalized,
                    DateTimeFormatter.BASIC_ISO_DATE,
                ).atStartOfDay(zone).toInstant().toEpochMilli()

                normalized.endsWith("Z") -> LocalDateTime.parse(
                    normalized.dropLast(1),
                    RECURRENCE_DATETIME_FORMAT,
                ).toInstant(ZoneOffset.UTC).toEpochMilli()

                else -> LocalDateTime.parse(normalized, RECURRENCE_DATETIME_FORMAT)
                    .atZone(zone).toInstant().toEpochMilli()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun zoneIdOf(eventTimezone: String?): ZoneId =
        eventTimezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

    /**
     * Nullable-edit merge: a null payload with its clear flag false keeps the
     * loaded value; a set clear flag writes null over it ([EventFieldEdits]).
     */
    private fun EventFieldEdits.appliedTo(draft: EventDraft): EventDraft = draft.copy(
        title = if (clearTitle) null else title ?: draft.title,
        location = if (clearLocation) null else location ?: draft.location,
        description = if (clearDescription) null else description ?: draft.description,
        startMillis = startMillis ?: draft.startMillis,
        endMillis = if (clearEndMillis) null else endMillis ?: draft.endMillis,
        duration = if (clearDuration) null else duration ?: draft.duration,
        allDay = allDay ?: draft.allDay,
        eventTimezone = eventTimezone ?: draft.eventTimezone,
        eventEndTimezone = if (clearEventEndTimezone) null else eventEndTimezone ?: draft.eventEndTimezone,
        availability = availability ?: draft.availability,
        calendarId = calendarId ?: draft.calendarId,
    )
}
