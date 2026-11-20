package com.javierrb35.bibliotecaia.reader.annotations

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.ReadingRepository
import com.javierrb35.bibliotecaia.ui.AnnotationEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Scriptura-style reading interaction reused by EPUB, MOBI and PDF/OCR.
 * A short tap shows the entire saved quote and note; overlapping marks get
 * a chooser first. Deletion is confirmed; the original book is untouched.
 */
object SavedAnnotationSheet {
    fun show(
        activity: AppCompatActivity, repository: ReadingRepository,
        overlapping: List<AnnotationEntity>
    ) {
        val available = overlapping.filter {
            it.deletedAt == null && it.kind in setOf("NOTE", "HIGHLIGHT")
        }.distinctBy { it.id }
        if (available.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        if (available.size == 1) {
            detail(activity, repository, available.single())
            return
        }
        val labels = available.mapIndexed { index, mark ->
            val type = if (mark.kind == "HIGHLIGHT") "Subrayado" else "Nota"
            val preview = mark.body.ifBlank { mark.quote }.replace('\n', ' ')
            (index + 1).toString() + ". " + type + " · " + preview.take(70)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Anotaciones de este fragmento")
            .setItems(labels.toTypedArray()) { _, index ->
                detail(activity, repository, available[index])
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun detail(
        activity: AppCompatActivity, repository: ReadingRepository,
        mark: AnnotationEntity
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        fun line(value: String, size: Float, color: Int, weight: Boolean = false) {
            body.addView(TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(color)
                setTextIsSelectable(true)
                if (weight) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(3), 0, dp(9))
            })
        }
        if (mark.quote.isNotBlank()) {
            line("Fragmento seleccionado", 13f, 0xffd4b483.toInt(), true)
            line("«" + mark.quote + "»", 16f, 0xffe8dfce.toInt())
        }
        if (mark.kind == "NOTE") {
            line("Tu anotación", 13f, 0xffd4b483.toInt(), true)
            line(mark.body.ifBlank { "Sin reflexión escrita." }, 16f, 0xffe8dfce.toInt())
        } else {
            line("Fragmento subrayado. El texto original no se modifica.",
                14f, 0xffb8b0a4.toInt())
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(body)
        }
        // Cap the dialog, not the note: never truncate 900/1200 characters.
        scroll.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * .67f).toInt()
        )
        val isUnderline = mark.kind == "HIGHLIGHT"
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (isUnderline) "Subrayado" else "Nota")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .apply {
                if (!isUnderline) {
                    setNeutralButton("Editar anotación") { _, _ ->
                        AnnotationEditor.edit(activity, mark)
                    }
                }
            }
            .setPositiveButton(if (isUnderline) "Quitar subrayado" else "Eliminar anotación") { _, _ ->
                MaterialAlertDialogBuilder(activity)
                    .setTitle(if (isUnderline) "Quitar subrayado" else "Eliminar anotación")
                    .setMessage(if (isUnderline)
                        "¿Quitar el subrayado? El texto del libro no se modifica."
                    else
                        "¿Eliminar esta nota? Se moverá a la papelera y el libro no se modifica.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Eliminar") { _, _ ->
                        activity.lifecycleScope.launch {
                            try {
                                repository.setDeleted(mark.id, true)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                Toast.makeText(activity,
                                    "No se pudo eliminar la anotación.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.show()
            }
            .show()
    }
}
