package com.piercingxx.calendar.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Design §4.4: with no sync adapter installed, a fresh install must be
 * immediately usable. If no writable calendar exists, create exactly one
 * local calendar (`ACCOUNT_TYPE_LOCAL`) via a NORMAL-CLIENT insert — no
 * `CALLER_IS_SYNCADAPTER`. The provider permits normal clients to insert
 * local-account calendars, which is the entire mechanism here.
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

        // Normal-client insert of a local calendar — allowed by the provider
        // for ACCOUNT_TYPE_LOCAL, which is why this works without being a sync
        // adapter.
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
        val uri = context.contentResolver.insert(Calendars.CONTENT_URI, values)
            ?: return@withContext null
        ContentUris.parseId(uri)
    }
}
