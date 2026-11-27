package com.javierrb35.bibliotecaia.ai

import android.app.Instrumentation
import android.content.Intent
import android.view.View
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
import com.javierrb35.bibliotecaia.data.BookSynopsisEntity
import com.javierrb35.bibliotecaia.ui.BookDetailActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Tests the actual user actions without any real API key or fabricated synopsis. */
@RunWith(AndroidJUnit4::class)
class BookSynopsisActionDeviceTest {
    private fun book(id: String) = BookEntity(
        id = id, title = "Libro para el argumento", authors = "Autora",
        format = BookFormat.EPUB, fileName = "sample.epub", localPath = "",
        contentHash = id.replace("-", ""), sizeBytes = 0, addedAt = 1
    )

    @Test fun missingSynopsisButtonIsBelowCollectionsAndRequestsAiAutomatically() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.get(context)
        val id = UUID.randomUUID().toString()
        runBlocking { db.bookDao().insert(book(id)) }
        val activity = startDetail(instrumentation, id)
        val monitor = Instrumentation.ActivityMonitor(AiChatActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        try {
            awaitLabel(instrumentation, activity, R.string.detail_synopsis_add)
            val coordsButton = IntArray(2)
            val coordsCollections = IntArray(2)
            instrumentation.runOnMainSync {
                activity.findViewById<View>(R.id.btnSynopsis).getLocationOnScreen(coordsButton)
                activity.findViewById<View>(R.id.btnEditCollections).getLocationOnScreen(coordsCollections)
            }
            assertTrue("Synopsis CTA must follow Collections", coordsButton[1] > coordsCollections[1])
            onView(withId(R.id.btnSynopsis)).perform(click())
            val ai = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
            assertNotNull("Añadir argumento must launch the AI request", ai)
            assertTrue(ai.intent.getBooleanExtra("generate_synopsis", false))
            instrumentation.runOnMainSync { ai.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
            finishAndClean(instrumentation, activity, db, id)
        }
    }

    @Test fun savedArgumentCanBeReadEditedAndDeletedOnlyAfterConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.get(context)
        val id = UUID.randomUUID().toString()
        runBlocking {
            db.bookDao().insert(book(id))
            db.synopsisDao().save(BookSynopsisEntity(
                id, "Argumento inicial", "gemini", System.currentTimeMillis(), true
            ))
        }
        val activity = startDetail(instrumentation, id)
        try {
            awaitLabel(instrumentation, activity, R.string.detail_synopsis_view)
            onView(withId(R.id.btnSynopsis)).perform(click())
            onView(withText("Argumento inicial")).check(matches(isDisplayed()))
            onView(withText(R.string.detail_synopsis_edit)).perform(click())
            onView(withHint(R.string.detail_synopsis_hint)).perform(
                replaceText("Argumento revisado"), closeSoftKeyboard())
            onView(withText(R.string.detail_synopsis_save)).perform(click())
            awaitText(db, id, "Argumento revisado")
            onView(withId(R.id.btnSynopsis)).perform(click())
            onView(withText("Argumento revisado")).check(matches(isDisplayed()))
            onView(withText(R.string.detail_synopsis_delete)).perform(click())
            onView(withText(R.string.detail_synopsis_delete_message)).check(matches(isDisplayed()))
            onView(withText(R.string.dialog_cancel)).perform(click())
            assertNotNull(runBlocking { db.synopsisDao().byBook(id) })
            // The reading modal survives cancelled deletion.
            onView(withText(R.string.detail_synopsis_delete)).perform(click())
            onView(withText(R.string.detail_synopsis_delete_message)).check(matches(isDisplayed()))
            onView(withId(android.R.id.button1)).perform(click())
            awaitLabel(instrumentation, activity, R.string.detail_synopsis_add)
            assertNull(runBlocking { db.synopsisDao().byBook(id) })
            assertNotNull(runBlocking { db.bookDao().findById(id) })
        } finally {
            finishAndClean(instrumentation, activity, db, id)
        }
    }

    private fun startDetail(instrumentation: Instrumentation, bookId: String) =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, BookDetailActivity::class.java)
                .putExtra("book_id", bookId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as BookDetailActivity

    private fun awaitLabel(instrumentation: Instrumentation, activity: BookDetailActivity, resId: Int) {
        repeat(70) {
            instrumentation.waitForIdleSync()
            var value: String? = null
            instrumentation.runOnMainSync {
                value = activity.findViewById<TextView>(R.id.txtSynopsisAction).text.toString()
            }
            if (value == activity.getString(resId)) return
            Thread.sleep(75)
        }
        fail("El botón no cambió a: " + activity.getString(resId))
    }

    private fun awaitText(db: AppDatabase, bookId: String, text: String) {
        repeat(70) {
            if (runBlocking { db.synopsisDao().byBook(bookId)?.text } == text) return
            Thread.sleep(75)
        }
        fail("El texto editado no quedó guardado")
    }

    private fun finishAndClean(
        instrumentation: Instrumentation, activity: BookDetailActivity,
        db: AppDatabase, bookId: String
    ) {
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM book_synopses WHERE bookId=?", arrayOf(bookId)
        )
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM books WHERE id=?", arrayOf(bookId)
        )
    }
}
