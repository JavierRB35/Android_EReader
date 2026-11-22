package com.javierrb35.bibliotecaia.reader.gestures

import org.junit.Assert.*
import org.junit.Test

class HorizontalSwipeGestureTest {
    private fun recognizer() = HorizontalSwipeGesture(10f, 80f)

    @Test fun deliberateHorizontalSwipesChangeOncePerGesture() {
        val g = recognizer()
        g.down(240f, 100f)
        assertTrue(g.move(140f, 108f))
        assertEquals(1, g.up(70f, 115f))
        assertEquals(0, g.up(50f, 115f))
        g.down(60f, 100f)
        assertEquals(-1, g.up(210f, 105f))
    }

    @Test fun verticalAndDiagonalScrollNeverTurnPages() {
        val g = recognizer()
        g.down(100f, 100f)
        assertFalse(g.move(110f, 130f))
        assertFalse(g.move(280f, 200f))
        assertEquals(0, g.up(330f, 230f))
        g.down(200f, 200f)
        assertEquals(0, g.up(40f, 95f))
    }

    @Test fun edgeTapsTinyDragsAndMultitouchDoNothing() {
        val g = recognizer()
        g.down(0f, 100f); assertEquals(0, g.up(0f, 100f))
        g.down(400f, 100f); assertEquals(0, g.up(370f, 100f))
        g.down(350f, 100f)
        assertFalse(g.move(200f, 105f, 2))
        assertEquals(0, g.up(100f, 110f))
    }
}
