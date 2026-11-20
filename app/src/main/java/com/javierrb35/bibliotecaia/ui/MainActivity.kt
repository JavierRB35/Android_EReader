package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
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
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.importer.BookImporter
import com.javierrb35.bibliotecaia.importer.ImportError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Biblioteca principal (§7.1): catálogo persistente, búsqueda, estantes por estado y
 * colección, vista cuadrícula/lista e importación real vía SAF.
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

        val collectionId = intent.getStringExtra(EXTRA_COLLECTION_ID) ?: ""
        val collectionName = intent.getStringExtra(EXTRA_COLLECTION_NAME)
        collectionFlow.value = collectionId
        if (collectionName != null) {
            findViewById<android.widget.TextView>(R.id.txtTitle).text =
                getString(R.string.collection_view_title, collectionName)
        }

        recycler = findViewById(R.id.recyclerBooks)
        emptyState = findViewById(R.id.txtEmpty)
        adapter = BookAdapter { book -> BookDetailActivity.start(this, book.id) }
        applyViewMode(recycler, adapter, loadViewMode())
        recycler.adapter = adapter

        findViewById<EditText>(R.id.txtSearch).doOnTextChanged { text, _, _, _ ->
            queryFlow.value = text?.toString()?.trim() ?: ""
        }

        findViewById<ChipGroup>(R.id.chipShelves).setOnCheckedStateChangeListener { group, _ ->
            val checked = group.checkedChipIds.firstOrNull()
            statusFlow.value = when (checked) {
                R.id.chipFavorites -> "ALL" // filtro por columna favorite, ver favoriteOnlyFlow
                R.id.chipPending -> "PENDING"
                R.id.chipReading -> "READING"
                R.id.chipFinished -> "FINISHED"
                R.id.chipAbandoned -> "ABANDONED"
                else -> "ALL"
            }
            favoriteOnlyFlow.value = checked == R.id.chipFavorites
        }

        findViewById<View>(R.id.btnViewMode).setOnClickListener { toggleViewMode() }
        findViewById<View>(R.id.btnOrganize).setOnClickListener {
            startActivity(Intent(this, OrganizeActivity::class.java))
        }
        findViewById<View>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(IMPORT_MIME_TYPES)
        }

        observeCatalog()
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.mainNavigation)
            .setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.navKnowledge -> KnowledgeActivity.start(this)
                    R.id.navRead -> lifecycleScope.launch {
                        val recent = db.bookDao().lastRead()
                        if (recent == null) Toast.makeText(this@MainActivity, R.string.no_recent_book, Toast.LENGTH_LONG).show()
                        else ReaderRouter.open(this@MainActivity, recent.id)
                    }
                }
                item.itemId == R.id.navLibrary
            }
    }

    private fun observeCatalog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(queryFlow, statusFlow, favoriteOnlyFlow, collectionFlow) { q, s, f, c ->
                    db.bookDao().search(q, s, if (f) 1 else 0, c)
                }.flatMapLatest { it }.collect { books ->
                    adapter.submit(books)
                    findViewById<TextView>(R.id.txtBookCount).text = getString(R.string.bia_book_count, books.size)
                    val filtered = queryFlow.value.isNotEmpty() || statusFlow.value != "ALL" || favoriteOnlyFlow.value || collectionFlow.value.isNotEmpty()
                    findViewById<TextView>(R.id.txtEmptyDetail).setText(if (filtered) R.string.bia_no_results else R.string.bia_empty_detail)
                    emptyState.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
                    recycler.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun loadViewMode(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_GRID, true)

    private fun applyViewMode(recycler: RecyclerView, adapter: BookAdapter, grid: Boolean) {
        recycler.layoutManager =
            if (grid) GridLayoutManager(this, ((resources.configuration.screenWidthDp - 40) / 160).coerceIn(2, 6)) else LinearLayoutManager(this)
        adapter.grid = grid
        findViewById<View>(R.id.btnViewMode).let { btn ->
            (btn as android.widget.Button).setText(if (grid) R.string.action_list else R.string.action_grid)
        }
    }

    private fun toggleViewMode() {
        val grid = !loadViewMode()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_GRID, grid).apply()
        applyViewMode(recycler, adapter, grid)
    }

    private fun importBook(uri: Uri) {
        if (importing) return
        importing = true
        findViewById<View>(R.id.btnImport).isEnabled = false
        findViewById<TextView>(R.id.btnImport).setText(R.string.bia_importing)
        findViewById<View>(R.id.importProgress).visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val book = BookImporter(applicationContext, db.bookDao()).import(uri)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.import_success, book.title),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportError) {
                // Errores recuperables con mensaje claro (protocolo §17: prevención y recuperación)
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.import_error_generic, Toast.LENGTH_LONG).show()
            } finally {
                importing = false
                findViewById<View>(R.id.btnImport).isEnabled = true
                findViewById<TextView>(R.id.btnImport).setText(R.string.action_import)
                findViewById<View>(R.id.importProgress).visibility = View.GONE
            }
        }
    }

    private companion object {
        const val PREFS = "biblioteca_prefs"
        const val KEY_GRID = "view_mode_grid"
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
