package com.javierrb35.bibliotecaia.backup

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Proves that each new export replaces ONE existing content URI, not another ZIP. */
@RunWith(AndroidJUnit4::class)
class SingleFileBackupInstrumentedTest {
    @Test fun calibreLegacyZipWithVerifiedOriginalIsRestoredWithoutDuplicates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val id = UUID.randomUUID().toString()
        val tag = UUID.randomUUID().toString()
        val dir = File(context.cacheDir, "backup-test/calibre-" + id).apply { mkdirs() }
        val zip = File(dir, "Migracion-001.zip")
        val bytes = "%PDF-1.4 synthetic calibre original".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        // The Windows migrator emits BackupCodec v2 metadata first, then
        // books/<uuid>.bin, without a named-books-v1 layout flag.
        val json = """{
            "manifest":{"app":"biblioteca-ia","formatVersion":2,"appVersion":"CalibreMigrator-1",
            "exportedAt":100,"counts":{"books":1,"annotations":0,"versions":0,"positions":0,"synopses":0}},
            "books":[{"id":"$id","title":"Libro Calibre","authors":"Autor",
            "format":"PDF","file_name":"Libro.pdf","content_hash":"$hash","size_bytes":${bytes.size},
            "added_at":100,"last_opened_at":null,"reading_status":"PENDING","favorite":false,"progress":0.0}],
            "categories":[],"tags":[{"id":"$tag","name":"Historia"}],"collections":[],
            "bookCategories":[],"bookTags":[{"first":"$id","second":"$tag"}],"collectionBooks":[],
            "annotations":[],"annotationVersions":[],"readingPositions":[],"bookSynopses":[],"deletedBooks":[]
        }""".trimIndent()
        try {
            java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(java.util.zip.ZipEntry("biblioteca.json"))
                out.write(json.toByteArray(Charsets.UTF_8))
                out.closeEntry()
                out.putNextEntry(java.util.zip.ZipEntry("books/$id.bin"))
                out.write(bytes)
                out.closeEntry()
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".testbackup", zip)
            val manager = CompleteBackupManager(context, db, "test")
            assertEquals(1, manager.restoreFrom(uri).originals)
            val restored = requireNotNull(db.bookDao().findByHash(hash))
            assertEquals("Libro Calibre", restored.title)
            assertEquals("Autor", restored.authors)
            assertArrayEquals(bytes, File(restored.localPath).readBytes())
            manager.restoreFrom(uri)
            assertEquals(1, db.backupDao().booksAll().size)
            assertTrue(zip.isFile)
        } finally {
            db.close()
            dir.deleteRecursively()
            File(context.filesDir, "books/$id").deleteRecursively()
        }
    }

    @Test fun reuseSameDocumentAndPreservePreviousIfPreparingBackupFails() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val testDir = File(context.cacheDir, "backup-test").apply { mkdirs() }
        val target = File(testDir, "BibliotecaIA-completa-" + UUID.randomUUID() + ".zip")
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()
        val bookDir1 = File(context.filesDir, "books/" + id1).apply { mkdirs() }
        val bookDir2 = File(context.filesDir, "books/" + id2).apply { mkdirs() }
        val original1 = File(bookDir1, "uno.epub").apply { writeText("EPUB de prueba propio") }
        val original2 = File(bookDir2, "dos.pdf")
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".testbackup", target
        )
        val manager = CompleteBackupManager(context, db, "0.1.0")
        try {
            fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }

            db.bookDao().insert(BookEntity(id1, "Uno", null, BookFormat.EPUB,
                original1.name, original1.absolutePath, hash(original1),
                original1.length(), System.currentTimeMillis()))
            assertEquals(1, manager.exportTo(uri).books)
            val before = target.readBytes()
            assertTrue(before.isNotEmpty())

            // An absent second original must preserve the first and all metadata.
            // The resulting archive is explicitly partial, not a complete claim.
            db.bookDao().insert(BookEntity(id2, "Dos", null, BookFormat.PDF,
                original2.name, original2.absolutePath, MessageDigest.getInstance("SHA-256")
                    .digest("PDF de prueba propio".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) },
                "PDF de prueba propio".toByteArray().size.toLong(), System.currentTimeMillis()))
            val partial = manager.exportTo(uri, replaceExisting = true)
            assertEquals(1, partial.books)
            assertEquals(listOf("Dos"), partial.missingTitles)
            val partialDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val restored = CompleteBackupManager(context, partialDb, "0.1.0").restoreFrom(uri)
                assertEquals(1, restored.originals)
                assertEquals(listOf("Dos"), restored.missingTitles)
                assertEquals(2, partialDb.backupDao().booksAll().size)
                assertTrue(partialDb.bookDao().findById(id2)!!.localPath.isBlank())
                assertEquals(hash(original1), hash(File(partialDb.bookDao().findById(id1)!!.localPath)))
            } finally { partialDb.close() }
            val partialBytes = target.readBytes()
            // A present but corrupt file is not a declared absence; preserve ZIP.
            original2.writeText("X".repeat("PDF de prueba propio".toByteArray().size))
            try {
                manager.exportTo(uri, replaceExisting = true)
                fail("Corrupt original should abort before truncating the old ZIP")
            } catch (_: IllegalArgumentException) {
                assertArrayEquals(partialBytes, target.readBytes())
            }

            original2.writeText("PDF de prueba propio")
            db.bookDao().update(BookEntity(id2, "Dos", null, BookFormat.PDF,
                original2.name, original2.absolutePath, hash(original2),
                original2.length(), System.currentTimeMillis()))
            assertEquals(2, manager.exportTo(uri, replaceExisting = true).books)
            val after = target.readBytes()
            assertFalse(before.contentEquals(after))
            assertFalse(manager.hasPendingRecovery())
            assertEquals(1, testDir.listFiles()?.count { it.extension == "zip" })

            // Simulate eviction after the central archive was verified.
            val firstHash = hash(original1)
            assertTrue(original1.delete())
            assertEquals(1, db.bookDao().clearLocalPath(id1, original1.absolutePath))
            assertEquals(2, manager.exportTo(uri, replaceExisting = true).books)
            assertFalse(original1.exists())
            val metadataOnlyDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val secondDevice = CompleteBackupManager(context, metadataOnlyDb, "0.1.0")
                assertEquals(0, secondDevice.restoreFrom(uri, emptySet()).originals)
                assertEquals(2, metadataOnlyDb.backupDao().booksAll().size)
                assertTrue(metadataOnlyDb.backupDao().booksAll().all { it.localPath.isBlank() })
                assertEquals(1, secondDevice.restoreFrom(uri, setOf(firstHash)).originals)
                val downloaded = metadataOnlyDb.bookDao().findById(id1)!!
                assertEquals(firstHash, hash(File(downloaded.localPath)))
                assertTrue(metadataOnlyDb.bookDao().findById(id2)!!.localPath.isBlank())
            } finally { metadataOnlyDb.close() }
            val stage = File(testDir, "stage-" + UUID.randomUUID()).apply { mkdirs() }
            try {
                val contents = target.inputStream().use { CompleteBackupArchive.read(it, stage) }
                assertEquals(2, BackupCodec.fromJson(contents.json).books.size)
            } finally {
                stage.deleteRecursively()
            }
        } finally {
            db.close()
            target.delete()
            bookDir1.deleteRecursively()
            bookDir2.deleteRecursively()
        }
    }
}
