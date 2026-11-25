package com.javierrb35.bibliotecaia.reader.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionEdgeSpeedTest {
    @Test fun edgesScrollInOppositeDirectionsAndCenterStops() {
        assertEquals(0f, SelectionEdgeSpeed.velocity(300f, 600f, 1f), 0f)
        assertTrue(SelectionEdgeSpeed.velocity(30f, 600f, 1f) < 0f)
        assertTrue(SelectionEdgeSpeed.velocity(570f, 600f, 1f) > 0f)
        assertEquals(-1400f, SelectionEdgeSpeed.velocity(-50f, 600f, 1f), 0f)
        assertEquals(1400f, SelectionEdgeSpeed.velocity(650f, 600f, 1f), 0f)
    }

    @Test fun speedIncreasesTowardEdgeAndShortViewportKeepsNeutralZone() {
        assertTrue(SelectionEdgeSpeed.velocity(595f, 600f, 1f) >
            SelectionEdgeSpeed.velocity(550f, 600f, 1f))
        assertEquals(0f, SelectionEdgeSpeed.velocity(40f, 80f, 3f), 0f)
        assertEquals(0f, SelectionEdgeSpeed.velocity(0f, 0f, 1f), 0f)
    }
}
