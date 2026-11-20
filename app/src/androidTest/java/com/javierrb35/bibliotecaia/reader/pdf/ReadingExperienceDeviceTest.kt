package com.javierrb35.bibliotecaia.reader.pdf

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.ReadingRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genuine on-device SQLite reopen, two distinct books and persisted locator/
 * annotations. Does not substitute for a full Activity/process-death UI test.
 */
@RunWith(AndroidJUnit4::class)
class ReadingExperienceDeviceTest {
    @Test fun twoBookPositionsAndPdfHighlightSurviveSqliteCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-reader-persistence-" + System.nanoTime() + ".db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val oneId = "pdf-book-one"
        val twoId = "epub-book-two"
        val hash = "sha256-pdf-same-edition"
        var savedMark = ""
        val locator = """{"format":"PDF","page":7}"""
        try {
            val first = open()
            try {
                val db = first
                db.bookDao().insert(BookEntity(
                    id = oneId, title = "Documento A", authors = null,
                    format = BookFormat.PDF, fileName = "a.pdf", localPath = "",
                    contentHash = hash, sizeBytes = 100, addedAt = 1
                ))
                db.bookDao().insert(BookEntity(
                    id = twoId, title = "Obra B", authors = null,
                    format = BookFormat.EPUB, fileName = "b.epub", localPath = "",
                    contentHash = "another-edition", sizeBytes = 100, addedAt = 2
                ))
                val repo = ReadingRepository(db)
                repo.savePosition(oneId, locator, .5f)
                repo.savePosition(twoId, """{"href":"chapter3","type":"EPUB"}""", .8f)
                val snapshot = requireNotNull(PdfOcrGeometry.fromLines(
                    listOf(listOf("Hola" to PdfOcrRect(.10f, .20f, .30f, .25f))), hash))
                val marked = requireNotNull(PdfOcrAnchors.anchoredLocator(
                    7, 0, "Hola", snapshot))
                savedMark = repo.addAnnotation(oneId, marked, "Hola", "", "HIGHLIGHT")
            } finally { first.close() }
            val second = open()
            try {
                val db = second
                val repo = ReadingRepository(db)
                assertEquals(locator, repo.position(oneId)?.locator)
                assertEquals(.5f, repo.position(oneId)!!.progress, 0f)
                assertEquals(.8f, repo.position(twoId)!!.progress, 0f)
                assertNotEquals(repo.position(oneId)?.locator, repo.position(twoId)?.locator)
                val mark = requireNotNull(db.readingDao().annotation(savedMark))
                assertEquals("HIGHLIGHT", mark.kind)
                assertEquals(oneId, mark.bookId)
                assertEquals(1, PdfOcrAnchors.overlayBoxes(mark.locator, 7,
                    mark.quote, hash).size)
                repo.setDeleted(mark.id, true)
                assertNotNull(db.readingDao().annotation(savedMark)?.deletedAt)
            } finally { second.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
