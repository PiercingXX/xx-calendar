package com.piercingxx.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The navigation strings that thread occurrence identity (14.1): the route a
 * tap builds and the parse of the `?start=` param back into millis. A missing
 * or malformed start must fall back to the series anchor — never crash.
 */
@RunWith(RobolectricTestRunner::class)
class NavigationRoutesTest {

    @Test
    fun `detailRoute without an occurrence start stays the plain id route`() {
        assertEquals("detail/42", detailRoute(42L, null))
    }

    @Test
    fun `detailRoute carries the tapped occurrence begin`() {
        assertEquals("detail/42?start=123456789", detailRoute(42L, 123456789L))
    }

    @Test
    fun `newEventRoute pins both ends`() {
        assertEquals("editor/new?start=100&end=200", newEventRoute(100L, 200L))
    }

    @Test
    fun `editorRoute defaults to a plain edit`() {
        assertEquals("editor/7?duplicate=", editorRoute(7L, null))
    }

    @Test
    fun `editorRoute carries occurrence and duplicate flags independently`() {
        assertEquals("editor/7?duplicate=1", editorRoute(7L, null, duplicate = true))
        assertEquals("editor/7?duplicate=&start=555", editorRoute(7L, 555L))
        assertEquals("editor/7?duplicate=1&start=555", editorRoute(7L, 555L, duplicate = true))
        assertEquals(
            "editor/7?duplicate=&start=555&dropStart=100&dropEnd=200",
            editorRoute(7L, 555L, dropStartMillis = 100L, dropEndMillis = 200L),
        )
    }

    @Test
    fun `parseNavMillis reads only real values`() {
        assertEquals(123456789L, parseNavMillis("123456789"))
        assertNull(parseNavMillis(null))
        assertNull(parseNavMillis(""))
        assertNull(parseNavMillis("null-placeholder"))
        assertNull(parseNavMillis("12x3"))
    }
}
