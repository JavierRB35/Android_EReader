package com.javierrb35.bibliotecaia.ui

import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.reader.ReaderPalette
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorialVisualDeviceTest {
    @Test fun bundledFontsLoadAndGridCoversKeepRatioWithoutCropping() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_BibliotecaIA)
            assertNotNull(ResourcesCompat.getFont(context, R.font.inter))
            assertNotEquals(Typeface.SERIF, EditorialTypeface.serif(context))
            assertNotNull(Typeface.createFromAsset(context.assets, EditorialTypeface.ITALIC))
            assertTrue(context.assets.open("licenses/Inter-OFL.txt").use { it.bufferedReader().readText() }.contains("SIL OPEN FONT LICENSE"))
            assertTrue(context.assets.open("licenses/Literata-OFL.txt").use { it.bufferedReader().readText() }.contains("SIL OPEN FONT LICENSE"))
            val card = LayoutInflater.from(context).inflate(R.layout.item_book, null)
            val image = card.findViewById<ImageView>(R.id.imgBookCover)
            val cover = image.parent as BookCoverFrame
            assertEquals(ImageView.ScaleType.FIT_CENTER, image.scaleType)
            for (dp in listOf(84, 110, 150, 210)) {
                val pixels = (dp * context.resources.displayMetrics.density).toInt()
                cover.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertTrue(cover.measuredWidth <= pixels)
                assertTrue(abs(cover.measuredHeight - 1.5f * cover.measuredWidth) <= 2f)
            }
            val label = card.findViewById<TextView>(R.id.txtBookFormat)
            assertTrue(label.textSize >= 11 * context.resources.displayMetrics.scaledDensity - 1)
        }
    }

    @Test fun persistedThemeIndicesAndOledContractAreStable() {
        assertEquals(0xff000000.toInt(), ReaderPalette.at(0).background)
        assertFalse(ReaderPalette.at(0).light)
        assertFalse(ReaderPalette.at(1).light)
        assertTrue(ReaderPalette.at(2).light)
        assertTrue(ReaderPalette.at(3).light)
        assertEquals(4, ReaderPalette.values().map { it.background }.distinct().size)
    }
}
