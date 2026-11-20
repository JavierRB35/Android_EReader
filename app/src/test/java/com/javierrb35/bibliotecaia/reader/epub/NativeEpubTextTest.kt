package com.javierrb35.bibliotecaia.reader.epub

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class NativeEpubTextTest {
    @Test fun preservesParagraphBoundariesAndExactSelectionWords() {
        val html = """
          <html><body><h1>Capítulo I</h1>
          <p>En un lugar de la Mancha</p>
          <p>de cuyo nombre no quiero acordarme.</p>
          <p>Otra frase <b>con varias palabras</b> y acentos: áéíóú.</p>
          </body></html>
        """.trimIndent()
        val text = NativeEpubText.readableText(Jsoup.parse(html).body())
        assertTrue(text.contains("En un lugar de la Mancha"))
        assertTrue(text.contains("de cuyo nombre no quiero acordarme."))
        assertTrue(text.contains("Otra frase con varias palabras y acentos: áéíóú."))
        assertTrue(text.contains("\n\n"))
    }

    @Test fun htmlMarkupNeverBecomesVisibleSelectionContent() {
        val text = NativeEpubText.readableText(Jsoup.parse(
            "<html><body><p>Texto principal.</p><script>alert(1)</script><p>Fin.</p></body></html>"
        ).also { it.select("script").remove() }.body())
        assertFalse(text.contains("alert"))
        assertTrue(text.contains("Texto principal."))
    }

    @Test fun repeatedQuoteOffsetsAreStableInNativeRendering() {
        val body = Jsoup.parse("<body><p>Una palabra</p><p>Una palabra</p></body>").body()
        val text = NativeEpubText.readableText(body)
        val quote = "Una palabra"
        assertTrue(text.lastIndexOf(quote) > text.indexOf(quote))
        assertEquals(2, Regex(Regex.escape(quote)).findAll(text).count())
    }
}
