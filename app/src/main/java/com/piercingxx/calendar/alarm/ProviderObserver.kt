package com.piercingxx.calendar.alarm

import android.content.Context
import android.util.Log
import com.piercingxx.calendar.calendar.CalendarRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Process-wide ContentObserver on the provider (design §4.3: "on provider
 * change"), debounced 2s — bursts of notifications collapse into one
 * reconcile, and only the latest state matters.
 *
 * HONEST LIFECYCLE (§4.3's whole point): this observer lives exactly as long
 * as the process. Process death stops it until the next boot / broadcast /
 * heartbeat re-arms something. That is fine by construction — a missed
 * observer notification is a missed *signal*, not a missed reminder; the
 * reconciler converges on the next trigger. The observer exists to make
 * convergence quick for edits made while the process is alive, not to be the
 * reliability mechanism itself.
 */
internal object ProviderObserver {

    private const val TAG = "ProviderObserver"

    const val DEBOUNCE_MILLIS = 2_000L

    private var scope: CoroutineScope? = null

    @Volatile
    private var started = false

    /** Idempotent. Registers on the Instances + Events trees via the repository. */
    fun start(appContext: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val app = appContext.applicationContext
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            newScope.launch {
                try {
                    CalendarRepository(app.contentResolver)
                        .changes()
                        .debounced(DEBOUNCE_MILLIS) { ReminderReconciler.reconcile(app) }
                } catch (e: CancellationException) {
                    // collectLatest cancels in-flight passes and stopForTests
                    // cancels the scope: normal structured shutdown, never an
                    // error — rethrow so cancellation stays cancellable.
                    throw e
                } catch (e: Exception) {
                    // Registration can fail on a broken/absent provider; the
                    // other trigger rows keep reminders converging.
                    Log.w(TAG, "observer stopped: ${e.message}")
                }
            }
            scope = newScope
            started = true
        }
    }

    /** Test hook: stop collecting and allow re-start. */
    internal fun stopForTests() {
        synchronized(this) {
            scope?.cancel()
            scope = null
            started = false
        }
    }

    /**
     * collectLatest + delay == debounce with latest-wins semantics, without
     * the @FlowPreview annotation tax of `kotlinx.coroutines.flow.debounce`.
     */
    private suspend fun Flow<Unit>.debounced(
        quietMillis: Long,
        action: suspend () -> Unit,
    ) {
        collectLatest {
            delay(quietMillis)
            action()
        }
    }
}
