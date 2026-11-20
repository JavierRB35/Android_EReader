package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.BookTagCrossRef
import com.javierrb35.bibliotecaia.data.TagEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.not
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Regression for the user's real interaction, not just repository APIs:
 * uncheck an existing tag inside the book details dialog, save, and re-open.
 * The global tag must survive. No other book or library data is modified.
 */
@RunWith(AndroidJUnit4::class)
class BookDetailUnassignDeviceTest {
    @Test
    fun uncheckTagInBookDialogRemovesOnlyItsLinkAndStaysOnDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.get(context)
        val bookId = UUID.randomUUID().toString()
        val tagId = UUID.randomUUID().toString()
        val tagName = "Etiqueta UX " + UUID.randomUUID().toString()
        runBlocking {
            db.bookDao().insert(
                BookEntity(
                    id = bookId,
                    title = "Libro de prueba de retirada",
                    authors = "Autor de prueba",
                    format = BookFormat.EPUB,
                    fileName = "archivo.epub",
                    localPath = "",
                    contentHash = bookId.replace("-", ""),
                    sizeBytes = 100L,
                    addedAt = System.currentTimeMillis()
                )
            )
            db.tagDao().insert(TagEntity(tagId, tagName))
            db.refsDao().addBookTag(BookTagCrossRef(bookId, tagId))
        }

        val activity = instrumentation.startActivitySync(
            Intent(context, BookDetailActivity::class.java).apply {
                putExtra("book_id", bookId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        ) as BookDetailActivity

        try {
            instrumentation.waitForIdleSync()
            onView(withId(R.id.btnEditTags)).perform(click())
            onView(withText(tagName)).check(matches(isChecked()))
            onView(withText(tagName)).perform(click())
            onView(withText(R.string.detail_assignment_save)).perform(click())

            runBlocking {
                repeat(50) {
                    if (db.refsDao().tagIdsForBook(bookId).isEmpty()) return@runBlocking
                    delay(100)
                }
            }
            runBlocking {
                assertFalse("Tag link must be removed from this book", tagId in db.refsDao().tagIdsForBook(bookId))
                assertTrue("Unassign must not delete the tag itself",
                    db.tagDao().allOnce().any { it.id == tagId && it.name == tagName })
            }
            assertFalse("The user must stay on the book details screen", activity.isFinishing)

            onView(withId(R.id.btnEditTags)).perform(click())
            onView(withText(tagName)).check(matches(not(isChecked())))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            runBlocking {
                db.refsDao().removeBookTag(bookId, tagId)
                db.tagDao().deleteById(tagId)
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM books WHERE id = ?", arrayOf(bookId)
                )
            }
        }
    }
}
