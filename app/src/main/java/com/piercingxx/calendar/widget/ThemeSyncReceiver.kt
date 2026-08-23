package com.piercingxx.calendar.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.piercingxx.calendar.settings.AppBackground
import com.piercingxx.calendar.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives XX-Launcher's `com.piercingxx.launcher.THEME_CHANGED` broadcast, as
 * TxxT implements it (design §12).
 *
 * Contract, as closely as a broadcast allows: an optional `background` String
 * extra names the desired background preset. When it matches a known
 * [AppBackground] value the choice is persisted to [SettingsStore]; anything
 * else — missing action, missing extra, unknown name, null anything — is
 * ignored silently. No network is touched. The receiver stays exported because
 * the sender is another app, not the system (manifest §12), but it is guarded
 * by the signature-level `com.piercingxx.calendar.permission.THEME_SYNC`
 * permission: only apps signed with the PiercingXX key that also hold the
 * permission can deliver here — anything else has its broadcast silently
 * dropped by the platform before onReceive runs.
 */
class ThemeSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_THEME_CHANGED) return
        val name = intent.getStringExtra(EXTRA_BACKGROUND) ?: return
        val value = AppBackground.entries.firstOrNull { it.name == name } ?: return
        val app = context?.applicationContext ?: return

        // DataStore writes are async disk IO; goAsync keeps the process alive
        // until the write lands. A failed write (IOException from a broken
        // store, say) must not crash the process from inside a broadcast.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { SettingsStore(app).setBackground(value) }
                    .onFailure { Log.w(TAG, "background persist failed: ${it.message}") }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ThemeSyncReceiver"
        const val ACTION_THEME_CHANGED = "com.piercingxx.launcher.THEME_CHANGED"
        const val EXTRA_BACKGROUND = "background"
    }
}
