package com.javierrb35.bibliotecaia.study

import org.junit.Assert.*
import org.junit.Test

class SelectionAnchorsTest {
    @Test fun repeatedTextUsesOnlyTheSavedOffset() {
        val text = "luz y más luz, luego luz"
        assertTrue(SelectionAnchors.exactMatch(text, 0, "luz"))
        assertTrue(SelectionAnchors.exactMatch(text, 10, "luz"))
        assertFalse(SelectionAnchors.exactMatch(text, 1, "luz"))
        assertFalse(SelectionAnchors.exactMatch(text, 99, "luz"))
    }

    @Test fun unicodeAndCrossChunkSpansRemainAnchored() {
        val text = "María dijo: «Salvación y fe»"
        val offset = text.indexOf("Salvación")
        val first = SelectionAnchors.visibleRange(text, offset, "Salvación y fe", offset, offset + 5)
        val second = SelectionAnchors.visibleRange(text, offset, "Salvación y fe", offset + 5, text.length)
        assertEquals(0 until 5, first)
        assertNotNull(second)
        assertFalse(SelectionAnchors.exactMatch(text.replace("Salvación", "Remisión"),
            offset, "Salvación y fe"))
    }

    @Test fun emptyWrongAndOutOfBoundsSelectionsDoNotGetPainted() {
        assertNull(SelectionAnchors.visibleRange("hola", 0, "", 0, 4))
        assertNull(SelectionAnchors.visibleRange("hola", 0, "holaa", 0, 4))
        assertNull(SelectionAnchors.visibleRange("hola", 0, "hola", 3, 3))
        assertNull(SelectionAnchors.visibleRange("hola", 0, "hola", -1, 4))
    }
}
