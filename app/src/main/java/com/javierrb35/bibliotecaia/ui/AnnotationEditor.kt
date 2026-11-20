package com.javierrb35.bibliotecaia.ui

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.ReadingRepository
import kotlinx.coroutines.launch

object AnnotationEditor {
    fun show(activity: AppCompatActivity, bookId: String?, locator: String?, quote: String, kind: String = "HIGHLIGHT") {
        editor(activity, quote, "", if (kind == "BOOKMARK") "Guardar marcador" else "Guardar anotación") { body ->
            ReadingRepository(activity).addAnnotation(bookId, locator, quote, body, kind)
        }
    }

    fun edit(activity: AppCompatActivity, note: AnnotationEntity) {
        editor(activity, note.quote, note.body, "Editar nota") { body ->
            ReadingRepository(activity).editAnnotation(note.id, note.updatedAt, body, note.color)
        }
    }

    private fun editor(activity: AppCompatActivity, quote: String, initial: String, title: String,
                       save: suspend (String) -> Unit) {
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        if (quote.isNotBlank()) container.addView(TextView(activity).apply {
            text = "Texto del libro\n${quote.take(1000)}"
            setTextColor(0xFFE8DFCE.toInt())
            setTextIsSelectable(true)
            maxLines = 8
        })
        val input = EditText(activity).apply {
            hint = "Tu reflexión (opcional al guardar un fragmento)"
            setText(initial)
            minLines = 3
            maxLines = 10
            maxHeight = pad * 10
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(100000))
        }
        container.addView(input)
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(title).setView(container)
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val button = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false
                activity.lifecycleScope.launch {
                    try {
                        save(input.text.toString())
                        Toast.makeText(activity, "Guardado en Conocimiento", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } catch (e: IllegalArgumentException) {
                        input.error = "Escribe una nota o selecciona un fragmento."
                    } catch (e: IllegalStateException) {
                        Toast.makeText(activity, "La nota ha cambiado. Vuelve a abrirla.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Toast.makeText(activity, "No se pudo guardar. Tu texto sigue aquí; vuelve a intentarlo.", Toast.LENGTH_LONG).show()
                    } finally { button.isEnabled = true }
                }
            }
        }
        dialog.show()
    }
}
