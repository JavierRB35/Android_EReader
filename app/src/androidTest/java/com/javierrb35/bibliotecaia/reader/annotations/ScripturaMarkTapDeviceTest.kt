package com.javierrb35.bibliotecaia.reader.annotations

import android.graphics.Bitmap
import android.graphics.Canvas
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

    @Test fun shortTapOpensSavedTextButLongPressAndDragRemainSelectionGestures() {
        val hits = mutableListOf<List<String>>()
        instrumentation.runOnMainSync {
            val text = TextView(context).apply {
                this.text = "palabra marcada y seleccionable"
                textSize = 18f
                setTextIsSelectable(true)
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
                page.onMarkedTap { ids -> hits.add(ids) }
                page.draw(Canvas(output)) // populates the same zoom/pan hit matrix
                touch(page, 65f, 65f)
                assertEquals(listOf("underline", "note"), hits.single())
            } finally {
                output.recycle()
                bitmap.recycle()
            }
        }
    }
}
