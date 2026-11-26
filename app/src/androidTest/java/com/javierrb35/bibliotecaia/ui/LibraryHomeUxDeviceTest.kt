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
}
