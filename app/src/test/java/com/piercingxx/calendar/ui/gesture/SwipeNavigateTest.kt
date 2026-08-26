package com.piercingxx.calendar.ui.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeNavigateTest {

    @Test
    fun `swipe left past the threshold goes to the next window`() {
        assertEquals(SwipeNavigate.NEXT, SwipeNavigate.direction(-80f, 4f, 56f))
    }

    @Test
    fun `swipe right past the threshold goes to the previous window`() {
        assertEquals(SwipeNavigate.PREVIOUS, SwipeNavigate.direction(80f, -6f, 56f))
    }

    @Test
    fun `a mostly-vertical drag does not navigate`() {
        assertEquals(SwipeNavigate.NONE, SwipeNavigate.direction(-40f, 90f, 56f))
        assertEquals(SwipeNavigate.NONE, SwipeNavigate.direction(90f, 120f, 56f))
    }

    @Test
    fun `a short flick below the threshold does not navigate`() {
        assertEquals(SwipeNavigate.NONE, SwipeNavigate.direction(-20f, 0f, 56f))
        assertEquals(SwipeNavigate.NONE, SwipeNavigate.direction(55f, 0f, 56f))
    }
}
