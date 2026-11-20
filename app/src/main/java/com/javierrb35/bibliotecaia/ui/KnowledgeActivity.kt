package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.javierrb35.bibliotecaia.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeActivity : AppCompatActivity() {
    private val db by lazy { AppDatabase.get(this) }
    private val repository by lazy { ReadingRepository(this) }
    private val query = MutableStateFlow("")
    private val trash = MutableStateFlow(false)
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val notes = db.readingDao().allNotes()
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openOutputStream(uri, "wt") ?: error("No destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { out ->
                        out.appendLine("# Cuaderno · Biblioteca IA\n")
                        for (note in notes) {
                            out.appendLine("## ${label(note.kind)} · ${DateFormat.getDateTimeInstance().format(Date(note.createdAt))}\n")
                            note.bookId?.let { id -> db.bookDao().findById(id)?.let { out.appendLine("Libro: ${it.title}\n") } }
                            if (note.quote.isNotBlank()) {
                                out.appendLine("Texto del libro:")
                                note.quote.lineSequence().forEach { out.appendLine("> $it") }
                            }
                            if (note.body.isNotBlank()) out.appendLine("\nNota del usuario:\n\n${note.body}")
                            note.locator?.let { out.appendLine("\nLocalizador: $it") }
                            out.appendLine("\n---\n")
                        }
                    }
                }
                toast("Cuaderno exportado")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast("No se pudo exportar. Comprueba el destino y vuelve a intentarlo.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0); setBackgroundColor(0xFF000000.toInt()) }
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(button("‹", "Volver a la biblioteca") { finish() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(TextView(this).apply { text = "Conocimiento"; textSize = 26f; setTextColor(0xFFE8DFCE.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        root.addView(TextView(this).apply { text = "Tus ideas, siempre contigo"; textSize = 14f; setTextColor(0xFFAAA79E.toInt()); setPadding(0, 0, 0, dp(12)) })
        val search = EditText(this).apply { hint = "Buscar notas y fragmentos"; isSingleLine = true; doOnTextChanged { text, _, _, _ -> query.value = text.toString() } }
        root.addView(search)
        val actions = LinearLayout(this)
        actions.addView(button("Nueva nota") { AnnotationEditor.show(this, null, null, "", "NOTE") }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Exportar") { export.launch("BibliotecaIA-cuaderno.md") }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        root.addView(com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = "Mostrar papelera"; setTextColor(0xFFE8DFCE.toInt())
            setOnCheckedChangeListener { _, checked -> trash.value = checked }
        })
        val empty = TextView(this).apply { textSize = 18f; setTextColor(0xFFAAA79E.toInt()); setPadding(0, dp(28), 0, 0) }
        root.addView(empty)
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@KnowledgeActivity) }
        val adapter = NotesAdapter { note -> actions(note) }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(query, trash) { q, t -> db.readingDao().notebook(q, t) }.flatMapLatest { it }.collect { notes ->
                    adapter.submit(notes)
                    empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
                    empty.text = if (query.value.isNotEmpty()) "No hay resultados para esta búsqueda." else if (trash.value) "La papelera está vacía." else "Tu cuaderno empieza con una idea.\nCrea una nota o guarda un fragmento al leer."
                }
            }
        }
    }

    private fun actions(note: AnnotationEntity) {
        if (note.deletedAt != null) {
            MaterialAlertDialogBuilder(this).setTitle("Recuperar anotación")
                .setMessage(note.body.ifBlank { note.quote }.take(500))
                .setPositiveButton("Restaurar") { _, _ -> lifecycleScope.launch { repository.setDeleted(note.id, false) } }
                .setNegativeButton("Cerrar", null).show()
            return
        }
        val items = mutableListOf("Editar", "Versiones anteriores", "Mover a papelera")
        if (note.bookId != null) items.add("Abrir origen")
        MaterialAlertDialogBuilder(this).setTitle(label(note.kind)).setItems(items.toTypedArray()) { _, index ->
            when (index) {
                0 -> AnnotationEditor.edit(this, note)
                1 -> showVersions(note)
                2 -> lifecycleScope.launch { repository.setDeleted(note.id, true) }
                3 -> ReaderRouter.open(this, note.bookId!!, note.locator)
            }
        }.show()
    }

    private fun showVersions(note: AnnotationEntity) = lifecycleScope.launch {
        val versions = db.readingDao().versions(note.id)
        if (versions.isEmpty()) { toast("Todavía no hay versiones anteriores"); return@launch }
        MaterialAlertDialogBuilder(this@KnowledgeActivity).setTitle("Versiones anteriores")
            .setItems(versions.map { DateFormat.getDateTimeInstance().format(Date(it.savedAt)) + " · " + it.body.take(45) }.toTypedArray()) { _, index ->
                val version = versions[index]
                MaterialAlertDialogBuilder(this@KnowledgeActivity).setTitle("Versión guardada")
                    .setMessage(version.body.ifBlank { "Sin texto de nota" })
                    .setNegativeButton("Cerrar", null)
                    .setPositiveButton("Recuperar versión") { _, _ -> lifecycleScope.launch {
                        try { repository.editAnnotation(note.id, note.updatedAt, version.body, version.color); toast("Versión recuperada") }
                        catch (e: IllegalStateException) { toast("La nota ha cambiado; ábrela de nuevo.") }
                    } }.show()
            }.show()
    }

    private fun button(title: String, description: String = title, action: () -> Unit) = MaterialButton(this).apply {
        text = title; contentDescription = description; minHeight = dp(48); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private class NotesAdapter(val click: (AnnotationEntity) -> Unit) : RecyclerView.Adapter<NotesAdapter.Holder>() {
        private var notes = emptyList<AnnotationEntity>()
        fun submit(value: List<AnnotationEntity>) { notes = value; notifyDataSetChanged() }
        class Holder(val view: TextView) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context).apply {
            setPadding(24, 24, 24, 24); textSize = 17f; setTextColor(0xFFE8DFCE.toInt()); minHeight = 64
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        })
        override fun getItemCount() = notes.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val note = notes[position]
            holder.view.text = buildString {
                append(label(note.kind)); append("  ·  "); append(DateFormat.getDateInstance().format(Date(note.updatedAt)))
                if (note.quote.isNotBlank()) append("\n“${note.quote.take(220)}”")
                if (note.body.isNotBlank()) append("\n${note.body.take(300)}")
                if (note.bookId == null) append("\nNota independiente")
            }
            holder.view.setOnClickListener { click(note) }
        }
    }
    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, KnowledgeActivity::class.java))
        private fun label(kind: String) = when (kind) { "HIGHLIGHT" -> "Subrayado"; "BOOKMARK" -> "Marcador"; else -> "Nota" }
    }
}
