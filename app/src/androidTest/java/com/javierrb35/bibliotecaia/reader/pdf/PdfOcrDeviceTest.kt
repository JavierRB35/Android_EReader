package com.javierrb35.bibliotecaia.reader.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs an actual Android PdfRenderer + bundled ML Kit recognition + on-page
 * highlight render. This is NOT a JVM mock or a PDF text-layer test.
 */
@RunWith(AndroidJUnit4::class)
class PdfOcrDeviceTest {
    @Test fun renderedPdfRecognizesTextAndDrawsPersistedHighlight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pdf = File.createTempFile("ocr-device-", ".pdf", context.cacheDir)
        val bookId = "device-ocr-check"
        val hash = "fixed-edition-hash-for-instrumented-test"
        var pageBitmap: Bitmap? = null
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(1200, 800, 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("BIBLIOTECA", 80f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 100f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })
                document.finishPage(page)
                pdf.outputStream().use { output -> document.writeTo(output) }
            } finally { document.close() }
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        pageBitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
                        page.render(pageBitmap!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
            val bitmap = requireNotNull(pageBitmap)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val detected = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    45, TimeUnit.SECONDS)
            } finally { recognizer.close() }
            val snapshot = requireNotNull(snapshotFromRecognition(detected, bitmap, hash))
            val word = snapshot.words.firstOrNull {
                snapshot.text.substring(it.start, it.end).contains("BIBLIOTECA", ignoreCase = true)
            }
            assertNotNull("El motor OCR no ha reconocido el título del PDF de prueba", word)
            val chosen = requireNotNull(word)
            val quote = snapshot.text.substring(chosen.start, chosen.end)
            val locator = requireNotNull(PdfOcrAnchors.anchoredLocator(
                0, chosen.start, quote, snapshot))
            assertTrue(PdfOcrStore.save(context, bookId, 0, snapshot))
            assertEquals(snapshot, PdfOcrStore.load(context, bookId, 0, hash))
            val boxes = PdfOcrAnchors.overlayBoxes(locator, 0, quote, hash)
            assertFalse(boxes.isEmpty())
            assertEquals(boxes, PdfOcrAnchors.overlayBoxes(locator, 0, quote, hash,
                PdfOcrStore.load(context, bookId, 0, hash)))

            instrumentation.runOnMainSync {
                val view = PdfPageView(context)
                view.layout(0, 0, 1200, 800)
                view.display(bitmap)
                val unmarked = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(unmarked))
                view.showMarks(boxes.map { PdfPaintedMark("mark-1", it) })
                val marked = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(marked))
                assertFalse("No se dibujó ningún píxel del subrayado sobre PDF",
                    unmarked.sameAs(marked))
                view.changeZoom(1.5f)
                view.draw(Canvas(marked))
                marked.recycle()
                unmarked.recycle()
                view.clear()
            }
        } finally {
            pageBitmap?.recycle()
            pdf.delete()
            File(context.filesDir, "ocr/$bookId").deleteRecursively()
        }
    }
}
