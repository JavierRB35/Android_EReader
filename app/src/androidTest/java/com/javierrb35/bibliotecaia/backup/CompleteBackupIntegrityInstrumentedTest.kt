package com.javierrb35.bibliotecaia.backup

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.AnnotationVersionEntity
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.CategoryEntity
import com.javierrb35.bibliotecaia.data.CollectionBookCrossRef
import com.javierrb35.bibliotecaia.data.CollectionEntity
import com.javierrb35.bibliotecaia.data.ReadingPositionEntity
import com.javierrb35.bibliotecaia.data.TagEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Real SQLite + real files + Android ContentResolver (file URI, NOT a real
 * Drive/DocumentsProvider test). Never uses the user's live database or books.
 */
@RunWith(AndroidJUnit4::class)
class CompleteBackupIntegrityInstrumentedTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun database(): AppDatabase =
        Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()

    @Test fun sameLengthCorruptLocalOriginalIsRelinkedToVerifiedCopy() = runBlocking {
        val dir = File(ctx.cacheDir, "backup-corrupt-${UUID.randomUUID()}").apply { mkdirs() }
        val db = database()
        try {
            val validBytes = "epub ORIGINAL intacto".toByteArray()
            val corruptBytes = "epub ORIGINAL mutado!".toByteArray()
            assertEquals(validBytes.size, corruptBytes.size)
            val damaged = File(dir, "local-corrupt.epub").apply { writeBytes(corruptBytes) }
            val recovered = File(dir, "from-zip.epub").apply { writeBytes(validBytes) }
            val hash = sha256(validBytes)
            val existing = BookEntity("local", "Libro", null, BookFormat.EPUB,
                damaged.name, damaged.absolutePath, hash, validBytes.size.toLong(), 1L)
            val remote = existing.copy(id = "remote", fileName = recovered.name,
                localPath = recovered.absolutePath)
            db.bookDao().insert(existing)
            db.backupDao().insertAnnotationsIgnore(listOf(
                AnnotationEntity("prior-note", "local", "cfi:old", "Cita", "Anterior",
                    "NOTE", 1, 1, 1)))
            val snapshot = LibrarySnapshot(listOf(remote), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList(),
                listOf(AnnotationEntity("new-note", "remote", "cfi:new", "Cita 2",
                    "Restaurada", "NOTE", 1, 2, 3)), emptyList(),
                listOf(ReadingPositionEntity("remote", "cfi:position", 0.5f, 5)))
            val json = BackupCodec.toJson(snapshot, "0.1.0", 10)
            val manager = BackupManager(db, "0.1.0")
            val result = manager.restoreJson(json, mapOf(hash to recovered.absolutePath))
            assertEquals(0, result.booksRestored)
            assertEquals(recovered.absolutePath, db.bookDao().findById("local")!!.localPath)
            assertArrayEquals(validBytes, File(db.bookDao().findById("local")!!.localPath).readBytes())
            assertTrue(damaged.exists()) // Never delete or overwrite preexisting files.
            assertEquals(2, db.backupDao().annotationsAll().size)
            assertEquals("local", db.backupDao().annotationsAll()
                .single { it.id == "new-note" }.bookId)
            assertEquals("local", db.backupDao().positionsAll().single().bookId)
            val repeat = manager.restoreJson(json, mapOf(hash to recovered.absolutePath))
            assertEquals(0, repeat.annotationsRestored)
            assertEquals(2, db.backupDao().annotationsAll().size)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test fun verifiedExistingOriginalRetainsItsPathAndUnreadableReplacementRollsBack() = runBlocking {
        val dir = File(ctx.cacheDir, "backup-valid-${UUID.randomUUID()}").apply { mkdirs() }
        val db = database()
        try {
            val bytes = "Contenido de libro".toByteArray()
            val first = File(dir, "installed.epub").apply { writeBytes(bytes) }
            val replacement = File(dir, "from-backup.epub").apply { writeBytes(bytes) }
            val hash = sha256(bytes)
            val existing = BookEntity("local", "Libro", null, BookFormat.EPUB,
                first.name, first.absolutePath, hash, bytes.size.toLong(), 1L)
            db.bookDao().insert(existing)
            val incoming = existing.copy(id = "remote", localPath = "")
            val json = BackupCodec.toJson(LibrarySnapshot(listOf(incoming),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList()), "0.1.0", 2L)
            BackupManager(db, "0.1.0").restoreJson(json, mapOf(hash to replacement.absolutePath))
            assertEquals(first.absolutePath, db.bookDao().findById("local")!!.localPath)

            val damagedReplacement = File(dir, "damaged.epub").apply {
                writeBytes("Contenido de librO".toByteArray())
            }
            try {
                BackupManager(db, "0.1.0").restoreJson(
                    json, mapOf(hash to damagedReplacement.absolutePath))
                fail("An unverified replacement must not enter the DB transaction")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("dañado"))
            }
            assertEquals(first.absolutePath, db.bookDao().findById("local")!!.localPath)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test fun completeZipRoundTripRestoresPhysicalBookAndKnowledgeToEmptyRoom() = runBlocking {
        val id = UUID.randomUUID().toString()
        val temp = File(ctx.cacheDir, "backup-test/backup-roundtrip-$id").apply { mkdirs() }
        val internal = File(ctx.filesDir, "books/$id").apply { mkdirs() }
        val filename = "portable.epub"
        val original = File(internal, filename)
        val zip = File(temp, "backup.zip")
        val bytes = "EPUB fixture content, exact bytes".toByteArray()
        val dbSource = database()
        val dbDestination = database()
        try {
            original.writeBytes(bytes)
            val book = BookEntity(id, "Portátil", null, BookFormat.EPUB,
                filename, original.absolutePath, sha256(bytes), bytes.size.toLong(), 1)
            dbSource.bookDao().insert(book)
            val cat = CategoryEntity("cat-$id", "Historia")
            val tag = TagEntity("tag-$id", "Clásicos")
            val collection = CollectionEntity("collection-$id", "Estantería", 1)
            dbSource.backupDao().insertCategoriesIgnore(listOf(cat))
            dbSource.backupDao().insertTagsIgnore(listOf(tag))
            dbSource.backupDao().insertCollectionsIgnore(listOf(collection))
            dbSource.backupDao().insertBookCategoriesIgnore(listOf(
                com.javierrb35.bibliotecaia.data.BookCategoryCrossRef(id, cat.id)))
            dbSource.backupDao().insertBookTagsIgnore(listOf(
                com.javierrb35.bibliotecaia.data.BookTagCrossRef(id, tag.id)))
            dbSource.backupDao().insertCollectionBooksIgnore(listOf(
                CollectionBookCrossRef(collection.id, id)))
            dbSource.backupDao().insertAnnotationsIgnore(listOf(AnnotationEntity(
                "note-$id", id, "cfi:2", "texto", "Anotación", "HIGHLIGHT", 3, 2, 4)))
            dbSource.backupDao().insertVersionsIgnore(listOf(AnnotationVersionEntity(
                "version-$id", "note-$id", "Antes", 2, 3)))
            dbSource.backupDao().insertPositionReplace(
                ReadingPositionEntity(id, "cfi:3", 0.6f, 5L))
            val managerSource = CompleteBackupManager(ctx, dbSource, "0.1.0")
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".testbackup", zip)
            val exported = managerSource.exportTo(uri)
            assertEquals(1, exported.books)
            assertEquals(bytes.size.toLong(), exported.originalBytes)
            assertTrue(zip.isFile && zip.length() > bytes.size)

            // Emulate a different installation: no old local file or Room records.
            original.delete()
            val restored = CompleteBackupManager(ctx, dbDestination, "0.1.0")
                .restoreFrom(uri)
            assertEquals(1, restored.originals)
            assertArrayEquals(bytes, File(dbDestination.backupDao().booksAll()
                .single().localPath).readBytes())
            assertEquals("Anotación", dbDestination.backupDao().annotationsAll().single().body)
            assertEquals(1, dbDestination.backupDao().versionsAll().size)
            assertEquals("cfi:3", dbDestination.backupDao().positionsAll().single().locator)
            assertEquals(1, dbDestination.backupDao().bookCategoryPairs().size)
            assertEquals(1, dbDestination.backupDao().bookTagPairs().size)
            assertEquals(1, dbDestination.backupDao().collectionBookPairs().size)

            // A second restore adds nothing and preserves marks and originals.
            val again = CompleteBackupManager(ctx, dbDestination, "0.1.0")
                .restoreFrom(uri)
            assertEquals(0, again.report.booksRestored)
            assertEquals(0, again.report.annotationsRestored)
            assertEquals(1, dbDestination.backupDao().booksAll().size)
            assertEquals(1, dbDestination.backupDao().annotationsAll().size)

            // The canonical file still exists but its bytes were damaged.
            // Repair without overwriting that file or losing linked notes.
            val damagedCanonical = File(dbDestination.backupDao().booksAll().single().localPath)
            val damage = ByteArray(bytes.size) { 'x'.code.toByte() }
            damagedCanonical.writeBytes(damage)
            val healed = CompleteBackupManager(ctx, dbDestination, "0.1.0")
                .restoreFrom(uri)
            assertEquals(0, healed.report.booksRestored)
            val recoveredBook = dbDestination.backupDao().booksAll().single()
            assertNotEquals(damagedCanonical.absolutePath, recoveredBook.localPath)
            assertEquals(File(recoveredBook.localPath).name, recoveredBook.fileName)
            assertArrayEquals(bytes, File(recoveredBook.localPath).readBytes())
            assertArrayEquals(damage, damagedCanonical.readBytes())
            assertEquals(1, dbDestination.backupDao().annotationsAll().size)

            val corrupt = File(temp, "corrupt.zip").apply { writeText("not a ZIP") }
            val before = dbDestination.backupDao().booksAll()
            try {
                CompleteBackupManager(ctx, dbDestination, "0.1.0")
                    .restoreFrom(FileProvider.getUriForFile(
                        ctx, ctx.packageName + ".testbackup", corrupt))
                fail("Damaged ZIP must be rejected")
            } catch (_: IllegalArgumentException) {
                // No DB rows must be changed by an invalid archive.
            }
            assertEquals(before, dbDestination.backupDao().booksAll())
        } finally {
            dbDestination.close()
            dbSource.close()
            internal.deleteRecursively()
            temp.deleteRecursively()
        }
    }
}
