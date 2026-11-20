package com.javierrb35.bibliotecaia.reader.pdf

import org.junit.Assert.*
import org.junit.Test

class PdfOcrGeometryTest {
    private val first = PdfOcrRect(.10f, .20f, .26f, .25f)
    private val second = PdfOcrRect(.28f, .20f, .44f, .25f)
    private val hash = "sha256-of-the-same-imported-edition"

    private fun snapshot() = requireNotNull(PdfOcrGeometry.fromLines(
        listOf(listOf("luz" to first, "luz" to second)), hash))

    @Test fun repeatedWordsRetainDifferentExactOffsetsAndRects() {
        val original = snapshot()
        assertEquals("luz luz", original.text)
        assertEquals(listOf(0, 4), original.words.map { it.start })
        val locator = requireNotNull(PdfOcrAnchors.anchoredLocator(3, 4, "luz", original))
        assertEquals(listOf(second),
            PdfOcrAnchors.overlayBoxes(locator, 3, "luz", hash, original))
        assertEquals(4 until 7, PdfOcrAnchors.range(locator, 3, original.text, "luz"))
    }

    @Test fun geometrySurvivesSerializationAndPageRerender() {
        val original = snapshot()
        val restored = requireNotNull(PdfOcrGeometry.decode(
            PdfOcrGeometry.encode(original), hash))
        assertEquals(original, restored)
        val locator = requireNotNull(PdfOcrAnchors.anchoredLocator(3, 0, "luz", restored))
        // Annotations carry normalized geometry even if the OCR cache is absent.
        assertEquals(listOf(first), PdfOcrAnchors.overlayBoxes(locator, 3, "luz", hash))
    }

    @Test fun differentPdfEditionPageOrStaleTextCannotRebindMarks() {
        val original = snapshot()
        val locator = requireNotNull(PdfOcrAnchors.anchoredLocator(3, 0, "luz", original))
        assertTrue(PdfOcrAnchors.overlayBoxes(locator, 3, "luz", "different-sha").isEmpty())
        assertTrue(PdfOcrAnchors.overlayBoxes(locator, 4, "luz", hash).isEmpty())
        val altered = original.copy(text = "sol luz")
        assertTrue(PdfOcrAnchors.overlayBoxes(locator, 3, "luz", hash, altered).isEmpty())
        assertNull(PdfOcrGeometry.decode(PdfOcrGeometry.encode(original), "other-sha"))
    }

    @Test fun invalidOrOutOfBoundsCoordinatesAreAlwaysRejected() {
        assertNull(PdfOcrGeometry.fromLines(
            listOf(listOf("peligro" to PdfOcrRect(-1f, .1f, .4f, .2f))), hash))
        val original = snapshot()
        val locator = requireNotNull(PdfOcrAnchors.anchoredLocator(0, 0, "luz", original))
        val invalid = org.json.JSONObject(locator).put("boxes",
            org.json.JSONArray().put(org.json.JSONArray().put(-.01).put(.2).put(.5).put(.3)))
        assertTrue(PdfOcrAnchors.overlayBoxes(invalid.toString(), 0, "luz", hash).isEmpty())
        assertNull(PdfOcrAnchors.anchoredLocator(0, 50, "luz", original))
    }

    @Test fun multiLineSelectionIncludesBothWordsWithoutSearchingForQuote() {
        val original = requireNotNull(PdfOcrGeometry.fromLines(
            listOf(listOf("María" to first), listOf("leyó" to second)), hash))
        assertEquals("María\nleyó", original.text)
        val anchor = requireNotNull(PdfOcrAnchors.anchoredLocator(0, 0, original.text, original))
        assertEquals(listOf(first, second),
            PdfOcrAnchors.overlayBoxes(anchor, 0, original.text, hash, original))
    }
}
