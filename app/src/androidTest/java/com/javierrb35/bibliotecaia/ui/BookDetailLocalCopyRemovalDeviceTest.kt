package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookDetailLocalCopyRemovalDeviceTest {
    @Test fun removeLocalCopyWithoutVerifiedCentralArchiveKeepsOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.get(context)
        val id = UUID.randomUUID().toString()
        val original = File(context.filesDir, "books/$id/sample.epub").apply {
            parentFile!!.mkdirs()
            writeText("epub test sample")
        }
        runBlocking {
            db.bookDao().insert(BookEntity(
                id = id,
                title = "Libro para borrar copia",
                authors = "Autor prueba",
                format = BookFormat.EPUB,
                fileName = "sample.epub",
                localPath = original.absolutePath,
                contentHash = UUID.randomUUID().toString().replace("-", ""),
                sizeBytes = original.length(),
                addedAt = System.currentTimeMillis(),
                favorite = true,
                progress = 0.45f
            ))
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, BookDetailActivity::class.java).apply {
                putExtra("book_id", id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        ) as BookDetailActivity
        try {
            instrumentation.waitForIdleSync()
            onView(withId(R.id.btnRemoveLocalCopy)).check(matches(isDisplayed())).perform(click())
            onView(withText(R.string.detail_remove_local_button))
                .inRoot(isDialog()).perform(click())
            instrumentation.waitForIdleSync()
            runBlocking { delay(500) }
            val retained = runBlocking { db.bookDao().findById(id) }
            assertNotNull("The library entry must remain", retained)
            assertTrue("The unbacked original must not be deleted", original.exists())
            assertEquals(original.absolutePath, retained!!.localPath)
            assertTrue(retained.favorite)
            assertEquals(0.45f, retained.progress, 0.001f)
            onView(withId(R.id.btnRemoveLocalCopy)).check(matches(isDisplayed()))
            assertFalse(activity.isFinishing)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            original.delete()
            original.parentFile?.delete()
            runBlocking {
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM books WHERE id = ?", arrayOf(id)
                )
            }
        }
    }
}
