package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.reader.mobi.MobiDocument
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MobiDocumentTest {
    private fun mobi(text: ByteArray, compression: Int = 1, length: Int = text.size,
                     version: Int = 6, encrypted: Int = 0, encoding: Int = 65001,
                     trailing: Int = 0): ByteArray {
        val first = 96
        val headerSize = 244
        return ByteBuffer.allocate(first + headerSize + text.size).order(ByteOrder.BIG_ENDIAN).apply {
            position(60); put("BOOKMOBI".toByteArray())
            putShort(76, 2); putInt(78, first); putInt(86, first + headerSize)
            putShort(first, compression.toShort()); putInt(first + 4, length)
            putShort(first + 8, 1); putShort(first + 10, 4096); putShort(first + 12, encrypted.toShort())
            position(first + 16); put("MOBI".toByteArray())
            putInt(first + 20, headerSize - 16); putInt(first + 28, encoding); putInt(first + 36, version)
            putShort(first + 242, trailing.toShort())
            position(first + headerSize); put(text)
        }.array()
    }
    private fun read(bytes: ByteArray): String {
        val file = File.createTempFile("biblioteca-mobi", ".mobi")
        return try { file.writeBytes(bytes); MobiDocument.readHtml(file) } finally { file.delete() }
    }
    @Test fun readsRealContainerAndPreservesSpanishUtf8() {
        val html = "<h1>Capítulo</h1><p>Lectura sin conexión: español.</p>"
        assertEquals(html, read(mobi(html.toByteArray())))
    }
    @Test fun readsWindows1252() {
        val html = "<p>España — café</p>"
        assertEquals(html, read(mobi(html.toByteArray(charset("windows-1252")), encoding = 1252)))
    }
    @Test fun readsPalmDocCompressionAndOverlap() {
        // A then back-reference distance 1, length 5 => six As.
        val packed = byteArrayOf(65, 0x80.toByte(), 0x0a)
        assertEquals("AAAAAA", read(mobi(packed, compression = 2, length = 6)))
    }
    @Test fun decodesLiteralsAndSpaceShortcut() {
        val packed = byteArrayOf(2, 0, 0xff.toByte(), 0xc1.toByte())
        assertArrayEquals(byteArrayOf(0, 0xff.toByte(), 32, 65), MobiDocument.palmDoc(packed, 20))
    }
    @Test fun trimsRecordTrailersBeforeDecompressing() {
        val packed = byteArrayOf(65, 66, 67, 42, 0x82.toByte())
        assertEquals("ABC", read(mobi(packed, length = 3, trailing = 2)))
    }
    @Test fun trimsMultibyteOverlap() {
        assertArrayEquals(byteArrayOf(65, 66), MobiDocument.stripTrailing(byteArrayOf(65, 66, 0), 1))
    }
    @Test fun rejectsDrmWithoutDecrypting() {
        val error = assertThrows(MobiDocument.Unsupported::class.java) { read(mobi("secret".toByteArray(), encrypted = 2)) }
        assertTrue(error.message!!.contains("DRM"))
    }
    @Test fun rejectsDrmMetadataEvenWithoutEncryptionFlag() {
        val file = mobi("secret".toByteArray())
        ByteBuffer.wrap(file).putInt(96 + 172, 1)
        assertThrows(MobiDocument.Unsupported::class.java) { read(file) }
    }
    @Test fun detectsLegacyDrmBeforeReadingEncryptedMobiHeader() {
        val file = mobi("secret".toByteArray(), encrypted = 1)
        "TEXtREAd".toByteArray().copyInto(file, 60)
        file[96 + 16] = 0
        val error = assertThrows(MobiDocument.Unsupported::class.java) { read(file) }
        assertTrue(error.message!!.contains("DRM"))
    }
    @Test fun rejectsKf8Explicitly() {
        assertThrows(MobiDocument.Unsupported::class.java) { read(mobi("x".toByteArray(), version = 8)) }
    }
    @Test fun rejectsHuffmanExplicitly() {
        assertThrows(MobiDocument.Unsupported::class.java) { read(mobi("x".toByteArray(), compression = 17480)) }
    }
    @Test fun rejectsUnknownEncoding() {
        assertThrows(MobiDocument.Unsupported::class.java) { read(mobi("x".toByteArray(), encoding = 0)) }
    }
    @Test fun rejectsInvalidUtf8InsteadOfSilentlyChangingAnchors() {
        assertThrows(IOException::class.java) { read(mobi(byteArrayOf(0xc3.toByte()))) }
    }
    @Test fun rejectsOversizedTextBeforeAllocation() {
        assertThrows(MobiDocument.Unsupported::class.java) { read(mobi(byteArrayOf(65), length = MobiDocument.MAX_TEXT_BYTES + 1)) }
    }
    @Test fun rejectsTruncatedContainer() {
        assertThrows(IOException::class.java) { read(mobi("hello".toByteArray()).copyOf(100)) }
    }
    @Test fun rejectsBackwardsRecordOffsets() {
        val file = mobi("x".toByteArray())
        ByteBuffer.wrap(file).putInt(86, 95)
        assertThrows(IOException::class.java) { read(file) }
    }
    @Test fun rejectsMismatchedDeclaredLength() {
        assertThrows(IOException::class.java) { read(mobi("abc".toByteArray(), length = 4)) }
    }
    @Test fun rejectsInvalidBackReference() {
        assertThrows(IOException::class.java) { MobiDocument.palmDoc(byteArrayOf(0x80.toByte(), 8), 30) }
        assertThrows(IOException::class.java) { MobiDocument.palmDoc(byteArrayOf(65, 0x80.toByte(), 0), 30) }
    }
    @Test fun rejectsTruncatedCompressionTokens() {
        assertThrows(IOException::class.java) { MobiDocument.palmDoc(byteArrayOf(0x80.toByte()), 30) }
        assertThrows(IOException::class.java) { MobiDocument.palmDoc(byteArrayOf(3, 65), 30) }
    }
    @Test fun limitsDecompressionExpansion() {
        assertThrows(IOException::class.java) { MobiDocument.palmDoc(byteArrayOf(65, 0x80.toByte(), 0x0a), 4) }
    }
    @Test fun rejectsMalformedTrailingLength() {
        assertThrows(IOException::class.java) { MobiDocument.stripTrailing(byteArrayOf(65, 0xff.toByte()), 2) }
        assertThrows(IOException::class.java) { MobiDocument.stripTrailing(byteArrayOf(65, 0), 2) }
    }
}
