package com.piercingxx.calendar.calendar

/**
 * Best-effort, conservative detection of Gmail auto-added events (flights,
 * hotels, deliveries, bills) after they have synced down through DAVx⁵.
 *
 * OPEN QUESTION 1 (design §17) is unresolved: the reliable marker for these
 * events on a real DAVx⁵-synced account is unknown — Google does not document
 * their CalDAV representation, and no synced rows were inspectable while this
 * was written. This detector therefore encodes only the plausible signals, in
 * decreasing order of confidence:
 *
 *  1. Source-calendar identity. Google's generated secondary calendars
 *     (holidays, birthdays, "interesting calendars") surface through CalDAV
 *     with account names under `@group.v.calendar.google.com`, or with blunt
 *     holiday/birthday display names.
 *  2. Event metadata that survives CalDAV sync and correlates with Google
 *     bookings: a URL aimed at the calendar-render endpoints, or a non-null
 *     `CUSTOM_APP_PACKAGE`. The latter is weak evidence — any conferencing or
 *     third-party integration can set it — so it is the loosest signal here
 *     and the first candidate to tighten after on-device inspection (WS12's
 *     instrumented suite against a real account).
 *
 * It FAILS CLOSED: anything ambiguous returns false. Hiding a real meeting is
 * worse than showing junk. WS9 wraps this behind both a global toggle and
 * per-calendar hides, so either outcome of the open question is survivable;
 * do not treat this object as settled truth about Google's data.
 */
object AutoAddedDetector {

    /** Google-generated secondary calendars reachable via CalDAV. */
    private const val GOOGLE_GROUP_CALENDAR_SUFFIX = "@group.v.calendar.google.com"

    /** Display-name hints for the same family of calendars. */
    private val SOURCE_NAME_HINTS = listOf("birthday", "holiday")

    /** Booking-render URL fragments observed on Gmail-inserted events. */
    private val BOOKING_URL_MARKERS = listOf(
        "googlemail.com/calendar-render",
        "google.com/calendar-render",
    )

    /**
     * Event-level metadata outside [CalendarInstance]; feed from the loaded
     * event's opaque columns (`CUSTOM_APP_*`) where available.
     */
    data class Metadata(
        val url: String? = null,
        val customAppPackage: String? = null,
    )

    fun isLikelyAutoAdded(
        instance: CalendarInstance?,
        calendar: CalendarSummary?,
        metadata: Metadata = Metadata(),
    ): Boolean {
        // Stage 1 — dedicated source calendar.
        if (calendar != null) {
            val account = calendar.accountName?.lowercase()
            if (account != null && account.endsWith(GOOGLE_GROUP_CALENDAR_SUFFIX)) return true
            val name = calendar.displayName.lowercase()
            if (SOURCE_NAME_HINTS.any { name.contains(it) }) return true
        }
        // Stage 2 — booking-correlated metadata that survived sync.
        val url = metadata.url?.lowercase()
        if (url != null && BOOKING_URL_MARKERS.any { url.contains(it) }) return true
        if (!metadata.customAppPackage.isNullOrBlank()) return true
        // Unsure → show the event. Fail closed.
        return false
    }
}
