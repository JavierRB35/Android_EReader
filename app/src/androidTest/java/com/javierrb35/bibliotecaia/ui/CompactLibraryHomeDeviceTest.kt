package com.javierrb35.bibliotecaia.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.javierrb35.bibliotecaia.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical proportions and navigation contracts for the compact reading shortcut. */
@RunWith(AndroidJUnit4::class)
class CompactLibraryHomeDeviceTest {
    @Test
    fun continuationIsMiniatureAndLibraryOwnsTheFlexibleViewport() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()

                val card = activity.findViewById<View>(R.id.recentBookCard)
                val thumb = activity.findViewById<View>(R.id.recentCoverThumb)
                val title = activity.findViewById<TextView>(R.id.txtRecentBook)
                val shelf = activity.findViewById<RecyclerView>(R.id.recyclerBooks)
                val shelfContainer = shelf.parent as View
                val params = shelfContainer.layoutParams as LinearLayout.LayoutParams

                assertEquals(dp(32), thumb.layoutParams.width)
                assertEquals(dp(44), thumb.layoutParams.height)
                assertTrue("Continuation must not impose a hero-card minimum height",
                    card.minimumHeight <= dp(56))
                assertEquals(1, title.maxLines)
                assertEquals(0, params.height)
                assertEquals(1f, params.weight, 0.01f)
                assertNotNull(activity.findViewById<View>(R.id.txtSearch))
                assertNotNull(activity.findViewById<View>(R.id.chipShelves))

                val dock = activity.findViewById<LinearLayout>(R.id.mainNavigation)
                assertEquals(4, dock.childCount)
                assertEquals(R.id.navLibrary, dock.getChildAt(0).id)
                assertEquals(R.id.navRead, dock.getChildAt(1).id)
                assertEquals(R.id.btnImport, dock.getChildAt(2).id)
                assertEquals(R.id.navKnowledge, dock.getChildAt(3).id)
            }
        } finally {
            scenario.close()
        }
    }
}
