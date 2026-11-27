package com.javierrb35.bibliotecaia.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.ReadingPositionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Actual SQLite transaction and a portable ZIP with a real local original. */
@RunWith(AndroidJUnit4::class)
class CompleteBackupRestoreInstrumentedTest {
    @Test fun fullZipRestoresPhysicalBookAndMarksToExistingHashIdentity() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "complete-device-" + System.nanoTime())
        assertTrue(dir.mkdirs())
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val original = File(dir, "novela.epub").apply { writeText("ebook original íntegro") }
            val digest = MessageDigest.getInstance("SHA-256").digest(original.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val imported = BookEntity("backup-id", "Novela", null, BookFormat.EPUB,
                original.name, original.absolutePath, digest, original.length(), 1)
            val local = imported.copy(id = "local-id", localPath = "")
            db.bookDao().insert(local)

            val snapshot = LibrarySnapshot(
                books = listOf(imported),
                categories = emptyList(), tags = emptyList(), collections = emptyList(),
                bookCategories = emptyList(), bookTags = emptyList(), collectionBooks = emptyList(),
                annotations = listOf(AnnotationEntity("a1", "backup-id", "chapter:1",
                    "Frase", "Nota", "NOTE", 0, 10, 11)),
                versions = emptyList(),
                positions = listOf(ReadingPositionEntity("backup-id", "chapter:2", 0.8f, 12))
            )
            val json = BackupCodec.toJson(snapshot, "0.1.0", 1)
            val zip = File(dir, "portable.zip")
            zip.outputStream().use { CompleteBackupArchive.write(it, json, listOf(imported)) }
            val stage = File(dir, "stage").apply { mkdirs() }
            val read = zip.inputStream().use { CompleteBackupArchive.read(it, stage) }
            assertArrayEquals(original.readBytes(),
                read.staged.getValue(CompleteBackupArchive.entryName(imported.id)).readBytes())
            val finalOriginal = File(dir, "restored-original.epub")
            original.copyTo(finalOriginal)
            val report = BackupManager(db, "0.1.0")
                .restoreJson(read.json, mapOf(digest to finalOriginal.absolutePath))

            assertEquals(0, report.booksRestored)
            assertEquals(1, report.annotationsRestored)
            assertEquals(1, report.positionsUpdated)
            assertEquals(finalOriginal.absolutePath, db.bookDao().findById("local-id")?.localPath)
            assertEquals("local-id", db.backupDao().annotationsAll().single().bookId)
            assertEquals("local-id", db.backupDao().positionsAll().single().bookId)
            assertEquals(0, BackupManager(db, "0.1.0")
                .restoreJson(read.json, mapOf(digest to finalOriginal.absolutePath)).annotationsRestored)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }
}
