package com.javierrb35.bibliotecaia.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class PdfOcrAnchorsTest {
    @Test fun repeatedQuoteIsAnchoredToItsOriginalOccurrence() {
        val text = "La Mancha. Aún en la Mancha."
        val offset = text.lastIndexOf("Mancha")
        val locator = PdfOcrAnchors.locator(2, offset)
        assertEquals(offset until offset + 6, PdfOcrAnchors.range(locator, 2, text, "Mancha"))
        assertNull(PdfOcrAnchors.range(locator, 1, text, "Mancha"))
    }

    @Test fun changedOcrTextDoesNotPaintTheWrongWord() {
        val locator = PdfOcrAnchors.locator(0, 5)
        assertNull(PdfOcrAnchors.range(locator, 0, "Hola mundo", "otra"))
        assertNull(PdfOcrAnchors.range(locator, 0, "Hola mundo", "Mundo"))
    }

    @Test fun rejectsPageOnlyLocatorAndMismatchedOcrVersion() {
        val text = "Salvación"
        assertNull(PdfOcrAnchors.range("""{"format":"PDF","page":0}""", 0, text, text))
        val outdated = JSONObject(PdfOcrAnchors.locator(0, 0)).put("textVersion", 2).toString()
        assertNull(PdfOcrAnchors.range(outdated, 0, text, text))
    }

    @Test fun unicodeOffsetsAreVerifiedExactly() {
        val text = "🕊️ María leyó."
        val offset = text.indexOf("María")
        val locator = PdfOcrAnchors.locator(3, offset)
        assertEquals(offset until offset + 5, PdfOcrAnchors.range(locator, 3, text, "María"))
    }
}
