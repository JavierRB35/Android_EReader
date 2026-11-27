package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.reader.epub.EpubSafety
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubSafetyTest {
    @Test fun removesBookScriptsAndInstallsNetworkRestrictionWithoutLosingText() {
        val html = """<html><head><meta http-equiv="refresh" content="0;url=https://tracking.invalid"/></head>
            <body onload="steal()"><script>steal()</script><p id="source">Texto original</p>
            <a href="java&#x73;cript:steal()">enlace</a><iframe src="https://tracking.invalid"></iframe></body></html>"""
        val result = Jsoup.parse(String(EpubSafety.sanitize(html.toByteArray())))
        assertEquals("Texto original", result.getElementById("source")!!.text())
        assertTrue(result.select("script,iframe,[onload]").isEmpty())
        assertFalse(result.selectFirst("a")!!.hasAttr("href"))
        val policy = result.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        assertTrue(policy.contains("connect-src 'none'"))
        assertTrue(result.select("meta[http-equiv=refresh]").isEmpty())
    }

    @Test fun removesSvgActiveContentAndExternalReferences() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script>
            <image href="https://tracking.invalid/pixel"/><rect onload="x()"/>
            <animate attributeName="href" values="javascript:x()"/><style>@import 'https://tracking.invalid';</style></svg>"""
        val result = String(EpubSafety.sanitize(svg.toByteArray(), true))
        assertFalse(result.contains("tracking.invalid"))
        assertFalse(result.contains("javascript:"))
        assertFalse(result.contains("onload"))
        assertFalse(result.contains("<script"))
    }

    @Test fun acceptsSmallArchive() {
        archive("OPS/chapter.xhtml", "<p>Hola</p>".toByteArray()).useFile { EpubSafety.validate(it) }
    }

    @Test fun emitsWellFormedXhtmlAfterInjectingPolicy() {
        val input = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Libro</title></head>
            <body><p>A&amp;B&#160;C</p><img src="cover.jpg" /></body></html>"""
        val bytes = EpubSafety.sanitize(input.toByteArray())
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val parsed = factory.newDocumentBuilder().parse(bytes.inputStream())
        assertEquals("Libro", parsed.getElementsByTagName("title").item(0).textContent)
    }

    @Test fun rejectsTraversalBeforeRendering() {
        archive("../chapter.xhtml", byteArrayOf(1)).useFile {
            assertThrows(IllegalArgumentException::class.java) { EpubSafety.validate(it) }
        }
    }

    @Test fun rejectsExpandedChapterBeyondLimit() {
        archive("OPS/chapter.xhtml", ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() }).useFile {
            assertThrows(IllegalArgumentException::class.java) { EpubSafety.validate(it) }
        }
    }

    private fun archive(name: String, bytes: ByteArray): File = File.createTempFile("safe-epub-", ".epub").also {
        ZipOutputStream(it.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
    }
    private fun File.useFile(block: (File) -> Unit) { try { block(this) } finally { delete() } }
}
