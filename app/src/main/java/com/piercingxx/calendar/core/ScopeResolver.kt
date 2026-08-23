package com.piercingxx.calendar.core

data class RecurringEventContext(
    val parentEventId: Long,
    val rule: RRuleModel?,      // null => non-recurring event
    val startMillis: Long,
    val allDay: Boolean,
)

data class InstanceRef(val parentEventId: Long, val instanceStartMillis: Long)

sealed interface RecurrenceScope {
    data object ThisInstance : RecurrenceScope
    data object ThisAndFollowing : RecurrenceScope
    data object AllEvents : RecurrenceScope
}

/**
 * Modeled Events-column edits held by the editor (design §6.2 whitelist).
 *
 * Per-field semantics across the whole pipeline (diff -> resolve -> apply):
 * a non-null payload means "set to this value"; a null payload with its
 * clear flag false means "leave as loaded"; the clear flag set means
 * "write null over the loaded value". Clearing cannot ride in the payload
 * itself because null payload already means "unchanged".
 */
data class EventFieldEdits(
    val title: String? = null,
    val location: String? = null,
    val description: String? = null,
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val duration: String? = null,
    val allDay: Boolean? = null,
    val eventTimezone: String? = null,
    val eventEndTimezone: String? = null,
    val availability: Int? = null,
    val clearTitle: Boolean = false,
    val clearLocation: Boolean = false,
    val clearDescription: Boolean = false,
    val clearEndMillis: Boolean = false,
    val clearDuration: Boolean = false,
    val clearEventEndTimezone: Boolean = false,
)

sealed interface Resolution {
    data class UpdateParentRow(val parentEventId: Long, val edits: EventFieldEdits) : Resolution
    data class InsertExceptionRow(
        val parentEventId: Long,
        val originalInstanceTimeMillis: Long,
        val newRowEdits: EventFieldEdits,
    ) : Resolution

    data class SplitParent(
        val parentEventId: Long,
        val newUntil: EndCondition.Until,
        val newRowStartMillis: Long,
        val newRowEdits: EventFieldEdits,
        val remainingRule: RRuleModel,
    ) : Resolution

    data class DeleteParentRow(val parentEventId: Long) : Resolution
    data class SetUntil(val parentEventId: Long, val until: EndCondition.Until) : Resolution
    data class DeleteInstanceUri(val parentEventId: Long, val instanceStartMillis: Long) : Resolution
    data class Refusal(val reason: String) : Resolution
}

/**
 * Resolves recurring-edit/delete scopes into concrete provider writes as plain
 * data objects (design §6.3). Pure JVM — no Android dependency.
 *
 * "Just before" boundary: every [Resolution.SplitParent.newUntil] and
 * [Resolution.SetUntil.until] is `instance.instanceStartMillis - 1`.
 * For an all-day instance stored at UTC midnight (design §6.4) this lands at
 * 23:59:59.999 the previous day — correct: it excludes the edited instance
 * while including everything earlier in the series. Both carry the parent's
 * ALL_DAY flag as [EndCondition.Until.dateOnly] so the rewritten RRULE keeps
 * UNTIL's value type matched to DTSTART (RFC 5545 §3.3.10).
 */
object ScopeResolver {

    private const val COUNT_REFUSAL = "cannot split a COUNT-bounded series without expanding it"

    /**
     * Non-recurring events (rule == null) always pass through to a plain
     * parent update regardless of the supplied scope — a scope prompt is
     * never offered for them (§6.3).
     */
    fun resolveEdit(
        context: RecurringEventContext,
        scope: RecurrenceScope,
        instance: InstanceRef,
        edits: EventFieldEdits,
    ): Resolution {
        val rule = context.rule
            ?: return Resolution.UpdateParentRow(context.parentEventId, edits)

        return when (scope) {
            RecurrenceScope.ThisInstance -> Resolution.InsertExceptionRow(
                parentEventId = context.parentEventId,
                originalInstanceTimeMillis = instance.instanceStartMillis,
                newRowEdits = edits,
            )

            RecurrenceScope.ThisAndFollowing -> if (rule.end is EndCondition.Count) {
                Resolution.Refusal(COUNT_REFUSAL)
            } else {
                Resolution.SplitParent(
                    parentEventId = context.parentEventId,
                    newUntil = EndCondition.Until(
                        instance.instanceStartMillis - 1,
                        dateOnly = context.allDay,
                    ),
                    newRowStartMillis = instance.instanceStartMillis,
                    newRowEdits = edits,
                    remainingRule = rule.copy(end = EndCondition.Never),
                )
            }

            RecurrenceScope.AllEvents -> Resolution.UpdateParentRow(context.parentEventId, edits)
        }
    }

    /**
     * Deleting a single instance resolves to [Resolution.DeleteInstanceUri]:
     * we let the provider write EXDATE rather than inserting a canceled
     * exception row. §6.3 offers either; we pick one and never mix them.
     */
    fun resolveDelete(
        context: RecurringEventContext,
        scope: RecurrenceScope,
        instance: InstanceRef,
    ): Resolution {
        val rule = context.rule
            ?: return Resolution.DeleteParentRow(context.parentEventId)

        return when (scope) {
            RecurrenceScope.ThisInstance -> Resolution.DeleteInstanceUri(
                parentEventId = context.parentEventId,
                instanceStartMillis = instance.instanceStartMillis,
            )

            RecurrenceScope.ThisAndFollowing -> if (rule.end is EndCondition.Count) {
                Resolution.Refusal(COUNT_REFUSAL)
            } else {
                Resolution.SetUntil(
                    parentEventId = context.parentEventId,
                    until = EndCondition.Until(
                        instance.instanceStartMillis - 1,
                        dateOnly = context.allDay,
                    ),
                )
            }

            RecurrenceScope.AllEvents -> Resolution.DeleteParentRow(context.parentEventId)
        }
    }
}
