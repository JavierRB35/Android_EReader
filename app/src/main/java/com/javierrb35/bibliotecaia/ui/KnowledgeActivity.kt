package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.javierrb35.bibliotecaia.R
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
    private val kindFilter = MutableStateFlow("ALL")
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bia_background))
        }

        val brand = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(12), 0)
        }
        brand.addView(TextView(this).apply {
            text = "◫  BibliotecaAI"
            textSize = 22f
            typeface = Typeface.SERIF
            setTextColor(getColor(R.color.bia_text))
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        brand.addView(button("⚙", "Abrir ajustes") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(brand)

        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(10))
        }
        intro.addView(TextView(this).apply {
            text = "Conocimiento"
            textSize = 33f
            typeface = Typeface.SERIF
            setTextColor(getColor(R.color.bia_text))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        intro.addView(TextView(this).apply {
            text = "Tus ideas, notas y reflexiones de lectura"
            textSize = 12f
            setTextColor(getColor(R.color.bia_text_secondary))
        })
        root.addView(intro)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), 0, dp(18), dp(7))
        }
        val tabButtons = listOf("ALL" to "Notas", "QUOTE" to "Fragmentos", "IDEAS" to "Ideas").map { (kind, title) ->
            button(title) { kindFilter.value = kind }.also {
                tabs.addView(it, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                })
            }
        }
        fun updateTabs(value: String) {
            tabButtons.forEachIndexed { index, tab ->
                val active = when (index) { 0 -> value == "ALL"; 1 -> value == "QUOTE"; else -> value == "IDEAS" }
                tab.backgroundTintList = ColorStateList.valueOf(
                    getColor(if (active) R.color.bia_accent else R.color.bia_surface))
                tab.setTextColor(getColor(if (active) R.color.bia_background else R.color.bia_text))
            }
        }
        updateTabs(kindFilter.value)
        tabButtons.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                kindFilter.value = when (index) { 0 -> "ALL"; 1 -> "QUOTE"; else -> "IDEAS" }
                updateTabs(kindFilter.value)
            }
        }
        root.addView(tabs)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val searchBox = TextInputLayout(this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Buscar notas y fragmentos"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = getColor(R.color.bia_surface)
            boxStrokeColor = getColor(R.color.bia_outline)
            setBoxCornerRadii(dp(17).toFloat(), dp(17).toFloat(),
                dp(17).toFloat(), dp(17).toFloat())
            setStartIconDrawable(R.drawable.ic_premium_search)
        }
        searchBox.addView(TextInputEditText(searchBox.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 14f
            doOnTextChanged { text, _, _, _ -> query.value = text.toString() }
        })
        controls.addView(searchBox)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, dp(2))
        }
        actionsRow.addView(button("Nueva nota") {
            AnnotationEditor.show(this, null, null, "", "NOTE")
        }.apply {
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.bia_accent))
            setTextColor(getColor(R.color.bia_background))
        }, LinearLayout.LayoutParams(0, dp(52), 1.25f).apply { marginEnd = dp(6) })
        actionsRow.addView(button("Exportar") {
            export.launch("BibliotecaIA-cuaderno.md")
        }.apply {
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.bia_surface_high))
            setTextColor(getColor(R.color.bia_text))
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        controls.addView(actionsRow)

        controls.addView(com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = "Mostrar papelera"
            textSize = 13f
            setTextColor(getColor(R.color.bia_text_secondary))
            isChecked = savedInstanceState?.getBoolean("premium_trash") ?: false
            trash.value = isChecked
            setOnCheckedChangeListener { _, checked -> trash.value = checked }
        })
        root.addView(controls)

        val empty = TextView(this).apply {
            textSize = 15f
            setTextColor(getColor(R.color.bia_text_secondary))
            setPadding(dp(24), dp(24), dp(24), 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(empty)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@KnowledgeActivity)
            clipToPadding = false
            setPadding(dp(16), dp(3), dp(16), dp(10))
        }
        val adapter = NotesAdapter { note -> actions(note) }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = layoutInflater.inflate(R.layout.premium_bottom_navigation, root, false)
        root.addView(nav)
        PremiumBottomBar.bind(this, nav, R.id.navKnowledge) {
            PremiumBottomBar.launchImportFromKnowledge(this)
        }
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(query, trash, kindFilter) { q, t, kind -> Triple(q, t, kind) }
                    .flatMapLatest { (q, t, kind) ->
                        db.readingDao().notebook(q, t).map { notes ->
                            when (kind) {
                                "QUOTE" -> notes.filter { it.quote.isNotBlank() }
                                "IDEAS" -> notes.filter { it.kind == "NOTE" && it.bookId == null }
                                else -> notes
                            }
                        }
                    }.collect { notes ->
                        adapter.submit(notes)
                        empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
                        empty.text = when {
                            query.value.isNotEmpty() -> "No hay resultados para esta búsqueda."
                            trash.value -> "La papelera está vacía."
                            kindFilter.value == "QUOTE" -> "Aquí aparecerán los fragmentos que guardes al leer."
                            kindFilter.value == "IDEAS" -> "Aquí aparecerán tus notas independientes."
                            else -> "Tu cuaderno empieza con una idea. Crea una nota o guarda un fragmento al leer."
                        }
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
        text = title; contentDescription = description; minHeight = dp(48)
        isAllCaps = false; cornerRadius = dp(16)
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private class NotesAdapter(val click: (AnnotationEntity) -> Unit) :
        RecyclerView.Adapter<NotesAdapter.Holder>() {

        private var notes = emptyList<AnnotationEntity>()
        fun submit(value: List<AnnotationEntity>) {
            notes = value
            notifyDataSetChanged()
        }

        class Holder(
            val card: MaterialCardView,
            val title: TextView,
            val quote: TextView,
            val body: TextView,
            val date: TextView,
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            fun d(value: Int) = (value * density).toInt()
            val card = MaterialCardView(ctx).apply {
                radius = d(18).toFloat()
                strokeColor = ctx.getColor(R.color.bia_accent_low)
                strokeWidth = d(1)
                setCardBackgroundColor(ctx.getColor(R.color.bia_surface))
                cardElevation = 0f
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    setMargins(d(3), d(7), d(3), d(7))
                }
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(18), d(17), d(18), d(15))
            }
            val heading = TextView(ctx).apply {
                textSize = 20f
                typeface = Typeface.SERIF
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ctx.getColor(R.color.bia_text))
            }
            val quote = TextView(ctx).apply {
                textSize = 14f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ctx.getColor(R.color.bia_text))
                setPadding(0, d(10), 0, 0)
            }
            val body = TextView(ctx).apply {
                textSize = 13f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ctx.getColor(R.color.bia_text_secondary))
                setPadding(0, d(8), 0, d(8))
            }
            val date = TextView(ctx).apply {
                textSize = 12f
                setTextColor(ctx.getColor(R.color.bia_accent))
                setPadding(0, d(8), 0, 0)
            }
            box.addView(heading)
            box.addView(quote)
            box.addView(body)
            val divider = View(ctx).apply {
                setBackgroundColor(ctx.getColor(R.color.bia_outline))
            }
            box.addView(divider, LinearLayout.LayoutParams(-1, d(1)))
            box.addView(date)
            card.addView(box)
            return Holder(card, heading, quote, body, date)
        }

        override fun getItemCount(): Int = notes.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val note = notes[position]
            val firstLine = note.body.lineSequence().firstOrNull { it.isNotBlank() }
                ?: note.quote.lineSequence().firstOrNull { it.isNotBlank() }
                ?: label(note.kind)
            holder.title.text = firstLine.take(80)
            holder.quote.text = if (note.quote.isNotBlank()) "“${note.quote}”" else ""
            holder.quote.visibility = if (note.quote.isNotBlank()) View.VISIBLE else View.GONE
            holder.body.text = if (note.body.isNotBlank() && note.quote.isNotBlank())
                note.body else if (note.body.isNotBlank() && firstLine != note.body.trim())
                note.body else ""
            holder.body.visibility = if (holder.body.text.isNotBlank()) View.VISIBLE else View.GONE
            holder.date.text = "${label(note.kind)}  ·  ${DateFormat.getDateInstance().format(Date(note.updatedAt))}" +
                if (note.deletedAt != null) "  ·  Papelera" else ""
            holder.card.contentDescription = "${label(note.kind)}: ${firstLine.take(80)}"
            holder.card.setOnClickListener { click(note) }
        }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, KnowledgeActivity::class.java))
        private fun label(kind: String) = when (kind) { "HIGHLIGHT" -> "Subrayado"; "BOOKMARK" -> "Marcador"; else -> "Nota" }
    }
}
