package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.importer.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportBoundsTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun oversizedMimetypeIsNotAccepted() {
        val bytes = zip("mimetype" to ("application/epub+zip" + " ".repeat(10000)))
        assertEquals(DetectedFormat.UNKNOWN, BookFormatDetector.detectFull { bytes.inputStream() })
    }

    @Test fun oversizedContainerDoesNotAllocateUnboundedMetadata() {
        val bytes = zip("META-INF/container.xml" to "a".repeat(1024 * 1024 + 1))
        assertNull(EpubMetadataParser.parse { bytes.inputStream() })
    }

    @Test fun externalEntityMetadataIsRejected() {
        val bytes = zip("META-INF/container.xml" to "<!DOCTYPE container [<!ENTITY x SYSTEM 'file:///private'>]><container>&x;</container>")
        assertNull(EpubMetadataParser.parse { bytes.inputStream() })
    }
}
