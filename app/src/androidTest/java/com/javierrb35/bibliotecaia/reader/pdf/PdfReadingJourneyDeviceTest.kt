package com.javierrb35.bibliotecaia.reader.pdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
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
 * Two-page real PdfRenderer/PdfRenderService path: opening, saving a quoted
 * annotation, drawing it on page zero, zooming, changing pages, reopening,
 * editing, and removing it.
 *
 * OCR recognition itself is covered by PdfOcrDeviceTest; cached geometry here
 * is synthetic and only used to verify the reader Activity's persistence path.
 */
@RunWith(AndroidJUnit4::class)
class PdfReadingJourneyDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val database get() = AppDatabase.get(context)

    private fun fixture(): File {
        val file = File.createTempFile("pdf-reading-journey-", ".pdf", context.cacheDir)
        val doc = PdfDocument()
        try {
            repeat(2) { index ->
                val page = doc.startPage(
                    PdfDocument.PageInfo.Builder(850, 1100, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText(
                    if (index == 0) "BIBLIOTECA" else "PAGINA DOS",
                    90f, 290f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 80f
                    })
                doc.finishPage(page)
            }
            file.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
        return file
    }

    private fun pdfView(root: View): PdfPageView? {
        if (root is PdfPageView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            pdfView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        repeat(130) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(120)
        }
        fail(message)
    }

    private fun open(id: String): PdfReaderActivity {
        val activity = instrumentation.startActivitySync(
            Intent(context, PdfReaderActivity::class.java).putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as PdfReaderActivity
        waitUntil("Actual PDF reader never rendered the first page") {
            pdfView(activity.window.decorView)?.currentBitmap() != null
        }
        // PdfRenderService is asynchronous. Bitmap ready is not equivalent
        // to the Activity window having focus: Espresso needs both.
        waitUntil("PDF reader window never regained focus after opening") {
            activity.hasWindowFocus()
        }
        return activity
    }

    private fun differsFromUnmarked(view: PdfPageView, zoom: Float = 1f): Boolean {
        var differs = false
        instrumentation.runOnMainSync {
            val width = view.width
            val height = view.height
            val image = view.currentBitmap()
            if (width > 0 && height > 0 && image != null) {
                val blank = PdfPageView(context).apply {
                    layout(0, 0, width, height)
                    display(image)
                    if (zoom > 1f) changeZoom(zoom)
                }
                val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    blank.draw(Canvas(base))
                    view.draw(Canvas(actual))
                    differs = !base.sameAs(actual)
                } finally {
                    base.recycle()
                    actual.recycle()
                }
            }
        }
        return differs
    }

    @Test fun pdfNoteIsVisibleOnCorrectPageAfterZoomReopenEditAndDelete() {
        val id = UUID.randomUUID().toString()
        val hash = "pdf-journey-" + id
        val original = fixture()
        val snapshot = requireNotNull(PdfOcrGeometry.fromLines(
            listOf(listOf("BIBLIOTECA" to PdfOcrRect(.10f, .21f, .65f, .28f))), hash
        ))
        val quote = "BIBLIOTECA"
        val locator = requireNotNull(
            PdfOcrAnchors.anchoredLocator(0, 0, quote, snapshot))
        var activity: PdfReaderActivity? = null
        var noteId: String? = null
        try {
            runBlocking {
                database.bookDao().insert(BookEntity(
                    id = id, title = "PDF sintético de prueba", authors = null,
                    format = BookFormat.PDF, fileName = original.name,
                    localPath = original.absolutePath, contentHash = hash,
                    sizeBytes = original.length(), addedAt = System.currentTimeMillis()))
            }
            assertTrue(PdfOcrStore.save(context, id, 0, snapshot))
            val reading = open(id)
            activity = reading
            val image = requireNotNull(pdfView(reading.window.decorView))
            waitUntil("The PDF page should begin with no text marks") {
                !differsFromUnmarked(image)
            }
            instrumentation.runOnMainSync {
                AnnotationEditor.show(reading, id, locator, quote, "NOTE")
            }
            onView(withHint("Tu reflexión (opcional al guardar un fragmento)"))
                .perform(replaceText("PDF con nota vinculada"), closeSoftKeyboard())
            onView(withText("Guardar")).perform(click())
            waitUntil("Saved NOTE is not painted on the actual PDF page") {
                val note = runBlocking { database.readingDao().allNotes().firstOrNull {
                    it.bookId == id && it.quote == quote && it.deletedAt == null
                } }
                noteId = note?.id
                note != null && differsFromUnmarked(image)
            }
            instrumentation.runOnMainSync { image.changeZoom(1.5f) }
            assertTrue("PDF note lost its relative coordinates on zoom",
                differsFromUnmarked(image, zoom = 1.5f))
            instrumentation.runOnMainSync { image.fit() }

            horizontalSwipe(image, -1)
            waitUntil("PDF second page was not rendered or stale first-page annotation remains") {
                val status = reading.window.decorView
                val nextPageText = findPageStatus(status)?.contains("2 / 2") == true
                nextPageText && image.currentBitmap() != null && !differsFromUnmarked(image)
            }
            horizontalSwipe(image, 1)
            waitUntil("Returning to page one lost the original PDF NOTE") {
                findPageStatus(reading.window.decorView)?.contains("1 / 2") == true &&
                    differsFromUnmarked(image)
            }

            instrumentation.runOnMainSync { reading.finish() }
            instrumentation.waitForIdleSync()
            activity = null
            val reopened = open(id)
            activity = reopened
            val reloaded = requireNotNull(pdfView(reopened.window.decorView))
            waitUntil("Reopened PDF did not restore the note's exact page rectangles") {
                differsFromUnmarked(reloaded)
            }
            val note = requireNotNull(runBlocking { database.readingDao().annotation(noteId!!) })
            waitUntil("Reopened PDF reader lost focus before note editing") {
                reopened.hasWindowFocus()
            }
            instrumentation.runOnMainSync { AnnotationEditor.edit(reopened, note) }
            onView(withHint("Tu reflexión (opcional al guardar un fragmento)"))
                .perform(replaceText("PDF nota editada"), closeSoftKeyboard())
            onView(withText("Guardar")).perform(click())
            waitUntil("Edited PDF note is not stored") {
                runBlocking { database.readingDao().annotation(noteId!!)?.body } ==
                    "PDF nota editada"
            }
            assertEquals(1, runBlocking { database.readingDao().versions(noteId!!).size })
            runBlocking { ReadingRepository(context).setDeleted(noteId!!, true) }
            waitUntil("Deleted PDF NOTE still appears on the page") {
                !differsFromUnmarked(reloaded)
            }
        } finally {
            activity?.let {
                instrumentation.runOnMainSync { it.finish() }
                instrumentation.waitForIdleSync()
            }
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
            File(context.filesDir, "ocr/$id").deleteRecursively()
            original.delete()
        }
    }

    /** Real PDF View receives a long, predominantly horizontal single-pointer drag. */
    private fun horizontalSwipe(view: PdfPageView, direction: Int) {
        instrumentation.runOnMainSync {
            val downAt = android.os.SystemClock.uptimeMillis()
            val y = (view.height / 2f).coerceAtLeast(30f)
            val distance = view.width * .65f
            val fromX = if (direction > 0) view.width * .2f else view.width * .8f
            val toX = if (direction > 0) fromX + distance else fromX - distance
            fun send(action: Int, at: Long, x: Float) {
                val event = android.view.MotionEvent.obtain(
                    downAt, at, action, x, y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            send(android.view.MotionEvent.ACTION_DOWN, downAt, fromX)
            send(android.view.MotionEvent.ACTION_MOVE, downAt + 100, (fromX + toX) / 2f)
            send(android.view.MotionEvent.ACTION_MOVE, downAt + 180, toX)
            send(android.view.MotionEvent.ACTION_UP, downAt + 210, toX)
        }
    }

    private fun findPageStatus(root: View): String? {
        if (root is TextView && root.text.contains(" / 2")) return root.text.toString()
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            findPageStatus(root.getChildAt(i))?.let { return it }
        }
        return null
    }
}
