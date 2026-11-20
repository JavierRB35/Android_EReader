package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.BuildConfig
import com.javierrb35.bibliotecaia.backup.BackupRoutine
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.importer.BookImporter
import com.javierrb35.bibliotecaia.importer.ImportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
        adapter = BookAdapter { book -> BookDetailActivity.start(this, book.id) }
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
        findViewById<View>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(IMPORT_MIME_TYPES)
        }

        observeCatalog()
        // Scriptura behaviour: on opening the library, refresh its one verified
        // SAF ZIP only if books, notes, positions or metadata changed.
        BackupRoutine.refreshOnLibraryOpen(applicationContext, BuildConfig.VERSION_NAME)
        findViewById<BottomNavigationView>(R.id.mainNavigation)
            .setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.navKnowledge -> KnowledgeActivity.start(this)
                    R.id.navRead -> lifecycleScope.launch {
                        val recent = db.bookDao().lastRead()
                        if (recent == null) {
                            Toast.makeText(this@MainActivity, R.string.no_recent_book, Toast.LENGTH_LONG).show()
                        } else {
                            ReaderRouter.open(this@MainActivity, recent.id)
                        }
                    }
                }
                item.itemId == R.id.navLibrary
            }
    }

    // Notification returns to this Activity without leaving the application.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val latest = db.bookDao().lastRead()
            val card = findViewById<View>(R.id.recentBookCard)
            // Collection pages stay focused on their own content.
            if (latest == null || collectionFlow.value.isNotEmpty()) {
                card.visibility = View.GONE
                card.setOnClickListener(null)
            } else {
                findViewById<TextView>(R.id.txtRecentBook).text = latest.title
                card.contentDescription = getString(R.string.bia_ux_continue_a11y, latest.title)
                card.setOnClickListener { ReaderRouter.open(this@MainActivity, latest.id) }
                card.visibility = View.VISIBLE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
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
                combine(queryFlow, statusFlow, favoriteOnlyFlow, collectionFlow) { q, s, f, c ->
                    db.bookDao().search(q, s, if (f) 1 else 0, c)
                }.flatMapLatest { it }.collect { books ->
                    adapter.submit(books)
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
        val button = findViewById<MaterialButton>(R.id.btnImport)
        button.isEnabled = false
        button.setText(R.string.bia_importing)
        findViewById<View>(R.id.importProgress).visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val book = BookImporter(applicationContext, db.bookDao()).import(uri)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.import_success, book.title),
                    Toast.LENGTH_LONG
                ).show()
                // Also refresh after a successful import rather than postponing
                // its physical EPUB/PDF/MOBI original until the next app launch.
                BackupRoutine.refreshOnLibraryOpen(applicationContext, BuildConfig.VERSION_NAME)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportError) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.import_error_generic, Toast.LENGTH_LONG).show()
            } finally {
                importing = false
                button.isEnabled = true
                button.setText(R.string.action_import)
                findViewById<View>(R.id.importProgress).visibility = View.GONE
            }
        }
    }

    private companion object {
        const val PREFS = "biblioteca_prefs"
        const val KEY_GRID = "view_mode_grid"
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
