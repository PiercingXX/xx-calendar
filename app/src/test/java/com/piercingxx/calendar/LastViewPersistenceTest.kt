package com.piercingxx.calendar

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.calendar.settings.DefaultView
import com.piercingxx.calendar.settings.SettingsStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Last-view persistence: the top-bar switcher writes the switched-to view
 * through [SettingsStore.setDefaultView], so §8.6 `default view` doubles as
 * "the view the user was last in" and the next launch reopens there ("if I
 * am in month view, it should open that way"). The behavior rests on two
 * seams, both pinned here without a device:
 *
 *  1. the switcher's menu ([VIEWS]) stays in lockstep with [DefaultView] —
 *     a view that exists but cannot be switched to could never be resumed;
 *  2. the view written on a switch is exactly what a fresh [SettingsStore]
 *     (a relaunch) reads back and maps to a launch route via [routeFor].
 *
 * Robolectric, like SettingsStoreTest, because DataStore needs a real
 * Context for its file; the store logic itself is plain Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
class LastViewPersistenceTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "last-view-test-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Fresh file per test: no singleton-delegate leakage between tests.
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = SettingsStore(context, dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `switcher menu offers every view exactly once`() {
        // Lockstep guard: adding a DefaultView entry without a switcher row
        // (or duplicating one) fails here, not on a device.
        assertEquals(DefaultView.entries.toList(), VIEWS.map { it.first })
    }

    @Test
    fun `every view maps to its own launch route`() {
        val routes = DefaultView.entries.map { routeFor(it) }
        // Distinct routes, or two views would collapse into one at launch.
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `the switched-to view is the view a relaunch opens`() = runTest {
        for (view in DefaultView.entries) {
            // The switcher's write path...
            store.setDefaultView(view)
            // ...and the next launch: a fresh store over the same file, read
            // exactly the way AppShell picks its start destination.
            val relaunch = SettingsStore(
                ApplicationProvider.getApplicationContext(),
                dataStore,
            )
            val reopened = relaunch.current().defaultView
            assertEquals(view, reopened)
            assertEquals(routeFor(view), routeFor(reopened))
        }
    }
}
