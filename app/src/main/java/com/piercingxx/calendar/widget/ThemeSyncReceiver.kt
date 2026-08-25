package com.piercingxx.calendar.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.piercingxx.calendar.ui.theme.ThemeGroundState
import com.piercingxx.calendar.ui.theme.ThemeGroundStore
import com.piercingxx.calendar.ui.theme.resolveGround

/**
 * Receives the xx-launcher's family-wide theme broadcast
 * (`xx.launcher.THEME_CHANGED`, explicitly targeted at this package — required
 * since Android O for manifest receivers) and persists the carried ground.
 *
 * Contract (identical across the family, as TxxT implements it): the
 * THEME_NAME extra carries the preset display name ("AMOLED Night" …
 * "Custom", matched case-insensitively) and the BACKGROUND extra the resolved
 * background ARGB int (present even for Custom). A known name resolves through
 * the canonical preset table; "Custom" takes the carried background; anything
 * malformed — wrong action, missing/unknown name, Custom without a background
 * — is ignored silently. The result persists via [ThemeGroundState.storeFor],
 * which also updates the live Compose state so a foregrounded app re-themes
 * immediately; a dead process picks it up on the next activity onResume.
 *
 * Exported without a permission guard, matching TxxT and the launcher's
 * contract (the launcher holds no calendar-defined permission). The worst a
 * spoofed broadcast can do is switch the ground to another valid preset — no
 * data moves, no network exists (no INTERNET permission in this manifest).
 *
 * The action/extra keys and the store factory are injectable so a JVM unit
 * test can drive [onReceive] via seams without mocking SharedPreferences.
 */
class ThemeSyncReceiver(
    /** Action to match; injectable for tests. */
    private val action: String = ACTION_THEME_CHANGED,
    /** Extracts the display name; defaults to the THEME_NAME extra. */
    private val extractThemeName: (Intent) -> String? = { intent ->
        intent.getStringExtra(EXTRA_THEME_NAME)
    },
    /** Extracts the resolved background ARGB, null when the extra is absent. */
    private val extractBackground: (Intent) -> Long? = { intent ->
        if (intent.hasExtra(EXTRA_BACKGROUND)) {
            intent.getIntExtra(EXTRA_BACKGROUND, 0).toLong() and 0xFFFFFFFFL
        } else {
            null
        }
    },
    /** Builds the store the resolved ground persists into; injectable seam. */
    private val storeFactory: (Context) -> ThemeGroundStore = { context ->
        ThemeGroundState.storeFor(context)
    },
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != action) return
        val ground = resolveGround(extractThemeName(intent), extractBackground(intent))
            ?: return
        storeFactory(context).save(ground)
    }

    companion object {
        /** The xx-launcher's theme-change broadcast action. */
        const val ACTION_THEME_CHANGED = "xx.launcher.THEME_CHANGED"

        /** String extra: the active preset's display name. */
        const val EXTRA_THEME_NAME = "xx.launcher.extra.THEME_NAME"

        /** Int extra: the resolved background ARGB (present even for Custom). */
        const val EXTRA_BACKGROUND = "xx.launcher.extra.BACKGROUND"
    }
}
