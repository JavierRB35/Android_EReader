@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.javierrb35.bibliotecaia.reader.epub

import android.content.Intent
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.ui.AnnotationEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Actual EPUB Activity + TextView annotations, reopening the stored book and
 * switching native/Readium reader surfaces without losing the native anchor.
 *
 * Readium cannot safely render all native UTF-16 offsets as DOM-CFI decorations.
 * This test does NOT claim annotation parity across the two representations.
 */
@RunWith(AndroidJUnit4::class)
class EpubReadingJourneyDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val database get() = AppDatabase.get(context)

    private fun waitUntil(message: String, check: () -> Boolean) {
        repeat(120) {
            instrumentation.waitForIdleSync()
            if (check()) return
            Thread.sleep(100)
        }
        fail(message)
    }

    private fun chapter(activity: EpubReaderActivity): NativeEpubText.Chapter? {
        val field = EpubReaderActivity::class.java.getDeclaredField("nativeChapter")
        field.isAccessible = true
        return field.get(activity) as? NativeEpubText.Chapter
    }

    private fun open(id: String): EpubReaderActivity {
        val activity = instrumentation.startActivitySync(
            Intent(context, EpubReaderActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as EpubReaderActivity
        waitUntil("EPUB did not show a selectable real native text chapter") {
            val view = activity.findViewById<TextView>(R.id.epub_native_text)
            view.visibility == View.VISIBLE &&
                view.isTextSelectable && view.text.isNotBlank() &&
                chapter(activity)?.text == view.text.toString()
        }
        return activity
    }

    private fun marked(text: CharSequence, quote: String): Boolean {
        if (text !is Spanned) return false
        val begin = text.indexOf(quote)
        if (begin < 0) return false
        val end = begin + quote.length
        return text.getSpans(begin, end, BackgroundColorSpan::class.java).any {
            text.getSpanStart(it) <= begin && text.getSpanEnd(it) >= end
        } && text.getSpans(begin, end, UnderlineSpan::class.java).any {
            text.getSpanStart(it) <= begin && text.getSpanEnd(it) >= end
        }
    }

    private fun findToggle(root: View): TextView? {
        if (root is TextView && root.text in listOf("Maquetación", "Selección precisa")) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            findToggle(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test fun noteRendersInRealEpubAndSurvivesCloseReopenModeSwitchEditAndDelete() {
        val id = UUID.randomUUID().toString()
        val epub = File.createTempFile("reader-epub-journey-", ".epub", context.cacheDir)
        instrumentation.context.assets.open("reader-sample.epub").use { input ->
            epub.outputStream().use { input.copyTo(it) }
        }
        val prefs = context.getSharedPreferences("epub_reader", android.content.Context.MODE_PRIVATE)
        val wasNative = prefs.getBoolean("native_selection", true)
        prefs.edit().putBoolean("native_selection", true).commit()
        runBlocking {
            database.bookDao().insert(BookEntity(
                id = id, title = "EPUB prueba de anotación", authors = null,
                format = BookFormat.EPUB, fileName = epub.name,
                localPath = epub.absolutePath, contentHash = id.replace("-", ""),
                sizeBytes = epub.length(), addedAt = System.currentTimeMillis()
            ))
        }
        var active: EpubReaderActivity? = null
        var noteId: String? = null
        try {
            val reading = open(id)
            active = reading
            val first = requireNotNull(chapter(reading))
            val start = first.text.indexOfFirst { it.isLetterOrDigit() }.coerceAtLeast(0)
            val quote = first.text.substring(start, (start + 24).coerceAtMost(first.text.length))
            assertTrue(quote.isNotBlank())
            val locator = NativeEpubAnchors.locator(
                first.href, first.title, start, first.text.length, 0.0, quote
            )
            instrumentation.runOnMainSync {
                AnnotationEditor.show(reading, id, locator, quote, "NOTE")
            }
            onView(withHint("Tu reflexión (opcional al guardar un fragmento)"))
                .perform(replaceText("Nota EPUB real"), closeSoftKeyboard())
            onView(withText("Guardar")).perform(click())

            waitUntil("Saved EPUB note was not shown over its exact TextView selection") {
                val note = runBlocking {
                    database.readingDao().allNotes().firstOrNull {
                        it.bookId == id && it.quote == quote && it.deletedAt == null
                    }
                }
                noteId = note?.id
                note != null && marked(reading.findViewById<TextView>(R.id.epub_native_text).text, quote)
            }
            instrumentation.runOnMainSync { reading.finish() }
            instrumentation.waitForIdleSync()
            active = null

            val reopened = open(id)
            active = reopened
            waitUntil("Saved NOTE is no longer highlighted after reopening EPUB") {
                marked(reopened.findViewById<TextView>(R.id.epub_native_text).text, quote)
            }
            val switch = requireNotNull(findToggle(reopened.window.decorView))
            instrumentation.runOnMainSync { switch.performClick() }
            waitUntil("Maquetación Readium was not shown after switching modes") {
                reopened.findViewById<View>(R.id.epub_navigator).visibility == View.VISIBLE &&
                    switch.text == "Selección precisa"
            }
            instrumentation.runOnMainSync { switch.performClick() }
            waitUntil("Returning to native mode lost the EPUB NOTE marker") {
                switch.text == "Maquetación" &&
                    marked(reopened.findViewById<TextView>(R.id.epub_native_text).text, quote)
            }

            val saved = requireNotNull(runBlocking { database.readingDao().annotation(noteId!!) })
            instrumentation.runOnMainSync { AnnotationEditor.edit(reopened, saved) }
            onView(withHint("Tu reflexión (opcional al guardar un fragmento)"))
                .perform(replaceText("Nota EPUB corregida"), closeSoftKeyboard())
            onView(withText("Guardar")).perform(click())
            waitUntil("Edited EPUB note did not persist") {
                runBlocking { database.readingDao().annotation(noteId!!)?.body } ==
                    "Nota EPUB corregida"
            }
            assertEquals(1, runBlocking { database.readingDao().versions(noteId!!).size })
            runBlocking { ReadingRepository(context).setDeleted(noteId!!, true) }
            waitUntil("Deleted EPUB NOTE still paints the text") {
                !marked(reopened.findViewById<TextView>(R.id.epub_native_text).text, quote)
            }
        } finally {
            active?.let { activity ->
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
            prefs.edit().putBoolean("native_selection", wasNative).commit()
            runBlocking {
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM annotation_versions WHERE annotationId IN (SELECT id FROM annotations WHERE bookId=?)", arrayOf(id))
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM annotations WHERE bookId=?", arrayOf(id))
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM reading_positions WHERE bookId=?", arrayOf(id))
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM books WHERE id=?", arrayOf(id))
            }
            epub.delete()
        }
    }
}
