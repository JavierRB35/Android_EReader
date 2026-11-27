package com.javierrb35.bibliotecaia.reader.annotations

import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import com.javierrb35.bibliotecaia.study.DefinitionUi
import com.javierrb35.bibliotecaia.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real attached Android touch -> marked span -> saved-note Material modal. */
@RunWith(AndroidJUnit4::class)
class SavedAnnotationModalDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun selectingTheMarkedTextAndChoosingVerAnotacionOpensTheSavedNote() {
        val quote = "pasaje subrayado"
        val body = "Mi nota guardada debe abrirse sin volver a crearla."
        val rendered = "El pasaje subrayado mantiene su anotación original."
        val from = rendered.indexOf(quote)
        val note = AnnotationEntity(
            id = "saved-modal-native-selection", bookId = "selection-fixture",
            locator = null, quote = quote, body = body, kind = "NOTE",
            color = 0xffd4b483.toInt(), createdAt = 1, updatedAt = 1
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val painted = SpannableString(rendered)
                painted.setSpan(UnderlineSpan(), from, from + quote.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                painted.setSpan(AnnotationHitSpan(note.id), from, from + quote.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val target = TextView(activity).apply {
                    textSize = 20f
                    setText(painted, TextView.BufferType.SPANNABLE)
                    setTextIsSelectable(true)
                }
                DefinitionUi.attach(activity, target, false, object : DefinitionUi.Delegate {
                    override fun openExternal(url: String) = Unit
                    override fun explainWithGemini(word: String) = Unit
                    override fun showError(message: String) =
                        throw AssertionError("Error inesperado: $message")
                }, null, null, null, null, object : DefinitionUi.SavedMarkHandler {
                    override fun hasSavedMark(start: Int, end: Int) =
                        MarkedTextTap.idsWithin(target.text, start, end).contains(note.id)

                    override fun onShowSavedMark(start: Int, end: Int) {
                        SavedAnnotationSheet.show(activity, ReadingRepository(activity),
                            listOf(note))
                    }
                })
                activity.findViewById<FrameLayout>(android.R.id.content).addView(
                    target, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 250, Gravity.TOP))
                val callback = requireNotNull(target.customSelectionActionModeCallback)
                val menu = PopupMenu(activity, target).menu
                // Regression: Samsung can create ActionMode BEFORE setting
                // the final selection range. The action must not disappear.
                Selection.setSelection(target.text as Spannable, 0, 0)
                assertTrue(callback.onCreateActionMode(null, menu))
                val action = requireNotNull(menu.findItem(0x5C124))
                assertTrue(action.isVisible)
                Selection.setSelection(target.text as Spannable, from + 2, from + 11)
                assertTrue(callback.onActionItemClicked(null, action))
            }
            instrumentation.waitForIdleSync()
            onView(withText("Tu anotación")).check(matches(isDisplayed()))
            onView(withText(body)).check(matches(isDisplayed()))
            onView(withText("Cerrar")).perform(click())
        } finally {
            scenario.close()
        }
    }

    @Test fun writtenNoteInsideUnderlineRecordIsNotHiddenByTheUnderlineModal() {
        val quote = "fragmento marcado"
        val body = "Nota escrita directamente sobre el subrayado."
        val rendered = "El fragmento marcado conserva la reflexión guardada."
        val from = rendered.indexOf(quote)
        val highlightWithNote = AnnotationEntity(
            id = "highlight-with-body", bookId = "highlight-note-fixture",
            locator = null, quote = quote, body = body, kind = "HIGHLIGHT",
            color = 0xffd4b483.toInt(), createdAt = 1, updatedAt = 1
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val painted = SpannableString(rendered).apply {
                    setSpan(UnderlineSpan(), from, from + quote.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(AnnotationHitSpan(highlightWithNote.id), from,
                        from + quote.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                SavedAnnotationSheet.show(activity, ReadingRepository(activity),
                    listOf(highlightWithNote))
            }
            instrumentation.waitForIdleSync()
            onView(withText("Subrayado con nota")).check(matches(isDisplayed()))
            onView(withText("Tu anotación")).check(matches(isDisplayed()))
            onView(withText(body)).check(matches(isDisplayed()))
            onView(withText("Editar anotación")).check(matches(isDisplayed()))
            onView(withText("Cerrar")).perform(click())
        } finally {
            scenario.close()
        }
    }

    @Test fun scrollingParentOpensNoteAfterChildConsumesTouchAndSpansDisappear() {
        val body = "Una reflexión guardada y completa para consultar mientras leo."
        val quote = "palabras anotadas"
        val rendered = "Inicio del libro. " + "texto del libro y lectura. ".repeat(100) +
            "Estas palabras anotadas pertenecen al libro y no se deben modificar."
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
            lateinit var scrolling: SavedMarkScrollView
            scenario.onActivity { activity ->
                val painted = SpannableString(rendered)
                painted.setSpan(UnderlineSpan(), from, from + quote.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Reproduce a TextView transformation dropping transient hit
                // spans while the underline and persisted anchor remain.
                target = TextView(activity).apply {
                    textSize = 22f
                    setPadding(24, 24, 24, 24)
                    setTextIsSelectable(true)
                    setText(painted, TextView.BufferType.SPANNABLE)
                    setBackgroundColor(0xff1b1a18.toInt())
                }
                // PDF OCR nests the text inside a padded panel; a parent
                // hit test must handle every ancestor, not only direct children.
                val panel = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(34, 42, 16, 12)
                    addView(target)
                }
                scrolling = SavedMarkScrollView(activity).apply { addView(panel) }
                scrolling.attach(target, marksAt = { at ->
                    if (at in from until (from + quote.length))
                        listOf(note, underline)
                    else emptyList()
                }, onMarks = { marks ->
                    SavedAnnotationSheet.show(activity, ReadingRepository(activity), marks)
                })
                val parent = activity.findViewById<FrameLayout>(android.R.id.content)
                parent.addView(scrolling, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 280, Gravity.TOP))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val layout = requireNotNull(target.layout)
                val char = from + 5
                val line = layout.getLineForOffset(char)
                scrolling.scrollTo(0, (layout.getLineTop(line) - 100).coerceAtLeast(0))
                assertTrue("The test should actually scroll", scrolling.scrollY > 0)
                assertEquals(emptyList<String>(), MarkedTextTap.idsAt(target.text, char))
            }
            // Use screen coordinates and the actual Android window dispatch.
            // offsetDescendantRectToMyCoords returns content coordinates, not
            // viewport coordinates; synthetic events previously missed the glyph.
            PlatformNoteTap.tapOnText(instrumentation, target, from + 5, durationMs = 320L)
            instrumentation.waitForIdleSync()
            onView(withText("Tu anotación")).check(matches(isDisplayed()))
            onView(withText(body)).check(matches(isDisplayed()))
            onView(withText("Cerrar")).perform(click())
        } finally {
            scenario.close()
        }
    }
}
