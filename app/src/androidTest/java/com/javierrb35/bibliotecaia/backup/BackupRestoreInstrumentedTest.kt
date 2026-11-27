package com.javierrb35.bibliotecaia.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.AnnotationVersionEntity
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookCategoryCrossRef
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.CategoryEntity
import com.javierrb35.bibliotecaia.data.ReadingPositionEntity
import com.javierrb35.bibliotecaia.data.TagEntity
import com.javierrb35.bibliotecaia.data.BookTagCrossRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device/emulator contract: real Room transaction, no mocks or network. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreInstrumentedTest {

    private fun book(id: String, path: String = "") = BookEntity(
        id = id, title = "Don Quijote", authors = null,
        format = BookFormat.EPUB, fileName = "quijote.epub",
        localPath = path, contentHash = "same-verified-content-hash",
        sizeBytes = 567, addedAt = 100, progress = 0.10f,
    )
    private fun snapshot(): LibrarySnapshot = LibrarySnapshot(
        books = listOf(book("remote-book")),
        categories = listOf(CategoryEntity("remote-category", "Novela")),
        tags = listOf(TagEntity("remote-tag", "Clásicos")),
        collections = emptyList(),
        bookCategories = listOf(BookCategoryCrossRef("remote-book", "remote-category")),
        bookTags = listOf(BookTagCrossRef("remote-book", "remote-tag")),
        collectionBooks = emptyList(),
        annotations = listOf(AnnotationEntity("note-1", "remote-book", "chapter:1",
            "En un lugar", "Nota original", "NOTE", 1, 1, 10)),
        versions = listOf(AnnotationVersionEntity("version-1", "note-1",
            "Nota anterior", 1, 5)),
        positions = listOf(ReadingPositionEntity("remote-book", "chapter:2", 0.75f, 20)),
    )

    private inline fun withDb(block: (AppDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }

    @Test fun existingBookHashPreservesLocalFileAndRestoresNoteAndPosition() = withDb { db ->
        runBlocking {
            val dao = db.backupDao()
            val original = book("local-book", "/real/offline/book.epub")
            db.bookDao().insert(original)
            dao.insertCategoriesIgnore(listOf(CategoryEntity("local-category", "Novela")))
            dao.insertTagsIgnore(listOf(TagEntity("local-tag", "Clásicos")))
            val manager = BackupManager(db, "0.1.0")
            val json = BackupCodec.toJson(snapshot(), "0.1.0", 123)

            val report = manager.restoreJson(json)
            assertEquals(0, report.booksRestored)
            assertEquals(1, report.booksSkipped)
            assertEquals(0, report.categoriesRestored)
            assertEquals(0, report.tagsRestored)
            assertEquals(1, report.annotationsRestored)
            assertEquals(1, report.positionsUpdated)

            val restoredBook = dao.booksAll().single()
            assertEquals("local-book", restoredBook.id)
            assertEquals(original.localPath, restoredBook.localPath)
            assertEquals(0.75f, restoredBook.progress, 0f)
            assertEquals("local-book", dao.annotationsAll().single().bookId)
            assertEquals("local-book", dao.positionsAll().single().bookId)
            assertEquals(1, dao.versionsAll().size)
            assertEquals("local-category", dao.bookCategoryPairs().single().categoryId)
            assertEquals("local-tag", dao.bookTagPairs().single().tagId)

            // Idempotent: reimporting the same JSON makes no new changes.
            val again = manager.restoreJson(json)
            assertEquals(0, again.booksRestored)
            assertEquals(0, again.annotationsRestored)
            assertEquals(0, again.positionsUpdated)
            assertEquals(0, again.categoriesRestored)
            assertEquals(0, again.tagsRestored)
            assertEquals(1, dao.annotationsAll().size)
            assertEquals(1, dao.versionsAll().size)
        }
    }

    @Test fun syncUpdatesNotesAndTombstonesAndBackwardPositionsWithoutReplacingLocalPath() = withDb { db ->
        runBlocking {
            val original = book("local-book", "/real/offline/book.epub")
            db.bookDao().insert(original)
            val manager = BackupManager(db, "0.1.0")
            val initial = snapshot()
            manager.restoreJson(BackupCodec.toJson(initial, "0.1.0", 1), synchronize = true)
            val edited = initial.copy(
                annotations = listOf(initial.annotations.single().copy(body = "Editado", updatedAt = 30)),
                positions = listOf(ReadingPositionEntity("remote-book", "chapter:1", .25f, 31)))
            manager.restoreJson(BackupCodec.toJson(edited, "0.1.0", 2), synchronize = true)
            assertEquals("Editado", db.backupDao().annotationsAll().single().body)
            assertEquals(.25f, db.backupDao().positionsAll().single().progress, 0f)
            val removed = edited.copy(annotations = listOf(edited.annotations.single().copy(
                updatedAt = 40, deletedAt = 40)))
            manager.restoreJson(BackupCodec.toJson(removed, "0.1.0", 3), synchronize = true)
            manager.restoreJson(BackupCodec.toJson(initial, "0.1.0", 1), synchronize = true)
            assertEquals(40L, db.backupDao().annotationsAll().single().deletedAt)
            assertEquals(.25f, db.backupDao().positionsAll().single().progress, 0f)
            assertEquals(original.localPath, db.bookDao().findById("local-book")!!.localPath)
        }
    }

    @Test fun concurrentOfflineAnnotationEditsConvergeAndPreserveBothTexts() = withDb { dbA ->
        withDb { dbB ->
            runBlocking {
                val baseBook = book("shared-book", "/offline/quijote.epub")
                val baseNote = AnnotationEntity("note-conflict", "shared-book", "chapter:1",
                    "En un lugar", "Texto común", "NOTE", 1, 10L, 10L)
                val baseSnapshot = LibrarySnapshot(
                    books = listOf(baseBook),
                    categories = emptyList(), tags = emptyList(), collections = emptyList(),
                    bookCategories = emptyList(), bookTags = emptyList(), collectionBooks = emptyList(),
                    annotations = listOf(baseNote),
                    versions = emptyList(),
                    positions = emptyList(),
                )
                val mgrA = BackupManager(dbA, "0.1.0")
                val mgrB = BackupManager(dbB, "0.1.0")
                val baseJson = BackupCodec.toJson(baseSnapshot, "0.1.0", 10L)
                mgrA.restoreJson(baseJson, synchronize = true)
                mgrB.restoreJson(baseJson, synchronize = true)

                // Both devices edit the exact same note offline without seeing each other.
                val repoA = com.javierrb35.bibliotecaia.data.ReadingRepository(dbA)
                val repoB = com.javierrb35.bibliotecaia.data.ReadingRepository(dbB)
                repoA.editAnnotation("note-conflict", 10L, "Edición dispositivo A", 1)
                repoB.editAnnotation("note-conflict", 10L, "Edición dispositivo B", 2)
                // Force deterministic newer timestamp on B while preserving A's divergent body.
                val editedA = dbA.readingDao().annotation("note-conflict")!!.copy(updatedAt = 100L)
                val editedB = dbB.readingDao().annotation("note-conflict")!!.copy(updatedAt = 200L)
                dbA.readingDao().updateAnnotation(editedA)
                dbB.readingDao().updateAnnotation(editedB)

                val exportA = mgrA.exportJson(300L)
                val exportB = mgrB.exportJson(300L)
                mgrA.restoreJson(exportB, synchronize = true)
                mgrB.restoreJson(exportA, synchronize = true)

                // Both devices must converge to the exact same live note and retain A's losing edit.
                val finalA = dbA.readingDao().annotation("note-conflict")!!
                val finalB = dbB.readingDao().annotation("note-conflict")!!
                assertEquals("Edición dispositivo B", finalA.body)
                assertEquals(finalA.body, finalB.body)
                assertEquals(finalA.updatedAt, finalB.updatedAt)

                val versionsA = dbA.readingDao().versions("note-conflict").map { it.body }.toSet()
                val versionsB = dbB.readingDao().versions("note-conflict").map { it.body }.toSet()
                assertTrue(versionsA.containsAll(setOf("Texto común", "Edición dispositivo A")))
                assertEquals(versionsA, versionsB)

                // Idempotent: re-syncing does not duplicate conflict versions.
                val beforeCount = dbA.readingDao().versions("note-conflict").size
                mgrA.restoreJson(exportB, synchronize = true)
                assertEquals(beforeCount, dbA.readingDao().versions("note-conflict").size)
            }
        }
    }

    @Test fun brokenReferenceRollsBackEntireRestore() = withDb { db ->
        runBlocking {
            val incomplete = snapshot().copy(
                categories = emptyList(),
                bookCategories = listOf(BookCategoryCrossRef("remote-book", "unknown-category")),
            )
            val json = BackupCodec.toJson(incomplete, "0.1.0", 22)
            try {
                BackupManager(db, "0.1.0").restoreJson(json)
                fail("Expected all-or-nothing failure")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("categoría"))
            }
            assertTrue(db.backupDao().booksAll().isEmpty())
            assertTrue(db.backupDao().annotationsAll().isEmpty())
            assertTrue(db.backupDao().positionsAll().isEmpty())
        }
    }
}
