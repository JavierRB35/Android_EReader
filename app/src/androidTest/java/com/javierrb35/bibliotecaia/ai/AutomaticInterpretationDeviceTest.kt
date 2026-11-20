package com.javierrb35.bibliotecaia.ai

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Selection -> Interpretar con IA is already an explicit request.
 * The screen must not require another question or hide an unresponsive wait.
 * No external API key or remote call is required to test its presentation.
 */
@RunWith(AndroidJUnit4::class)
class AutomaticInterpretationDeviceTest {
    @Test
    fun selectedQuoteShowsAnimatedLoadingWithoutDuplicateQuestion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.get(context)
        val id = UUID.randomUUID().toString()
        runBlocking {
            db.bookDao().insert(BookEntity(
                id = id,
                title = "Libro de prueba de IA",
                authors = "Autor de prueba",
                format = BookFormat.EPUB,
                fileName = "no-original.epub",
                localPath = "",
                contentHash = id.replace("-", ""),
                sizeBytes = 1L,
                addedAt = System.currentTimeMillis()
            ))
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, AiChatActivity::class.java)
                .putExtra("book_id", id)
                .putExtra("selected_quote", "fragmento de prueba")
                .putExtra("selected_locator", "{\"chapter\":1}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as AiChatActivity
        try {
            instrumentation.waitForIdleSync()
            val views = mutableListOf<View>()
            fun visit(view: View) {
                views += view
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
            }
            visit(activity.window.decorView)
            val texts = views.filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("La consulta debe abrir directamente interpretación", texts.any { it == "Interpretación IA" })
            assertFalse("La pregunta duplicada no debe aparecer", texts.any { it.contains("Interpreta el fragmento seleccionado") })
            assertFalse("No debe mostrarse una segunda confirmación", texts.any { it.contains("Solo se enviará al proveedor") })
            assertFalse("La interpretación automática no tiene campo de pregunta", views.any { it is EditText })
            assertTrue("Debe existir un indicador circular animado durante la espera",
                views.any { it is CircularProgressIndicator && it.isIndeterminate })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            runBlocking {
                db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = ?", arrayOf(id))
            }
        }
    }
}
