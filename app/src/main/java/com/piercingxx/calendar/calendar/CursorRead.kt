package com.piercingxx.calendar.calendar

import android.database.Cursor

/**
 * Thin, shared cursor readers. Null-index and null-value safe so callers can
 * project fewer columns than they read.
 */
internal fun Cursor.idxOrNull(column: String): Int {
    val i = getColumnIndex(column)
    return if (i >= 0) i else -1
}

internal fun Cursor.longOr(column: String): Long? {
    val i = idxOrNull(column)
    return if (i < 0 || isNull(i)) null else getLong(i)
}

internal fun Cursor.intOr(column: String): Int? = longOr(column)?.toInt()

internal fun Cursor.stringOr(column: String): String? {
    val i = idxOrNull(column)
    return if (i < 0 || isNull(i)) null else getString(i)
}

internal fun Cursor.boolOr(column: String): Boolean? = intOr(column)?.let { it != 0 }
