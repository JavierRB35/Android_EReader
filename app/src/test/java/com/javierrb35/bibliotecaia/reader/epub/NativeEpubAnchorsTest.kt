package com.javierrb35.bibliotecaia.reader.epub

import org.junit.Assert.*
import org.junit.Test

class NativeEpubAnchorsTest {
    private val href = "https://readium.local/book/Chapter%201.xhtml"
    private val text = "Palabra. Un versículo. Palabra. Un versículo."

    @Test fun keepsExactOffsetWhenSamePhraseAppearsTwice() {
        val second = text.lastIndexOf("Un versículo")
        val json = NativeEpubAnchors.locator(href, "Prueba", second, text.length, 0.5, "Un versículo")
        val range = requireNotNull(NativeEpubAnchors.validatedRange(json, "Un versículo", href, text))
        assertEquals(second, range.first)
        assertEquals(second + "Un versículo".length, range.last + 1)
        assertNotEquals(text.indexOf("Un versículo"), range.first)
    }

    @Test fun refusesCrossChapterAndStaleEdition() {
        val json = NativeEpubAnchors.locator(href, "Prueba", 0, text.length, 0.0, "Palabra")
        assertNull(NativeEpubAnchors.validatedRange(json, "Palabra", "$href-other", text))
        assertNull(NativeEpubAnchors.validatedRange(json, "Palabra", href, "Texto modificado."))
    }

    @Test fun refusesDamagedOffsetAndLocatorVersion() {
        val json = NativeEpubAnchors.locator(href, "Prueba", 0, text.length, 0.0, "Palabra")
        val wrongOffset = org.json.JSONObject(json)
        wrongOffset.getJSONObject("nativeText").put("offset", 999999)
        assertNull(NativeEpubAnchors.validatedRange(wrongOffset.toString(), "Palabra", href, text))
        val wrongHref = org.json.JSONObject(json)
        wrongHref.getJSONObject("nativeText").put("href", "https://other.example/Chapter.xhtml")
        assertNull(NativeEpubAnchors.point(wrongHref.toString()))
        assertNull(NativeEpubAnchors.point("not json"))
    }

    @Test fun progressRoundTripsWithoutAnUnverifiedQuote() {
        val json = NativeEpubAnchors.locator(href, "Prueba", 10, text.length, 0.27)
        assertEquals(10, NativeEpubAnchors.point(json)?.offset)
        assertEquals(0, NativeEpubAnchors.point(json)?.length)
        assertFalse(org.json.JSONObject(json).has("text"))
    }

    @Test fun unicodeUsesUtf16OffsetsLikeNativeTextView() {
        val source = "🕊️ En el principio; 🕊️ En el principio."
        val second = source.lastIndexOf("🕊️")
        val quote = "🕊️ En el principio"
        val json = NativeEpubAnchors.locator(href, "Prueba", second, source.length, 0.8, quote)
        assertNotNull(NativeEpubAnchors.validatedRange(json, quote, href, source))
        assertEquals(second, NativeEpubAnchors.point(json)?.offset)
    }
}
