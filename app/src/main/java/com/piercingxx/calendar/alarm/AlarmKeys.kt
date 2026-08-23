package com.piercingxx.calendar.alarm

/**
 * Stable identity + wire format for [AlarmKey] outside the planner.
 *
 * - [stableRequestCode] derives AlarmManager request codes and notification
 *   ids from the key with plain 31-based arithmetic, which is guaranteed
 *   stable across processes, reboots and platform versions (unlike, say,
 *   String.hashCode mixing — also stable, but spelled out here because a
 *   colliding requestCode silently replaces another alarm's PendingIntent).
 *   Collisions remain possible in principle; at the few-dozen alarms the
 *   48h window produces the probability is negligible and the worst case is
 *   one replaced/cancelled reminder.
 * - [encode]/[decode] are the registry's persistence format. Deliberately
 *   not JSON: three longs need no dependency, and org.json is unavailable in
 *   plain-JVM unit tests.
 */
object AlarmKeys {

    private const val SEP = ":"

    fun stableRequestCode(key: AlarmKey): Int {
        var h = key.eventId.hashCode()
        h = h * 31 + key.instanceStartMillis.hashCode()
        h = h * 31 + key.triggerAtMillis.hashCode()
        return h
    }

    fun encode(key: AlarmKey): String =
        key.eventId.toString() + SEP +
            key.instanceStartMillis.toString() + SEP +
            key.triggerAtMillis.toString()

    /** Returns null for any malformed line; the registry drops bad rows silently. */
    fun decode(line: String): AlarmKey? {
        val parts = line.split(SEP)
        if (parts.size != 3) return null
        val eventId = parts[0].toLongOrNull() ?: return null
        val start = parts[1].toLongOrNull() ?: return null
        val trigger = parts[2].toLongOrNull() ?: return null
        return AlarmKey(eventId, start, trigger)
    }

    fun encodeAll(keys: Set<AlarmKey>): String =
        keys.map(::encode).sorted().joinToString("\n")

    fun decodeAll(blob: String): Set<AlarmKey> =
        blob.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull(::decode)
            .toSortedSet()
}
