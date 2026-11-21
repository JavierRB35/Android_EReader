package com.javierrb35.bibliotecaia.reader.epub

import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.view.Gravity
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.javierrb35.bibliotecaia.ui.MainActivity
import org.json.JSONTokener
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prevents a regression where native notes silently vanish in Readium
 * because a native UTF-16 offset is not a DOM CFI.
 */
@RunWith(AndroidJUnit4::class)
class ReadiumNativeNoteBridgeDeviceTest {
    private fun note(id: String, href: String, quote: String = "frase guardada",
                     deleted: Boolean = false) = AnnotationEntity(
        id = id, bookId = "book", locator =
            NativeEpubAnchors.locator(href, "Capítulo", 12, 500, 0.1, quote),
        quote = quote, body = "Mi anotación", kind = "NOTE",
        color = 0xffd4b483.toInt(), createdAt = 1L, updatedAt = 2L,
        deletedAt = if (deleted) 3L else null
    )

    @Test fun bridgeNeverUsesNativeOffsetAsReadiumCfiOrOtherChapter() {
        val first = note("note-one", "OEBPS/chapter-01.xhtml")
        val otherChapter = note("note-other", "OEBPS/chapter-02.xhtml")
        val deleted = note("note-deleted", "OEBPS/chapter-01.xhtml", deleted = true)
        val notes = ReadiumNativeNoteBridge.notesForHref(
            listOf(first, otherChapter, deleted), "OEBPS/chapter-01.xhtml")
        assertEquals(listOf(first.id), notes.map { it.id })
        val paint = ReadiumNativeNoteBridge.decorateScript(notes)
        assertTrue(paint.contains("document.createRange()"))
        assertTrue(paint.contains("CSS.highlights.set"))
        assertTrue(paint.contains("\"id\":\"note-one\""))
        assertFalse(paint.contains("note-other"))
        assertFalse(paint.contains("note-deleted"))
        assertFalse(paint.contains("\"cfi\""))
    }

    /** Runs the actual generated script inside Android WebView, not just
     * string-content assertions. A unique native note must resolve to its ID;
     * a repeated quote must NOT resolve to either occurrence. */
    @Test fun realWebViewHitTestFindsUniqueSavedNoteAndRejectsRepeatedQuote() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val loaded = CountDownLatch(1)
        lateinit var web: WebView
        try {
            scenario.onActivity { activity ->
                web = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url?.contains("chapter.xhtml") == true) loaded.countDown()
                        }
                    }
                }
                activity.findViewById<FrameLayout>(android.R.id.content).addView(
                    web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        600, Gravity.TOP))
                web.loadDataWithBaseURL("https://book.example/chapter.xhtml",
                    "<html><head><meta name='viewport' content='width=device-width'></head>" +
                        "<body><p id='passage'>Un texto con una frase guardada para consultar.</p>" +
                        "</body></html>", "text/html", "UTF-8", null)
            }
            assertTrue("Fixture WebView did not load", loaded.await(15, TimeUnit.SECONDS))

            fun evaluate(script: String): String {
                val result = ArrayBlockingQueue<String>(1)
                instrumentation.runOnMainSync {
                    web.evaluateJavascript(script) { value -> result.offer(value) }
                }
                return result.poll(15, TimeUnit.SECONDS)
                    ?: error("WebView did not evaluate JavaScript")
            }

            val notes = listOf(note("saved-note", "chapter.xhtml"))
            // The rendering path must complete without throwing; on older
            // WebViews CSS.highlights is optional, but the hit test still works.
            val painted = evaluate(ReadiumNativeNoteBridge.decorateScript(notes))
            assertTrue("Unexpected CSS highlights result: $painted",
                painted == "0" || painted == "1")
            val pointJson = evaluate("""
                (function(){
                  var t=document.getElementById('passage').firstChild;
                  var p=t.textContent.indexOf('frase guardada');
                  var r=document.createRange();
                  r.setStart(t,p);r.setEnd(t,p+14);
                  var b=r.getBoundingClientRect();
                  return JSON.stringify({x:(b.left+b.right)/2*devicePixelRatio,
                    y:(b.top+b.bottom)/2*devicePixelRatio});
                })()
            """.trimIndent())
            val location = org.json.JSONObject(JSONTokener(pointJson).nextValue() as String)
            val x = location.getDouble("x").toFloat()
            val y = location.getDouble("y").toFloat()
            assertEquals("saved-note",
                JSONTokener(evaluate(ReadiumNativeNoteBridge.hitTestScript(
                    x, y, notes))).nextValue())

            evaluate("(function(){var p=document.createElement('p');" +
                "p.textContent='frase guardada';document.body.appendChild(p);" +
                "return true;})()")
            assertEquals(org.json.JSONObject.NULL,
                JSONTokener(evaluate(ReadiumNativeNoteBridge.hitTestScript(
                    x, y, notes))).nextValue())
        } finally {
            scenario.onActivity { activity ->
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
            scenario.close()
        }
    }

    @Test fun tapScriptUsesSameVerifiedDomRangeAndNeverProducesUnvalidatedId() {
        val safeQuote = "Decía: «esto\\ y \"aquello\"»"
        val notes = listOf(note("saved-note", "Text/1.xhtml", safeQuote))
        val script = ReadiumNativeNoteBridge.hitTestScript(100f, 50f, notes)
        assertTrue(script.contains("range.getClientRects()"))
        assertTrue(script.contains("\"saved-note\""))
        assertTrue(script.contains("normalized.indexOf(quote, pos + 1)"))
        assertFalse(script.contains("window.open("))
        assertEquals("null", ReadiumNativeNoteBridge.hitTestScript(Float.NaN, 5f, notes))
        assertTrue(ReadiumNativeNoteBridge.notesForHref(notes, "Text/2.xhtml").isEmpty())
    }
}
