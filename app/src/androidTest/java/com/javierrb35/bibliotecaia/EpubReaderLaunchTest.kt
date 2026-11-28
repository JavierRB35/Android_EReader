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


    @Test fun opensLocalEpubWhileRemoteRefreshIsSuspendedAndIgnoresDoubleTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val epub = File.createTempFile("book-offline-", ".epub", context.filesDir)
        instrumentation.context.assets.open("reader-sample.epub").use { input ->
            epub.outputStream().use { input.copyTo(it) }
        }
        val id = UUID.randomUUID().toString()
        runBlocking {
            AppDatabase.get(context).bookDao().insert(BookEntity(id = id, title = "Lectura offline",
                authors = null, format = BookFormat.EPUB, fileName = epub.name,
                localPath = epub.absolutePath, contentHash = id.replace("-", ""),
                sizeBytes = epub.length(), addedAt = System.currentTimeMillis()))
        }
        val detail = instrumentation.startActivitySync(Intent(context,
            com.javierrb35.bibliotecaia.ui.BookDetailActivity::class.java)
            .putExtra("book_id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            as com.javierrb35.bibliotecaia.ui.BookDetailActivity
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val monitor = instrumentation.addMonitor(EpubReaderActivity::class.java.name, null, false)
        var reader: EpubReaderActivity? = null
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                repeat(2) {
                    com.javierrb35.bibliotecaia.ui.ReaderRouter.openLocal(detail, id) {
                        entered.countDown()
                        release.await()
                    }
                }
            }
            val opened = monitor.waitForActivityWithTimeout(5000) as? EpubReaderActivity
            assertNotNull("Local reader must open before provider responds", opened)
            val actual = requireNotNull(opened)
            reader = actual
            assertTrue("Synthetic provider must still be waiting",
                entered.await(5, java.util.concurrent.TimeUnit.SECONDS) && !release.isCompleted)
            var rendered = false
            for (step in 0 until 100) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    rendered = actual.findViewById<android.widget.TextView>(R.id.epub_native_text)
                        ?.text?.isNotBlank() == true
                }
                if (rendered) break
                Thread.sleep(100)
            }
            assertTrue("Actual EPUB text must be available during remote wait", rendered)
            assertTrue("Only one reader may open for a double tap", monitor.hits == 1)
            val database = AppDatabase.get(context)
            runBlocking {
                assertTrue("An opening without movement must not manufacture a new position",
                    database.readingDao().position(id) == null)
                assertNotNull("Recency must still be tracked independently",
                    database.bookDao().findById(id)?.lastOpenedAt)
            }
        } finally {
            release.complete(Unit)
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { reader?.finish(); detail.finish() }
            instrumentation.waitForIdleSync()
            epub.delete()
        }
    }

    @Test fun firstVisibleNativeFrameIsAtSavedPositionWithoutChangingIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val database = AppDatabase.get(context)
        val prefs = context.getSharedPreferences("epub_reader", android.content.Context.MODE_PRIVATE)
        val previousMode = prefs.all["native_selection"] as? Boolean
        prefs.edit().putBoolean("native_selection", true).commit()
        val epub = File.createTempFile("book-resume-", ".epub", context.filesDir)
        Epub2RegressionFixture.write(epub)
        val id = UUID.randomUUID().toString()
        var activity: EpubReaderActivity? = null
        try {
            val chapter = runBlocking {
                val publication = com.javierrb35.bibliotecaia.reader.epub.EpubOpener.open(context, epub)
                try {
                    com.javierrb35.bibliotecaia.reader.epub.NativeEpubText.load(publication, publication.readingOrder[22])
                } finally { publication.close() }
            }
            // Well inside a long chapter, not at its start/end or a clamped scroll boundary.
            val offset = chapter.text.length / 3
            val locator = com.javierrb35.bibliotecaia.reader.epub.NativeEpubAnchors.locator(
                chapter.href, chapter.title, offset, chapter.text.length, .29)
            val saved = runBlocking {
                database.bookDao().insert(BookEntity(id = id, title = "Retomar lectura",
                    authors = null, format = BookFormat.EPUB, fileName = epub.name,
                    localPath = epub.absolutePath, contentHash = id.replace("-", ""),
                    sizeBytes = epub.length(), addedAt = System.currentTimeMillis()))
                com.javierrb35.bibliotecaia.data.ReadingRepository(database).savePosition(id, locator, .29f)
                requireNotNull(database.readingDao().position(id))
            }
            val intent = Intent(context, EpubReaderActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val opened = instrumentation.startActivitySync(intent) as EpubReaderActivity
            activity = opened
            val first = java.util.concurrent.CountDownLatch(1)
            val wrongFrame = java.util.concurrent.atomic.AtomicBoolean(false)
            val frames = java.util.concurrent.atomic.AtomicInteger(0)
            instrumentation.runOnMainSync {
                val text = opened.findViewById<android.widget.TextView>(R.id.epub_native_text)
                val scroll = text.parent as android.widget.ScrollView
                fun inspectFrame() {
                    if (!scroll.isShown) return
                    val layout = text.layout
                    if (layout == null || text.text.length != chapter.text.length) {
                        wrongFrame.set(true)
                    } else {
                        val expected = layout.getLineTop(layout.getLineForOffset(offset)) + text.totalPaddingTop
                        if (expected <= 0 || scroll.scrollY != expected) wrongFrame.set(true)
                    }
                    frames.incrementAndGet()
                    first.countDown()
                }
                // Inspect an already-visible frame as well as the following draws.
                inspectFrame()
                scroll.viewTreeObserver.addOnDrawListener { inspectFrame() }
            }
            assertTrue("Native chapter was never revealed", first.await(30, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(1300) // longer than the existing 850ms save debounce
            instrumentation.waitForIdleSync()
            assertTrue("Every visible frame must start at the saved line", frames.get() > 0 && !wrongFrame.get())
            runBlocking {
                org.junit.Assert.assertEquals("Opening is not a reading movement", saved, database.readingDao().position(id))
            }
            instrumentation.runOnMainSync { opened.finish() }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            runBlocking {
                org.junit.Assert.assertEquals("Closing without movement must preserve the exact locator and timestamp",
                    saved, database.readingDao().position(id))
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            if (previousMode == null) prefs.edit().remove("native_selection").commit()
            else prefs.edit().putBoolean("native_selection", previousMode).commit()
            epub.delete()
        }
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
            // Repeated size changes must not close the existing dialog.
            val fontPrefs = context.getSharedPreferences("epub_reader", android.content.Context.MODE_PRIVATE)
            val originalScale = fontPrefs.getFloat("font", 1.1f)
            val initialPx = activity.findViewById<android.widget.TextView>(R.id.epub_native_text).textSize
            try {
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                    activity.getString(R.string.epub_settings))).perform(androidx.test.espresso.action.ViewActions.click())
                val larger = originalScale <= 1.7f
                val action = if (larger) R.string.reader_font_increase else R.string.reader_font_decrease
                repeat(2) {
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(action))
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                        .perform(androidx.test.espresso.action.ViewActions.click())
                }
                val actual = fontPrefs.getFloat("font", 1.1f)
                assertTrue("Two adjustments within one menu must persist", if (larger) actual > originalScale + .15f else actual < originalScale - .15f)
                val finalPx = activity.findViewById<android.widget.TextView>(R.id.epub_native_text).textSize
                assertTrue("Text must resize immediately", if (larger) finalPx > initialPx else finalPx < initialPx)
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.reader_settings_done))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.click())
            } finally {
                fontPrefs.edit().putFloat("font", originalScale).commit()
            }
            // Focus must hide all controls; page turns require a deliberate
            // horizontal swipe rather than any on-screen chapter arrow.
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
                    it is android.widget.TextView && it.text.toString() == "Concentración"
                }
                assertNotNull("Reader needs an explicit focus-mode action", focus)
                focus!!.performClick()
                val previous = find(decor) { it.contentDescription == "Capítulo anterior" }
                val next = find(decor) { it.contentDescription == "Capítulo siguiente" }
                assertTrue("Previous chapter arrow must not exist", previous == null)
                assertTrue("Next chapter arrow must not exist", next == null)
                val headerField = EpubReaderActivity::class.java.getDeclaredField("controls")
                headerField.isAccessible = true
                assertTrue("Focus mode must hide the header",
                    (headerField.get(activity) as android.view.View).visibility == android.view.View.GONE)
                val footerField = EpubReaderActivity::class.java.getDeclaredField("footer")
                footerField.isAccessible = true
                assertTrue("Focus mode must hide all footer controls",
                    (footerField.get(activity) as android.view.View).visibility == android.view.View.GONE)
            }
            assertTrue("EPUB reader closed during opening", !activity.isFinishing && !activity.isDestroyed)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
