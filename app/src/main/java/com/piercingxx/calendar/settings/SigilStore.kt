package com.piercingxx.calendar.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.piercingxx.calendar.core.CalendarKey
import com.piercingxx.calendar.core.SigilTier
import kotlinx.coroutines.flow.first

private val Context.sigilDataStore by preferencesDataStore(name = "sigils")

/**
 * The persisted calendar→sigil map (design §6.1) and nothing else. One
 * preference per calendar, named `calendar:<id>:<accountName>`, holding the
 * [SigilTier] ordinal. Load-on-open, save-after-assign — no flow plumbing.
 */
class SigilStore(private val context: Context) {

    suspend fun load(): Map<CalendarKey, SigilTier> {
        val prefs = context.sigilDataStore.data.first()
        val out = HashMap<CalendarKey, SigilTier>()
        for (key in prefs.asMap().keys) {
            val parsed = parseKeyName(key.name) ?: continue
            val ordinal = (prefs[key] as? String)?.toIntOrNull() ?: continue
            val tier = SigilTier.entries.getOrNull(ordinal) ?: continue
            out[parsed] = tier
        }
        return out
    }

    suspend fun save(map: Map<CalendarKey, SigilTier>) {
        context.sigilDataStore.edit { prefs ->
            prefs.clear()
            for ((key, tier) in map) {
                prefs[stringPreferencesKey(keyName(key))] = tier.ordinal.toString()
            }
        }
    }

    private fun keyName(key: CalendarKey): String = "calendar:${key.calendarId}:${key.accountName}"

    /** Account names may contain ':', so split only at the first separator. */
    private fun parseKeyName(name: String): CalendarKey? {
        if (!name.startsWith(PREFIX)) return null
        val rest = name.removePrefix(PREFIX)
        val sep = rest.indexOf(':')
        if (sep <= 0) return null
        val id = rest.substring(0, sep).toLongOrNull() ?: return null
        return CalendarKey(id, rest.substring(sep + 1))
    }

    private companion object {
        const val PREFIX = "calendar:"
    }
}
