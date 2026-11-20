package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.ui.EpubCoverSource
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubCoverSourceTest {
    private fun book(opf: String, coverPath: String = "OPS/images/cover.png", image: ByteArray = byteArrayOf(1,2,3), container: String = "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>"): File {
        val file = File.createTempFile("cover-test", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in listOf("META-INF/container.xml" to container.toByteArray(), "OPS/book.opf" to opf.toByteArray(), coverPath to image)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return file
    }
    private fun checkBook(file: File, action: (File) -> Unit) { try { action(file) } finally { file.delete() } }
    @Test fun epub3UsesDeclaredCoverAndResolvesEncodedPath() {
        val opf = "<package><manifest><item id=\"c\" href=\"images/my%20cover.png\" media-type=\"image/png\" properties=\"cover-image\"/></manifest></package>"
        checkBook(book(opf,"OPS/images/my cover.png")) { assertArrayEquals(byteArrayOf(1,2,3), EpubCoverSource.read(it)) }
    }
    @Test fun epub2UsesMetadataCoverId() {
        val opf = "<package><metadata><meta name=\"cover\" content=\"jacket\"/></metadata><manifest><item id=\"jacket\" href=\"images/cover.png\" media-type=\"image/png\"/></manifest></package>"
        checkBook(book(opf)) { assertNotNull(EpubCoverSource.read(it)) }
    }
    @Test fun undeclaredImagesAreNotInventedAsCovers() {
        checkBook(book("<package><manifest><item id=\"c\" href=\"images/cover.png\" media-type=\"image/png\"/></manifest></package>")) { assertNull(EpubCoverSource.read(it)) }
    }
    @Test fun externalCoverReferencesAreRejected() {
        checkBook(book("<package><manifest><item href=\"https://invalid.example/cover.png\" media-type=\"image/png\" properties=\"cover-image\"/></manifest></package>")) { assertNull(EpubCoverSource.read(it)) }
    }
    @Test fun archiveRootEscapeIsRejected() {
        checkBook(book("<package><manifest><item href=\"../../cover.png\" media-type=\"image/png\" properties=\"cover-image\"/></manifest></package>", "../cover.png")) { assertNull(EpubCoverSource.read(it)) }
    }
    @Test fun oversizeImageIsRejected() {
        checkBook(book("<package><manifest><item href=\"images/cover.png\" media-type=\"image/png\" properties=\"cover-image\"/></manifest></package>", image=ByteArray(8*1024*1024+1))) { assertNull(EpubCoverSource.read(it)) }
    }
    @Test fun entityDeclarationIsRejected() {
        checkBook(book("<!DOCTYPE package [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><package>&secret;</package>")) { assertNull(EpubCoverSource.read(it)) }
    }
    @Test fun oversizedContainerIsRejected() {
        checkBook(book("<package/>", container="<container>" + " ".repeat(512*1024) + "</container>")) { assertNull(EpubCoverSource.read(it)) }
    }
}
