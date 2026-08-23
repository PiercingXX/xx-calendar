package com.piercingxx.calendar.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WS9: defaults are the quiet defaults (design D12, §8.6) and every setter
 * round-trips through DataStore.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "settings-test-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Fresh file per test: no singleton-delegate leakage between tests.
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = SettingsStore(context, dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------ defaults

    @Test
    fun `fresh store returns the sixteen quiet defaults`() = runTest {
        val s = store.current()

        assertEquals(DefaultView.SCHEDULE, s.defaultView)
        assertEquals(StartDayOfWeek.MONDAY, s.startDayOfWeek)
        assertFalse(s.weekNumbers)
        assertFalse(s.showDeclined)
        assertTrue(s.dimPast)
        assertEquals(Density.COMFORTABLE, s.density)
        assertEquals(30, s.defaultDurationMin)
        assertEquals(10, s.defaultNotificationMin)
        assertEquals(AllDayNotification(hourOfDay = 18, daysBefore = 1), s.allDayNotification)
        assertFalse(s.lockScreenTitle)
        assertFalse(s.dailyAgenda)
        assertFalse(s.headsUp)
        assertTrue(s.hideAutoAdded)
        assertEquals(AppBackground.AMOLED_NIGHT, s.background)
        assertEquals(AppFont.JETBRAINS_MONO, s.font)
        assertEquals(1.0f, s.textSizeScale)

        // The seventeenth, unwrapped honesty switch (§4.5 / §17).
        assertEquals(AutoAddedFilterMode.METADATA, s.autoAddedFilterMode)
    }

    @Test
    fun `quiet-defaults posture holds - nothing leaks by default`() = runTest {
        val s = store.current()
        // D12: privacy leaks are opt-in; §4.5: junk hidden, real events shown.
        assertFalse("lock-screen title preview off", s.lockScreenTitle)
        assertFalse("daily agenda off", s.dailyAgenda)
        assertFalse("heads-up off", s.headsUp)
        assertFalse("declined events hidden", s.showDeclined)
        assertTrue("Gmail bookings hidden at render", s.hideAutoAdded)
        assertTrue("dim past on", s.dimPast)
    }

    // ---------------------------------------------------------- round trip

    @Test
    fun `every setter round-trips`() = runTest {
        store.setDefaultView(DefaultView.MONTH)
        store.setStartDayOfWeek(StartDayOfWeek.SUNDAY)
        store.setWeekNumbers(true)
        store.setShowDeclined(true)
        store.setDimPast(false)
        store.setDensity(Density.COMPACT)
        store.setDefaultDurationMin(90)
        store.setDefaultNotificationMin(60)
        store.setAllDayNotification(AllDayNotification(hourOfDay = 8, daysBefore = 2))
        store.setLockScreenTitle(true)
        store.setDailyAgenda(true)
        store.setHeadsUp(true)
        store.setHideAutoAdded(false)
        store.setBackground(AppBackground.AMOLED_NIGHT)
        store.setFont(AppFont.JETBRAINS_MONO)
        store.setTextSizeScale(1.25f)
        store.setAutoAddedFilterMode(AutoAddedFilterMode.CALENDAR)

        val s = store.settings.first()

        assertEquals(DefaultView.MONTH, s.defaultView)
        assertEquals(StartDayOfWeek.SUNDAY, s.startDayOfWeek)
        assertTrue(s.weekNumbers)
        assertTrue(s.showDeclined)
        assertFalse(s.dimPast)
        assertEquals(Density.COMPACT, s.density)
        assertEquals(90, s.defaultDurationMin)
        assertEquals(60, s.defaultNotificationMin)
        assertEquals(AllDayNotification(hourOfDay = 8, daysBefore = 2), s.allDayNotification)
        assertTrue(s.lockScreenTitle)
        assertTrue(s.dailyAgenda)
        assertTrue(s.headsUp)
        assertFalse(s.hideAutoAdded)
        assertEquals(AppFont.JETBRAINS_MONO, s.font)
        assertEquals(1.25f, s.textSizeScale)
        assertEquals(AutoAddedFilterMode.CALENDAR, s.autoAddedFilterMode)
    }

    @Test
    fun `flow re-emits after each write`() = runTest {
        assertFalse(store.settings.first().dailyAgenda)
        store.setDailyAgenda(true)
        assertTrue(store.settings.first().dailyAgenda)
        store.setDailyAgenda(false)
        assertFalse(store.settings.first().dailyAgenda)
    }

    @Test
    fun `second instance over the same store sees persisted values`() = runTest {
        store.setDefaultDurationMin(45)
        val other = SettingsStore(
            ApplicationProvider.getApplicationContext(),
            dataStore,
        )
        assertEquals(45, other.current().defaultDurationMin)
    }

    // ------------------------------------------------------------ defensive

    @Test
    fun `unknown stored enum names fall back to defaults instead of crashing`() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("default_view")] = "TELEPORT"
            prefs[stringPreferencesKey("density")] = "cozy"
            prefs[stringPreferencesKey("auto_added_filter_mode")] = "MAYBE"
        }

        val s = store.current()

        assertEquals(DefaultView.SCHEDULE, s.defaultView)
        assertEquals(Density.COMFORTABLE, s.density)
        assertEquals(AutoAddedFilterMode.METADATA, s.autoAddedFilterMode)
    }
}
