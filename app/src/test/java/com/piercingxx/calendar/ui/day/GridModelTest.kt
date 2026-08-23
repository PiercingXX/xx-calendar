package com.piercingxx.calendar.ui.day

import com.piercingxx.calendar.core.TimeMath
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry of the §8.3 grid: 15-minute snapping, slot coercion, and the
 * column-packing that splits overlapping events side by side.
 */
class GridModelTest {

    private fun span(index: Int, start: Float, end: Float) = LayoutSpan(index, start, end)

    @Test
    fun `pack keeps disjoint spans in one lane`() {
        val slots = GridEventLayout.pack(
            listOf(span(0, 0f, 60f), span(1, 120f, 180f)),
        )
        assertEquals(1, slots[0].laneCount)
        assertEquals(0, slots[0].lane)
        assertEquals(1, slots[1].laneCount)
        assertEquals(0, slots[1].lane)
    }

    @Test
    fun `pack puts overlapping spans side by side`() {
        val slots = GridEventLayout.pack(
            listOf(span(0, 0f, 90f), span(1, 30f, 60f)),
        )
        assertEquals(2, slots[0].laneCount)
        assertEquals(2, slots[1].laneCount)
        assertTrue(setOf(slots[0].lane, slots[1].lane).containsAll(setOf(0, 1)))
    }

    @Test
    fun `pack reuses a freed lane inside a cluster`() {
        // A 09:00-11:00, B 09:30-10:00, C 10:00-10:30: three lanes at most,
        // and C can take B's lane once B ends exactly when C starts.
        val slots = GridEventLayout.pack(
            listOf(span(0, 540f, 660f), span(1, 570f, 600f), span(2, 600f, 630f)),
        )
        assertEquals(2, slots.maxOf { it.laneCount })
    }

    @Test
    fun `pack closes a cluster on touching edges`() {
        val slots = GridEventLayout.pack(
            listOf(span(0, 0f, 60f), span(1, 60f, 120f)),
        )
        assertEquals(1, slots[0].laneCount)
        assertEquals(1, slots[1].laneCount)
    }

    @Test
    fun `snapMinute lands on quarter hours`() {
        assertEquals(0, snapMinute(7f))
        assertEquals(15, snapMinute(8f))
        assertEquals(15, snapMinute(22f))
        assertEquals(30, snapMinute(37.4f))
        assertEquals(1440, snapMinute(1439f))
    }

    @Test
    fun `coerceSlot clamps and enforces minimum length`() {
        assertEquals(30 to 45, coerceSlot(30, 30))
        assertEquals(1425 to 1440, coerceSlot(1500, 1440))
        assertEquals(0 to 15, coerceSlot(-40, 5))
    }

    @Test
    fun `resize edges clamp against the fixed edge`() {
        assertEquals(0 to 120, coerceResizeTop(0, 120))
        assertEquals(105 to 120, coerceResizeTop(200, 120)) // cannot cross the bottom
        assertEquals(60 to 1440, coerceResizeBottom(60, 1500))
        assertEquals(60 to 75, coerceResizeBottom(60, 10))  // cannot cross the top
    }

    @Test
    fun `initial scroll opens near now on today else working hours`() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 8, 23)
        val noon = TimeMath.localDayStart(today, zone) + 12 * 3_600_000L
        val nearNow = initialScrollMinutes(listOf(today), noon, zone)
        assertEquals(10 * 60 + 30, nearNow)

        val otherDay = today.plusDays(3)
        assertEquals(7 * 60, initialScrollMinutes(listOf(otherDay), noon, zone))
    }
}
