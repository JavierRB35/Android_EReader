package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.importer.BookFormatDetector
import com.javierrb35.bibliotecaia.importer.ContentHasher
import com.javierrb35.bibliotecaia.importer.DetectedFormat
import com.javierrb35.bibliotecaia.importer.TitleSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FormatDetectionTest {

    private fun minimalEpubBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            // "mimetype" debe ir primero (sin compresión, como exige la spec EPUB)
            val mimetype = ZipEntry("mimetype").apply { method = ZipEntry.STORED }
            mimetype.size = "application/epub+zip".length.toLong()
            mimetype.crc = java.util.zip.CRC32().apply {
                update("application/epub+zip".toByteArray(Charsets.US_ASCII))
            }.value
            zip.putNextEntry(mimetype)
            zip.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0"/>""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return bos.toByteArray()
    }

    @Test
    fun `detecta EPUB por mimetype en cabecera`() {
        val bytes = minimalEpubBytes()
        assertEquals(DetectedFormat.EPUB, BookFormatDetector.detect(bytes))
    }

    @Test
    fun `detectFull valida EPUB por estructura ZIP`() {
        val tmp = File.createTempFile("test_epub", ".epub").apply {
            writeBytes(minimalEpubBytes())
            deleteOnExit()
        }
        assertEquals(DetectedFormat.EPUB, BookFormatDetector.detectFull { FileInputStream(tmp) })
    }

    @Test
    fun `ZIP sin mimetype epub se rechaza`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("cualquier.txt"))
            zip.write("contenido".toByteArray())
            zip.closeEntry()
        }
        assertEquals(DetectedFormat.UNKNOWN, BookFormatDetector.detect(bos.toByteArray()))
        assertEquals(DetectedFormat.UNKNOWN, BookFormatDetector.detectFull { bos.toByteArray().inputStream() })
    }

    @Test
    fun `detecta PDF por cabecera`() {
        val bytes = "%PDF-1.7\n".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()) +
            "\n".toByteArray(Charsets.US_ASCII)
        assertEquals(DetectedFormat.PDF, BookFormatDetector.detect(bytes))
        assertEquals(DetectedFormat.PDF, BookFormatDetector.detectFull { bytes.inputStream() })
    }

    @Test
    fun `detecta MOBI por magic BOOKMOBI en offset 60`() {
        val header = ByteArray(78)
        "Mi Libro".toByteArray(Charsets.ISO_8859_1).copyInto(header)
        "BOOKMOBI".toByteArray(Charsets.US_ASCII).copyInto(header, 60)
        assertEquals(DetectedFormat.MOBI, BookFormatDetector.detect(header))
        assertEquals(DetectedFormat.MOBI, BookFormatDetector.detectFull { header.inputStream() })
    }

    @Test
    fun `rechaza texto plano`() {
        val bytes = "esto no es un libro".toByteArray(Charsets.UTF_8)
        assertEquals(DetectedFormat.UNKNOWN, BookFormatDetector.detect(bytes))
        assertEquals(DetectedFormat.UNKNOWN, BookFormatDetector.detectFull { bytes.inputStream() })
    }

    @Test
    fun `sha256 conocido`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHasher.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun `hash en streaming coincide con hash en memoria`() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        val viaStream = data.inputStream().use { ContentHasher.sha256Hex(it) }
        assertEquals(ContentHasher.sha256Hex(data), viaStream)
    }

    @Test
    fun `sanitizador de títulos`() {
        assertEquals("Mi libro genial", TitleSanitizer.fromFileName("mi_libro_genial.epub"))
        assertEquals("Don quijote", TitleSanitizer.fromFileName("don_quijote.pdf"))
        assertTrue(TitleSanitizer.fromFileName("  a b.epub").isNotEmpty())
        assertFalse(TitleSanitizer.fromFileName("libro.epub").endsWith(".epub"))
    }
    @org.junit.Test fun pdfIsRejectedForImportEvenWhenRenamedEpub() {
        val detected = BookFormatDetector.detectFull { "%PDF-1.7".byteInputStream() }
        var rejected = false
        try { BookFormatDetector.importFormat(detected, "renamed.epub") }
        catch (_: com.javierrb35.bibliotecaia.importer.ImportError.UnsupportedPdf) { rejected = true }
        org.junit.Assert.assertTrue(rejected)
        org.junit.Assert.assertEquals(com.javierrb35.bibliotecaia.data.BookFormat.EPUB,
            BookFormatDetector.importFormat(DetectedFormat.EPUB, "a.epub"))
        org.junit.Assert.assertEquals(com.javierrb35.bibliotecaia.data.BookFormat.MOBI,
            BookFormatDetector.importFormat(DetectedFormat.MOBI, "b.mobi"))
    }
}
