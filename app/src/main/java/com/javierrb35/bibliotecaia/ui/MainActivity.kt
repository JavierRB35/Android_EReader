package com.javierrb35.bibliotecaia.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.importer.BookImporter
import com.javierrb35.bibliotecaia.importer.ImportError
import kotlinx.coroutines.launch

/**
 * Biblioteca principal (Incremento 1): catálogo persistente + importación real vía SAF.
 * El lector de libros llega con el Incremento 2; de momento la ficha muestra metadatos.
 */
class MainActivity : AppCompatActivity() {

    private val bookDao by lazy { AppDatabase.get(this).bookDao() }
    private lateinit var adapter: BookAdapter

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importBook(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recycler = findViewById<RecyclerView>(R.id.recyclerBooks)
        val emptyState = findViewById<View>(R.id.txtEmpty)
        adapter = BookAdapter { book -> BookDetailActivity.start(this, book.id) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bookDao.observeAll().collect { books ->
                    adapter.submit(books)
                    emptyState.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
                    recycler.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        findViewById<View>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(IMPORT_MIME_TYPES)
        }
    }

    private fun importBook(uri: Uri) {
        lifecycleScope.launch {
            try {
                val book = BookImporter(applicationContext, bookDao).import(uri)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.import_success, book.title),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: ImportError) {
                // Errores recuperables con mensaje claro (protocolo §17: prevención y recuperación)
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.import_error_generic, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        val IMPORT_MIME_TYPES = arrayOf(
            "application/epub+zip",
            "application/pdf",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook"
        )
    }
}
