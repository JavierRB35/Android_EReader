package com.javierrb35.bibliotecaia

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.ui.BookDetailActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * P0 regression: opening a newly imported book must not access Activity.filesDir
 * from a property initializer before ContextWrapper has its base context.
 */
@RunWith(AndroidJUnit4::class)
class BookDetailLaunchTest {
    @Test
    fun bookDetailsCanBeOpenedWithoutCrashingDuringActivityConstruction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bookId = UUID.randomUUID().toString()
        runBlocking {
            AppDatabase.get(context).bookDao().insert(
                BookEntity(id = bookId,
                    title = "Libro para prueba de apertura",
                    authors = null,
                    format = BookFormat.EPUB,
                    fileName = "prueba.epub",
                    localPath = "",
                    contentHash = bookId.replace("-", ""),
                    sizeBytes = 0L,
                    addedAt = System.currentTimeMillis())
            )
        }
        val intent = Intent(context, BookDetailActivity::class.java).apply {
            putExtra("book_id", bookId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as BookDetailActivity
        try {
            instrumentation.waitForIdleSync()
            assertNotNull(activity.findViewById<android.view.View>(R.id.txtDetailTitle))
            assertNotNull(activity.findViewById<android.view.View>(R.id.btnReadBook))
            assertFalse(activity.isFinishing)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
