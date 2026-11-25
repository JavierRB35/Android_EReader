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
import com.javierrb35.bibliotecaia.importer.ImportError
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
    private var importing = false

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
        centralFlow.value = central to BackupRoutine.centralHashes(applicationContext)
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
                    startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_cloud_wizard", true))
                }
                .setNeutralButton("Abrir copia antigua") { _, _ ->
                    centralPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        lifecycleScope.launch {
            val button = findViewById<View>(R.id.btnCentralLibrary)
            button.isEnabled = false
            try {
                BackupRoutine.refreshCentral(applicationContext, db, BuildConfig.VERSION_NAME)
                setCentralMode(true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Biblioteca central no disponible.", Toast.LENGTH_LONG).show()
                // Cached metadata remains searchable offline; download still requires access.
                setCentralMode(true)
            } finally { button.isEnabled = true }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importBook(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        collectionFlow.value = intent.getStringExtra(EXTRA_COLLECTION_ID).orEmpty()
        intent.getStringExtra(EXTRA_COLLECTION_NAME)?.let { name ->
            findViewById<TextView>(R.id.txtTitle).text =
                getString(R.string.collection_view_title, name)
        }

        recycler = findViewById(R.id.recyclerBooks)
        emptyState = findViewById(R.id.txtEmpty)
        adapter = BookAdapter { book -> openDetail(book.id) }
        applyViewMode(loadViewMode())
        recycler.adapter = adapter

        val search = findViewById<EditText>(R.id.txtSearch)
        search.setText(savedInstanceState?.getString(STATE_QUERY).orEmpty())
        queryFlow.value = search.text?.toString()?.trim().orEmpty()
        search.doOnTextChanged { text, _, _, _ ->
            queryFlow.value = text?.toString()?.trim().orEmpty()
        }

        val shelves = findViewById<ChipGroup>(R.id.chipShelves)
        val restoredShelf = savedInstanceState?.getInt(STATE_SHELF, R.id.chipAll) ?: R.id.chipAll
        shelves.check(restoredShelf)
        updateShelfFilter(shelves.checkedChipId)
        shelves.setOnCheckedStateChangeListener { group, _ ->
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
        findViewById<View>(R.id.btnCloudSetup).setOnClickListener {
            getSharedPreferences("cloud_setup_wizard", MODE_PRIVATE).edit().putBoolean("offered", true).apply()
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_cloud_wizard", true))
        }
        findViewById<View>(R.id.btnDismissCloudSetup).setOnClickListener {
            getSharedPreferences("cloud_setup_wizard", MODE_PRIVATE).edit().putBoolean("offered", true).apply()
            findViewById<View>(R.id.cloudSetupOffer).visibility = View.GONE
        }
        setCentralMode(savedInstanceState?.getBoolean("central_mode") == true)
        findViewById<View>(R.id.btnMissingOriginals).setOnClickListener { showMissingOriginals() }
        observeCatalog()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.javierrb35.bibliotecaia.sync.SharedLibrary.changes.collect {
                    if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(applicationContext))
                        setCentralMode(centralFlow.value.first)
                }
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
        if (!importing) importLauncher.launch(IMPORT_MIME_TYPES)
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext
        val needsSetup = !com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(app) ||
            !ReadingPositionSafSync.hasOwn(app) || BackupRoutine.status(app).uri == null
        findViewById<View>(R.id.cloudSetupOffer).visibility =
            if (needsSetup && collectionFlow.value.isEmpty() &&
                !getSharedPreferences("cloud_setup_wizard", MODE_PRIVATE).getBoolean("offered", false))
                View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnStorageSetup).visibility =
            if (StorageSetupWizard.pending(applicationContext)) View.VISIBLE else View.GONE
        setCentralMode(centralFlow.value.first)
        lifecycleScope.launch {
            if (centralFlow.value.first && BackupRoutine.centralUri(applicationContext) != null) {
                try {
                    BackupRoutine.refreshCentral(applicationContext, db, BuildConfig.VERSION_NAME)
                    if (centralFlow.value.first) setCentralMode(true)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the last verified catalogue offline. */ }
            }
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
                val bitmap = try {
                    BookCoverLoader.load(latest)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A missing/corrupt original cannot crash the home screen.
                    null
                }
                if (bitmap != null && !isDestroyed) coverView.setImageBitmap(bitmap)
                card.contentDescription = getString(R.string.bia_ux_continue_a11y, latest.title)
                card.setOnClickListener { ReaderRouter.open(this@MainActivity, latest.id) }
                card.visibility = View.VISIBLE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("central_mode", centralFlow.value.first)
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
                    adapter.submit(books)
                    refreshMissingOriginals()
                    findViewById<TextView>(R.id.txtBookCount).text =
                        resources.getQuantityString(R.plurals.bia_ux_book_count, books.size, books.size)
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

    private suspend fun refreshMissingOriginals() {
        val missing = CentralCatalogue.missing(db.backupDao().booksAll(),
            BackupRoutine.centralHashes(applicationContext))
        findViewById<View>(R.id.btnMissingOriginals).visibility =
            if (missing.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showMissingOriginals() {
        lifecycleScope.launch {
            val missing = CentralCatalogue.missing(db.backupDao().booksAll(),
                BackupRoutine.centralHashes(applicationContext))
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

    private fun importBook(uri: Uri) {
        if (importing) return
        importing = true
        val button = findViewById<View>(R.id.btnImport)
        button.isEnabled = false
        findViewById<TextView>(R.id.txtImportLabel).setText(R.string.bia_importing)
        findViewById<View>(R.id.importProgress).visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val book = BookImporter(applicationContext, db.bookDao()).import(uri)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.import_success, book.title),
                    Toast.LENGTH_LONG
                ).show()
                val shared = com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(applicationContext)
                if (shared) {
                    com.javierrb35.bibliotecaia.sync.SharedLibrary.schedule(applicationContext)
                }
                val remoteChecked = !shared && ReadingPositionSafSync.refresh(applicationContext, db)
                if (remoteChecked && ReadingPositionSafSync.status(applicationContext).peerCount == 0) {
                    BackupRoutine.refreshOnLibraryOpen(applicationContext, BuildConfig.VERSION_NAME)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportError) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.import_error_generic, Toast.LENGTH_LONG).show()
            } finally {
                importing = false
                button.isEnabled = true
                findViewById<TextView>(R.id.txtImportLabel).setText(R.string.premium_import)
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
            "application/pdf",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook"
        )
    }
}
