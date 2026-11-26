package com.javierrb35.bibliotecaia.reader.annotations

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
    @Test fun twoBookPositionsAndHighlightSurviveSqliteCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-reader-persistence-" + System.nanoTime() + ".db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val oneId = "mobi-book-one"
        val twoId = "epub-book-two"
        val hash = "sha256-mobi-same-edition"
        var savedMark = ""
        val locator = """{"format":"MOBI","offset":7}"""
        try {
            val first = open()
            try {
                val db = first
                db.bookDao().insert(BookEntity(
                    id = oneId, title = "Documento A", authors = null,
                    format = BookFormat.MOBI, fileName = "a.mobi", localPath = "",
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
                val marked = """{"format":"MOBI","offset":7,"end":11}"""
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
                assertEquals("""{"format":"MOBI","offset":7,"end":11}""", mark.locator)
                assertEquals("Hola", mark.quote)
                repo.setDeleted(mark.id, true)
                assertNotNull(db.readingDao().annotation(savedMark)?.deletedAt)
            } finally { second.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
