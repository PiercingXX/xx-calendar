package com.piercingxx.calendar.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import com.piercingxx.calendar.calendar.CalendarInstance
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.InstanceFilters
import com.piercingxx.calendar.settings.SettingsStore
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The §4.3 reconciler: recompute the desired alarm set over the next 48h
 * from the provider, diff against what is scheduled, apply the difference.
 * Never chases individual events; every trigger below funnels into this one
 * code path, which is what makes it converge after any missed signal (§10).
 *
 * Trigger matrix:
 * | Signal                                            | Entry point |
 * |---------------------------------------------------|-------------|
 * | BOOT_COMPLETED / LOCKED_BOOT_COMPLETED            | [BootReceiver] -> [reconcileAfterBroadcast] |
 * | TIME_SET / TIMEZONE_CHANGED / MY_PACKAGE_REPLACED | [ReminderReceiver] -> [reconcileAfterBroadcast] |
 * | Provider change on Instances/Events tree          | [ProviderObserver] debounce 2s -> [reconcile] |
 * | Daily heartbeat, 03:00 local, inexact             | [ReminderReceiver] ACTION_DAILY_HEARTBEAT |
 * | App start                                         | [ensureObserving] — called from MainActivity's onCreate; the provider observer stays attached for the whole process lifetime. |
 *
 * Single-flight: overlapping signals collapse on a Mutex. The app is
 * single-process; cross-process races are not handled (documented honestly).
 */
object ReminderReconciler {

    /** Recompute window per design §4.3: "Instances joined to Reminders for the next 48 hours". */
    const val HORIZON_MILLIS: Long = 48L * 60L * 60L * 1000L

    private const val TAG = "ReminderReconciler"

    /** Reminders EVENT_ID IN (...) chunk size — well under any binder limit. */
    private const val CHUNK = 800

    private const val HEARTBEAT_REQUEST_CODE = 0x0C11A17

    /** Scope shared by receivers; dies with the process, like they do. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val flight = Mutex()

    @Volatile
    private var observing: Boolean = false

    /**
     * Full reconcile pass. Failures are logged, never thrown at callers —
     * a boot receiver must not crash the process (e.g. READ_CALENDAR denied
     * makes provider queries throw SecurityException, which degrades here to
     * "skip this pass"; §10's permission gate owns telling the user).
     */
    suspend fun reconcile(context: Context) {
        val app = context.applicationContext
        try {
            flight.withLock { reconcileLocked(app) }
        } catch (e: Exception) {
            Log.w(TAG, "reconcile aborted: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Receiver entry: runs the reconcile off the main thread and finishes the
     * broadcast's PendingResult afterwards so the system keeps the process
     * alive for the whole pass. Pass [finishBroadcast] = `goAsync()::finish`.
     */
    fun reconcileAfterBroadcast(context: Context, finishBroadcast: (() -> Unit)?) {
        val app = context.applicationContext
        scope.launch {
            try {
                reconcile(app)
            } finally {
                finishBroadcast?.invoke()
            }
        }
    }

    /**
     * Blocking convenience for tests and for callers that already run on a
     * background dispatcher and need completion guaranteed before returning.
     */
    fun reconcileBlocking(context: Context) {
        runBlocking { reconcile(context) }
    }

    /**
     * Starts the process-wide [ProviderObserver] exactly once. Idempotent.
     */
    fun ensureObserving(context: Context) {
        if (observing) return
        synchronized(this) {
            if (observing) return
            ProviderObserver.start(context.applicationContext)
            observing = true
        }
    }

    private suspend fun reconcileLocked(app: Context) {
        val now = System.currentTimeMillis()
        val repository = CalendarRepository(app.contentResolver)

        // §8.6 all-day anchor: every pass reads the stored policy itself, so
        // boot- and broadcast-driven reconciles plan with the user's choice
        // instead of the legacy fixed lead — no dependency on MainActivity
        // having run in this process. MainActivity's mirror stays as the
        // change-trigger that re-reconciles on edits; on a read failure the
        // last-known mirror is kept rather than reset.
        val settings = runCatching { SettingsStore(app).current() }.getOrNull()
        settings?.allDayNotification?.let { ReminderPlanner.allDayPolicy = it }

        // Instances over [now, now+48h). A trigger can never precede its
        // instance start minus lead, and past triggers are dropped by the
        // planner, so no earlier window is needed.
        val raw = repository.instances(now, now + HORIZON_MILLIS)

        // WS16: alarms match the VISIBLE calendar. Views and widgets run
        // InstanceFilters (declined + auto-added) over every instance they
        // consume; a Gmail-booking row hidden by hideAutoAdded must not keep
        // firing an alarm from behind the filter. Same arguments, same
        // predicate as Schedule/Month/Week. On a settings read failure the
        // pass keeps the pre-WS16 behavior (raw window) rather than inventing
        // hides from defaults; a calendars read failure degrades to an empty
        // map, where stage-1 calendar evidence simply cannot fire.
        val instances = if (settings != null) {
            val calendarsById = runCatching { repository.calendars() }
                .getOrDefault(emptyList())
                .associateBy { it.id }
            InstanceFilters.apply(
                raw,
                showDeclined = settings.showDeclined,
                hideAutoAdded = settings.hideAutoAdded,
                autoAddedFilterMode = settings.autoAddedFilterMode,
                calendarsById = calendarsById,
            )
        } else {
            raw
        }
        val minutesByEvent =
            reminderMinutesByEvent(app, instances.map { it.eventId }.distinct())

        val plannerInstances = instances.map { it.toPlannerInstance(minutesByEvent) }
        val desired = ReminderPlanner.plan(plannerInstances, now)

        val registry = ScheduledAlarmRegistry(app)
        val scheduled = registry.load()
        val plan = ReminderPlanner.computePlan(desired, scheduled)

        val scheduler = AlarmScheduler(app)
        plan.toCancel.forEach(scheduler::cancel)
        plan.toSet.forEach(scheduler::schedule)

        registry.persist((scheduled - plan.toCancel) + plan.toSet)

        scheduleDailyHeartbeat(app)

        Log.d(
            TAG,
            "reconciled: desired=${desired.size} scheduled=${scheduled.size} " +
                "set=${plan.toSet.size} cancel=${plan.toCancel.size}",
        )
    }

    /**
     * Inexact repeating alarm at the next 03:00 local, reset on every
     * reconcile. Idempotent: same PendingIntent replaces itself. Inexact on
     * purpose — the heartbeat only needs to happen roughly nightly.
     */
    internal fun scheduleDailyHeartbeat(app: Context) {
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = HeartbeatClock.nextAfter(System.currentTimeMillis(), ZoneId.systemDefault())
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            AlarmManager.INTERVAL_DAY,
            heartbeatPendingIntent(app),
        )
    }

    private fun heartbeatPendingIntent(app: Context): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            HEARTBEAT_REQUEST_CODE,
            Intent(app, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_DAILY_HEARTBEAT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Reminders lookup for many events in ONE round-trip per chunk of 800.
     *
     * WHY THIS DUPLICATES A QUERY LOCALLY (task constraint): the repository
     * exposes only `remindersFor(eventId)` — per-event, an N+1 over the whole
     * window — and calendar/ is outside this workstream's write scope, so the
     * batched join lives here instead of in calendar/. Channel filtering also
     * happens HERE because the pure planner must stay android-free:
     * METHOD_EMAIL / METHOD_SMS request delivery channels this app cannot
     * perform locally; honoring them as notifications would spam popups that
     * duplicate server-side mail/SMS. METHOD_DEFAULT, METHOD_ALERT and
     * METHOD_ALARM all mean "device notification".
     */
    private suspend fun reminderMinutesByEvent(
        app: Context,
        eventIds: List<Long>,
    ): Map<Long, List<Int>> = withContext(Dispatchers.IO) {
        if (eventIds.isEmpty()) return@withContext emptyMap()
        val resolver = app.contentResolver
        val projection = arrayOf(
            CalendarContract.Reminders.EVENT_ID,
            CalendarContract.Reminders.MINUTES,
            CalendarContract.Reminders.METHOD,
        )
        val out = HashMap<Long, MutableList<Int>>()
        eventIds.chunked(CHUNK).forEach { chunk ->
            val selection = "${CalendarContract.Reminders.EVENT_ID} IN (" +
                chunk.joinToString(",") { "?" } + ")"
            val args = chunk.map { it.toString() }.toTypedArray()
            try {
                resolver.query(
                    CalendarContract.Reminders.CONTENT_URI, projection, selection, args, null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(CalendarContract.Reminders.EVENT_ID)
                    val minCol = c.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)
                    val methodCol = c.getColumnIndexOrThrow(CalendarContract.Reminders.METHOD)
                    while (c.moveToNext()) {
                        if (!isNotifyingMethod(c.getInt(methodCol))) continue
                        out.getOrPut(c.getLong(idCol)) { mutableListOf() }.add(c.getInt(minCol))
                    }
                }
            } catch (e: Exception) {
                // One bad chunk must not sink the pass; a missing entry reads
                // as "no reminders", i.e. quiet.
                Log.w(TAG, "reminders chunk failed: ${e.message}")
            }
        }
        out
    }

    private fun isNotifyingMethod(method: Int): Boolean = when (method) {
        CalendarContract.Reminders.METHOD_DEFAULT,
        CalendarContract.Reminders.METHOD_ALERT,
        CalendarContract.Reminders.METHOD_ALARM,
        -> true

        else -> false
    }

    private fun CalendarInstance.toPlannerInstance(
        minutesByEvent: Map<Long, List<Int>>,
    ): PlannerInstance = PlannerInstance(
        eventId = eventId,
        startMillis = startMillis,
        allDay = allDay,
        declined = selfAttendeeStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
        reminderMinutes = minutesByEvent[eventId].orEmpty().sorted(),
    )
}
