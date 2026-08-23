package com.piercingxx.calendar.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.piercingxx.calendar.calendar.AutoAddedDetector
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** §8.6 VIEW — which Instances view opens first. D10: schedule answers "what is next". */
enum class DefaultView { SCHEDULE, DAY, WEEK, MONTH }

/** §8.6 VIEW — first column of the week grids. */
enum class StartDayOfWeek { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

/** §8.6 VIEW — row rhythm of the time grids. */
enum class Density { COMFORTABLE, COMPACT }

/** §8.6 APPEARANCE — deferred this wave; one theme ships (see SettingsScreen). */
enum class AppBackground { AMOLED_NIGHT }

/** §8.6 APPEARANCE — deferred this wave; one face ships. */
enum class AppFont { JETBRAINS_MONO }

/**
 * How far the auto-added filter (§4.5) reaches when it runs. Wraps
 * [AutoAddedDetector]'s open question (design §17) honestly:
 *
 *  - [OFF] — never hide anything, whatever [Settings.hideAutoAdded] says.
 *  - [CALENDAR] — stage-1 signals only: source-calendar identity
 *    (`@group.v.calendar.google.com`, holiday/birthday names).
 *  - [METADATA] — stages 1 + 2: the full detector including booking URLs and
 *    `CUSTOM_APP_PACKAGE`, whose reliability is the weakest link and the
 *    first thing WS12 tightens after on-device inspection.
 *
 * A consumer's effective rule is
 * `settings.hideAutoAdded && settings.autoAddedFilterMode != OFF`.
 * Detection fails closed either way ([AutoAddedDetector] hides only what it
 * is confident about).
 */
enum class AutoAddedFilterMode { OFF, METADATA, CALENDAR }

/** The all-day-notification anchor: fire at [hourOfDay]:00 local, [daysBefore] days ahead. */
data class AllDayNotification(val hourOfDay: Int = 18, val daysBefore: Int = 1)

/**
 * The sixteen survivors of teardown §3.10 (§8.6), plus the filter-fidelity
 * switch above. Every default is a **quiet default** (D12): nothing leaks,
 * nothing nags — notification preview, agenda, heads-up and declined events
 * are all off until asked for.
 */
data class Settings(
    val defaultView: DefaultView = DefaultView.SCHEDULE,
    val startDayOfWeek: StartDayOfWeek = StartDayOfWeek.MONDAY,
    val weekNumbers: Boolean = false,
    val showDeclined: Boolean = false,
    val dimPast: Boolean = true,
    val density: Density = Density.COMFORTABLE,
    val defaultDurationMin: Int = 30,
    val defaultNotificationMin: Int = 10,
    val allDayNotification: AllDayNotification = AllDayNotification(),
    val lockScreenTitle: Boolean = false,
    val dailyAgenda: Boolean = false,
    val headsUp: Boolean = false,
    val hideAutoAdded: Boolean = true,
    val background: AppBackground = AppBackground.AMOLED_NIGHT,
    val font: AppFont = AppFont.JETBRAINS_MONO,
    val textSizeScale: Float = 1.0f,
    val autoAddedFilterMode: AutoAddedFilterMode = AutoAddedFilterMode.METADATA,
)

/** Preference keys of the `settings` store; names are the backup/restore format (§9). */
private object Keys {
    const val DEFAULT_VIEW_NAME = "default_view"
    const val START_DAY_OF_WEEK_NAME = "start_day_of_week"
    const val WEEK_NUMBERS_NAME = "week_numbers"
    const val SHOW_DECLINED_NAME = "show_declined"
    const val DIM_PAST_NAME = "dim_past"
    const val DENSITY_NAME = "density"
    const val DEFAULT_DURATION_MIN_NAME = "default_duration_min"
    const val DEFAULT_NOTIFICATION_MIN_NAME = "default_notification_min"
    const val ALL_DAY_NOTIFY_HOUR_NAME = "all_day_notify_hour"
    const val ALL_DAY_NOTIFY_DAYS_BEFORE_NAME = "all_day_notify_days_before"
    const val LOCK_SCREEN_TITLE_NAME = "lock_screen_title"
    const val DAILY_AGENDA_NAME = "daily_agenda"
    const val HEADS_UP_NAME = "heads_up"
    const val HIDE_AUTO_ADDED_NAME = "hide_auto_added"
    const val BACKGROUND_NAME = "background"
    const val FONT_NAME = "font"
    const val TEXT_SIZE_SCALE_NAME = "text_size_scale"
    const val AUTO_ADDED_FILTER_MODE_NAME = "auto_added_filter_mode"

    val DEFAULT_VIEW = stringPreferencesKey(DEFAULT_VIEW_NAME)
    val START_DAY_OF_WEEK = stringPreferencesKey(START_DAY_OF_WEEK_NAME)
    val WEEK_NUMBERS = booleanPreferencesKey(WEEK_NUMBERS_NAME)
    val SHOW_DECLINED = booleanPreferencesKey(SHOW_DECLINED_NAME)
    val DIM_PAST = booleanPreferencesKey(DIM_PAST_NAME)
    val DENSITY = stringPreferencesKey(DENSITY_NAME)
    val DEFAULT_DURATION_MIN = intPreferencesKey(DEFAULT_DURATION_MIN_NAME)
    val DEFAULT_NOTIFICATION_MIN = intPreferencesKey(DEFAULT_NOTIFICATION_MIN_NAME)
    val ALL_DAY_NOTIFY_HOUR = intPreferencesKey(ALL_DAY_NOTIFY_HOUR_NAME)
    val ALL_DAY_NOTIFY_DAYS_BEFORE = intPreferencesKey(ALL_DAY_NOTIFY_DAYS_BEFORE_NAME)
    val LOCK_SCREEN_TITLE = booleanPreferencesKey(LOCK_SCREEN_TITLE_NAME)
    val DAILY_AGENDA = booleanPreferencesKey(DAILY_AGENDA_NAME)
    val HEADS_UP = booleanPreferencesKey(HEADS_UP_NAME)
    val HIDE_AUTO_ADDED = booleanPreferencesKey(HIDE_AUTO_ADDED_NAME)
    val BACKGROUND = stringPreferencesKey(BACKGROUND_NAME)
    val FONT = stringPreferencesKey(FONT_NAME)
    val TEXT_SIZE_SCALE = floatPreferencesKey(TEXT_SIZE_SCALE_NAME)
    val AUTO_ADDED_FILTER_MODE = stringPreferencesKey(AUTO_ADDED_FILTER_MODE_NAME)
}

/**
 * Persisted settings (D9: DataStore, not Room) — one preference per field in
 * the `settings` store. Enum values are stored by name and parsed back
 * defensively: an unknown stored name falls back to the default rather than
 * throwing, so a restored or hand-edited dump can never crash the app.
 *
 * Sigils deliberately live elsewhere ([SigilStore]); backup/restore (§9) is
 * a JSON dump of exactly these two stores and nothing else.
 */
class SettingsStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.settingsDataStore,
) {

    val settings: Flow<Settings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun setDefaultView(value: DefaultView) =
        write { it[Keys.DEFAULT_VIEW] = value.name }

    suspend fun setStartDayOfWeek(value: StartDayOfWeek) =
        write { it[Keys.START_DAY_OF_WEEK] = value.name }

    suspend fun setWeekNumbers(value: Boolean) =
        write { it[Keys.WEEK_NUMBERS] = value }

    suspend fun setShowDeclined(value: Boolean) =
        write { it[Keys.SHOW_DECLINED] = value }

    suspend fun setDimPast(value: Boolean) =
        write { it[Keys.DIM_PAST] = value }

    suspend fun setDensity(value: Density) =
        write { it[Keys.DENSITY] = value.name }

    suspend fun setDefaultDurationMin(minutes: Int) =
        write { it[Keys.DEFAULT_DURATION_MIN] = minutes }

    suspend fun setDefaultNotificationMin(minutes: Int) =
        write { it[Keys.DEFAULT_NOTIFICATION_MIN] = minutes }

    suspend fun setAllDayNotification(value: AllDayNotification) = write {
        it[Keys.ALL_DAY_NOTIFY_HOUR] = value.hourOfDay
        it[Keys.ALL_DAY_NOTIFY_DAYS_BEFORE] = value.daysBefore
    }

    suspend fun setLockScreenTitle(value: Boolean) =
        write { it[Keys.LOCK_SCREEN_TITLE] = value }

    suspend fun setDailyAgenda(value: Boolean) =
        write { it[Keys.DAILY_AGENDA] = value }

    suspend fun setHeadsUp(value: Boolean) =
        write { it[Keys.HEADS_UP] = value }

    suspend fun setHideAutoAdded(value: Boolean) =
        write { it[Keys.HIDE_AUTO_ADDED] = value }

    suspend fun setBackground(value: AppBackground) =
        write { it[Keys.BACKGROUND] = value.name }

    suspend fun setFont(value: AppFont) =
        write { it[Keys.FONT] = value.name }

    suspend fun setTextSizeScale(scale: Float) =
        write { it[Keys.TEXT_SIZE_SCALE] = scale }

    suspend fun setAutoAddedFilterMode(value: AutoAddedFilterMode) =
        write { it[Keys.AUTO_ADDED_FILTER_MODE] = value.name }

    private suspend fun write(transform: (MutablePreferences) -> Unit) {
        dataStore.edit(transform)
    }
}

private inline fun <reified T : Enum<T>> Preferences.enumOf(
    key: Preferences.Key<String>,
    fallback: T,
): T = this[key]?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: fallback

private fun Preferences.toSettings(): Settings {
    return Settings(
        defaultView = enumOf(Keys.DEFAULT_VIEW, DefaultView.SCHEDULE),
        startDayOfWeek = enumOf(Keys.START_DAY_OF_WEEK, StartDayOfWeek.MONDAY),
        weekNumbers = this[Keys.WEEK_NUMBERS] ?: false,
        showDeclined = this[Keys.SHOW_DECLINED] ?: false,
        dimPast = this[Keys.DIM_PAST] ?: true,
        density = enumOf(Keys.DENSITY, Density.COMFORTABLE),
        defaultDurationMin = this[Keys.DEFAULT_DURATION_MIN] ?: 30,
        defaultNotificationMin = this[Keys.DEFAULT_NOTIFICATION_MIN] ?: 10,
        allDayNotification = AllDayNotification(
            hourOfDay = this[Keys.ALL_DAY_NOTIFY_HOUR] ?: 18,
            daysBefore = this[Keys.ALL_DAY_NOTIFY_DAYS_BEFORE] ?: 1,
        ),
        lockScreenTitle = this[Keys.LOCK_SCREEN_TITLE] ?: false,
        dailyAgenda = this[Keys.DAILY_AGENDA] ?: false,
        headsUp = this[Keys.HEADS_UP] ?: false,
        hideAutoAdded = this[Keys.HIDE_AUTO_ADDED] ?: true,
        background = enumOf(Keys.BACKGROUND, AppBackground.AMOLED_NIGHT),
        font = enumOf(Keys.FONT, AppFont.JETBRAINS_MONO),
        textSizeScale = this[Keys.TEXT_SIZE_SCALE] ?: 1.0f,
        autoAddedFilterMode = enumOf(Keys.AUTO_ADDED_FILTER_MODE, AutoAddedFilterMode.METADATA),
    )
}
