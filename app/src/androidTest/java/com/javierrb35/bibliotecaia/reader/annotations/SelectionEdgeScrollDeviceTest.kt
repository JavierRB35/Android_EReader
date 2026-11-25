package com.javierrb35.bibliotecaia.reader.annotations

import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.study.DefinitionUi
import com.javierrb35.bibliotecaia.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real long press and popup-window pointer events, not direct range assignment. */
@RunWith(AndroidJUnit4::class)
class SelectionEdgeScrollDeviceTest {
    @Test fun dragToBottomExtendsSelectionAndStopsOnRelease() = exercise(false)

    @Test fun dragToTopExtendsSelectionAndStopsOnRelease() = exercise(true)

    private fun exercise(leading: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var text: TextView
        lateinit var scroll: SavedMarkScrollView
        var turns = 0
        try {
            scenario.onActivity { activity ->
                text = TextView(activity).apply {
                    textSize = 20f
                    setPadding(24, 20, 24, 20)
                    setTextIsSelectable(true)
                    this.text = (1..250).joinToString("\n") { "Línea " + it + " de prueba de selección." }
                }
                DefinitionUi.attach(activity, text, false, object : DefinitionUi.Delegate {
                    override fun openExternal(url: String) = Unit
                    override fun explainWithGemini(word: String) = Unit
                    override fun showError(message: String) = Unit
                })
                scroll = SavedMarkScrollView(activity).apply { addView(text) }
                scroll.attach(text, marksAt = { emptyList() }, onMarks = {},
                    onHorizontalSwipe = { turns++ })
                activity.findViewById<FrameLayout>(android.R.id.content).addView(
                    scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        (300 * activity.resources.displayMetrics.density).toInt(), Gravity.TOP))
            }
            instrumentation.waitForIdleSync()
            val selectedOffset = if (leading) 2500 else 3
            if (leading) scenario.onActivity {
                val line = requireNotNull(text.layout).getLineForOffset(selectedOffset)
                scroll.scrollTo(0, (text.layout.getLineTop(line) - scroll.height / 2).coerceAtLeast(0))
            }
            PlatformNoteTap.holdOnText(instrumentation, text, selectedOffset)
            instrumentation.waitForIdleSync()
            var start = 0
            var end = 0
            var point = 0f to 0f
            var center = 0f
            var edge = 0f
            var initialScroll = 0
            scenario.onActivity {
                start = text.selectionStart
                end = text.selectionEnd
                assertTrue(end > start)
                point = requireNotNull(scroll.selectionGripCenterOnScreen(leading))
                initialScroll = scroll.scrollY
                val pos = IntArray(2)
                scroll.getLocationOnScreen(pos)
                center = pos[1] + scroll.height / 2f
                edge = if (leading) pos[1] + 5f else pos[1] + scroll.height - 5f
            }
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, point.first, point.second)
            send(MotionEvent.ACTION_MOVE, point.first, edge)
            SystemClock.sleep(900)
            scenario.onActivity {
                assertTrue("Viewport must scroll on a stationary finger",
                    if (leading) scroll.scrollY < initialScroll else scroll.scrollY > initialScroll)
                if (leading) {
                    assertEquals(end, text.selectionEnd)
                    assertTrue(text.selectionStart < start)
                } else {
                    assertEquals(start, text.selectionStart)
                    assertTrue(text.selectionEnd > end)
                }
            }
            send(MotionEvent.ACTION_MOVE, point.first, center)
            SystemClock.sleep(200)
            var stopped = 0
            scenario.onActivity { stopped = scroll.scrollY }
            SystemClock.sleep(300)
            scenario.onActivity { assertEquals(stopped, scroll.scrollY) }
            send(MotionEvent.ACTION_UP, point.first, center)
            SystemClock.sleep(300)
            scenario.onActivity {
                assertEquals(stopped, scroll.scrollY)
                if (leading) {
                    assertEquals(end, text.selectionEnd)
                    assertTrue(text.selectionStart < start)
                } else {
                    assertEquals(start, text.selectionStart)
                    assertTrue(text.selectionEnd > end)
                }
                assertEquals(0, turns)
            }
        } finally { scenario.close() }
    }
}
