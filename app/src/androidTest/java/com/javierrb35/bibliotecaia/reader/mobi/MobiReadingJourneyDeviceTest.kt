package com.javierrb35.bibliotecaia.reader.mobi

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import android.text.Spanned
import android.text.Spannable
import com.javierrb35.bibliotecaia.reader.annotations.AnnotationHitSpan
import com.javierrb35.bibliotecaia.reader.annotations.MarkedTextTap
import com.javierrb35.bibliotecaia.reader.annotations.PlatformNoteTap
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.ui.AnnotationEditor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Genuine reader view + native UI editor + PDF-independent MOBI text spans,
 * then close/reopen two independent books through the actual Activities.
 * This complements PR #18 (editor/Knowledge only) without duplicating it.
 */
@RunWith(AndroidJUnit4::class)
class MobiReadingJourneyDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val database get() = AppDatabase.get(context)
    private val notes get() = ReadingRepository(context)

    private fun fixture(title: String): File {
        val html = "<html><body><p>" +
            ((title + " contiene un texto de lectura y anotaciones verificables. ")
                .repeat(95)) + "</p></body></html>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        val first = 96
        val headerSize = 244
        val data = ByteBuffer.allocate(first + headerSize + bytes.size)
            .order(ByteOrder.BIG_ENDIAN).apply {
                position(60); put("BOOKMOBI".toByteArray(Charsets.US_ASCII))
                putShort(76, 2); putInt(78, first); putInt(86, first + headerSize)
                putShort(first, 1); putInt(first + 4, bytes.size)
                putShort(first + 8, 1); putShort(first + 10, bytes.size.toShort())
                position(first + 16); put("MOBI".toByteArray(Charsets.US_ASCII))
                putInt(first + 20, headerSize - 16)
                putInt(first + 28, 65001); putInt(first + 36, 6)
                position(first + headerSize); put(bytes)
            }.array()
        return File.createTempFile("mobi-journey-", ".mobi", context.cacheDir).apply {
            writeBytes(data)
        }
    }

    private fun open(id: String): MobiReaderActivity {
        val activity = instrumentation.startActivitySync(
            Intent(context, MobiReaderActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MobiReaderActivity
        waitUntil("MOBI text was never rendered") {
            val s = findScroll(activity)
            s != null && (((s.getChildAt(0) as? TextView)?.text?.length ?: 0) > 100)
        }
        return activity
    }

    private fun findScroll(activity: MobiReaderActivity): ScrollView? {
        fun visit(view: View): ScrollView? {
            if (view is ScrollView && view.getChildAt(0) is TextView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                visit(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        return visit(activity.window.decorView)
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        repeat(90) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(90)
        }
        fail(message)
    }

    private fun setup(id: String, file: File) = runBlocking {
        database.bookDao().insert(BookEntity(
            id = id, title = "Libro $id", authors = null,
            format = BookFormat.MOBI, fileName = file.name,
            localPath = file.absolutePath, contentHash = id.replace("-", ""),
            sizeBytes = file.length(), addedAt = System.currentTimeMillis()
        ))
    }

    private fun finish(activity: MobiReaderActivity) {
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    private fun exactMark(text: CharSequence, quote: String): Boolean {
        if (text !is Spanned) return false
        val start = text.indexOf(quote)
        if (start < 0) return false
        val end = start + quote.length
        return text.getSpans(start, end, BackgroundColorSpan::class.java)
            .any { text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end } &&
            text.getSpans(start, end, UnderlineSpan::class.java)
                .any { text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end }
    }

    @Test fun adjustsFontRepeatedlyWithoutReopeningMenu() {
        val id = UUID.randomUUID().toString()
        val file = fixture("Tamaño de lectura")
        val prefs = context.getSharedPreferences("mobi_reader", android.content.Context.MODE_PRIVATE)
        val original = prefs.all["size"] as? Float
        prefs.edit().putFloat("size", 20f).commit()
        setup(id, file)
        var activity: MobiReaderActivity? = null
        try {
            val opened = open(id)
            activity = opened
            val reader = requireNotNull(findScroll(opened)).getChildAt(0) as TextView
            val before = reader.textSize
            onView(withText("Aa")).perform(click())
            repeat(2) { onView(withText(R.string.reader_font_increase)).inRoot(isDialog()).perform(click()) }
            assertEquals(24f, prefs.getFloat("size", 20f), .01f)
            assertTrue(reader.textSize > before)
            onView(withText(R.string.reader_font_decrease)).inRoot(isDialog()).perform(click())
            assertEquals(22f, prefs.getFloat("size", 20f), .01f)
            onView(withText(R.string.reader_settings_done)).inRoot(isDialog()).perform(click())
        } finally {
            activity?.let(::finish)
            if (original == null) prefs.edit().remove("size").commit() else prefs.edit().putFloat("size", original).commit()
            file.delete()
        }
    }

    @Test fun annotationPaintsInBookAndSurvivesReopenWithIndependentPositions() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val fileA = fixture("Primer libro")
        val fileB = fixture("Segundo libro")
        setup(a, fileA); setup(b, fileB)
        var first: MobiReaderActivity? = null
        var second: MobiReaderActivity? = null
        var reopened: MobiReaderActivity? = null
        var annotationId: String? = null
        try {
            val readingA = open(a)
            first = readingA
            val scroll = requireNotNull(findScroll(readingA))
            val displayed = (scroll.getChildAt(0) as TextView).text.toString()
            // The exact selected range is anchored to the real rendered text.
            val quoteOffset = displayed.indexOf("anotaciones verificables")
            assertTrue(quoteOffset > 0)
            val quote = "anotaciones verificables"
            val locator = JSONObject().put("format", "MOBI")
                .put("textVersion", 1).put("offset", quoteOffset).toString()
            instrumentation.runOnMainSync {
                AnnotationEditor.show(readingA, a, locator, quote, "NOTE")
            }
            onView(withHint("Tu reflexión (opcional al guardar un fragmento)"))
                .inRoot(isDialog())
                .perform(replaceText("Primera reflexión"), closeSoftKeyboard())
            onView(withText("Guardar")).inRoot(isDialog()).perform(click())

            waitUntil("The MOBI NOTE was not stored or drawn on its selected words") {
                val saved = runBlocking { database.readingDao().allNotes().firstOrNull {
                    it.bookId == a && it.quote == quote && it.deletedAt == null
                } }
                annotationId = saved?.id
                saved != null && exactMark((scroll.getChildAt(0) as TextView).text, quote)
            }

            // Recreate v0.1.40 regression: after a TextView transformation,
            // painted underline/background remain while transient annotation
            // IDs can disappear. The saved absolute locator MUST still open.
            val displayedText = scroll.getChildAt(0) as TextView
            instrumentation.runOnMainSync {
                val spanned = displayedText.text as Spannable
                spanned.getSpans(0, spanned.length, AnnotationHitSpan::class.java)
                    .forEach { spanned.removeSpan(it) }
                assertTrue(exactMark(displayedText.text, quote))
                assertTrue(MarkedTextTap.idsAt(displayedText.text, quoteOffset + 2).isEmpty())
            }
            // A normal short touch on the painted note opens its modal.
            PlatformNoteTap.tapOnText(instrumentation, displayedText, quoteOffset + 2)
            onView(withText("Tu anotación")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Primera reflexión")).check(matches(isDisplayed()))
            onView(withText("Cerrar")).inRoot(isDialog()).perform(click())

            instrumentation.runOnMainSync { scroll.scrollTo(0, 610) }
            waitUntil("MOBI scrolling did not move the reading viewport") { scroll.scrollY > 0 }
            finish(readingA); first = null
            val positionA = runBlocking {
                var value = notes.position(a)
                repeat(60) {
                    if ((JSONObject(value?.locator ?: "{}").optInt("offset", 0)) > 0) return@runBlocking value
                    delay(100)
                    value = notes.position(a)
                }
                value
            }
            val savedA = JSONObject(requireNotNull(positionA).locator).getInt("offset")
            assertTrue("Last visible offset must be preserved, not just the chunk start", savedA > 0)

            val readingB = open(b)
            second = readingB
            val scrollB = requireNotNull(findScroll(readingB))
            instrumentation.runOnMainSync { scrollB.scrollTo(0, 320) }
            waitUntil("Second book never scrolled") { scrollB.scrollY > 0 }
            finish(readingB); second = null
            val savedB = runBlocking {
                repeat(60) {
                    val value = notes.position(b)
                    val off = JSONObject(value?.locator ?: "{}").optInt("offset", 0)
                    if (off > 0) return@runBlocking off
                    delay(100)
                }
                0
            }
            assertTrue(savedB > 0)
            assertNotEquals("Two different books must not share the reading offset", savedA, savedB)

            val readingRestored = open(a)
            reopened = readingRestored
            val fresh = requireNotNull(findScroll(readingRestored))
            assertTrue("Reopened MOBI reader must display text", 
                (fresh.getChildAt(0) as TextView).text.isNotBlank())
            // Depending on which visible line was bookmarked, the annotated
            // phrase may lie above the current viewport. Jump to its exact
            // locator, just as Knowledge → Abrir origen does.
            assertEquals(savedA, JSONObject(requireNotNull(runBlocking { notes.position(a) }).locator)
                .getInt("offset"))
            finish(readingRestored); reopened = null

            // The note location is still navigable after reopening.
            val origin = instrumentation.startActivitySync(
                Intent(context, MobiReaderActivity::class.java)
                    .putExtra("book_id", a).putExtra("locator", locator)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as MobiReaderActivity
            reopened = origin
            waitUntil("Opening from the saved note did not restore its highlight") {
                val sc = findScroll(origin)
                sc != null && exactMark((sc.getChildAt(0) as TextView).text, quote)
            }

            // Editing preserves the anchor and saves a version; deletion
            // removes the actual painted span without reimporting the book.
            val saved = requireNotNull(runBlocking { database.readingDao().annotation(annotationId!!) })
            waitUntil("Reopened MOBI Activity never regained input focus") {
                origin.hasWindowFocus()
            }
            // Physical Android input after closing/reopening, not merely
            // dispatchTouchEvent() on a test view. Check the persisted note
            // before allowing any edit flow to mask a broken tap.
            val reopenedText = requireNotNull(findScroll(origin)).getChildAt(0) as TextView
            val visibleQuoteAt = reopenedText.text.indexOf(quote)
            assertTrue("Reopened note's quote must be visible in this chunk",
                visibleQuoteAt >= 0)
            PlatformNoteTap.tapOnText(instrumentation, reopenedText, visibleQuoteAt + 2)
            onView(withText("Tu anotación")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Primera reflexión")).check(matches(isDisplayed()))
            onView(withText("Cerrar")).inRoot(isDialog()).perform(click())
            // On Android 16 the previous note modal may still own the focus
            // while the Activity window becomes visible again. Opening the
            // editor immediately can confuse Espresso's root picker even
            // though the saved-note tap itself succeeded.
            waitUntil("MOBI reader did not regain focus after closing saved note") {
                origin.hasWindowFocus()
            }

            instrumentation.runOnMainSync { AnnotationEditor.edit(origin, saved) }
            instrumentation.waitForIdleSync()
            onView(withHint("Tu reflexión (opcional al guardar un fragmento)"))
                .inRoot(isDialog())
                .perform(replaceText("Reflexión editada"), closeSoftKeyboard())
            onView(withText("Guardar")).inRoot(isDialog()).perform(click())
            waitUntil("Edited MOBI note not committed") {
                runBlocking { database.readingDao().annotation(annotationId!!)?.body } ==
                    "Reflexión editada"
            }
            assertEquals(1, runBlocking { database.readingDao().versions(annotationId!!).size })
            runBlocking { notes.setDeleted(annotationId!!, true) }
            waitUntil("Deleted MOBI note remains visibly painted") {
                !exactMark((requireNotNull(findScroll(origin)).getChildAt(0) as TextView).text, quote)
            }
        } finally {
            first?.let(::finish); second?.let(::finish); reopened?.let(::finish)
            runBlocking {
                listOf(a, b).forEach { id ->
                    database.openHelper.writableDatabase.execSQL(
                        "DELETE FROM annotation_versions WHERE annotationId IN (SELECT id FROM annotations WHERE bookId=?)",
                        arrayOf(id))
                    database.openHelper.writableDatabase.execSQL(
                        "DELETE FROM annotations WHERE bookId=?", arrayOf(id))
                    database.openHelper.writableDatabase.execSQL(
                        "DELETE FROM reading_positions WHERE bookId=?", arrayOf(id))
                    database.openHelper.writableDatabase.execSQL(
                        "DELETE FROM books WHERE id=?", arrayOf(id))
                }
            }
            fileA.delete(); fileB.delete()
        }
    }
}
