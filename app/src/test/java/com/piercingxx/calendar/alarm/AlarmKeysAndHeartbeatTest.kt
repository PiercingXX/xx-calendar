package com.piercingxx.calendar.alarm

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codec + stable-hash + heartbeat math: the pure glue around the planner. */
class AlarmKeysAndHeartbeatTest {

    // ---- AlarmKeys codec round-trip

    @Test
    fun `codec round-trips keys and preserves order`() {
        val keys = setOf(
            AlarmKey(1L, 100L, 50L),
            AlarmKey(2L, -5L, 0L),
            AlarmKey(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        )

        val decoded = AlarmKeys.decodeAll(AlarmKeys.encodeAll(keys))

        assertEquals(keys.sorted(), decoded.toList())
    }

    @Test
    fun `codec drops malformed lines instead of throwing`() {
        val blob = "1:2:3\n\nnot-a-key\n4:5\n6:7:8:9\n7:8:9"

        assertEquals(setOf(AlarmKey(1, 2, 3), AlarmKey(7, 8, 9)), AlarmKeys.decodeAll(blob))
        assertNull(AlarmKeys.decode("x:y:z"))
    }

    @Test
    fun `stable request code is deterministic and spread across distinct keys`() {
        val a = AlarmKey(1L, 100L, 50L)
        assertEquals(AlarmKeys.stableRequestCode(a), AlarmKeys.stableRequestCode(a))

        val codes = mutableSetOf<Int>()
        for (eventId in 1L..200L) {
            codes += AlarmKeys.stableRequestCode(AlarmKey(eventId, eventId * 1000, eventId))
        }
        assertEquals(200, codes.size)
    }

    // ---- HeartbeatClock (asserted in wall-clock terms, not epoch arithmetic)

    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun local(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `heartbeat before 0300 lands same day at 0300`() {
        // 01:00 Berlin on Aug 20.
        val now = local(2026, 8, 20, 1, 0)

        assertEquals(local(2026, 8, 20, 3, 0), HeartbeatClock.nextAfter(now, zone))
    }

    @Test
    fun `heartbeat after 0300 rolls to tomorrow 0300`() {
        // 16:00 CEST on Aug 20 == past that day's 03:00.
        val now = at("2026-08-20T14:00:00Z")

        assertEquals(local(2026, 8, 21, 3, 0), HeartbeatClock.nextAfter(now, zone))
    }

    @Test
    fun `heartbeat exactly at 0300 rolls to next day - strictly after now`() {
        val now = local(2026, 8, 20, 3, 0)

        assertEquals(local(2026, 8, 21, 3, 0), HeartbeatClock.nextAfter(now, zone))
        assertTrue(HeartbeatClock.nextAfter(now, zone) > now)
    }
}
