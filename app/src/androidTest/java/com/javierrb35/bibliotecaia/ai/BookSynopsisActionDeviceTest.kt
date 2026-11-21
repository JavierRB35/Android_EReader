package com.javierrb35.bibliotecaia.ai

import android.app.Instrumentation
import android.content.Intent
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.ui.BookDetailActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** End-to-end Room UI: add, view and edit from a book detail without an AI key. */
@RunWith(AndroidJUnit4::class)
class BookSynopsisActionDeviceTest {
    @Test fun synopsisButtonSwitchesFromAddToViewAndPreservesEditedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.get(context)
        val id = UUID.randomUUID().toString()
        runBlocking {
            db.bookDao().insert(BookEntity(
                id = id, title = "Libro para editar argumento", authors = "Autora",
                format = BookFormat.EPUB, fileName = "sample.epub", localPath = "",
                contentHash = id.replace("-", ""), sizeBytes = 0, addedAt = 1
            ))
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, BookDetailActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as BookDetailActivity
        try {
            awaitLabel(instrumentation, activity, R.string.detail_synopsis_add)
            onView(withId(R.id.btnSynopsis)).perform(click())
            onView(withHint(R.string.detail_synopsis_hint)).perform(
                replaceText("Argumento original"), closeSoftKeyboard())
            onView(withText(R.string.detail_synopsis_save)).perform(click())

            awaitLabel(instrumentation, activity, R.string.detail_synopsis_view)
            assertEquals("Argumento original", runBlocking { db.synopsisDao().byBook(id)?.text })
            onView(withId(R.id.btnSynopsis)).perform(click())
            onView(withText("Argumento original")).check(matches(isDisplayed()))
            onView(withText(R.string.detail_synopsis_edit)).perform(click())

            onView(withHint(R.string.detail_synopsis_hint)).perform(
                replaceText("Argumento revisado"), closeSoftKeyboard())
            onView(withText(R.string.detail_synopsis_save)).perform(click())
            awaitPersisted(db, id, "Argumento revisado")
            onView(withId(R.id.btnSynopsis)).perform(click())
            onView(withText("Argumento revisado")).check(matches(isDisplayed()))
            assertEquals("manual", runBlocking { db.synopsisDao().byBook(id)?.provider })
            assertFalse(runBlocking { db.synopsisDao().byBook(id)!!.spoilerSafe })
            onView(withText("Cerrar")).perform(click())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM book_synopses WHERE bookId=?", arrayOf(id))
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM books WHERE id=?", arrayOf(id))
        }
    }

    private fun awaitLabel(
        instrumentation: Instrumentation, activity: BookDetailActivity, resId: Int
    ) {
        repeat(60) {
            instrumentation.waitForIdleSync()
            var text: String? = null
            instrumentation.runOnMainSync {
                text = activity.findViewById<TextView>(R.id.txtSynopsisAction).text.toString()
            }
            if (text == activity.getString(resId)) return
            Thread.sleep(75)
        }
        fail("El botón no cambió al estado esperado: " + activity.getString(resId))
    }

    private fun awaitPersisted(db: AppDatabase, bookId: String, expected: String) {
        repeat(60) {
            if (runBlocking { db.synopsisDao().byBook(bookId)?.text } == expected) return
            Thread.sleep(75)
        }
        fail("El argumento editado no se guardó en Room")
    }
}
