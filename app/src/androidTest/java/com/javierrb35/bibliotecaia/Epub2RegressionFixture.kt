package com.javierrb35.bibliotecaia

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Original, artificial EPUB 2.0 for testing a long NCX spine and legacy XHTML 1.1.
 * Its document layout approximates the reporter's EPUB, but contains NO book text.
 */
internal object Epub2RegressionFixture {
    fun write(file: File) {
        val chapters = (1..78).map { "Section%04d.xhtml".format(java.util.Locale.ROOT, it) }
        val items = chapters.mapIndexed { index, name ->
            """<item id="chapter${index + 1}" href="Text/$name" media-type="application/xhtml+xml"/>"""
        }.joinToString("\n")
        val spine = chapters.indices.joinToString("\n") { index ->
            """<itemref idref="chapter${index + 1}"/>"""
        }
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="BookId">urn:uuid:aaaaaa11-aaaa-4aaa-baaa-aaaaaaaaaaaa</dc:identifier>
                <dc:title>Libro de prueba EPUB 2</dc:title>
                <dc:creator>Autor de prueba</dc:creator>
                <dc:language>es</dc:language>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                $items
              </manifest>
              <spine toc="ncx">$spine</spine>
            </package>""".trimIndent()
        val navPoints = chapters.mapIndexed { index, name ->
            """<navPoint id="n${index + 1}" playOrder="${index + 1}">
              <navLabel><text>Sección ${index + 1}</text></navLabel>
              <content src="Text/$name"/>
              </navPoint>"""
        }.joinToString("\n")
        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
                  "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="urn:uuid:aaaaaa11-aaaa-4aaa-baaa-aaaaaaaaaaaa"/></head>
              <docTitle><text>Libro de prueba EPUB 2</text></docTitle>
              <navMap>$navPoints</navMap>
            </ncx>""".trimIndent()
        val container = """<?xml version="1.0" encoding="UTF-8"?>
          <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles>
              <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles>
          </container>""".trimIndent()

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(name: String, data: String, stored: Boolean = false) {
                val bytes = data.toByteArray(Charsets.UTF_8)
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = bytes.size.toLong()
                    val crc = CRC32()
                    crc.update(bytes)
                    e.crc = crc.value
                }
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip", stored = true)
            entry("META-INF/container.xml", container)
            entry("OEBPS/content.opf", opf)
            entry("OEBPS/toc.ncx", ncx)
            chapters.forEachIndexed { index, name ->
                // An intentionally long chapter probes bounded sanitization.
                val paragraphs = if (index == 22) 3000 else 2
                val body = buildString {
                    repeat(paragraphs) {
                        append("<p>Este texto es una prueba técnica original del lector EPUB.</p>")
                    }
                }
                entry("OEBPS/Text/$name", """<?xml version="1.0" encoding="utf-8"?>
                  <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
                    "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                  <html xmlns="http://www.w3.org/1999/xhtml">
                    <head><title>Capítulo ${index + 1}</title></head>
                    <body><h1>Capítulo ${index + 1}</h1>$body</body>
                  </html>""".trimIndent())
            }
        }
    }
}
