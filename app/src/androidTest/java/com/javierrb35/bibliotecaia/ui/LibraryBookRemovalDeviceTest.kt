package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryBookRemovalDeviceTest {
    @Test fun deleteLinkedBook() = verifyRemoval(true)
    @Test fun deleteBookAlreadyWaitingForOriginal() = verifyRemoval(false)

    private fun verifyRemoval(linked: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.get(context)
        val id = UUID.randomUUID().toString()
        val noteId = UUID.randomUUID().toString()
        val original = File(context.filesDir, "books/$id/sample.epub")
        if (linked) {
            original.parentFile!!.mkdirs()
            original.writeText("sample")
        }
        runBlocking {
            db.bookDao().insert(BookEntity(id, "Libro a eliminar", null, BookFormat.EPUB,
                "sample.epub", if (linked) original.absolutePath else "",
                id.replace("-", ""), original.length(), System.currentTimeMillis()))
            db.readingDao().savePosition(ReadingPositionEntity(id, "{}", 0.4f, 1L))
            db.readingDao().insertAnnotation(AnnotationEntity(noteId, id, "{}", "cita",
                "nota", "NOTE", 0, 1L, 1L))
            db.readingDao().insertVersion(AnnotationVersionEntity(UUID.randomUUID().toString(),
                noteId, "anterior", 0, 1L))
        }
        val activity = instrumentation.startActivitySync(Intent(context, BookDetailActivity::class.java)
            .putExtra("book_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BookDetailActivity
        try {
            instrumentation.waitForIdleSync()
            onView(withId(R.id.btnDeleteBook)).perform(scrollTo(), click())
            onView(withText(R.string.detail_delete_book_button)).inRoot(isDialog()).perform(click())
            runBlocking {
                repeat(60) {
                    if (db.bookDao().findById(id) == null) return@runBlocking
                    delay(100)
                }
            }
            runBlocking {
                assertNull(db.bookDao().findById(id))
                assertFalse(db.backupDao().booksAll().any { it.id == id })
                assertNull(db.readingDao().position(id))
                assertNull(db.readingDao().annotation(noteId))
                assertTrue(db.readingDao().versions(noteId).isEmpty())
            }
            assertFalse(original.exists())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            runBlocking {
                com.javierrb35.bibliotecaia.importer.LibraryBookRemover.delete(db, context.filesDir, id)
            }
            original.delete()
            original.parentFile?.delete()
        }
    }
}
