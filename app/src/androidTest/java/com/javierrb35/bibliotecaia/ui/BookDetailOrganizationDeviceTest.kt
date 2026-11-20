package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import android.view.View
import android.widget.ScrollView
import androidx.room.Room
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withHint
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The saved assignments and the visible phone viewport are tested on Android, not mocked. */
@RunWith(AndroidJUnit4::class)
class BookDetailOrganizationDeviceTest {

    private fun book(id: String) = BookEntity(
        id = id,
        title = "Filosofía para bufones",
        authors = "Pedro González Calero",
        format = BookFormat.EPUB,
        fileName = "Filosofia_para_bufones_Pedro_Gonzalez_Calero.epub",
        localPath = "",
        contentHash = id.replace("-", ""),
        sizeBytes = 2_300_000L,
        addedAt = System.currentTimeMillis()
    )

    @Test fun createAssignReuseAndUnassignRemainAtomicAndBookScoped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val first = UUID.randomUUID().toString()
            val second = UUID.randomUUID().toString()
            db.bookDao().insert(book(first))
            db.bookDao().insert(book(second))
            val editor = BookOrganizationEditor(db)
            val created = "Creado desde ficha " + UUID.randomUUID()

            for (kind in BookOrganizationEditor.Kind.values()) {
                editor.save(first, kind, emptySet(), created)
                val selected = editor.snapshot(first, kind)
                assertEquals("Should create and link one item in $kind", 1, selected.selected.size)
                val selectedId = selected.selected.single()
                assertEquals(created, selected.options.single { it.id == selectedId }.name)
                assertTrue("Other books must stay unchanged", editor.snapshot(second, kind).selected.isEmpty())

                // Same name with different case: link the original, never duplicate it.
                editor.save(first, kind, setOf(selectedId), created.lowercase())
                assertEquals(1, editor.snapshot(first, kind).options.count { it.id == selectedId })
                assertEquals(setOf(selectedId), editor.snapshot(first, kind).selected)

                editor.save(first, kind, emptySet(), "")
                assertTrue(editor.snapshot(first, kind).selected.isEmpty())
                assertTrue("Unassignment must not delete the library item",
                    editor.snapshot(second, kind).options.any { it.id == selectedId })
            }

            try {
                editor.save("unknown-book", BookOrganizationEditor.Kind.TAG,
                    emptySet(), "Orphan")
                fail("Cannot create an orphan item for a missing book")
            } catch (_: IllegalArgumentException) {
                // requireNotNull is deliberately enforced in the transaction.
            }
        } finally {
            db.close()
        }
    }

    @Test fun compactBookScreenFitsViewportAndOrganizesWithoutLeavingDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val database = AppDatabase.get(context)
        val bookId = UUID.randomUUID().toString()
        val suffix = UUID.randomUUID().toString().take(8)
        val names = listOf("Categoría-$suffix", "Etiqueta-$suffix", "Colección-$suffix")
        runBlocking { database.bookDao().insert(book(bookId)) }

        val activity = instrumentation.startActivitySync(
            Intent(context, BookDetailActivity::class.java).apply {
                putExtra("book_id", bookId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        ) as BookDetailActivity
        try {
            instrumentation.waitForIdleSync()
            val viewport = activity.findViewById<ScrollView>(R.id.bookDetailScroll)
            val primary = activity.findViewById<View>(R.id.btnReadBook)
            val edit = activity.findViewById<View>(R.id.btnEditMetadata)

            assertFalse("The normal phone layout should not need vertical scrolling",
                viewport.canScrollVertically(1))
            assertTrue("Primary reader action must remain visible", primary.isShown)
            assertTrue("Metadata action must remain visible", edit.isShown)

            val buttons = listOf(R.id.btnEditCategories, R.id.btnEditTags,
                R.id.btnEditCollections)
            val hints = listOf(R.string.detail_assignment_new_category,
                R.string.detail_assignment_new_tag,
                R.string.detail_assignment_new_collection)

            buttons.indices.forEach { i ->
                onView(withId(buttons[i])).perform(click())
                onView(withHint(hints[i])).perform(
                    replaceText(names[i]), closeSoftKeyboard())
                onView(withText(R.string.detail_assignment_save)).perform(click())
                val labels = awaitNames(database, bookId, i)
                assertTrue("A new element must be linked from its own dialog", names[i] in labels)
            }
            assertFalse("Creating categories must not navigate away from book details",
                activity.isFinishing)
            assertFalse("Adding taxonomy must not create new scrolling",
                viewport.canScrollVertically(1))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            runBlocking {
                // Clean up only this test's isolated items/links.
                val category = database.categoryDao().allOnce().firstOrNull { it.name == names[0] }
                val tag = database.tagDao().allOnce().firstOrNull { it.name == names[1] }
                val collection = database.collectionDao().allOnce().firstOrNull { it.name == names[2] }
                category?.let {
                    database.refsDao().removeBookCategory(bookId, it.id)
                    database.categoryDao().deleteById(it.id)
                }
                tag?.let {
                    database.refsDao().removeBookTag(bookId, it.id)
                    database.tagDao().deleteById(it.id)
                }
                collection?.let {
                    database.refsDao().removeCollectionBook(bookId, it.id)
                    database.collectionDao().deleteById(it.id)
                }
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM books WHERE id = ?", arrayOf(bookId))
            }
        }
    }

    private fun awaitNames(db: AppDatabase, bookId: String, index: Int): List<String> =
        runBlocking {
            repeat(60) {
                val names = when (index) {
                    0 -> db.refsDao().categoryNamesForBook(bookId)
                    1 -> db.refsDao().tagNamesForBook(bookId)
                    else -> db.refsDao().collectionNamesForBook(bookId)
                }
                if (names.isNotEmpty()) return@runBlocking names
                delay(100)
            }
            fail("Item not persisted on Android after saving organization")
            emptyList()
        }
}
