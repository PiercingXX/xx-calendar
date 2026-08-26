package com.piercingxx.calendar.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Design §4.4: with no sync adapter installed, a fresh install must be
 * immediately usable. If no writable calendar exists, create exactly one
 * local calendar (`ACCOUNT_TYPE_LOCAL`).
 *
 * The insert uses the documented local-calendar form: `CALLER_IS_SYNCADAPTER`
 * = true with this app's `ACCOUNT_NAME`/`ACCOUNT_TYPE=LOCAL` as URI query
 * parameters (see the CalendarContract guide's "sync adapter" insert example,
 * which CalendarProvider2 requires here — a NORMAL-client insert carrying
 * `ACCOUNT_NAME`/`ACCOUNT_TYPE`/`OWNER_ACCOUNT`/`CALENDAR_ACCESS_LEVEL` hits
 * `verifyNoSyncColumns` and is rejected with "Only sync adapters may write to
 * …"). This is the narrow local-account exception the contract documents for
 * bootstrap, not general sync-adapter impersonation: no other write in the app
 * ever goes out as a sync adapter, so events created here stay ordinary dirty
 * rows from the provider's point of view and there is nothing to sync anyway.
 *
 * Idempotent by the existence check; concurrent first runs could in principle
 * race into two calendars (single-process app, single call site — accepted).
 */
object LocalCalendarBootstrap {

    const val LOCAL_ACCOUNT_NAME = "Local"
    const val LOCAL_DISPLAY_NAME = "Calendar"

    /** Brand ink — identity is carried by sigils, never hue (D6). */
    const val LOCAL_COLOR = 0xFF000000.toInt()

    suspend fun ensureWritableCalendar(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Long? = withContext(ioDispatcher) {
        val repository = CalendarRepository(context.contentResolver, ioDispatcher)
        repository.calendars().firstOrNull { it.isWritable }?.let { return@withContext it.id }

        // Documented local-calendar insert: as a sync adapter on the URI, with
        // the local account as query parameters (see class KDoc).
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, LOCAL_ACCOUNT_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, LOCAL_DISPLAY_NAME)
            put(Calendars.CALENDAR_COLOR, LOCAL_COLOR)
            put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
        }
        val inserted = context.contentResolver.insert(uri, values) ?: return@withContext null
        ContentUris.parseId(inserted)
    }
}
