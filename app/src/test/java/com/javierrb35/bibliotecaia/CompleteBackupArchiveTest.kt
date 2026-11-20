package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.backup.BackupCodec
import com.javierrb35.bibliotecaia.backup.CompleteBackupArchive
import com.javierrb35.bibliotecaia.backup.LibrarySnapshot
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.AnnotationVersionEntity
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.ReadingPositionEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CompleteBackupArchiveTest {
    @get:Rule val dir = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun book(id: String, fileName: String, body: ByteArray): BookEntity {
        val file = dir.newFile(fileName)
        file.writeBytes(body)
        return BookEntity(id, "Título " + id, null,
            if (fileName.endsWith(".pdf")) BookFormat.PDF else BookFormat.EPUB,
            fileName, file.absolutePath, hash(body), body.size.toLong(), 1)
    }

    private fun json(books: List<BookEntity>): String {
        val id = books.firstOrNull()?.id
        return BackupCodec.toJson(LibrarySnapshot(
            books = books, categories = emptyList(), tags = emptyList(),
            collections = emptyList(), bookCategories = emptyList(),
            bookTags = emptyList(), collectionBooks = emptyList(),
            annotations = if (id == null) emptyList() else listOf(
                AnnotationEntity("note1", id, "chapter-2", "cita", "comentario", "NOTE",
                    0, 1, 2)),
            versions = if (id == null) emptyList() else listOf(
                AnnotationVersionEntity("v1", "note1", "antes", 0, 1)),
            positions = if (id == null) emptyList() else listOf(
                ReadingPositionEntity(id, "chapter-3", .6f, 3))
        ), "0.1.0", 3)
    }

    private fun write(books: List<BookEntity>, json: String = json(books)): ByteArray {
        val stream = ByteArrayOutputStream()
        CompleteBackupArchive.write(stream, json, books)
        return stream.toByteArray()
    }

    @Test fun roundTripContainsEveryOriginalAlongsideNotesVersionsAndPositions() {
        val first = book("book-one", "first.epub", "EPUB fichero original".toByteArray())
        val second = book("book-two", "second.pdf", "%PDF fichero original".toByteArray())
        val bytes = write(listOf(first, second))
        val stage = dir.newFolder("extract")
        val full = CompleteBackupArchive.read(ByteArrayInputStream(bytes), stage)
        assertEquals(2, full.bookCount)
        assertEquals(first.sizeBytes + second.sizeBytes, full.bytes)
        assertArrayEquals(File(first.localPath).readBytes(),
            full.staged.getValue(CompleteBackupArchive.entryName(first.id)).readBytes())
        assertArrayEquals(File(second.localPath).readBytes(),
            full.staged.getValue(CompleteBackupArchive.entryName(second.id)).readBytes())
        val snapshot = BackupCodec.fromJson(full.json)
        assertEquals(1, snapshot.annotations.size)
        assertEquals("comentario", snapshot.annotations.single().body)
        assertEquals(1, snapshot.versions.size)
        assertEquals(.6f, snapshot.positions.single().progress, 0f)
    }

    @Test fun missingOriginalFailsClosedInsteadOfProducingMetadataOnlyBackup() {
        val book = book("missing", "missing.epub", "libro".toByteArray())
        File(book.localPath).delete()
        assertThrows(IllegalArgumentException::class.java) { write(listOf(book)) }
    }

    @Test fun changedOriginalWithSameLengthFailsSha256Check() {
        val book = book("tampered", "tampered.epub", "abcdef".toByteArray())
        File(book.localPath).writeText("ghijkl")
        assertThrows(IllegalArgumentException::class.java) { write(listOf(book)) }
    }

    @Test fun unexpectedEntryIsRejectedEvenWhenZipIsWellFormed() {
        val book = book("a", "a.epub", "correct".toByteArray())
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("biblioteca.json"))
            zip.write(json(listOf(book)).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../evil.txt"))
            zip.write("evil".toByteArray())
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompleteBackupArchive.read(ByteArrayInputStream(output.toByteArray()),
                dir.newFolder("extract-invalid"))
        }
    }

    @Test fun missingOriginalEntryCannotBeAccepted() {
        val book = book("b", "b.epub", "correct".toByteArray())
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("biblioteca.json"))
            zip.write(json(listOf(book)).toByteArray())
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompleteBackupArchive.read(ByteArrayInputStream(output.toByteArray()),
                dir.newFolder("extract-missing"))
        }
    }

    @Test fun forgedBookBytesAreRejectedBeforeDatabaseRestore() {
        val book = book("c", "c.epub", "correct".toByteArray())
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("biblioteca.json"))
            zip.write(json(listOf(book)).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(CompleteBackupArchive.entryName(book.id)))
            zip.write("garbled".toByteArray())
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompleteBackupArchive.read(ByteArrayInputStream(output.toByteArray()),
                dir.newFolder("extract-tampered"))
        }
    }

    @Test fun emptyLibraryProducesValidPortableArchive() {
        val bytes = write(emptyList())
        val unpacked = CompleteBackupArchive.read(
            ByteArrayInputStream(bytes), dir.newFolder("empty"))
        assertEquals(0, unpacked.bookCount)
        assertTrue(BackupCodec.fromJson(unpacked.json).books.isEmpty())
    }
}
