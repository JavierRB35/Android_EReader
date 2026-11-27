package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.importer.EpubMetadataParser
import com.javierrb35.bibliotecaia.importer.MobiMetadataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MetadataParsersTest {

    private fun epubWithMetadata(title: String, author: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>"""
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>$title</dc:title>
                    <dc:creator>$author</dc:creator>
                    <dc:language>es</dc:language>
                  </metadata>
                </package>"""
            )
        }
        return bos.toByteArray()
    }

    @Test
    fun `extrae título y autor del OPF`() {
        val meta = EpubMetadataParser.parse { epubWithMetadata("Don Quijote de la Mancha", "Miguel de Cervantes").inputStream() }
        assertEquals("Don Quijote de la Mancha", meta?.title)
        assertEquals("Miguel de Cervantes", meta?.authors)
    }

    @Test
    fun `ZIP sin OPF devuelve nulos sin lanzar excepción`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
        }
        val meta = EpubMetadataParser.parse { bos.toByteArray().inputStream() }
        assertNull(meta)
    }

    @Test
    fun `XML con DTD se rechaza sin excepción (anti-XXE)`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<?xml version="1.0"?><!DOCTYPE container [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><container/>""".toByteArray())
            zip.closeEntry()
        }
        assertNull(EpubMetadataParser.parse { bos.toByteArray().inputStream() })
    }

    @Test
    fun `parsea nombre de libro de la cabecera PDB`() {
        val header = ByteArray(78)
        "Don Quijote (Cervantes)".toByteArray(Charsets.ISO_8859_1).copyInto(header)
        "BOOKMOBI".toByteArray(Charsets.US_ASCII).copyInto(header, 60)
        val meta = MobiMetadataParser.parse(header)
        assertEquals("Don Quijote (Cervantes)", meta?.title)
        assertNull(meta?.authors)
    }

    @Test
    fun `cabecera PDB con nombre vacío devuelve nulo`() {
        val header = ByteArray(78)
        "BOOKMOBI".toByteArray(Charsets.US_ASCII).copyInto(header, 60)
        assertNull(MobiMetadataParser.parse(header))
    }

    @Test
    fun `cabecera truncada devuelve nulo`() {
        assertNull(MobiMetadataParser.parse(ByteArray(20)))
    }
}
