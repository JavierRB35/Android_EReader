package com.javierrb35.bibliotecaia.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
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
}
