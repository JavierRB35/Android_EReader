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
import com.javierrb35.bibliotecaia.Epub2RegressionFixture
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
import java.io.File
import java.util.UUID

/** Tests the actual user actions without any real API key or fabricated synopsis. */
@RunWith(AndroidJUnit4::class)
class BookSynopsisActionDeviceTest {
    private fun book(id: String, localPath: String = "") = BookEntity(
        id = id, title = "Libro para el argumento", authors = "Autora",
        format = BookFormat.EPUB, fileName = "sample.epub", localPath = localPath,
        contentHash = id.replace("-", ""), sizeBytes = 0, addedAt = 1
    )

    /** Sin argumento guardado, «Obtener argumento» sigue a Colecciones y pide la IA con copia local. */
    @Test fun missingSynopsisObtainActionIsBelowCollectionsAndRequestsWithLocalCopy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.get(context)
        val id = UUID.randomUUID().toString()
        // La generación IA inmediata solo se ofrece con copia local del libro.
        val local = File.createTempFile("action-synopsis-", ".epub", context.cacheDir)
        Epub2RegressionFixture.write(local)
        runBlocking { db.bookDao().insert(book(id, local.absolutePath)) }
        val activity = startDetail(instrumentation, id)
        val monitor = Instrumentation.ActivityMonitor(AiChatActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        try {
            awaitVisible(instrumentation, activity, R.id.btnSynopsisObtain)
            val coordsButton = IntArray(2)
            val coordsCollections = IntArray(2)
            instrumentation.runOnMainSync {
                activity.findViewById<View>(R.id.btnSynopsisObtain).getLocationOnScreen(coordsButton)
                activity.findViewById<View>(R.id.btnEditCollections).getLocationOnScreen(coordsCollections)
            }
            assertTrue("La sección Argumento debe seguir a Colecciones", coordsButton[1] > coordsCollections[1])
            onView(withId(R.id.btnSynopsisObtain)).perform(click())
            val ai = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
            assertNotNull("Obtener argumento debe lanzar la petición IA con copia local", ai)
            assertTrue(ai.intent.getBooleanExtra("generate_synopsis", false))
            instrumentation.runOnMainSync { ai.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
            finishAndClean(instrumentation, activity, db, id)
            local.delete()
        }
    }

    @Test fun savedArgumentIsVisibleEditableAndDeletableOnlyAfterConfirmation() {
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
            awaitBody(instrumentation, activity, "Argumento inicial")
            // Visible directamente: el propio texto del argumento aparece en la ficha.
            onView(withText("Argumento inicial")).check(matches(isDisplayed()))
            onView(withId(R.id.btnSynopsisEdit)).perform(click())
            onView(withHint(R.string.detail_synopsis_hint)).perform(
                replaceText("Argumento revisado"), closeSoftKeyboard())
            onView(withText(R.string.detail_synopsis_save)).perform(click())
            awaitText(db, id, "Argumento revisado")
            awaitBody(instrumentation, activity, "Argumento revisado")
            onView(withId(R.id.btnSynopsisDelete)).perform(click())
            onView(withText(R.string.detail_synopsis_delete_message)).check(matches(isDisplayed()))
            onView(withText(R.string.dialog_cancel)).perform(click())
            assertNotNull(runBlocking { db.synopsisDao().byBook(id) })
            // La cancelación conserva el argumento visible.
            onView(withText("Argumento revisado")).check(matches(isDisplayed()))
            onView(withId(R.id.btnSynopsisDelete)).perform(click())
            onView(withText(R.string.detail_synopsis_delete_message)).check(matches(isDisplayed()))
            onView(withId(android.R.id.button1)).perform(click())
            awaitBody(instrumentation, activity,
                activity.getString(R.string.detail_synopsis_empty))
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

    private fun awaitVisible(instrumentation: Instrumentation, activity: BookDetailActivity, resId: Int) {
        repeat(70) {
            instrumentation.waitForIdleSync()
            var visible = false
            instrumentation.runOnMainSync {
                visible = activity.findViewById<View>(resId).visibility == View.VISIBLE
            }
            if (visible) return
            Thread.sleep(75)
        }
        fail("La vista no se hizo visible: " + activity.getString(resId))
    }

    private fun awaitBody(instrumentation: Instrumentation, activity: BookDetailActivity, expected: String) {
        repeat(70) {
            instrumentation.waitForIdleSync()
            var value: String? = null
            instrumentation.runOnMainSync {
                value = activity.findViewById<TextView>(R.id.btnSynopsisBody).text.toString()
            }
            if (value == expected) return
            Thread.sleep(75)
        }
        fail("El argumento visible no cambió a: $expected")
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
            "DELETE FROM book_classifications WHERE bookId=?", arrayOf(bookId)
        )
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM books WHERE id=?", arrayOf(bookId)
        )
    }
}
