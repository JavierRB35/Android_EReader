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

            // Broken second original: the existing ZIP MUST NOT be touched.
            db.bookDao().insert(BookEntity(id2, "Dos", null, BookFormat.PDF,
                original2.name, original2.absolutePath, "0".repeat(64),
                3L, System.currentTimeMillis()))
            try {
                manager.exportTo(uri, replaceExisting = true)
                fail("Missing original should abort before truncating the old ZIP")
            } catch (_: IllegalArgumentException) {
                assertArrayEquals(before, target.readBytes())
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
