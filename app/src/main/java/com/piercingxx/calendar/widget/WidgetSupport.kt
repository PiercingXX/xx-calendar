package com.piercingxx.calendar.widget

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.CalendarContract
import com.piercingxx.calendar.calendar.CalendarRepository
import com.piercingxx.calendar.calendar.CalendarSummary
import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.SigilAssigner
import com.piercingxx.calendar.core.SigilTier
import com.piercingxx.calendar.settings.Settings as AppSettings
import com.piercingxx.calendar.settings.SettingsStore
import com.piercingxx.calendar.settings.SigilStore
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal fun SigilTier.rampArgb(): Color = WidgetTokens.rampArgb(this)

/**
 * PiercingXX tokens (design §7) as widget-local constants. Glance text styles
 * cannot resolve the compose theme's CompositionLocals, so the ramp is restated
 * here from pxx_colors.xml — same stops, one source of truth in resources.
 */
internal object WidgetTokens {
    val Ink = Color(0xFF000000)
    val EmphasisBg = Color(0xFFFFFFFF)
    val EmphasisFg = Color(0xFF000000)
    val Shade = Color(0x40FFFFFF)
    val Muted = Color(0x80FFFFFF)
    val Strong = Color(0xCCFFFFFF)
    val Text90 = Color(0xE6FFFFFF)

    /** §7.1: each tier renders its glyph at its named stop on the white ramp. */
    fun rampArgb(tier: SigilTier): Color = when (tier.rampName) {
        "text" -> Text90
        "strong" -> Strong
        "muted" -> Muted
        else -> Shade
    }
}

/**
 * The §6.1 sigil pass, exactly as ScheduleScreen/MonthScreen run it:
 * persisted map, allocate unseen calendars, persist what is new, then a
 * per-calendar-id lookup defaulting to TIER_6. [calendars] may be passed in
 * when the caller already queried them.
 */
internal suspend fun sigilTiersByCalendarId(
    repository: CalendarRepository,
    sigilStore: SigilStore,
    calendars: List<CalendarSummary>? = null,
): Map<Long, SigilTier> {
    val list = calendars ?: repository.calendars()
    val byId = list.associateBy { it.id }
    val existing = sigilStore.load()
    val assignment = SigilAssigner.assign(
        existing,
        list.map { CalendarKey(it.id, it.accountName ?: "") },
    )
    if (assignment.newlyAssigned.isNotEmpty()) {
        sigilStore.save(assignment.assignments)
    }
    return byId.mapValues { (_, summary) ->
        assignment.assignments[CalendarKey(summary.id, summary.accountName ?: "")] ?: SigilTier.TIER_6
    }
}

/**
 * §8.6 settings for a widget render pass (both widgets are suspended while
 * composing). Defaults on any read failure so a broken store degrades to the
 * quiet defaults instead of blanking the widget.
 */
internal suspend fun currentWidgetSettings(context: Context): AppSettings =
    runCatching { SettingsStore(context.applicationContext).settings.first() }
        .getOrDefault(AppSettings())

/**
 * Process-lifetime provider watch for both widgets (WS11 update strategy):
 *
 *  - registered once per process from [MonthWidget]/[ScheduleWidget]
 *    `onEnabled`/`onUpdate` (the receiver context), unregistered from
 *    `onDisabled`;
 *  - any provider change under Instances/Events collapses into one debounced
 *    `updateAll` across both widgets — including writes this app itself makes,
 *    so no extra app-start hook is needed (an Application.onCreate trigger
 *    would be redundant; noted as acceptable per WS11);
 *  - the scope lives for the process on purpose: widgets have no lifecycle of
 *    their own beyond these callbacks.
 *
 * Invariant (S1): the ContentObserver is attached exactly while at least one
 * provider class reports a live instance. `onDisabled` fires when the last
 * instance of *that* provider type is removed, not the last widget overall —
 * removing the Schedule widget must not kill observation for a surviving
 * Month widget, so the observer detaches only when every provider class has
 * dropped out of [liveProviders].
 */
internal object WidgetRefresher {

    private val lock = Any()

    /** Provider classes with at least one pinned widget in this process. */
    private val liveProviders = mutableSetOf<Class<out GlanceAppWidgetReceiver>>()
    private var registered = false
    private var appContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = AtomicBoolean(false)

    // Null handler: onChange runs inline on the notifying binder thread;
    // requestRefresh is thread-safe.
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            requestRefresh()
        }
    }

    fun register(context: Context, provider: Class<out GlanceAppWidgetReceiver>) {
        val app = context.applicationContext
        synchronized(lock) {
            liveProviders.add(provider)
            if (registered) return
            appContext = app
            app.contentResolver.registerContentObserver(
                CalendarContract.Instances.CONTENT_URI, true, observer,
            )
            app.contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI, true, observer,
            )
            registered = true
        }
        requestRefresh()
    }

    fun unregister(context: Context, provider: Class<out GlanceAppWidgetReceiver>) {
        synchronized(lock) {
            // A process spawned only to deliver onDisabled has no prior
            // register; nothing to detach in that case either.
            if (!liveProviders.remove(provider)) return
            if (liveProviders.isNotEmpty()) return
            if (!registered) return
            context.applicationContext.contentResolver.unregisterContentObserver(observer)
            registered = false
            appContext = null
        }
    }

    /** Test hook: drop all state so a test starts from an empty process. */
    internal fun resetForTests() {
        synchronized(lock) {
            liveProviders.clear()
            registered = false
            appContext = null
        }
    }

    /** Bursts collapse into a single refresh; only latest state matters (D1). */
    private fun requestRefresh() {
        val app = appContext ?: return
        if (!pending.compareAndSet(false, true)) return
        scope.launch {
            delay(DEBOUNCE_MILLIS)
            pending.set(false)
            runCatching {
                // No updateAll convenience in glance 1.1.0 — enumerate ids.
                val manager = GlanceAppWidgetManager(app)
                val month = MonthWidgetContent()
                manager.getGlanceIds(MonthWidgetContent::class.java)
                    .forEach { id -> month.update(app, id) }
                val schedule = ScheduleWidgetContent()
                manager.getGlanceIds(ScheduleWidgetContent::class.java)
                    .forEach { id -> schedule.update(app, id) }
            } // A dead or revoked-provider environment must never crash the host.
        }
    }

    private const val DEBOUNCE_MILLIS = 250L
}
