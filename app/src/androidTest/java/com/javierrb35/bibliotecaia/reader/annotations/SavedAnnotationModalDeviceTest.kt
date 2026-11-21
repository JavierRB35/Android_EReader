package com.javierrb35.bibliotecaia.reader.annotations

import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real attached Android touch -> marked span -> saved-note Material modal. */
@RunWith(AndroidJUnit4::class)
class SavedAnnotationModalDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun touchingAnUnderlinedNoteOpensTheFullAnnotationModal() {
        val body = "Una reflexión guardada y completa para consultar mientras leo."
        val quote = "palabras anotadas"
        val rendered = "Estas palabras anotadas pertenecen al libro y no se deben modificar."
        val from = rendered.indexOf(quote)
        val note = AnnotationEntity(
            id = "note-modal-fixture", bookId = "book-fixture", locator = null,
            quote = quote, body = body, kind = "NOTE",
            color = 0xffe9c282.toInt(), createdAt = 1, updatedAt = 1
        )
        val underline = note.copy(id = "highlight-fixture", kind = "HIGHLIGHT", body = "")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var target: TextView
            scenario.onActivity { activity ->
                val text = SpannableString(rendered)
                text.setSpan(UnderlineSpan(), from, from + quote.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(AnnotationHitSpan(note.id), from, from + quote.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(AnnotationHitSpan(underline.id), from, from + quote.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                target = TextView(activity).apply {
                    textSize = 22f
                    setPadding(24, 24, 24, 24)
                    setTextIsSelectable(true)
                    setText(text, TextView.BufferType.SPANNABLE)
                    setBackgroundColor(0xff1b1a18.toInt())
                }
                MarkedTextTap.attach(target, marksAt = { at ->
                    val ids = MarkedTextTap.idsAt(target.text, at)
                    listOf(note, underline).filter { it.id in ids }
                }, onMarks = { marks ->
                    SavedAnnotationSheet.show(activity, ReadingRepository(activity), marks)
                })
                val parent = activity.findViewById<FrameLayout>(android.R.id.content)
                parent.addView(target, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 240, Gravity.TOP))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val layout = requireNotNull(target.layout)
                val char = from + 5
                val line = layout.getLineForOffset(char)
                val x = target.totalPaddingLeft + layout.getPrimaryHorizontal(char)
                val y = target.totalPaddingTop +
                    (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                assertTrue(MarkedTextTap.idsAt(target.text, char).contains(note.id))
                assertEquals(emptyList<String>(), MarkedTextTap.idsAt(target.text, 0))
                val downAt = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(downAt, downAt,
                    MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(downAt, downAt + 75,
                    MotionEvent.ACTION_UP, x + 1f, y + 1f, 0)
                try {
                    target.dispatchTouchEvent(down)
                    target.dispatchTouchEvent(up)
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
            instrumentation.waitForIdleSync()
            // A NOTE overlapping an underline must show the note directly,
            // not a choice dialog or the annotation editor.
            onView(withText("Tu anotación")).check(matches(isDisplayed()))
            onView(withText(body)).check(matches(isDisplayed()))
            onView(withText("Cerrar")).perform(click())
        } finally {
            scenario.close()
        }
    }
}
