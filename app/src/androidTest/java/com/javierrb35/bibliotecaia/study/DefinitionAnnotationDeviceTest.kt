package com.javierrb35.bibliotecaia.study

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.*
import com.javierrb35.bibliotecaia.reader.epub.NativeEpubAnchors
import com.javierrb35.bibliotecaia.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DefinitionAnnotationDeviceTest {
    @Test fun generatedDefinitionSavesExactSecondOccurrenceAsNote() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.get(context)
        val id = UUID.randomUUID().toString()
        val text = "Se sentó en un banco. Depositó dinero en el banco."
        val quote = "banco"
        val start = text.lastIndexOf(quote)
        val locator = NativeEpubAnchors.locator("chapter.xhtml", "Capítulo", start, text.length, .5, quote)
        val target = DefinitionAnnotation.Target(id, locator, quote)
        val answer = "Entidad financiera en la que se deposita dinero."
        runBlocking {
            db.bookDao().insert(BookEntity(id, "Definición de prueba", null, BookFormat.EPUB,
                "sample.epub", "", id.replace("-", ""), 1L, System.currentTimeMillis()))
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val delegate = object : DefinitionUi.Delegate {
                    override fun openExternal(url: String) = Unit
                    override fun explainWithGemini(word: String) = Unit
                    override fun showError(message: String) { fail(message) }
                }
                DefinitionUi.showWithRequest(activity, quote, true, delegate,
                    ContextualDefinition.fromSelection(text, start, start + quote.length),
                    target, DefinitionUi.DefinitionRequest { _, _, _, callback ->
                        callback.done(answer, "Gemini", null)
                        Runnable {}
                    })
            }
            onView(withText(answer)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Añadir como anotación")).inRoot(isDialog()).perform(click())
            var stored: List<AnnotationEntity> = emptyList()
            repeat(60) {
                instrumentation.waitForIdleSync()
                stored = runBlocking { db.readingDao().allNotes().filter { it.bookId == id } }
                if (stored.isNotEmpty()) return@repeat
                Thread.sleep(50)
            }
            val note = stored.single()
            assertEquals("NOTE", note.kind)
            assertEquals(answer, note.body)
            assertEquals(quote, note.quote)
            assertEquals(locator, note.locator)
            assertEquals(start, NativeEpubAnchors.validatedRange(note.locator, note.quote,
                "chapter.xhtml", text)?.first)
        } finally {
            scenario.close()
            runBlocking {
                db.openHelper.writableDatabase.execSQL("DELETE FROM annotations WHERE bookId=?", arrayOf(id))
                db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id=?", arrayOf(id))
            }
        }
    }
}
