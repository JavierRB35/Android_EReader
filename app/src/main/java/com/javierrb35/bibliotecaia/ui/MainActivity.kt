package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.BuildConfig
import com.javierrb35.bibliotecaia.backup.BackupRoutine
import com.javierrb35.bibliotecaia.backup.CentralCatalogue
import com.javierrb35.bibliotecaia.sync.ReadingPositionSafSync
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.importer.BookImporter
import com.javierrb35.bibliotecaia.importer.BatchImport
import com.javierrb35.bibliotecaia.sync.SharedLibrary
import com.javierrb35.bibliotecaia.sync.SharedWriteAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Library home: reading-first navigation without sacrificing search, import or organization.
 * Book detail, readers and storage remain owned by their respective agents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : AppCompatActivity() {
    private val db by lazy { AppDatabase.get(this) }
    private lateinit var adapter: BookAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private val selection = LibrarySelection()
    private var visibleBooks = emptyList<com.javierrb35.bibliotecaia.data.BookEntity>()
    private var editions = emptyMap<String, List<com.javierrb35.bibliotecaia.data.BookEntity>>()
    private var selectionBack: androidx.activity.OnBackPressedCallback? = null
    private var batchRunning = false
    private var queueSubmitting = false
    private var queuedBatch: androidx.work.WorkInfo? = null
    private var dismissedBatch: String? = null
    private var batchHistory = emptyList<androidx.work.WorkInfo>()
    @Volatile private var cancelBatch = false
    private var batchJob: kotlinx.coroutines.Job? = null
    private var batchAction: LibraryBatchAction? = null
    private var importing = false
    private var pendingImportCentral = false
    private var pickerOpen = false

    private val queryFlow = MutableStateFlow("")
    private val statusFlow = MutableStateFlow("ALL")
    private val favoriteOnlyFlow = MutableStateFlow(false)
    private val collectionFlow = MutableStateFlow("")
    private val centralFlow = MutableStateFlow(false to null as Set<String>?)
    private val detailLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == BookDetailActivity.RESULT_LOCAL_REMOVED) {
            // A successful removal always returns to the downloaded-only shelf.
            setCentralMode(false)
            findViewById<View>(R.id.recentBookCard).visibility = View.GONE
        }
    }
    private fun openDetail(id: String) = detailLauncher.launch(
        Intent(this, BookDetailActivity::class.java).putExtra("book_id", id))
    private fun openEdition(id: String) {
        val alternatives = editions[id].orEmpty()
        if (alternatives.size < 2) { openDetail(id); return }
        val labels = alternatives.map { book ->
            book.format.name + " · " +
                (if (CentralCatalogue.local(book)) "En dispositivo" else "En biblioteca") +
                " · " + android.text.format.Formatter.formatShortFileSize(this, book.sizeBytes)
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(alternatives.first().title)
            .setItems(labels) { _, index -> openDetail(alternatives[index].id) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private val centralPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                BackupRoutine.connectCentral(applicationContext, db, BuildConfig.VERSION_NAME, uri)
                setCentralMode(true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(this@MainActivity, e.message ?: "No se pudo abrir la biblioteca central.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun setCentralMode(central: Boolean) {
        if (central != centralFlow.value.first) {
            selection.clear()
            visibleBooks = emptyList()
            editions = emptyMap()
            if (::adapter.isInitialized) {
                adapter.submit(emptyList())
                renderSelection()
            }
        }
        centralFlow.value = central to BackupRoutine.browseHashes(applicationContext)
        findViewById<TextView>(R.id.btnCentralLibrary).setText(
            if (central) R.string.central_device else R.string.central_complete)
        findViewById<TextView>(R.id.txtTitle).text =
            if (central) getString(R.string.central_complete)
            else intent.getStringExtra(EXTRA_COLLECTION_NAME)?.let {
                getString(R.string.collection_view_title, it)
            } ?: getString(R.string.central_device_title)
    }

    private fun openCentral() {
        if (centralFlow.value.first) { setCentralMode(false); return }
        if (BackupRoutine.centralUri(applicationContext) == null) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Biblioteca compartida")
                .setMessage("Configura Drive para compartir libros y posiciones entre dispositivos. Si solo quieres consultar una copia de seguridad antigua, puedes abrirla sin modificarla.")
                .setPositiveButton("Configurar biblioteca") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNeutralButton("Abrir copia antigua") { _, _ ->
                    centralPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        setCentralMode(true)
        refreshCentralCatalogue()
    }

    private var catalogueRefresh: kotlinx.coroutines.Job? = null
    private fun refreshCentralCatalogue() {
        if (catalogueRefresh?.isActive == true) return
        catalogueRefresh = lifecycleScope.launch {
            try {
                BackupRoutine.refreshCentral(applicationContext, db, BuildConfig.VERSION_NAME)
                if (centralFlow.value.first) setCentralMode(true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (centralFlow.value.first) Toast.makeText(this@MainActivity,
                    "Catálogo guardado. " + (e.message ?: "Actualización no disponible."),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        pickerOpen = false
        if (uris.isNotEmpty()) importBooks(uris, pendingImportCentral)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pendingImportCentral = savedInstanceState?.getBoolean("pending_import_central") == true
        pickerOpen = savedInstanceState?.getBoolean("import_picker_open") == true

        collectionFlow.value = intent.getStringExtra(EXTRA_COLLECTION_ID).orEmpty()
        intent.getStringExtra(EXTRA_COLLECTION_NAME)?.let { name ->
            findViewById<TextView>(R.id.txtTitle).text =
                getString(R.string.collection_view_title, name)
        }

        recycler = findViewById(R.id.recyclerBooks)
        emptyState = findViewById(R.id.txtEmpty)
        adapter = BookAdapter(onLongClick = { book ->
            if (!batchRunning && !importing && visibleBooks.any { it.id == book.id }) {
                selection.toggle(book.id)
                renderSelection()
            }
        }) { book ->
            if (selection.active) {
                selection.toggle(book.id)
                renderSelection()
            } else openEdition(book.id)
        }
        applyViewMode(loadViewMode())
        recycler.adapter = adapter
        findViewById<View>(R.id.btnSelectAllBooks).setOnClickListener {
            selection.toggleAll(visibleBooks.map { it.id })
            renderSelection()
        }
        findViewById<View>(R.id.btnClearBookSelection).setOnClickListener {
            selection.clear()
            renderSelection()
        }
        findViewById<View>(R.id.btnBatchPrimary).setOnClickListener {
            startBookBatch(if (centralFlow.value.first) LibraryBatchAction.DOWNLOAD else LibraryBatchAction.SAVE)
        }
        findViewById<View>(R.id.btnBatchSecondary).setOnClickListener {
            if (centralFlow.value.first) confirmBatchDeletion() else confirmBatchRemoval()
        }
        findViewById<View>(R.id.txtBookBatchProgress).setOnClickListener {
            if (!batchRunning && batchHistory.isNotEmpty()) {
                val details = batchHistory.takeLast(10).asReversed().joinToString("\n\n") { work ->
                    LibraryBatchQueue.label(work) + "\n" + (work.outputData.getString("summary")
                        ?: "Operación interrumpida; comprueba los libros y reintenta.")
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Resultados de operaciones").setMessage(details)
                    .setPositiveButton("Cerrar", null).show()
            }
        }
        findViewById<View>(R.id.btnCancelBookBatch).setOnClickListener {
            if (!batchRunning) {
                val work = queuedBatch ?: return@setOnClickListener
                if (work.state.isFinished) {
                    dismissedBatch = work.id.toString()
                    getSharedPreferences("batch_ui", MODE_PRIVATE).edit().putString("dismissed", dismissedBatch).apply()
                    findViewById<View>(R.id.bookBatchProgress).visibility = View.GONE
                } else {
                    findViewById<View>(R.id.btnCancelBookBatch).isEnabled = false
                    findViewById<TextView>(R.id.txtBookBatchProgress).text = "Deteniendo operación…"
                    lifecycleScope.launch {
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                LibraryBatchQueue.stop(applicationContext, work.id)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            findViewById<View>(R.id.btnCancelBookBatch).isEnabled = true
                            Toast.makeText(this@MainActivity, "No se pudo solicitar la detención. Reintenta.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                return@setOnClickListener
            }
            cancelBatch = true
            findViewById<View>(R.id.btnCancelBookBatch).isEnabled = false
            if (batchAction == LibraryBatchAction.DOWNLOAD) {
                findViewById<TextView>(R.id.txtBookBatchProgress).text =
                    "Cancelando descarga… Puedes seguir navegando."
                batchJob?.cancel()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                selection.clear()
                renderSelection()
            }
        }.also { selectionBack = it })

        val search = findViewById<EditText>(R.id.txtSearch)
        search.setText(savedInstanceState?.getString(STATE_QUERY).orEmpty())
        queryFlow.value = search.text?.toString()?.trim().orEmpty()
        search.doOnTextChanged { text, _, _, _ ->
            selection.clear()
            renderSelection()
            queryFlow.value = text?.toString()?.trim().orEmpty()
        }

        val shelves = findViewById<ChipGroup>(R.id.chipShelves)
        val restoredShelf = savedInstanceState?.getInt(STATE_SHELF, R.id.chipAll) ?: R.id.chipAll
        shelves.check(restoredShelf)
        updateShelfFilter(shelves.checkedChipId)
        shelves.setOnCheckedStateChangeListener { group, _ ->
            selection.clear()
            renderSelection()
            updateShelfFilter(group.checkedChipId)
        }

        findViewById<View>(R.id.btnClearFilters).setOnClickListener {
            search.text?.clear()
            shelves.check(R.id.chipAll)
            findViewById<HorizontalScrollView>(R.id.scrollShelves).smoothScrollTo(0, 0)
        }
        findViewById<ImageButton>(R.id.btnViewMode).setOnClickListener { toggleViewMode() }
        findViewById<View>(R.id.btnOrganize).setOnClickListener {
            startActivity(Intent(this, OrganizeActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnStorageSetup).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(StorageSetupWizard.EXTRA_OPEN, true))
        }
        PremiumBottomBar.bind(this, findViewById(R.id.mainNavigation), R.id.navLibrary) {
            openImportPicker()
        }
        findViewById<View>(R.id.btnCentralLibrary).setOnClickListener { openCentral() }
        setCentralMode(savedInstanceState?.getBoolean("central_mode") == true)
        findViewById<View>(R.id.btnMissingOriginals).setOnClickListener { showMissingOriginals() }
        observeCatalog()
        observeBatchQueue()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.javierrb35.bibliotecaia.sync.SharedLibrary.changes.collect {
                    if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(applicationContext))
                        setCentralMode(centralFlow.value.first)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SharedLibrary.coverChanges.collect { adapter.refreshCovers() }
            }
        }
        consumeImportIntent(intent)
    }

    // Notification returns to this Activity without leaving the application.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeImportIntent(intent)
    }

    private fun consumeImportIntent(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_IMPORT, false)) return
        intent.removeExtra(EXTRA_OPEN_IMPORT)
        findViewById<View>(R.id.btnImport).post { openImportPicker() }
    }

    private fun openImportPicker() {
        if (importing || pickerOpen || batchRunning) return
        pendingImportCentral = centralFlow.value.first
        if (pendingImportCentral && !checkSharedImportAccess()) return
        pickerOpen = true
        try { importLauncher.launch(IMPORT_MIME_TYPES) }
        catch (e: Exception) {
            pickerOpen = false
            Toast.makeText(this, "No se pudo abrir el selector de libros.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<View>(R.id.btnStorageSetup).visibility =
            if (StorageSetupWizard.pending(applicationContext)) View.VISIBLE else View.GONE
        setCentralMode(centralFlow.value.first)
        lifecycleScope.launch {
            if (centralFlow.value.first && BackupRoutine.centralUri(applicationContext) != null &&
                !SharedLibrary.namedFolderMode(applicationContext)) refreshCentralCatalogue()
            refreshMissingOriginals()
        }
        lifecycleScope.launch {
            // Pull peer positions before any automatic ZIP backup attempt.
            // Do not treat a failed peer read as evidence that the remote is current.
            val shared = com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(applicationContext)
            val remoteChecked = if (shared) {
                try {
                    ReadingPositionSafSync.ensureAutomaticFolder(applicationContext, db)
                    ReadingPositionSafSync.refreshForRead(applicationContext, db)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    com.javierrb35.bibliotecaia.sync.SharedLibrary.recordFailure(applicationContext, e)
                }
                com.javierrb35.bibliotecaia.sync.SharedLibrary.schedule(applicationContext)
                false
            } else ReadingPositionSafSync.refresh(applicationContext, db)
            // A shared ZIP remains a single-writer backup, NOT a sync channel.
            // With peer devices configured, do not silently overwrite it with
            // a possibly stale full library; keep manual backup in Settings.
            if (remoteChecked && ReadingPositionSafSync.status(applicationContext).peerCount == 0) {
                BackupRoutine.refreshOnLibraryOpen(applicationContext, BuildConfig.VERSION_NAME)
            }
        }
        // Retomar lectura is local UI; it must not await a remote pull.
        lifecycleScope.launch {
            val latest = db.backupDao().booksAll().filter {
                it.lastOpenedAt != null && it.localPath.isNotBlank() && java.io.File(it.localPath).isFile
            }.maxByOrNull { it.lastOpenedAt ?: 0L }
            val card = findViewById<View>(R.id.recentBookCard)
            // Collection pages stay focused on their own content.
            if (latest == null || collectionFlow.value.isNotEmpty()) {
                card.visibility = View.GONE
                card.setOnClickListener(null)
            } else {
                findViewById<TextView>(R.id.txtRecentBook).text = latest.title
                val percent = (latest.progress.coerceIn(0f, 1f) * 100).toInt()
                findViewById<TextView>(R.id.txtRecentProgress).text =
                    getString(R.string.bia_progress, percent)
                findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(
                    R.id.recentProgress).setProgressCompat(percent, false)
                findViewById<TextView>(R.id.txtRecentCoverFallback).text = latest.title
                val coverView = findViewById<ImageView>(R.id.imgRecentCover)
                coverView.setImageDrawable(null)
                card.contentDescription = getString(R.string.bia_ux_continue_a11y, latest.title)
                card.setOnClickListener { ReaderRouter.open(this@MainActivity, latest.id) }
                card.visibility = View.VISIBLE
                val bitmap = try {
                    BookCoverLoader.load(applicationContext, latest)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A missing/corrupt original cannot crash the home screen.
                    null
                }
                if (bitmap != null && !isDestroyed) coverView.setImageBitmap(bitmap)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("central_mode", centralFlow.value.first)
        outState.putBoolean("pending_import_central", pendingImportCentral)
        outState.putBoolean("import_picker_open", pickerOpen)
        outState.putString(STATE_QUERY, findViewById<EditText>(R.id.txtSearch).text?.toString())
        outState.putInt(STATE_SHELF, findViewById<ChipGroup>(R.id.chipShelves).checkedChipId)
        super.onSaveInstanceState(outState)
    }

    private fun updateShelfFilter(checked: Int) {
        statusFlow.value = when (checked) {
            R.id.chipPending -> "PENDING"
            R.id.chipReading -> "READING"
            R.id.chipFinished -> "FINISHED"
            R.id.chipAbandoned -> "ABANDONED"
            else -> "ALL"
        }
        favoriteOnlyFlow.value = checked == R.id.chipFavorites
    }

    private fun observeCatalog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(queryFlow, statusFlow, favoriteOnlyFlow, collectionFlow, centralFlow) { q, s, f, c, central ->
                    db.bookDao().search(q, s, if (f) 1 else 0, c) to central
                }.flatMapLatest { (flow, mode) ->
                    flow.map { all -> CentralCatalogue.visible(all, mode.first, mode.second) }
                }.collect { books ->
                    val groups = if (centralFlow.value.first) BibliographicEditions.group(books)
                        else books.map { listOf(it) }
                    visibleBooks = groups.map { it.first() }
                    editions = groups.associateBy { it.first().id }
                    selection.retain(visibleBooks.map { it.id })
                    adapter.submit(visibleBooks, editions)
                    renderSelection()
                    refreshMissingOriginals()
                    findViewById<TextView>(R.id.txtBookCount).text =
                        resources.getQuantityString(R.plurals.bia_ux_book_count, visibleBooks.size, visibleBooks.size)
                    val filters = queryFlow.value.isNotEmpty() ||
                        statusFlow.value != "ALL" || favoriteOnlyFlow.value
                    val collection = collectionFlow.value.isNotEmpty()
                    val emptyTitle = when {
                        filters -> R.string.bia_ux_no_matches_title
                        collection -> R.string.bia_ux_empty_collection_title
                        else -> R.string.bia_empty
                    }
                    val emptyDetail = when {
                        filters -> R.string.bia_no_results
                        collection -> R.string.bia_ux_empty_collection
                        else -> R.string.bia_empty_detail
                    }
                    findViewById<TextView>(R.id.txtEmptyHeading).setText(emptyTitle)
                    findViewById<TextView>(R.id.txtEmptyDetail).setText(emptyDetail)
                    findViewById<View>(R.id.btnClearFilters).visibility =
                        if (filters) View.VISIBLE else View.GONE
                    emptyState.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
                    recycler.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun renderSelection() {
        adapter.showSelection(selection.ids)
        selectionBack?.isEnabled = selection.active
        findViewById<View>(R.id.bookSelectionBar).visibility =
            if (selection.active && !batchRunning) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.txtSelectedBooks).text = "${selection.ids.size} seleccionados"
        findViewById<TextView>(R.id.btnSelectAllBooks).text =
            if (visibleBooks.isNotEmpty() && selection.ids.size == visibleBooks.size)
                "Deseleccionar todos" else "Seleccionar todos"
        findViewById<TextView>(R.id.btnBatchPrimary).text =
            if (centralFlow.value.first && selection.ids.any { editions[it].orEmpty().size > 1 })
                "Descargar formato preferido"
            else if (centralFlow.value.first) "Descargar al dispositivo" else "Guardar en biblioteca"
        val secondary = findViewById<TextView>(R.id.btnBatchSecondary)
        secondary.text = if (centralFlow.value.first) "Eliminar definitivamente" else "Retirar del dispositivo"
        secondary.visibility = if (!centralFlow.value.first || SharedLibrary.namedFolderMode(applicationContext))
            View.VISIBLE else View.GONE
    }

    private fun observeBatchQueue() {
        dismissedBatch = getSharedPreferences("batch_ui", MODE_PRIVATE).getString("dismissed", null)
        androidx.work.WorkManager.getInstance(applicationContext)
            .getWorkInfosByTagLiveData(LibraryBatchQueue.TAG).observe(this) { all ->
                val jobs = all.sortedWith(compareBy<androidx.work.WorkInfo> { LibraryBatchQueue.order(it) }
                    .thenBy { it.id.toString() })
                // WorkManager owns execution independently of this Activity.
                batchHistory = jobs.filter { it.state.isFinished }
                val active = jobs.filterNot { it.state.isFinished }
                val work = active.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
                    ?: active.firstOrNull() ?: jobs.lastOrNull()
                queuedBatch = work
                val app = applicationContext
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    jobs.filter { it.state.isFinished }.forEach { LibraryBatchQueue.clean(app, it.id) }
                }
                if (batchRunning) return@observe
                val panel = findViewById<View>(R.id.bookBatchProgress)
                if (work == null || (work.state.isFinished && work.id.toString() == dismissedBatch)) {
                    panel.visibility = View.GONE
                    return@observe
                }
                val status = findViewById<TextView>(R.id.txtBookBatchProgress)
                val button = findViewById<TextView>(R.id.btnCancelBookBatch)
                panel.visibility = View.VISIBLE
                val label = LibraryBatchQueue.label(work)
                val total = LibraryBatchQueue.total(work)
                if (work.state.isFinished) {
                    status.text = "$label · " + (work.outputData.getString("summary")
                        ?: "Operación interrumpida; comprueba los libros y reintenta.")
                    status.append(if (batchHistory.size > 1) "\nToca para ver los resultados anteriores." else "")
                    button.text = "Cerrar"
                    button.isEnabled = true
                    if ((work.tags.contains(LibraryBatchQueue.ACTION + LibraryBatchAction.REMOVE.name) ||
                        work.tags.contains(LibraryBatchQueue.ACTION + LibraryBatchAction.DELETE.name)) &&
                        work.outputData.getInt("done", 0) > 0)
                        findViewById<View>(R.id.recentBookCard).visibility = View.GONE
                } else {
                    val done = work.progress.getInt("done", 0)
                    val phase = work.progress.getString("phase") ?: "En cola"
                    status.text = "$label: $done/$total · $phase" +
                        if (active.size > 1) " · ${active.size - 1} en cola" else ""
                    button.text = "Detener"
                    button.isEnabled = true
                }
            }
    }

    private fun selectedBooks() = visibleBooks.filter { it.id in selection.ids }

    private fun confirmBatchDeletion() {
        val books = selectedBooks()
        if (books.isEmpty() || batchRunning || queueSubmitting) return
        if (!SharedLibrary.namedFolderMode(applicationContext)) return
        val details = books.take(8).joinToString("\n") { "• " + it.title.take(100) + " (" + it.format.name + ")" }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar definitivamente ${books.size} libros")
            .setMessage(details + (if (books.size > 8) "\n… y ${books.size - 8} más." else "") +
                "\n\nSe eliminarán de Drive y de los dispositivos vinculados al sincronizar, junto con sus notas, marcadores y posiciones. Solo los formatos seleccionados; las copias históricas externas no se modificarán. Esta operación no es una retirada del dispositivo.")
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton("Eliminar definitivamente") { _, _ -> startBookBatch(LibraryBatchAction.DELETE, books) }
            .show()
    }

    private fun confirmBatchRemoval() {
        val books = selectedBooks()
        if (books.isEmpty() || batchRunning) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Retirar ${books.size} libros del dispositivo")
            .setMessage("Se conservarán las notas y posiciones. Si un libro aún no está guardado en la biblioteca, se intentará guardarlo y verificarlo antes de retirar la copia local. Si falla, se conservará el original.")
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton("Guardar y retirar") { _, _ -> startBookBatch(LibraryBatchAction.REMOVE, books) }
            .show()
    }

    private fun startBookBatch(action: LibraryBatchAction,
        books: List<com.javierrb35.bibliotecaia.data.BookEntity> = selectedBooks()) {
        if (books.isEmpty() || batchRunning || queueSubmitting || importing || pickerOpen) return
        val app = applicationContext
        if (action != LibraryBatchAction.DOWNLOAD && !checkSharedImportAccess()) return
        val destination = SharedLibrary.uri(app)
        val centralUri = BackupRoutine.centralUri(app)
        if (action == LibraryBatchAction.DELETE && !SharedLibrary.namedFolderMode(app)) return
        if (SharedLibrary.namedFolderMode(app) && destination != null) {
            queueSubmitting = true
            selection.clear()
            renderSelection()
            lifecycleScope.launch {
                try {
                    LibraryBatchQueue.enqueue(app, action, destination, books)
                    if (!isDestroyed) Toast.makeText(this@MainActivity,
                        "Operación en cola. Puedes seguir navegando.", Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!isDestroyed) Toast.makeText(this@MainActivity,
                        e.message ?: "No se pudo guardar la operación. Reintenta.", Toast.LENGTH_LONG).show()
                } finally { queueSubmitting = false }
            }
            return
        }
        batchRunning = true
        batchAction = action
        cancelBatch = false
        selection.clear()
        renderSelection()
        val status = findViewById<TextView>(R.id.txtBookBatchProgress)
        val progress = findViewById<View>(R.id.bookBatchProgress)
        val cancel = findViewById<View>(R.id.btnCancelBookBatch)
        val importButton = findViewById<View>(R.id.btnImport)
        val centralButton = findViewById<View>(R.id.btnCentralLibrary)
        progress.visibility = View.VISIBLE
        cancel.isEnabled = true
        (cancel as TextView).text = "Detener"
        importButton.isEnabled = false
        // Downloads do not modify the central originals or connection. Browsing
        // the local shelf remains possible even if a provider blocks an I/O call.
        centralButton.isEnabled = action != LibraryBatchAction.REMOVE
        status.text = "${action.label}: 0/${books.size} · conectando…"
        batchJob = lifecycleScope.launch {
            try {
                // A save publishes the normal shared catalogue just once. Report
                // only the requested books, and never claim success on a failed sync.
                var savedHashes: Set<String>? = null
                var saveError: Exception? = null
                val result = if (action == LibraryBatchAction.DOWNLOAD && SharedLibrary.configured(app)) {
                    require(SharedLibrary.uri(app) == destination) { "La biblioteca ha cambiado." }
                    val downloaded = SharedLibrary.downloadBatch(app, db, books.map { it.contentHash }) { done, total, title ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            status.text = if (title.isBlank()) "${action.label}: $done/$total"
                                else "${action.label}: $done/$total · ${title.take(60)}"
                        }
                    }
                    LibraryBatch.Result(downloaded.completed, downloaded.failures, 0)
                } else if (action == LibraryBatchAction.REMOVE && SharedLibrary.namedFolderMode(app)) {
                    status.text = "Comprobando los ${books.size} originales seleccionados…"
                    val removed = SharedLibrary.evictSelection(app, db, requireNotNull(destination), books) { cancelBatch }
                    LibraryBatch.Result(removed, emptyList(), books.size - removed)
                } else LibraryBatch.run(books, { cancelBatch }, { done, total ->
                    status.text = "${action.label}: $done/$total"
                }, describe = { it.title }) { book ->
                    require(SharedLibrary.uri(app) == destination &&
                        BackupRoutine.centralUri(app) == centralUri) { "La biblioteca ha cambiado." }
                    val latest = db.bookDao().findById(book.id)
                    require(latest != null && latest.contentHash == book.contentHash) { "El libro ha cambiado." }
                    when (action) {
                        LibraryBatchAction.DOWNLOAD -> {
                            status.text = "Descargando: ${book.title.take(80)}"
                            BackupRoutine.downloadBook(app, db, BuildConfig.VERSION_NAME, book.contentHash)
                            require(db.bookDao().findById(book.id)?.let(CentralCatalogue::local) == true) {
                                "No se confirmó la copia local."
                            }
                        }
                        LibraryBatchAction.SAVE -> {
                            if (savedHashes == null && saveError == null) {
                                try {
                                    val sync = SharedLibrary.publishImports(app, db, requireNotNull(destination),
                                        books.map { it.contentHash }.toSet())
                                    require(sync.errors.isEmpty()) { sync.errors.joinToString(" · ") }
                                    savedHashes = SharedLibrary.hashes(app)
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { saveError = e }
                            }
                            saveError?.let { throw it }
                            require(book.contentHash in savedHashes.orEmpty()) { "Original compartido no verificado." }
                        }
                        LibraryBatchAction.DELETE -> error("La baja definitiva requiere la carpeta compartida.")
                        LibraryBatchAction.REMOVE -> BackupRoutine.evictBook(app, db, BuildConfig.VERSION_NAME, book.id)
                    }
                }
                setCentralMode(centralFlow.value.first)
                if (action == LibraryBatchAction.REMOVE) {
                    findViewById<View>(R.id.recentBookCard).visibility = View.GONE
                }
                val summary = buildString {
                    append(result.completed).append(" de ").append(books.size).append(" libros completados.")
                    if (result.pending > 0) append("\n").append(result.pending).append(" pendientes; puedes reintentarlos.")
                    for (failure in result.failures.take(8)) append("\n").append(failure)
                    if (result.failures.size > 8) append("\n… y ").append(result.failures.size - 8).append(" errores más.")
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(action.label).setMessage(summary)
                    .setPositiveButton("Aceptar", null).show()
            } catch (e: CancellationException) {
                if (cancelBatch && action == LibraryBatchAction.DOWNLOAD && !isDestroyed)
                    Toast.makeText(this@MainActivity, "Descarga detenida. Se conservan las copias completadas.", Toast.LENGTH_LONG).show()
                throw e
            } catch (e: Exception) {
                if (!isDestroyed) com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(action.label)
                    .setMessage(e.message ?: "No se pudo completar la operación. Puedes reintentar.")
                    .setPositiveButton("Aceptar", null).show()
            } finally {
                batchRunning = false
                batchJob = null
                batchAction = null
                progress.visibility = View.GONE
                importButton.isEnabled = true
                centralButton.isEnabled = true
                renderSelection()
            }
        }
    }

    private suspend fun refreshMissingOriginals() {
        val missing = CentralCatalogue.missing(db.backupDao().booksAll(),
            BackupRoutine.browseHashes(applicationContext))
        findViewById<View>(R.id.btnMissingOriginals).visibility =
            if (missing.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showMissingOriginals() {
        lifecycleScope.launch {
            val missing = CentralCatalogue.missing(db.backupDao().booksAll(),
                BackupRoutine.browseHashes(applicationContext))
            if (missing.isEmpty()) { refreshMissingOriginals(); return@launch }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.central_missing_title)
                .setItems(missing.map { it.title }.toTypedArray()) { _, index ->
                    openDetail(missing[index].id)
                }
                .setNegativeButton(R.string.dialog_cancel, null).show()
        }
    }

    private fun loadViewMode(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_GRID, true)

    private fun applyViewMode(grid: Boolean) {
        // Large Android font scales need wider book cards instead of clipped 2-column text.
        val minimumWidth = (164f * resources.configuration.fontScale.coerceAtLeast(1f)).toInt()
        val columns = ((resources.configuration.screenWidthDp - 32).coerceAtLeast(1) /
            minimumWidth.coerceAtLeast(1)).coerceIn(1, 6)
        recycler.layoutManager =
            if (grid) GridLayoutManager(this, columns) else LinearLayoutManager(this)
        adapter.grid = grid
        findViewById<ImageButton>(R.id.btnViewMode).apply {
            setImageResource(if (grid) R.drawable.ic_ux_list else R.drawable.ic_ux_grid)
            contentDescription = getString(if (grid) R.string.action_list else R.string.action_grid)
        }
    }

    private fun toggleViewMode() {
        val position = (recycler.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        val grid = !loadViewMode()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_GRID, grid).apply()
        applyViewMode(grid)
        recycler.scrollToPosition(position)
    }

    private fun checkSharedImportAccess(): Boolean {
        val access = SharedLibrary.writeAccess(applicationContext)
        if (access == SharedWriteAccess.READY) return true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Biblioteca compartida no disponible")
            .setMessage(access.message)
            .setPositiveButton("Configurar") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton(android.R.string.cancel, null).show()
        return false
    }

    private fun importBooks(uris: List<Uri>, central: Boolean) {
        if (importing) return
        val app = applicationContext
        if (central && !checkSharedImportAccess()) return
        val destination = if (central) SharedLibrary.uri(app) ?: return else null
        importing = true
        val button = findViewById<View>(R.id.btnImport)
        val label = findViewById<TextView>(R.id.txtImportLabel)
        button.isEnabled = false
        // Keep shelf navigation available during publication.
        label.setText(R.string.bia_importing)
        findViewById<View>(R.id.importProgress).visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val importer = BookImporter(app, db.bookDao())
                val result = BatchImport.run(
                    uris.distinct(), central,
                    importOne = { importer.import(it) },
                    progress = { done, total -> label.text = "$done/$total" },
                    publishVerified = { books ->
                        val target = requireNotNull(destination)
                        label.text = "Guardando originales nuevos en Drive…"
                        val synced = SharedLibrary.publishImports(app, db, target, books.map { it.contentHash }.toSet())
                        require(synced.errors.isEmpty()) { synced.errors.joinToString(" · ") }
                        require(books.all { it.contentHash in SharedLibrary.hashes(app) }) {
                            "No se han confirmado todos los originales en la biblioteca."
                        }
                    },
                    removeStaging = { books ->
                        label.text = "Comprobando los nuevos originales guardados…"
                        SharedLibrary.evictVerifiedImports(app, db, requireNotNull(destination), books)
                    }
                )
                if (result.imported.isNotEmpty()) {
                    if (SharedLibrary.configured(app)) SharedLibrary.schedule(app, immediate = true)
                    else if (ReadingPositionSafSync.refresh(app, db) &&
                        ReadingPositionSafSync.status(app).peerCount == 0) {
                        BackupRoutine.refreshOnLibraryOpen(app, BuildConfig.VERSION_NAME)
                    }
                }
                val text = buildString {
                    append(result.imported.size).append(" de ").append(uris.distinct().size).append(" libros importados")
                    if (central && result.centralError == null && result.imported.isNotEmpty())
                        append(" en la biblioteca compartida; originales verificados.")
                    else if (!central) append(" en el dispositivo.")
                    if (result.centralError != null)
                        append("\nPublicación pendiente o incompleta. Los originales no retirados se conservan en el dispositivo para sincronizarlos más adelante: ").append(result.centralError)
                    if (result.failures.isNotEmpty())
                        append("\n").append(result.failures.size).append(" no importados: ").append(result.failures.take(4).joinToString(" · "))
                }
                if (result.centralError != null || result.failures.isNotEmpty())
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Resultado de la importación").setMessage(text)
                        .setPositiveButton("Aceptar", null).show()
                else Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
                setCentralMode(centralFlow.value.first)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: getString(R.string.import_error_generic), Toast.LENGTH_LONG).show()
            } finally {
                importing = false
                button.isEnabled = true
                findViewById<View>(R.id.btnCentralLibrary).isEnabled = true
                label.setText(R.string.premium_import)
                findViewById<View>(R.id.importProgress).visibility = View.GONE
            }
        }
    }

    private companion object {
        const val PREFS = "biblioteca_prefs"
        const val KEY_GRID = "view_mode_grid"
        const val EXTRA_OPEN_IMPORT = "bibliotecaia.premium_open_import"
        const val STATE_QUERY = "ux_library_query"
        const val STATE_SHELF = "ux_library_shelf"
        const val EXTRA_COLLECTION_ID = "collection_id"
        const val EXTRA_COLLECTION_NAME = "collection_name"
        val IMPORT_MIME_TYPES = arrayOf(
            "application/epub+zip",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook"
        )
    }
}
