package com.piercingxx.calendar.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * [ThemeGroundStore] over SharedPreferences: the preset key plus the resolved
 * background ARGB int, exactly what the launcher broadcast carries. A known
 * preset key reloads through the preset table (canonical backgrounds survive
 * even a hand-edited int); only [ThemePreset.CUSTOM_KEY] trusts the stored
 * int. An unknown key loads as null so a bad write can never crash the theme.
 */
class SharedPreferencesThemeGroundStore(
    private val prefs: SharedPreferences,
    /** Called after a successful save; the singleton hooks live UI updates here. */
    private val onSaved: (ThemeGround) -> Unit = {},
) : ThemeGroundStore {

    override fun save(ground: ThemeGround) {
        prefs.edit()
            .putString(KEY_PRESET, ground.presetKey)
            .putInt(KEY_BACKGROUND, ground.background.toInt())
            .apply()
        onSaved(ground)
    }

    override fun load(): ThemeGround? {
        val key = prefs.getString(KEY_PRESET, null) ?: return null
        val preset = ThemePreset.fromKey(key)
        if (preset != null) return ThemeGround(preset.key, preset.background)
        if (key == ThemePreset.CUSTOM_KEY && prefs.contains(KEY_BACKGROUND)) {
            val argb = prefs.getInt(KEY_BACKGROUND, 0).toLong() and 0xFFFFFFFFL
            return ThemeGround(key, argb)
        }
        return null
    }

    companion object {
        const val PREFS_NAME = "theme_sync"
        const val KEY_PRESET = "preset_key"
        const val KEY_BACKGROUND = "background_argb"
    }
}

/**
 * Process-wide holder of the synced launcher ground. [CalendarTheme] reads
 * [ground] so a change recomposes every themed tree; [ThemeSyncReceiver]'s
 * default store updates it live when a broadcast lands while the process is
 * up, and each activity's `onResume` calls [refresh] to pick up broadcasts
 * that landed while the process was dead or backgrounded. Null means "no
 * synced ground" — the shipped AMOLED default path.
 */
object ThemeGroundState {

    /** The synced ground, or null for the shipped AMOLED default. */
    val ground: MutableState<ThemeGround?> = mutableStateOf(null)

    /** Re-read the persisted ground (each activity calls this in onResume). */
    fun refresh(context: Context) {
        ground.value = SharedPreferencesThemeGroundStore(prefs(context)).load()
    }

    /** The store the receiver persists into; saving also updates [ground] live. */
    fun storeFor(context: Context): ThemeGroundStore =
        SharedPreferencesThemeGroundStore(prefs(context)) { ground.value = it }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            SharedPreferencesThemeGroundStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
}
