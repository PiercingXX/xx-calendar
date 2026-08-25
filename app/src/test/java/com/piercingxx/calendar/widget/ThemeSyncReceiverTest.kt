package com.piercingxx.calendar.widget

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.calendar.ui.theme.SharedPreferencesThemeGroundStore
import com.piercingxx.calendar.ui.theme.ThemeGround
import com.piercingxx.calendar.ui.theme.ThemeGroundState
import com.piercingxx.calendar.ui.theme.ThemeGroundStore
import com.piercingxx.calendar.ui.theme.ThemePreset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The family theme-sync receiver: manifest wiring for the launcher's
 * `xx.launcher.THEME_CHANGED` broadcast, routing of the carried display name /
 * background int through [com.piercingxx.calendar.ui.theme.resolveGround] into
 * a [ThemeGroundStore] (via the injectable seam), and the default store path's
 * durability through real SharedPreferences plus the live [ThemeGroundState].
 */
@RunWith(RobolectricTestRunner::class)
class ThemeSyncReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** In-memory store so routing is asserted without touching Android prefs. */
    private class InMemoryStore : ThemeGroundStore {
        var saved: ThemeGround? = null
        override fun save(ground: ThemeGround) {
            saved = ground
        }
        override fun load(): ThemeGround? = saved
    }

    private fun receiver(store: ThemeGroundStore): ThemeSyncReceiver =
        ThemeSyncReceiver(storeFactory = { store })

    private fun themeIntent(name: String?, background: Int? = null): Intent =
        Intent(ThemeSyncReceiver.ACTION_THEME_CHANGED).apply {
            if (name != null) putExtra(ThemeSyncReceiver.EXTRA_THEME_NAME, name)
            if (background != null) putExtra(ThemeSyncReceiver.EXTRA_BACKGROUND, background)
        }

    @Before
    fun cleanSlate() {
        ThemeGroundState.ground.value = null
        app.getSharedPreferences(SharedPreferencesThemeGroundStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ---- wiring: the manifest declares the receiver for the family broadcast ----

    // Gradle unit tests run with the module directory (app/) as the working
    // directory; fall back to the repo-root-relative path for robustness.
    private val manifestText: String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    @Test
    fun `manifest declares the exported theme-sync receiver`() {
        assertTrue(manifestText.contains(".widget.ThemeSyncReceiver"))
    }

    @Test
    fun `manifest registers the receiver for the family theme-changed action`() {
        assertTrue(manifestText.contains(ThemeSyncReceiver.ACTION_THEME_CHANGED))
    }

    @Test
    fun `declared receiver name resolves to a class`() {
        Class.forName("com.piercingxx.calendar.widget.ThemeSyncReceiver")
    }

    // ---- routing through the injectable store seam ----

    @Test
    fun `a named preset broadcast persists its key and canonical background`() {
        val store = InMemoryStore()
        receiver(store).onReceive(app, themeIntent("Graphite", 0xFF131316.toInt()))
        assertEquals(ThemeGround("graphite", 0xFF131316), store.saved)
    }

    @Test
    fun `preset names match case-insensitively`() {
        val store = InMemoryStore()
        receiver(store).onReceive(app, themeIntent("ocean drift", 0xFF0F1C2E.toInt()))
        assertEquals(ThemeGround("ocean-drift", 0xFF0F1C2E), store.saved)
    }

    @Test
    fun `a custom broadcast persists the carried background int`() {
        val store = InMemoryStore()
        receiver(store).onReceive(app, themeIntent("Custom", 0xFF224466.toInt()))
        assertEquals(ThemeGround(ThemePreset.CUSTOM_KEY, 0xFF224466), store.saved)
    }

    @Test
    fun `a negative ARGB int round-trips to the unsigned color`() {
        val store = InMemoryStore()
        // 0xFF10261B as a JVM Int is negative; the store must see the color.
        receiver(store).onReceive(app, themeIntent("Custom", -15718885)) // 0xFF10261B
        assertEquals(ThemeGround(ThemePreset.CUSTOM_KEY, 0xFF10261B), store.saved)
    }

    @Test
    fun `custom without a background persists nothing`() {
        val store = InMemoryStore()
        receiver(store).onReceive(app, themeIntent("Custom"))
        assertNull(store.saved)
    }

    @Test
    fun `an unknown preset name persists nothing`() {
        val store = InMemoryStore()
        receiver(store).onReceive(app, themeIntent("Solarized", 0xFF224466.toInt()))
        assertNull(store.saved)
    }

    @Test
    fun `a missing theme name persists nothing`() {
        val store = InMemoryStore()
        receiver(store).onReceive(app, themeIntent(null, 0xFF224466.toInt()))
        assertNull(store.saved)
    }

    @Test
    fun `a foreign action is ignored`() {
        val store = InMemoryStore()
        val foreign = Intent("some.other.ACTION")
            .putExtra(ThemeSyncReceiver.EXTRA_THEME_NAME, "Graphite")
            .putExtra(ThemeSyncReceiver.EXTRA_BACKGROUND, 0xFF131316.toInt())
        receiver(store).onReceive(app, foreign)
        assertNull(store.saved)
    }

    // ---- durability: the default path persists and reaches a fresh reader ----

    @Test
    fun `the default store path persists into prefs a fresh load reads back`() {
        ThemeSyncReceiver().onReceive(app, themeIntent("Burgundy", 0xFF2A1018.toInt()))

        val fresh = SharedPreferencesThemeGroundStore(
            app.getSharedPreferences(
                SharedPreferencesThemeGroundStore.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )
        assertEquals(ThemeGround("burgundy", 0xFF2A1018), fresh.load())
        // The live Compose state updated too (foregrounded-app path).
        assertEquals(ThemeGround("burgundy", 0xFF2A1018), ThemeGroundState.ground.value)
    }

    @Test
    fun `refresh rehydrates the state from a cold process`() {
        ThemeSyncReceiver().onReceive(app, themeIntent("Custom", 0xFF224466.toInt()))
        ThemeGroundState.ground.value = null // simulate process death

        ThemeGroundState.refresh(app)

        assertEquals(
            ThemeGround(ThemePreset.CUSTOM_KEY, 0xFF224466),
            ThemeGroundState.ground.value,
        )
    }

    @Test
    fun `an unknown persisted key loads as no ground`() {
        app.getSharedPreferences(SharedPreferencesThemeGroundStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(SharedPreferencesThemeGroundStore.KEY_PRESET, "no-such-key").commit()
        ThemeGroundState.refresh(app)
        assertNull(ThemeGroundState.ground.value)
    }

    @Test
    fun `a persisted preset key reloads its canonical background`() {
        // Even a corrupted stored int cannot skew a named preset's ground.
        app.getSharedPreferences(SharedPreferencesThemeGroundStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SharedPreferencesThemeGroundStore.KEY_PRESET, "mist")
            .putInt(SharedPreferencesThemeGroundStore.KEY_BACKGROUND, 0x12345678)
            .commit()
        ThemeGroundState.refresh(app)
        assertEquals(ThemeGround("mist", 0xFFE6EDF5), ThemeGroundState.ground.value)
    }
}
