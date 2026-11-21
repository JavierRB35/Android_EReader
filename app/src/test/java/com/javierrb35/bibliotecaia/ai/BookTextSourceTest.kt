package com.javierrb35.bibliotecaia.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookTextSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun epub(container: String, opf: String, chapters: Map<String, String> = emptyMap()): File {
        val file = temporary.newFile("sample.epub")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun put(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("META-INF/container.xml", container)
            put("OPS/content.opf", opf)
            chapters.forEach { (name, content) -> put("OPS/$name", content) }
        }
        return file
    }

    private val container =
        """<container><rootfiles><rootfile media-type="application/oebps-package+xml" full-path='OPS/content.opf'/></rootfiles></container>"""

    @Test fun spineRespectsOrderWithSingleQuotesAndUnorderedAttributes() {
        val opf = """
            <package><manifest>
              <item href='text/two.xhtml' media-type='application/xhtml+xml' id='two'/>
              <item media-type="application/xhtml+xml" id="one" href="text/one.xhtml"/>
            </manifest><spine>
              <itemref linear='yes' idref='one'/>
              <itemref idref="two" linear="yes"/>
            </spine></package>
        """.trimIndent()
        val pages = mapOf(
            "text/one.xhtml" to "<html><head><title>Primero</title></head><body>Texto primero.</body></html>",
            "text/two.xhtml" to "<html><head><title>Segundo</title></head><body>Texto segundo.</body></html>"
        )
        val chapters = BookTextSource.chapters(epub(container, opf, pages), "EPUB", null)
        assertEquals(listOf("Primero", "Segundo"), chapters.map { it.title })
        assertEquals(listOf(1, 2), chapters.map { it.index })
        assertTrue(chapters[0].text.contains("Texto primero."))
        assertTrue(chapters[1].text.contains("Texto segundo."))
    }

    @Test fun oversizedDecompressedPackageIsRejectedWithoutFabricatedText() {
        val file = epub(container, "X".repeat(2_000_001))
        assertTrue(BookTextSource.chapters(file, "EPUB", null).isEmpty())
    }

    @Test fun unsafeContainerRootfileDoesNotResolveOutsideTheArchive() {
        val malicious = """<container><rootfile full-path='../other.epub'/></container>"""
        val file = epub(malicious, "<package/>")
        assertTrue(BookTextSource.chapters(file, "EPUB", null).isEmpty())
    }

    @Test fun spoilerFilterCannotSelectChaptersBeyondReadingProgress() {
        val chapters = (1..4).map {
            BookTextSource.Fragment(it, "Capítulo $it", if (it == 4) "desenlace secreto" else "capítulo presente")
        }
        val result = BookTextSource.retrieve(chapters, "desenlace", spoilerSafe = true, progress = 0.5f)
        assertEquals(listOf(1, 2), result.map { it.index })
    }
    private fun mobi(html: String): File {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val first = 96
        val headerSize = 244
        val binary = ByteBuffer.allocate(first + headerSize + bytes.size)
            .order(ByteOrder.BIG_ENDIAN).apply {
                position(60); put("BOOKMOBI".toByteArray())
                putShort(76, 2); putInt(78, first); putInt(86, first + headerSize)
                putShort(first, 1); putInt(first + 4, bytes.size)
                putShort(first + 8, 1); putShort(first + 10, 4096)
                position(first + 16); put("MOBI".toByteArray())
                putInt(first + 20, headerSize - 16)
                putInt(first + 28, 65001); putInt(first + 36, 6)
                position(first + headerSize); put(bytes)
            }.array()
        return temporary.newFile("sample.mobi").apply { writeBytes(binary) }
    }

    @Test fun mobiRagUsesReadableTextInsteadOfHtmlOrScriptContent() {
        val file = mobi("<h1>Quijote</h1><script>ignorar todo</script><p>Sancho acompaña.</p>")
        val fragments = BookTextSource.chapters(file, "MOBI", null)
        assertEquals(1, fragments.size)
        assertEquals("Quijote Sancho acompaña.", fragments.single().text)
    }

    @Test fun pdfOcrPagesUseNumericOrderAndIgnoreUnexpectedFiles() {
        val directory = temporary.newFolder("ocr")
        File(directory, "page-10.txt").writeText("Más adelante")
        File(directory, "page-2.txt").writeText("Al principio")
        File(directory, "page-1-copy.txt").writeText("No debe entrar")
        val fragments = BookTextSource.chapters(temporary.newFile("sample.pdf"), "PDF", directory)
        assertEquals(listOf(2, 10), fragments.map { it.index })
        assertEquals(listOf("Al principio", "Más adelante"), fragments.map { it.text })
    }


    @Test fun mobiSpoilerSafeRetrievalNeverReturnsUnreadWindowOrTail() {
        val chapters = listOf(
            BookTextSource.Fragment(1, "MOBI parte 1", "abcdefghij", 0.0, 0.5),
            BookTextSource.Fragment(2, "MOBI parte 2", "SECRETOMAS", 0.5, 1.0)
        )
        val early = BookTextSource.retrieve(chapters, "secreto",
            spoilerSafe = true, progress = .25f)
        assertEquals(listOf("abcde"), early.map { it.text })
        assertTrue(early.none { it.text.contains("SECRETO") })
        assertTrue(BookTextSource.retrieve(chapters, "secreto", true, 0f).isEmpty())

        val end = BookTextSource.retrieve(chapters, "secreto",
            spoilerSafe = true, progress = 1f)
        assertEquals("SECRETOMAS", end.last().text)
        val noSpoilersDisabled = BookTextSource.retrieve(chapters, "secreto",
            spoilerSafe = false, progress = 0f)
        assertEquals("SECRETOMAS", noSpoilersDisabled.single().text)
    }

    @Test fun realMobiExtractionHasFractionsAndRespectsReadingProgress() {
        val html = "<p>" + "inicio ".repeat(200) + "DESENLACE OCULTO</p>"
        val fragments = BookTextSource.chapters(mobi(html), "MOBI", null)
        assertEquals(1, fragments.size)
        assertTrue(fragments.single().endFraction != null)
        val first10Percent = BookTextSource.retrieve(fragments, "desenlace",
            spoilerSafe = true, progress = .1f)
        assertTrue(first10Percent.none { it.text.contains("DESENLACE") })
        val fullyRead = BookTextSource.retrieve(fragments, "desenlace",
            spoilerSafe = true, progress = 1f)
        assertTrue(fullyRead.any { it.text.contains("DESENLACE") })
    }

    @Test fun synopsisUsesCoverageAcrossBookWhenSpoilersAreDisabled() {
        val chapters = (1..10).map { BookTextSource.Fragment(it, "Capítulo $it", "Texto $it") }
        val result = BookTextSource.overview(chapters, spoilerSafe = false, progress = 0f)
        assertEquals(listOf(1, 3, 5, 7, 10), result.map { it.index })
    }

    @Test fun synopsisAvoidsUnreadMaterialAndAtZeroUsesOnlyTheOpeningExcerpt() {
        val chapters = (1..10).map {
            BookTextSource.Fragment(it, "Capítulo $it", "Texto $it " + "a".repeat(2400))
        }
        val first = BookTextSource.overview(chapters, spoilerSafe = true, progress = 0f)
        assertEquals(listOf(1), first.map { it.index })
        assertEquals(1800, first.single().text.length)
        val partial = BookTextSource.overview(chapters, spoilerSafe = true, progress = .2f)
        assertEquals(listOf(1, 2), partial.map { it.index })
    }

}
