package com.javierrb35.bibliotecaia.reader.annotations

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.SpannableString
import android.text.Spanned
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.reader.pdf.PdfOcrRect
import com.javierrb35.bibliotecaia.reader.pdf.PdfPageView
import com.javierrb35.bibliotecaia.reader.pdf.PdfPaintedMark
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScripturaMarkTapDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun touch(view: View, x: Float, y: Float, durationMs: Long = 70,
                      moveX: Float = x) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + durationMs, MotionEvent.ACTION_UP, moveX, y, 0)
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle(); up.recycle()
        }
    }

    private fun annotation(id: String) = AnnotationEntity(
        id = id, bookId = "book", locator = null,
        quote = "palabra", body = "Reflexión guardada",
        kind = "NOTE", color = 0xffd4b483.toInt(),
        createdAt = 1, updatedAt = 1
    )

    @Test fun contextualSelectionOnlyFindsIdsOnActuallyPaintedCharacters() {
        val text = SpannableString("abc def ghi jkl")
        text.setSpan(AnnotationHitSpan("note"), 4, 7,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(AnnotationHitSpan("underline"), 4, 7,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(AnnotationHitSpan("other"), 8, 11,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals(setOf("note", "underline"),
            MarkedTextTap.idsWithin(text, 5, 6).toSet())
        assertEquals(emptyList<String>(), MarkedTextTap.idsWithin(text, 0, 4))
        assertEquals(emptyList<String>(), MarkedTextTap.idsWithin(text, 7, 8))
        assertEquals(setOf("note", "underline", "other"),
            MarkedTextTap.idsWithin(text, 5, 9).toSet())
        assertTrue(MarkedTextTap.idsWithin(text, -1, 5).isEmpty())
        assertTrue(MarkedTextTap.idsWithin(text, 4, 20).isEmpty())
    }

    @Test fun shortTapOpensSavedTextButLongPressAndDragRemainSelectionGestures() {
        val hits = mutableListOf<List<String>>()
        instrumentation.runOnMainSync {
            val text = TextView(context).apply {
                this.text = "palabra marcada y seleccionable"
                textSize = 18f
                setTextIsSelectable(true)
                // Native text selection needs real layout parameters even in
                // a standalone instrumented test; otherwise TextView
                // checkForResize() dereferences null while setting selection.
                layoutParams = android.view.ViewGroup.LayoutParams(600, 240)
                measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
                layout(0, 0, 600, 240)
            }
            MarkedTextTap.attach(text,
                marksAt = { listOf(annotation("one"), annotation("two")) },
                onMarks = { hits.add(it.map { note -> note.id }) })
            touch(text, 25f, 26f)
            assertEquals(listOf("one", "two"), hits.single())
            // Scriptura must not replace Android's native long-press or drag.
            touch(text, 25f, 26f, durationMs = 1200)
            touch(text, 25f, 26f, durationMs = 80, moveX = 150f)
            assertEquals("Only quick stationary taps open saved notes", 1, hits.size)
        }
    }

    @Test fun pdfOverlappingGeometryReturnsEachAnnotationExactlyOnce() {
        val hits = mutableListOf<List<String>>()
        instrumentation.runOnMainSync {
            val page = PdfPageView(context)
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val output = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            try {
                page.layout(0, 0, 200, 200)
                page.display(bitmap)
                val rect = PdfOcrRect(.2f, .2f, .6f, .6f)
                page.showMarks(listOf(
                    PdfPaintedMark("note", rect, "NOTE"),
                    PdfPaintedMark("note", rect, "NOTE"),
                    PdfPaintedMark("underline", rect, "HIGHLIGHT")
                ))
                page.onMarkedLongPress { ids -> hits.add(ids) }
                page.draw(Canvas(output)) // populates the same zoom/pan hit matrix
                assertEquals(listOf("underline", "note"), page.markedIdsAt(65f, 65f))
                assertTrue("Short taps must never open saved PDF notes", hits.isEmpty())
            } finally {
                output.recycle()
                bitmap.recycle()
            }
        }
    }
}
