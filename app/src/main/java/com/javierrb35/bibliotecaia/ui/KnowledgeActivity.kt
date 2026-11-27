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
    private val bookFilter = MutableStateFlow(KnowledgeBookIndex.ALL)
    private val sortFilter = MutableStateFlow(KnowledgeBookIndex.Sort.MODIFIED)
    private var savedAuthor = ""
    private var bookTitles = emptyMap<String, String>()
    private var bookAuthors = emptyMap<String, String>()
    private var availableBookChoices = emptyList<KnowledgeBookIndex.Choice>()
    private val selectedIds = linkedSetOf<String>()
    private var visibleNotes = emptyList<AnnotationEntity>()
    private var pendingExportIds: List<String>? = emptyList()
    private val exportMarkdown = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {
        uri -> finishExport(uri, true)
    }
    private val exportText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        uri -> finishExport(uri, false)
    }

    private fun chooseExport(ids: Set<String>? = null) {
        val captured = ids?.toList()
        MaterialAlertDialogBuilder(this).setTitle("Exportar " + (captured?.let { "${it.size} anotaciones" } ?: "cuaderno"))
            .setItems(arrayOf("Markdown (.md)", "Texto (.txt)")) { _, index ->
                pendingExportIds = captured
                if (index == 0) exportMarkdown.launch("BibliotecaIA-cuaderno.md")
                else exportText.launch("BibliotecaIA-cuaderno.txt")
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun finishExport(uri: android.net.Uri?, markdown: Boolean) {
        val ids = pendingExportIds
        pendingExportIds = emptyList()
        if (uri == null) return
        lifecycleScope.launch {
            try {
                val dao = db.readingDao()
                val notes = if (ids == null) dao.allNotes() else ids.mapNotNull { dao.annotation(it) }
                val scoped = KnowledgeExport.scoped(notes, ids?.toSet())
                if (scoped.isEmpty()) { toast("No hay anotaciones disponibles para exportar."); return@launch }
                val books = scoped.mapNotNull { it.bookId }.distinct().mapNotNull { db.bookDao().findById(it) }
                withContext(Dispatchers.IO) {
                    val document = KnowledgeExport.render(scoped,
                        books.associate { it.id to it.title }, books.associate { it.id to it.authors.orEmpty() }, markdown)
                    val stream = contentResolver.openOutputStream(uri, "wt") ?: error("No destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(document) }
                }
                toast("Anotaciones exportadas: ${scoped.size}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast("No se pudo exportar. Comprueba el destino y vuelve a intentarlo.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedIds.addAll(savedInstanceState?.getStringArrayList("selected_notes").orEmpty())
        pendingExportIds = if (savedInstanceState?.getBoolean("export_all") == true) null
            else savedInstanceState?.getStringArrayList("export_ids").orEmpty()
        bookFilter.value = savedInstanceState?.getString("selected_book_filter")
            ?: KnowledgeBookIndex.ALL
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bia_background))
        }

        trash.value = savedInstanceState?.getBoolean("premium_trash") ?: false
        kindFilter.value = savedInstanceState?.getString("selected_kind_filter") ?: "ALL"
        query.value = savedInstanceState?.getString("knowledge_query").orEmpty()
        sortFilter.value = KnowledgeBookIndex.Sort.entries.firstOrNull { it.name == savedInstanceState?.getString("knowledge_sort") }
            ?: KnowledgeBookIndex.Sort.MODIFIED
        val brand = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(12), 0)
        }
        brand.addView(TextView(this).apply {
            text = "Conocimiento"
            textSize = 26f
            typeface = Typeface.SERIF
            setTextColor(getColor(R.color.bia_text))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val menuButton = button("⋮", "Opciones de conocimiento") {}
        menuButton.setOnClickListener {
            PopupMenu(this, menuButton).apply {
                menu.add("Nueva idea")
                menu.add("Exportar cuaderno")
                menu.add(if (trash.value) "Salir de papelera" else "Mostrar papelera")
                menu.add("Ajustes")
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        "Nueva idea" -> AnnotationEditor.show(this@KnowledgeActivity, null, null, "", "NOTE")
                        "Exportar cuaderno" -> chooseExport()
                        "Mostrar papelera", "Salir de papelera" -> trash.value = !trash.value
                        "Ajustes" -> startActivity(Intent(this@KnowledgeActivity, SettingsActivity::class.java))
                    }
                    true
                }
                show()
            }
        }
        brand.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(brand)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(3))
        }
        val kinds = listOf("ALL" to "Todo", "NOTE" to "Notas", "QUOTE" to "Fragmentos",
            "BOOKMARK" to "Marcadores", "IDEAS" to "Ideas")
        val tabButtons = kinds.map { (kind, title) ->
            button(title) { kindFilter.value = kind }.also {
                it.textSize = 13f
                it.setSingleLine(true)
                tabs.addView(it, LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = dp(4)
                })
            }
        }
        fun updateTabs(value: String) {
            tabButtons.forEachIndexed { index, tab ->
                val active = kinds[index].first == value
                tab.isSelected = active
                tab.backgroundTintList = ColorStateList.valueOf(
                    getColor(if (active) R.color.bia_accent else R.color.bia_surface))
                tab.setTextColor(getColor(if (active) R.color.bia_background else R.color.bia_text))
            }
        }
        updateTabs(kindFilter.value)
        tabButtons.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                kindFilter.value = kinds[index].first
                updateTabs(kindFilter.value)
            }
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(tabs)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val searchBox = TextInputLayout(this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Buscar en mi conocimiento"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = getColor(R.color.bia_surface)
            boxStrokeColor = getColor(R.color.bia_outline)
            setBoxCornerRadii(dp(17).toFloat(), dp(17).toFloat(),
                dp(17).toFloat(), dp(17).toFloat())
            setStartIconDrawable(R.drawable.ic_premium_search)
        }
        val searchInput = TextInputEditText(searchBox.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 14f
            setText(query.value)
            doOnTextChanged { text, _, _, _ -> query.value = text.toString() }
        }
        searchBox.addView(searchInput)
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
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        val bookIndex = button("Todas las obras", "Filtrar notas por libro") {
            val choices = availableBookChoices
            val labels = choices.map { choice ->
                choice.title + " · " + choice.count + if (choice.count == 1) " apunte" else " apuntes"
            }.toTypedArray()
            val selected = choices.indexOfFirst { it.id == bookFilter.value }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("Notas por libro")
                .setSingleChoiceItems(labels, selected) { dialog, index ->
                    bookFilter.value = choices[index].id
                    dialog.dismiss()
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }.apply {
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.bia_surface))
            setTextColor(getColor(R.color.bia_text))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(getColor(R.color.bia_outline))
        }
        actionsRow.addView(bookIndex, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(4)
        })
        controls.addView(actionsRow)
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.bia_text_secondary))
            setPadding(0, dp(4), 0, dp(4))
        }
        val authorFilter = MutableStateFlow(savedInstanceState?.getString("knowledge_author").orEmpty())
        val authorButton = button("Autor") {
            val authors = bookAuthors.values.filter { it.isNotBlank() }.distinct().sorted()
            val options = listOf("Todos los autores") + authors
            MaterialAlertDialogBuilder(this).setTitle("Filtrar por autor")
                .setSingleChoiceItems(options.toTypedArray(), (authors.indexOf(authorFilter.value) + 1)) { dialog, index ->
                    authorFilter.value = if (index == 0) "" else authors[index - 1]
                    dialog.dismiss()
                }.setNegativeButton("Cerrar", null).show()
        }
        controls.addView(authorButton, LinearLayout.LayoutParams(-1, -2))
        val filterTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        filterTools.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        val sortButton = button("Ordenar") {
            val options = KnowledgeBookIndex.Sort.entries
            MaterialAlertDialogBuilder(this).setTitle("Ordenar anotaciones")
                .setSingleChoiceItems(options.map { it.title }.toTypedArray(), options.indexOf(sortFilter.value)) { dialog, index ->
                    sortFilter.value = options[index]
                    dialog.dismiss()
                }.setNegativeButton("Cerrar", null).show()
        }.apply { textSize = 12f }
        filterTools.addView(sortButton, LinearLayout.LayoutParams(-2, -2))
        val clearButton = button("Limpiar", "Limpiar filtros de conocimiento") {
            searchInput.setText("")
            bookFilter.value = KnowledgeBookIndex.ALL
            kindFilter.value = "ALL"
            sortFilter.value = KnowledgeBookIndex.Sort.MODIFIED
            authorFilter.value = ""
            updateTabs("ALL")
        }.apply { textSize = 12f }
        filterTools.addView(clearButton, LinearLayout.LayoutParams(-2, -2))
        controls.addView(filterTools)
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
        val selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(2), dp(16), dp(2))
        }
        val selectionCount = TextView(this).apply {
            setTextColor(getColor(R.color.bia_text))
            textSize = 14f
        }
        selectionBar.addView(selectionCount)
        val selectionActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selectionBar.addView(selectionActions)
        lateinit var adapter: NotesAdapter
        fun refreshSelection() {
            selectionCount.text = "${selectedIds.size} seleccionadas"
            selectionBar.visibility = if (selectedIds.isEmpty()) View.GONE else View.VISIBLE
            adapter.setSelection(selectedIds)
        }
        fun toggle(note: AnnotationEntity) {
            if (!selectedIds.add(note.id)) selectedIds.remove(note.id)
            refreshSelection()
        }
        adapter = NotesAdapter(
            click = { note -> if (selectedIds.isEmpty()) showDetail(note) else toggle(note) },
            longClick = { note -> toggle(note) })
        list.adapter = adapter
        selectionActions.addView(button("Todas") {
            selectedIds.addAll(visibleNotes.map { it.id })
            refreshSelection()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        selectionActions.addView(button("Exportar") {
            chooseExport(selectedIds.toSet())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        selectionActions.addView(button("Cancelar") {
            selectedIds.clear()
            refreshSelection()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(selectionBar)
        
        val nav = layoutInflater.inflate(R.layout.premium_bottom_navigation, root, false)
        root.addView(nav)
        PremiumBottomBar.bind(this, nav, R.id.navKnowledge) {
            PremiumBottomBar.launchImportFromKnowledge(this)
        }
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(query, trash, kindFilter, bookFilter, sortFilter) { q, t, kind, book, sort ->
                    arrayOf(q, if (t) "1" else "0", kind, book, sort.name)
                }.combine(authorFilter) { state, author -> state + author }.flatMapLatest { state ->
                    combine(
                        db.readingDao().notebook("", state[1] == "1"),
                        db.bookDao().observeAll()
                    ) { notes, books ->
                        val titles = books.associate { it.id to it.title }
                        val authors = books.associate { it.id to it.authors.orEmpty() }
                        val matches = KnowledgeBookIndex.search(notes, state[0], titles, authors)
                        val kindFiltered = KnowledgeBookIndex.byKind(matches, state[2]).filter { note ->
                            state[5].isBlank() || authors[note.bookId] == state[5]
                        }
                        val choices = KnowledgeBookIndex.choices(kindFiltered, titles)
                        val visible = KnowledgeBookIndex.sorted(KnowledgeBookIndex.byBook(kindFiltered, state[3]),
                            KnowledgeBookIndex.Sort.valueOf(state[4]), titles, state[0])
                        Pair(Triple(visible, titles, choices), authors)
                    }
                }.collect { (result, authors) ->
                    val (notes, titles, choices) = result
                    bookTitles = titles
                    bookAuthors = authors
                    savedAuthor = authorFilter.value
                    authorButton.text = savedAuthor.ifBlank { "Todos los autores" }
                    availableBookChoices = choices
                    bookIndex.text = "Libro: " + (choices.firstOrNull {
                        it.id == bookFilter.value
                    }?.title ?: "Todas las obras")
                    status.text = (if (trash.value) "Papelera · " else "") + "${notes.size} apuntes"
                    sortButton.contentDescription = "Ordenar: " + sortFilter.value.title
                    clearButton.visibility = if (query.value.isNotBlank() || bookFilter.value != KnowledgeBookIndex.ALL ||
                        kindFilter.value != "ALL" || sortFilter.value != KnowledgeBookIndex.Sort.MODIFIED || savedAuthor.isNotBlank()) View.VISIBLE else View.GONE
                    visibleNotes = notes
                    selectedIds.retainAll(notes.map { it.id }.toSet())
                    adapter.submit(notes, titles)
                    refreshSelection()
                    empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
                    empty.text = when {
                        query.value.isNotEmpty() -> "No hay resultados para esta búsqueda."
                        bookFilter.value != KnowledgeBookIndex.ALL ->
                            "No hay anotaciones de esta obra con los filtros actuales."
                        trash.value -> "La papelera está vacía."
                        kindFilter.value == "QUOTE" -> "Aquí aparecerán los fragmentos que guardes al leer."
                        kindFilter.value == "IDEAS" -> "Aquí aparecerán tus notas independientes."
                        else -> "Tu cuaderno empieza con una idea. Crea una nota o guarda un fragmento al leer."
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected_book_filter", bookFilter.value)
        outState.putString("selected_kind_filter", kindFilter.value)
        outState.putString("knowledge_query", query.value)
        outState.putBoolean("premium_trash", trash.value)
        outState.putString("knowledge_author", savedAuthor)
        outState.putString("knowledge_sort", sortFilter.value.name)
        outState.putStringArrayList("selected_notes", ArrayList(selectedIds))
        outState.putBoolean("export_all", pendingExportIds == null)
        outState.putStringArrayList("export_ids", ArrayList(pendingExportIds.orEmpty()))
        super.onSaveInstanceState(outState)
    }

    private fun displayKind(note: AnnotationEntity): String =
        if (note.kind == "NOTE" && note.bookId == null) "Idea" else label(note.kind)

    private fun sourceTitle(note: AnnotationEntity): String =
        note.bookId?.let { bookTitles[it] ?: "Obra no disponible" } ?: "Sin libro asociado"

    private fun detailText(note: AnnotationEntity): String = buildString {
        appendLine(displayKind(note))
        appendLine(sourceTitle(note))
        note.bookId?.let { bookAuthors[it]?.takeIf(String::isNotBlank)?.let { author -> appendLine(author) } }
        if (note.quote.isNotBlank()) appendLine("\nTexto original:\n${note.quote}")
        if (note.body.isNotBlank()) appendLine("\nReflexión personal:\n${note.body}")
    }.trimEnd()

    private fun showDetail(note: AnnotationEntity) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(10))
        }
        fun section(title: String, value: String, literary: Boolean = false) {
            content.addView(TextView(this).apply {
                text = title
                textSize = 12f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(getColor(R.color.bia_accent))
                setPadding(0, dp(12), 0, dp(5))
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            })
            content.addView(TextView(this).apply {
                text = value
                textSize = 16f
                if (literary) typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                setTextColor(getColor(R.color.bia_text))
                setTextIsSelectable(true)
            })
        }
        val author = note.bookId?.let { bookAuthors[it] }.orEmpty()
        section("Origen", listOf(sourceTitle(note), author).filter { it.isNotBlank() }.joinToString("\n"))
        if (note.quote.isNotBlank()) section("Texto original", note.quote, true)
        if (note.body.isNotBlank()) section("Reflexión personal", note.body)
        section("Fecha", DateFormat.getDateTimeInstance().format(Date(note.updatedAt)))
        val scroll = ScrollView(this).apply { addView(content) }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(displayKind(note))
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
        if (note.deletedAt != null) {
            builder.setPositiveButton("Restaurar") { _, _ ->
                lifecycleScope.launch { repository.setDeleted(note.id, false) }
            }
        } else {
            builder.setPositiveButton("Editar") { _, _ -> AnnotationEditor.edit(this, note) }
            builder.setNeutralButton("Más opciones") { _, _ -> showDetailActions(note) }
        }
        val dialog = builder.create()
        if (note.bookId != null && note.deletedAt == null) {
            content.addView(button("Abrir origen") {
                dialog.dismiss()
                ReaderRouter.open(this, note.bookId, note.locator)
            })
        }
        dialog.show()
    }

    private fun showDetailActions(note: AnnotationEntity) {
        val items = listOf("Copiar", "Compartir", "Versiones anteriores", "Mover a papelera", "Exportar anotación")
        MaterialAlertDialogBuilder(this).setTitle("Opciones de anotación")
            .setItems(items.toTypedArray()) { _, index ->
                when (index) {
                    0 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(displayKind(note), detailText(note)))
                        toast("Anotación copiada")
                    }
                    1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, detailText(note))
                    }, "Compartir anotación"))
                    2 -> showVersions(note)
                    3 -> MaterialAlertDialogBuilder(this)
                        .setTitle("Mover a papelera")
                        .setMessage("Podrás recuperar la anotación desde la papelera.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Mover") { _, _ ->
                            lifecycleScope.launch { repository.setDeleted(note.id, true) }
                        }.show()
                    4 -> chooseExport(setOf(note.id))
                }
            }
            .setNegativeButton("Volver") { _, _ -> showDetail(note) }
            .show()
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

    private class NotesAdapter(val click: (AnnotationEntity) -> Unit,
                               val longClick: (AnnotationEntity) -> Unit) :
        RecyclerView.Adapter<NotesAdapter.Holder>() {

        private var notes = emptyList<AnnotationEntity>()
        private var titles = emptyMap<String, String>()
        private var selected = emptySet<String>()
        fun setSelection(ids: Set<String>) {
            val next = ids.toSet()
            if (selected == next) return
            val old = selected
            selected = next
            notes.forEachIndexed { index, note ->
                if ((note.id in old) != (note.id in next)) notifyItemChanged(index)
            }
        }
        fun submit(value: List<AnnotationEntity>, bookTitles: Map<String, String>) {
            notes = value
            titles = bookTitles
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
                    setMargins(d(3), d(4), d(3), d(4))
                }
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(14), d(12), d(14), d(12))
            }
            val heading = TextView(ctx).apply {
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ctx.getColor(R.color.bia_text))
            }
            val quote = TextView(ctx).apply {
                textSize = 14f
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
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
            val source = note.bookId?.let { titles[it] ?: "Obra no disponible" } ?: "Nota independiente"
            holder.date.text = source + "  ·  " + label(note.kind) + "  ·  " +
                DateFormat.getDateInstance().format(Date(note.updatedAt)) +
                if (note.deletedAt != null) "  ·  Papelera" else ""
            holder.card.contentDescription = source + ", " +
                label(note.kind) + ": " + firstLine.take(80)
            val checked = note.id in selected
            holder.card.isSelected = checked
            holder.card.strokeColor = holder.card.context.getColor(if (checked) R.color.bia_accent else R.color.bia_accent_low)
            holder.card.strokeWidth = ((if (checked) 2 else 1) * holder.card.resources.displayMetrics.density).toInt()
            if (checked) holder.card.contentDescription = "Seleccionada: " + holder.card.contentDescription
            holder.card.setOnClickListener { click(note) }
            holder.card.setOnLongClickListener { longClick(note); true }
        }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, KnowledgeActivity::class.java))
        private fun label(kind: String) = when (kind) { "HIGHLIGHT" -> "Fragmento"; "BOOKMARK" -> "Marcador"; else -> "Nota" }
    }
}
