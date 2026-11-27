package com.javierrb35.bibliotecaia.ui

import android.widget.EditText
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.chip.ChipGroup
import com.javierrb35.bibliotecaia.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Library UX regressions: preserve user context, keep essential actions reachable. */
@RunWith(AndroidJUnit4::class)
class LibraryHomeUxDeviceTest {
    @Test
    fun searchAndReadingShelfSurviveActivityRecreation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.txtSearch).setText("Mi libro")
                activity.findViewById<ChipGroup>(R.id.chipShelves).check(R.id.chipReading)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("Mi libro", activity.findViewById<EditText>(R.id.txtSearch).text.toString())
                assertEquals(R.id.chipReading, activity.findViewById<ChipGroup>(R.id.chipShelves).checkedChipId)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun selectionUsesCardShadingAndRecycledCardsResetInBothLayouts() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val book = com.javierrb35.bibliotecaia.data.BookEntity("select-a", "Libro A", null,
                    com.javierrb35.bibliotecaia.data.BookFormat.EPUB, "a.epub", "", "a".repeat(64), 1, 1)
                val other = book.copy(id = "select-b", title = "Libro B")
                var longPressed = ""
                val adapter = BookAdapter(onLongClick = { longPressed = it.id }) {}
                val parent = androidx.recyclerview.widget.RecyclerView(activity)
                adapter.submit(listOf(book, other))
                for (grid in listOf(true, false)) {
                    adapter.showSelection(emptySet())
                    longPressed = ""
                    parent.layoutManager = if (grid)
                        androidx.recyclerview.widget.GridLayoutManager(activity, 2)
                    else androidx.recyclerview.widget.LinearLayoutManager(activity)
                    adapter.grid = grid
                    val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
                    adapter.onBindViewHolder(holder, 0)
                    val card = holder.itemView as com.google.android.material.card.MaterialCardView
                    val normal = card.cardBackgroundColor.defaultColor
                    card.performLongClick()
                    assertEquals(book.id, longPressed)
                    adapter.showSelection(setOf(book.id))
                    adapter.onBindViewHolder(holder, 0)
                    org.junit.Assert.assertTrue(card.isSelected)
                    org.junit.Assert.assertFalse(card.isCheckable)
                    assertNotEquals(normal, card.cardBackgroundColor.defaultColor)
                    adapter.onBindViewHolder(holder, 1)
                    org.junit.Assert.assertFalse(card.isSelected)
                    assertEquals(normal, card.cardBackgroundColor.defaultColor)
                    adapter.onViewRecycled(holder)
                }
            }
        } finally { scenario.close() }
    }


    @Test
    fun catalogueAndCoverUpdatesPreserveCardClickAndDoNotInvalidateAllRows() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val book = com.javierrb35.bibliotecaia.data.BookEntity("stable-a", "Libro A", null,
                    com.javierrb35.bibliotecaia.data.BookFormat.EPUB, "a.epub", "", "a".repeat(64), 1, 1)
                val other = book.copy(id = "stable-b", title = "Libro B")
                var clicked = ""
                var resets = 0
                var payloadUpdates = 0
                val adapter = BookAdapter { clicked = it.id }
                adapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { resets++ }
                    override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                        if (payload != null) payloadUpdates += itemCount
                    }
                })
                val parent = androidx.recyclerview.widget.RecyclerView(activity).apply {
                    layoutManager = androidx.recyclerview.widget.GridLayoutManager(activity, 2)
                }
                adapter.submit(listOf(book, other))
                val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
                adapter.onBindViewHolder(holder, 0)
                adapter.submit(listOf(book.copy(), other.copy()))
                assertEquals(0, resets)
                adapter.refreshCovers()
                assertEquals(2, payloadUpdates)
                assertEquals(0, resets)
                holder.itemView.performClick()
                assertEquals(book.id, clicked)
                adapter.onViewRecycled(holder)
            }
        } finally { scenario.close() }
    }

    @Test
    fun contextualSelectionReplacesNormalChromeAndRestoresIt() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                fun view(id: Int) = activity.findViewById<android.view.View>(id)
                val recent = view(R.id.recentBookCard)
                recent.visibility = android.view.View.VISIBLE
                activity.renderSelectionChrome(1, 470, true)
                for (id in listOf(R.id.libraryNormalHeader, R.id.libraryQuickActions, R.id.mainNavigation))
                    assertEquals(android.view.View.GONE, view(id).visibility)
                for (id in listOf(R.id.bookSelectionBar, R.id.bookBatchActions, R.id.txtSearch, R.id.scrollShelves))
                    org.junit.Assert.assertTrue(view(id).isShown)
                org.junit.Assert.assertFalse(recent.isShown)
                assertEquals("1 seleccionado", activity.findViewById<android.widget.TextView>(R.id.txtSelectedBooks).text.toString())
                activity.renderSelectionChrome(470, 470, true)
                assertEquals("470 seleccionados", activity.findViewById<android.widget.TextView>(R.id.txtSelectedBooks).text.toString())
                assertEquals("Deseleccionar todos los resultados", view(R.id.btnSelectAllBooks).contentDescription.toString())
                // A background update during selection must not be overwritten by exit.
                recent.visibility = android.view.View.GONE
                activity.renderSelectionChrome(0, 470, false)
                for (id in listOf(R.id.libraryNormalHeader, R.id.libraryQuickActions, R.id.mainNavigation))
                    org.junit.Assert.assertTrue(view(id).isShown)
                for (id in listOf(R.id.bookSelectionBar, R.id.bookBatchActions))
                    assertEquals(android.view.View.GONE, view(id).visibility)
                assertEquals(android.view.View.GONE, recent.visibility)
            }
        } finally { scenario.close() }
    }

    @Test
    fun contextualActionsDistinguishDownloadGlobalDeletionAndLocalRemoval() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val primary = activity.findViewById<android.widget.TextView>(R.id.btnBatchPrimary)
                val secondary = activity.findViewById<android.widget.TextView>(R.id.btnBatchSecondary)
                activity.renderSelectionChrome(2, 470, true)
                activity.renderBatchActions(true, true, true)
                assertEquals("Descargar", primary.text.toString())
                assertEquals("Descargar formato preferido al dispositivo", primary.contentDescription.toString())
                assertEquals("Eliminar", secondary.text.toString())
                assertEquals("Eliminar definitivamente de la biblioteca", secondary.contentDescription.toString())
                org.junit.Assert.assertTrue(primary.isShown && secondary.isShown)
                assertEquals(primary.parent, secondary.parent)
                assertNotEquals(primary.currentTextColor, secondary.currentTextColor)
                activity.renderBatchActions(true, false, false)
                assertEquals(android.view.View.GONE, secondary.visibility)
                activity.renderBatchActions(false, false, false)
                assertEquals("Guardar en biblioteca", primary.text.toString())
                assertEquals("Retirar del dispositivo", secondary.text.toString())
                assertEquals(primary.currentTextColor, secondary.currentTextColor)
                org.junit.Assert.assertTrue(secondary.isShown)
                activity.renderSelectionChrome(0, 470, false)
                org.junit.Assert.assertFalse(secondary.isShown)
            }
        } finally { scenario.close() }
    }

    @Test
    fun compactHeaderKeepsImportAndViewSwitcherAccessible() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnImport))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnOrganize))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnSettings))
                assertNotNull(activity.findViewById<android.view.View>(R.id.recentBookCard))
                val switcher = activity.findViewById<ImageButton>(R.id.btnViewMode)
                val before = switcher.contentDescription.toString()
                switcher.performClick()
                assertNotEquals(before, switcher.contentDescription.toString())
                switcher.performClick()
                assertEquals(before, switcher.contentDescription.toString())
            }
        } finally {
            scenario.close()
        }
    }

    @Test fun queuedBatchDoesNotBlockLibraryAndSurvivesScreenRecreation() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val manager = androidx.work.WorkManager.getInstance(context)
        // A delayed work request represents a provider operation waiting in the
        // durable queue; no production library connection or files are touched.
        val work = androidx.work.OneTimeWorkRequestBuilder<LibraryBatchWorker>()
            .addTag(LibraryBatchQueue.TAG)
            .addTag(LibraryBatchQueue.ACTION + LibraryBatchAction.REMOVE.name)
            .addTag(LibraryBatchQueue.TOTAL + "4")
            .addTag(LibraryBatchQueue.ORDER + System.currentTimeMillis())
            .setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS).build()
        manager.enqueue(work).result.get(5, java.util.concurrent.TimeUnit.SECONDS)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            fun assertUsable() {
                var visible = false
                repeat(60) {
                    scenario.onActivity { activity ->
                        visible = activity.findViewById<android.view.View>(R.id.bookBatchProgress).isShown
                        if (visible) {
                            org.junit.Assert.assertTrue(activity.findViewById<android.view.View>(R.id.btnImport).isEnabled)
                            org.junit.Assert.assertTrue(activity.findViewById<android.view.View>(R.id.btnCentralLibrary).isEnabled)
                            val search = activity.findViewById<EditText>(R.id.txtSearch)
                            search.setText("Otro libro")
                            assertEquals("Otro libro", search.text.toString())
                        }
                    }
                    if (visible) return
                    Thread.sleep(100)
                }
                org.junit.Assert.fail("Pending batch was not shown")
            }
            assertUsable()
            scenario.recreate()
            assertUsable()
        } finally {
            scenario.close()
            manager.cancelWorkById(work.id).result.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
