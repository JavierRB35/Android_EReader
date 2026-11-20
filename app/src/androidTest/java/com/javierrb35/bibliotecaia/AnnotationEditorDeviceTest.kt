package com.javierrb35.bibliotecaia

import android.content.Intent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.ui.AnnotationEditor
import com.javierrb35.bibliotecaia.ui.KnowledgeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises actual AnnotationEditor, SQLite and notebook UI, not only a DAO. */
@RunWith(AndroidJUnit4::class)
class AnnotationEditorDeviceTest {
    private val inputHint = "Tu reflexión (opcional al guardar un fragmento)"

    @Test fun newNotebookNoteSavesAndCanBeEdited() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, KnowledgeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as KnowledgeActivity
        val original = "Nota desde Android real: " + UUID.randomUUID()
        val revised = "Nota editada desde Android: " + UUID.randomUUID()
        var id: String? = null
        try {
            onView(withText("Nueva nota")).perform(click())
            onView(withHint(inputHint)).perform(replaceText(original), closeSoftKeyboard())
            onView(withText("Guardar")).perform(click())
            id = awaitSaved(original)
            assertNotNull("The dialog did not persist the standalone note", id)
            onView(withText(org.hamcrest.Matchers.containsString(original))).perform(click())
            onView(withText("Editar")).perform(click())
            onView(withHint(inputHint)).perform(replaceText(revised), closeSoftKeyboard())
            onView(withText("Guardar")).perform(click())
            runBlocking {
                val dao = AppDatabase.get(context).readingDao()
                var note = dao.annotation(requireNotNull(id))
                for (attempt in 0..60) {
                    if (note?.body == revised) break
                    delay(100)
                    note = dao.annotation(requireNotNull(id))
                }
                assertEquals("Saved annotation edits are missing", revised, note?.body)
                assertEquals(1, dao.versions(requireNotNull(id)).size)
            }
        } finally {
            id?.let { saved -> runBlocking { ReadingRepository(context).setDeleted(saved, true) } }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun selectedQuoteCanSaveWithoutOptionalReflection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, KnowledgeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as KnowledgeActivity
        val phrase = "Cita verificada " + UUID.randomUUID()
        var id: String? = null
        try {
            instrumentation.runOnMainSync {
                AnnotationEditor.show(activity, null, null, phrase, "HIGHLIGHT")
            }
            onView(withText("Guardar")).perform(click())
            id = runBlocking {
                val dao = AppDatabase.get(context).readingDao()
                var found = dao.allNotes().firstOrNull { it.quote == phrase }
                for (attempt in 0..60) {
                    if (found != null) break
                    delay(100)
                    found = dao.allNotes().firstOrNull { it.quote == phrase }
                }
                found?.id
            }
            assertNotNull("The annotation was not stored without optional body", id)
        } finally {
            id?.let { saved -> runBlocking { ReadingRepository(context).setDeleted(saved, true) } }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun awaitSaved(body: String): String? = runBlocking {
        val dao = AppDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext)
            .readingDao()
        var saved = dao.allNotes().firstOrNull { it.body == body }
        for (attempt in 0..60) {
            if (saved != null) break
            delay(100)
            saved = dao.allNotes().firstOrNull { it.body == body }
        }
        saved?.id
    }
}
