@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.javierrb35.bibliotecaia

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.reader.epub.EpubReaderActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File
import java.util.UUID

/**
 * Opens the *actual reader screen*, not only the publication parser.
 * Regresses launch failures that JVM tests and assembleDebugAndroidTest miss.
 */
@RunWith(AndroidJUnit4::class)
class EpubReaderLaunchTest {

    @Test fun opensKnownGoodEpubThroughReaderActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val epub = File.createTempFile("book-readium-", ".epub", target.filesDir)
        instrumentation.context.assets.open("reader-sample.epub").use { input ->
            epub.outputStream().use { input.copyTo(it) }
        }
        try { assertReaderLaunches(epub) } finally { epub.delete() }
    }

    @Test fun opensSyntheticEpub2WithLegacyXhtmlAndLongNcx() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val epub = File.createTempFile("book-epub2-", ".epub", target.filesDir)
        Epub2RegressionFixture.write(epub)
        try { assertReaderLaunches(epub) } finally { epub.delete() }
    }

    private fun assertReaderLaunches(epub: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        runBlocking {
            AppDatabase.get(context).bookDao().insert(
                BookEntity(id = id, title = "Prueba de lectura",
                    authors = null, format = BookFormat.EPUB,
                    fileName = epub.name, localPath = epub.absolutePath,
                    contentHash = id.replace("-", ""), sizeBytes = epub.length(),
                    addedAt = System.currentTimeMillis())
            )
        }
        val intent = Intent(context, EpubReaderActivity::class.java).apply {
            putExtra("book_id", id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as EpubReaderActivity
        try {
            var ready = false
            // Readium publishes asynchronously, so a successful onCreate is not enough.
            for (step in 0 until 100) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    ready = activity.supportFragmentManager
                        .findFragmentById(R.id.epub_navigator) is EpubNavigatorFragment
                }
                if (ready) break
                Thread.sleep(200)
            }
            assertTrue("EPUB reader never mounted its Readium navigator", ready)
            instrumentation.runOnMainSync {
                val nativeText = activity.findViewById<android.widget.TextView>(R.id.epub_native_text)
                assertNotNull("Scriptura native text reader must exist", nativeText)
                assertTrue("EPUB must use native TextView selection by default",
                    nativeText.isTextSelectable)
            }
            var nativeReady = false
            for (step in 0 until 100) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val selectionView = activity.findViewById<android.widget.TextView>(
                        R.id.epub_native_text)
                    nativeReady = selectionView.visibility == android.view.View.VISIBLE &&
                        selectionView.text.isNotBlank() && selectionView.isTextSelectable
                }
                if (nativeReady) break
                Thread.sleep(200)
            }
            assertTrue("EPUB should display real native selectable text, not only a hidden TextView",
                nativeReady)
            instrumentation.runOnMainSync {
                val selectionView = activity.findViewById<android.widget.TextView>(
                    R.id.epub_native_text)
                assertTrue("Default selected-text highlight must not be bright cyan",
                    selectionView.highlightColor == 0x66D4B483)
            }
            // Real Activity regression: focus may hide the header, but it must
            // never hide the two chapter controls. Accessibility descriptions
            // identify the only manual navigation buttons in vertical mode.
            instrumentation.runOnMainSync {
                fun find(view: android.view.View, matches: (android.view.View) -> Boolean): android.view.View? {
                    if (matches(view)) return view
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) {
                            find(view.getChildAt(i), matches)?.let { return it }
                        }
                    }
                    return null
                }
                val decor = activity.window.decorView
                val focus = find(decor) {
                    it is android.widget.TextView && it.text == "Concentración"
                }
                assertNotNull("Reader needs an explicit focus-mode action", focus)
                focus!!.performClick()
                val previous = find(decor) { it.contentDescription == "Capítulo anterior" }
                val next = find(decor) { it.contentDescription == "Capítulo siguiente" }
                assertTrue("Previous chapter arrow must remain visible in focus mode",
                    previous?.isShown == true)
                assertTrue("Next chapter arrow must remain visible in focus mode",
                    next?.isShown == true)
                val headerField = EpubReaderActivity::class.java.getDeclaredField("controls")
                headerField.isAccessible = true
                assertTrue("Focus mode must hide the header",
                    (headerField.get(activity) as android.view.View).visibility == android.view.View.GONE)
            }
            assertTrue("EPUB reader closed during opening", !activity.isFinishing && !activity.isDestroyed)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
